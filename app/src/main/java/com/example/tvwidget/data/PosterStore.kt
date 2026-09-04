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
    private const val CACHE_VERSION = 2

    /**
     * The corner radius the stroke follows, in bitmap pixels. [Tokens.RadiusPoster] is 8dp, and a
     * poster renders around 54dp wide at the middle size tier against a 104px-wide bitmap — so a
     * little over 15px. The other tiers land close enough that a single baked radius reads correctly
     * at all three.
     */
    private const val STROKE_RADIUS_PX = 15f

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
        runCatching { marker.createNewFile() }
    }

    /** Stable, filesystem-safe cache key for a show — titles for demo content, TVMaze ids otherwise. */
    fun keyFor(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    private fun file(context: Context, key: String): File = File(dir(context), "$key.png")

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
            val bitmap = loadOne(context, key) ?: return@mapNotNull null
            key to if (maxWidthPx == null) bitmap else scaledFor(key, bitmap, maxWidthPx)
        }.toMap()

    /**
     * A smaller, denser copy of a poster, for callers whose bitmaps get parcelled.
     *
     * The widget's rows are the reason this exists. Every bitmap drawn into a widget crosses a Binder
     * transaction with a hard size limit, and a `LazyColumn` parcels *every* item rather than only
     * the visible ones — so a long list multiplies the cost of each poster by the whole list length,
     * not by what fits on screen. At the disk cache's 104x144 ARGB_8888 that is 58KB a row, which
     * overran the limit and killed the launcher's widget host outright.
     *
     * `RGB_565` halves it again and costs nothing visible: these are opaque photographs rendered at
     * roughly a centimetre tall, not gradients where banding would show.
     *
     * The app's Catalogue deliberately does *not* pass a width. It draws the same posters far larger
     * and has no parcelling constraint at all, so it keeps the full-resolution original.
     */
    private fun scaledFor(key: String, source: Bitmap, maxWidthPx: Int): Bitmap {
        if (source.width <= maxWidthPx) return source
        val cacheKey = "$key@$maxWidthPx"
        synchronized(scaledCache) { scaledCache[cacheKey]?.let { return it } }
        val height = (source.height * (maxWidthPx.toFloat() / source.width)).toInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(source, maxWidthPx, height, true)
                .copy(Bitmap.Config.RGB_565, false)
        }.getOrNull() ?: return source
        synchronized(scaledCache) { scaledCache[cacheKey] = scaled }
        return scaled
    }

    /**
     * The dominant colour of each cached poster, for the per-row edge light (see
     * [Surfaces.edgeLight]). Keys with no cached art are simply absent.
     *
     * Extraction is memoized with the decoded bitmap, so this is free for any poster the widget is
     * already drawing — which is every poster it asks about.
     */
    fun loadAccentsBlocking(context: Context, keys: Collection<String>): Map<String, Int> =
        keys.distinct().mapNotNull { key ->
            loadEntry(context, key)?.let { key to it.accent }
        }.toMap()

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
        val step = 4
        val hsv = FloatArray(3)
        var weightSum = 0f
        var hueX = 0f
        var hueY = 0f
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
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
                evicted.delete()
                synchronized(memoryCache) { memoryCache.remove(evicted.nameWithoutExtension) }
            }
    }
}
