package com.example.tvwidget.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Shows the user added from CATALOG. This lives app-wide in plain [android.content.SharedPreferences]
 * rather than in Glance's per-widget-instance `DataStore`, because it has to be reachable from
 * [com.example.tvwidget.MainActivity] (the search screen), which has no `GlanceId` to write through.
 *
 * [com.example.tvwidget.work.AnticipatedSyncWorker] reads this list, turns it into real [Release]
 * rows via TVMaze, and writes the result into every widget instance's state — this object never
 * touches the widget directly.
 */
object TrackedShowsRepository {

    /**
     * Deliberately still spelled the old way. This is the on-disk name of the preferences file that
     * holds every show the user tracks, and renaming it does not migrate anything — it silently
     * points at a new, empty file and the user's list is simply gone. Renaming the product's
     * spelling to "Catalog" briefly did exactly that. Storage keys are not cosmetic.
     */
    private const val PREFS_NAME = "catalogue_store"
    private const val KEY_TRACKED = "tracked_shows"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TrackedShow.serializer())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(context: Context): List<TrackedShow> {
        val raw = prefs(context).getString(KEY_TRACKED, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun isTracked(context: Context, tvMazeId: Int): Boolean =
        list(context).any { it.tvMazeId == tvMazeId }

    /**
     * The one way this list is ever changed.
     *
     * Every mutation here is a read, a modify and a write, and they are called from at least four
     * places that do not coordinate: the Catalog screen, the widget's track button (a broadcast on
     * its own thread), the sync worker backfilling IMDb ids, and the recommender backfilling genres
     * — the last of which writes an entry per show from a parallel `awaitAll`. Two of those
     * overlapping means one read sees the list before the other's write, and that update is simply
     * lost with nothing to show for it.
     *
     * A plain lock is sufficient because all of it runs in one process; SharedPreferences is
     * per-process, so there is no second writer to coordinate with.
     */
    private fun mutate(context: Context, transform: (List<TrackedShow>) -> List<TrackedShow>) {
        synchronized(lock) { save(context, transform(list(context))) }
    }

    private val lock = Any()

    fun add(context: Context, show: TrackedShow) = mutate(context) { current ->
        if (current.any { it.tvMazeId == show.tvMazeId }) current else current + show
    }

    fun remove(context: Context, tvMazeId: Int) = mutate(context) { current ->
        current.filterNot { it.tvMazeId == tvMazeId }
    }

    /**
     * Backfills a show's IMDb id after the fact — [AnticipatedSyncWorker] calls this when a sync's
     * episode lookup turns up an id for a show tracked before [TrackedShow.imdbId] existed, so
     * already-tracked shows get a working IMDb link without needing to be re-tracked.
     */
    fun updateImdbId(context: Context, tvMazeId: Int, imdbId: String) = mutate(context) { current ->
        if (current.none { it.tvMazeId == tvMazeId && it.imdbId != imdbId }) {
            current
        } else {
            current.map { if (it.tvMazeId == tvMazeId) it.copy(imdbId = imdbId) else it }
        }
    }

    /**
     * Backfills genres for a show tracked before they were stored, so [Recommender] only ever pays
     * the lookup cost once per show rather than on every visit to the RECOMMENDED tab.
     */
    fun updateGenres(context: Context, tvMazeId: Int, genres: List<String>) = mutate(context) { current ->
        if (current.none { it.tvMazeId == tvMazeId && it.genres != genres }) {
            current
        } else {
            current.map { if (it.tvMazeId == tvMazeId) it.copy(genres = genres) else it }
        }
    }

    private fun save(context: Context, shows: List<TrackedShow>) {
        prefs(context).edit()
            .putString(KEY_TRACKED, json.encodeToString(serializer, shows))
            .apply()
    }
}
