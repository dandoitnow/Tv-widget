package com.example.tvwidget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.tvwidget.ui.Surfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and disk-caches poster art so it can be drawn into the widget as a plain [Bitmap].
 *
 * Widgets can't fetch images at draw time ([com.example.tvwidget.widget.Common.Poster] used to be a
 * static placeholder for exactly this reason) — art has to already be on disk before `provideGlance`
 * runs. [AnticipatedSyncWorker] is what actually populates this cache; the widget only ever reads it.
 *
 * The disk cache is bounded: every show ever searched or tracked used to leave a PNG on disk
 * forever. [MAX_CACHED_POSTERS] caps it, evicted least-recently-used first — "used" meaning
 * re-cached by a sync (see [ensureCached]), which is what keeps a still-relevant poster's mtime
 * fresh. Loading a bitmap for display does *not* count as a use for this purpose: `provideGlance`
 * re-invokes on every tap, so touching mtime on every read would make everything look equally
 * "just used" within minutes and defeat the eviction signal entirely.
 *
 * A small process-lifetime [memoryCache] also sits in front of the disk read, since `provideGlance`
 * re-decodes whatever the active tab needs on every single interaction (tab switch, star toggle,
 * rewatch count, ...) — without it, the same handful of PNGs get re-read from disk and re-decoded to
 * a fresh [Bitmap] dozens of times in a row for no reason.
 */
object PosterStore {

    private const val TAG = "PosterStore"
    private const val TARGET_WIDTH = 104
    private const val TARGET_HEIGHT = 144

    /** ~104x144 PNGs run well under 50KB each; 200 of them is a low-single-digit-MB disk cache. */
    private const val MAX_CACHED_POSTERS = 200

    /** Comfortably more than one tab's worth of rows; bounds the in-memory bitmap cache's RAM use. */
    private const val MAX_MEMORY_ENTRIES = 60

    /**
     * Bumped whenever what gets *baked into* a cached PNG changes — currently the inner stroke and
     * vignette applied by [Surfaces.finishPoster]. Cached posters are finished art, not raw
     * downloads, so a change to the finishing has to invalidate everything already on disk;
     * otherwise old and new posters sit in the same list looking visibly different from each other.
     */
    private const val CACHE_VERSION = 4

    /**
     * The corner radius the stroke follows, in bitmap pixels. [Tokens.RadiusPoster] is 8dp, and a
     * poster renders around 54dp wide at the middle size tier against a 104px-wide bitmap — so a
     * little over 15px. The other tiers land close enough that a single baked radius reads correctly
     * at all three.
     */
    private const val STROKE_RADIUS_PX = 15f

    /**
     * Width of the pre-rendered widget copy. Rows draw posters between 28dp and 78dp wide, so this
     * is a touch soft only at the largest size tier — a fair trade for a redraw that keeps up with
     * the tap that caused it. See [loadVariant].
     */
    private const val VARIANT_WIDTH = 64

    private data class CacheEntry(val fileModifiedAt: Long, val bitmap: Bitmap, val accent: Int)

