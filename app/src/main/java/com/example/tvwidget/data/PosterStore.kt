package com.example.tvwidget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
 * The cache is bounded: every show ever searched or tracked used to leave a PNG on disk forever.
 * [MAX_CACHED_POSTERS] caps it, evicted least-recently-used first — "used" meaning either re-cached
 * or read via [loadBitmaps], both of which touch the file's mtime.
 */
object PosterStore {

    private const val TAG = "PosterStore"
    private const val TARGET_WIDTH = 104
    private const val TARGET_HEIGHT = 144

    /** ~104x144 PNGs run well under 50KB each; 200 of them is a low-single-digit-MB cache. */
    private const val MAX_CACHED_POSTERS = 200

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "posters").apply { mkdirs() }

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
                FileOutputStream(target).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 90, out) }
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
     * Loads every cached bitmap for [keys], skipping any not yet on disk. Reads happen off the UI
     * thread. Touches each hit's mtime — reading a poster counts as using it, for eviction purposes.
     */
    suspend fun loadBitmaps(context: Context, keys: Collection<String>): Map<String, Bitmap> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            keys.distinct().mapNotNull { key ->
                val file = file(context, key)
                if (!file.exists()) return@mapNotNull null
                val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                    ?: return@mapNotNull null
                file.setLastModified(now)
                key to bitmap
            }.toMap()
        }

    /** Deletes the oldest-by-mtime files once the cache grows past [MAX_CACHED_POSTERS]. */
    private fun evictLeastRecentlyUsed(context: Context) {
        val files = dir(context).listFiles() ?: return
        val overflow = files.size - MAX_CACHED_POSTERS
        if (overflow <= 0) return
        files.sortedBy { it.lastModified() }
            .take(overflow)
            .forEach { it.delete() }
    }
}