    // LinkedHashMap in access-order mode is a textbook bounded LRU: removeEldestEntry runs on every
    // insert/access and evicts the least-recently-touched entry once the cap is exceeded.
    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>) =
            size > MAX_MEMORY_ENTRIES
    }

    /** Downscaled widget-sized copies, keyed `poster@width`. See [scaledFor]. */
    private val scaledCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) =
            size > MAX_MEMORY_ENTRIES
    }

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "posters").apply {
            mkdirs()
            migrateIfStale(this)
        }

    @Volatile
    private var migrated = false

    /**
     * Wipes the cache when [CACHE_VERSION] moves on. Cheap and safe: the next sync re-downloads
     * whatever is still relevant, and the alternative — re-finishing every PNG in place — would mean
     * reading, decoding, and rewriting the whole cache to save a handful of downloads.
     */
    private fun migrateIfStale(dir: File) {
        // dir() is on the path of every single poster lookup, so the version check is latched for
        // the life of the process rather than paying a stat syscall per row per redraw.
        if (migrated) return
        migrated = true
        val marker = File(dir, ".v$CACHE_VERSION")
        if (marker.exists()) return
        dir.listFiles()?.forEach { it.delete() }
        synchronized(memoryCache) { memoryCache.clear() }
        synchronized(scaledCache) { scaledCache.clear() }
        runCatching { marker.createNewFile() }
    }

    /** Stable, filesystem-safe cache key for a show — titles for demo content, TVMaze ids otherwise. */
    fun keyFor(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    private fun file(context: Context, key: String): File = File(dir(context), "$key.png")

    /**
     * The widget-sized copy. A distinct extension rather than another `.png` so eviction can tell
     * cache entries from their derivatives — counting variants as entries would both halve the
     * effective cache size and make a variant eligible for deletion on its own.
     */
    private fun variantFile(context: Context, key: String): File = File(dir(context), "$key.wv")

    /** Dominant colours, measured once at cache time. See [loadAccentsBlocking]. */
    private fun accentPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("poster_accents", Context.MODE_PRIVATE)

    private fun writeVariant(context: Context, key: String, source: Bitmap) {
        runCatching {
            val scaled = scaleToVariant(source)
            FileOutputStream(variantFile(context, key)).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            if (scaled !== source) scaled.recycle()
        }.onFailure { Log.w(TAG, "Variant write failed for $key: ${it.message}") }
    }

    fun has(context: Context, key: String): Boolean = file(context, key).exists()

    /** Downloads [url] and caches it under [key] if not already cached. No-ops when offline. */
    suspend fun ensureCached(context: Context, key: String, url: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext false
            val target = file(context, key)
            if (target.exists()) {
                // Still relevant to a sync — bump its recency so an active poster never gets
                // evicted just because it happens not to be on screen at the moment.
                target.setLastModified(System.currentTimeMillis())
                // Backfill anything derived that is missing. A sync is the right place to pay for
                // this; the redraw path is not, and the redraw path is where it would otherwise
                // happen — once per tap, forever, because nothing there writes the result down.
                if (!variantFile(context, key).exists() || accentPrefs(context).getInt(key, 0) == 0) {
                    runCatching { BitmapFactory.decodeFile(target.absolutePath) }.getOrNull()?.let { existing ->
                        writeVariant(context, key, existing)
                        accentPrefs(context).edit().putInt(key, dominantColor(existing)).apply()
                    }
                }
                return@withContext true
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                if (connection.responseCode !in 200..299) return@withContext false
                val bitmap = connection.inputStream.use(BitmapFactory::decodeStream) ?: return@withContext false
                val scaled = Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, TARGET_HEIGHT, true)
                // Finishing is baked in here rather than applied at draw time: the widget redraws on
                // every interaction and compositing a stroke and vignette per poster per redraw
                // would be real work repeated for a result that never changes.
                val finished = Surfaces.finishPoster(scaled, STROKE_RADIUS_PX)
                FileOutputStream(target).use { out -> finished.compress(Bitmap.CompressFormat.PNG, 90, out) }

                // Everything the widget needs at draw time is produced here instead, because the
                // widget draws from a frozen-then-restarted process where nothing is cached and the
                // work lands squarely between a tap and the screen changing.
                writeVariant(context, key, finished)
                accentPrefs(context).edit().putInt(key, dominantColor(finished)).apply()

                if (finished !== scaled) scaled.recycle()
                if (scaled !== bitmap) bitmap.recycle()
                evictLeastRecentlyUsed(context)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Poster fetch failed for $key: ${t.message}")
                false
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Loads every cached bitmap for [keys], skipping any not yet on disk. A memory-cache hit whose
     * backing file hasn't changed since skips the disk entirely; everything else decodes off the UI
     * thread and is memoized for next time.
     */
    suspend fun loadBitmaps(context: Context, keys: Collection<String>): Map<String, Bitmap> =
        withContext(Dispatchers.IO) { loadBitmapsBlocking(context, keys) }

    /**
     * Same as [loadBitmaps], without the dispatcher hop — for calling directly from inside a Glance
     * composable body (see `TvWidget.WidgetContent`), which needs this read to happen fresh on every
     * recomposition rather than once in `provideGlance`: Glance's `update()`/`updateAll()` don't
     * guarantee `provideGlance`'s suspend body actually re-runs on every redraw (the same reason
     * `tab`/`releases` are read via `currentState()` there instead of a value computed once and
     * passed in) — a value snapshotted in `provideGlance` and merely passed down can go stale for as
     * long as the widget's session keeps recomposing without a fresh `provideGlance` call. These are
     * small, already-local, already-cached reads, so a blocking call inside composition is a fine
     * trade for correctness here.
     */
    fun loadBitmapsBlocking(
        context: Context,
        keys: Collection<String>,
        maxWidthPx: Int? = null,
    ): Map<String, Bitmap> =
        keys.distinct().mapNotNull { key ->
            val bitmap = if (maxWidthPx == null) loadOne(context, key) else loadVariant(context, key)
            bitmap?.let { key to it }
        }.toMap()

    /**
     * Loads the pre-rendered widget-sized copy of a poster.
     *
     * This variant exists for two separate reasons, and both of them bite hard.
     *
     * *Size*: every bitmap drawn into a widget crosses a Binder transaction with a hard limit, and a
     * `LazyColumn` parcels every item rather than only the visible ones — so cost scales with the
     * length of the list, not with what fits on screen. Full-size posters overran that limit and
     * killed the launcher's widget host.
     *
     * *Speed*: the widget redraws from a cold process almost every time, because the platform
     * freezes the app between interactions, so nothing in memory survives. Producing this variant at
     * draw time meant decoding a full-size PNG, scaling it, and re-encoding it to `RGB_565` — three
     * allocations per row, twenty rows, on the path between a tap and the screen changing. Rendering
     * it once at cache time reduces that to a single decode of a file a fifth the size.
     *
     * The fallback path still scales from the original, so a poster cached before this existed keeps
     * working rather than vanishing until the next sync.
     */
    private fun loadVariant(context: Context, key: String): Bitmap? {
        val file = variantFile(context, key)
        if (!file.exists()) return loadOne(context, key)?.let { scaledFor(key, it) }
        val cacheKey = "$key@variant"
        synchronized(scaledCache) { scaledCache[cacheKey]?.let { return it } }
        // ARGB, not RGB_565. Posters now carry squircle corners cut out of them, and 565 has no
        // alpha channel — decoding into it would fill those corners with black instead of letting
        // the row surface show through, which is the entire point of the shape.
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        synchronized(scaledCache) { scaledCache[cacheKey] = bitmap }
        return bitmap
    }

    /** Scales a full-size poster down to the widget variant's dimensions. */
    private fun scaledFor(key: String, source: Bitmap): Bitmap {
        if (source.width <= VARIANT_WIDTH) return source
        val cacheKey = "$key@variant"
        synchronized(scaledCache) { scaledCache[cacheKey]?.let { return it } }
        val scaled = runCatching { scaleToVariant(source) }.getOrNull() ?: return source
        synchronized(scaledCache) { scaledCache[cacheKey] = scaled }
        return scaled
    }

    private fun scaleToVariant(source: Bitmap): Bitmap {
        val height = (source.height * (VARIANT_WIDTH.toFloat() / source.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, VARIANT_WIDTH, height, true)
    }

    /**
     * The dominant colour of each cached poster, for the per-row edge light (see [Surfaces.row]).
     *
     * Read from a small preferences map written when the poster was cached, rather than measured
     * from pixels here. Measuring meant decoding every poster at full size on the redraw path — on
     * top of the variant decode the widget already needed — purely to sample it. That is the single
     * most expensive thing a tap used to trigger, and it produced a value that never changes for a
     * given image.
     *
     * Anything cached before this was persisted falls back to measuring, so no row loses its light.
     */
    fun loadAccentsBlocking(context: Context, keys: Collection<String>): Map<String, Int> {
        val stored = accentPrefs(context)
        return keys.distinct().mapNotNull { key ->
            val cached = stored.getInt(key, 0)
            if (cached != 0) return@mapNotNull key to cached
            val measured = loadEntry(context, key)?.accent ?: return@mapNotNull null
            stored.edit().putInt(key, measured).apply()
            key to measured
        }.toMap()
    }

    private fun loadOne(context: Context, key: String): Bitmap? = loadEntry(context, key)?.bitmap

    private fun loadEntry(context: Context, key: String): CacheEntry? {
        val file = file(context, key)
        if (!file.exists()) return null
        val modifiedAt = file.lastModified()

        synchronized(memoryCache) {
            memoryCache[key]?.let { cached ->
                if (cached.fileModifiedAt == modifiedAt) return cached
            }
        }

        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        val entry = CacheEntry(modifiedAt, bitmap, dominantColor(bitmap))
        synchronized(memoryCache) { memoryCache[key] = entry }
        return entry
    }

    /**
     * The colour a poster "reads as" — not its average, which on almost any real poster is a muddy
     * grey-brown, but the colour a person would name if asked.
     *
     * Pixels are weighted by saturation and filtered for mid-range brightness, so a poster's actual
     * subject wins over its black background and its white title text. The result is then pushed to
     * a fixed saturation and lightness, because the edge light has to be legible against a near-black
     * ground: an accurate but very dark navy would extract correctly and then be invisible.
     */
    private fun dominantColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        // One bulk read rather than a getPixel per sample: getPixel crosses into native code every
        // call, and at a thousand samples a poster that dominated the cost of this function.
        val pixels = IntArray(width * height)
        runCatching { bitmap.getPixels(pixels, 0, width, 0, 0, width, height) }
            .getOrElse { return 0xFFD8B45F.toInt() }

        val step = 4
        val hsv = FloatArray(3)
        var weightSum = 0f
        var hueX = 0f
        var hueY = 0f
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = pixels[y * width + x]
                android.graphics.Color.colorToHSV(pixel, hsv)
                val (h, s, v) = hsv
                if (s < 0.18f || v < 0.15f || v > 0.95f) continue
                val weight = s * v
                // Hues are angles, so they are averaged on the unit circle: a plain arithmetic mean
                // of a red poster's hues straddling 0/360 would come out cyan.
                val radians = Math.toRadians(h.toDouble())
                hueX += (kotlin.math.cos(radians) * weight).toFloat()
                hueY += (kotlin.math.sin(radians) * weight).toFloat()
                weightSum += weight
            }
        }
        // A poster with no usable colour at all (true monochrome art) falls back to the house gold,
        // which keeps the row lit and in-palette rather than leaving one row conspicuously flat.
        if (weightSum < 0.5f) return 0xFFD8B45F.toInt()

        val hue = ((Math.toDegrees(kotlin.math.atan2(hueY, hueX).toDouble()) + 360.0) % 360.0).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.78f))
    }

    /** Deletes the oldest-by-mtime files once the cache grows past [MAX_CACHED_POSTERS]. */
    private fun evictLeastRecentlyUsed(context: Context) {
        // Posters only — the version marker is bookkeeping, not cache content, and letting it into
        // this list would both inflate the count and make it eligible for eviction as the oldest
        // file, silently re-triggering a full cache wipe.
        val files = dir(context).listFiles { f: File -> f.name.endsWith(".png") } ?: return
        val overflow = files.size - MAX_CACHED_POSTERS
        if (overflow <= 0) return
        files.sortedBy { it.lastModified() }
            .take(overflow)
            .forEach { evicted ->
                val key = evicted.nameWithoutExtension
                evicted.delete()
                // The derived variant and the stored accent belong to this entry, not to the cache
                // at large; leaving either behind would outlive the art it describes.
                variantFile(context, key).delete()
                accentPrefs(context).edit().remove(key).apply()
                synchronized(memoryCache) { memoryCache.remove(key) }
                synchronized(scaledCache) { scaledCache.remove("$key@variant") }
            }
    }
}
