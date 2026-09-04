package com.example.tvwidget.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.example.tvwidget.ui.Dimens
import kotlinx.serialization.json.Json

/**
 * Everything the widget remembers, persisted in the Glance `DataStore` so the widget restores
 * identically after a launcher restart.
 *
 * Structured values are stored as JSON strings because `Preferences` only holds primitives.
 *
 * FAVORITES/REWATCH_LOG live here rather than in [TrackedShowsRepository] because they're written
 * from the widget (TODAY's star toggle) but also read — and, for unfavoriting, written — from
 * `MainActivity`'s Catalog screen, via `getAppWidgetState`/`updateAppWidgetState` against this
 * same Glance `DataStore`.
 */
object WidgetState {

    private val json = Json { ignoreUnknownKeys = true }

    val TAB = stringPreferencesKey("tab")
    val FAVORITES = stringPreferencesKey("favorites")
    val REWATCH_LOG = stringPreferencesKey("rewatch_log")
    val ANTICIPATED = stringPreferencesKey("anticipated")
    val LAST_SYNC = longPreferencesKey("last_sync")

    /**
     * How many rows the current tab is showing. Starts at [Dimens.RowPage] and grows by that much
     * each time SHOW MORE is tapped, so the common case stays small and fast and a long list is only
     * paid for by someone who actually scrolled to the end and asked for it.
     */
    val VISIBLE_ROWS = intPreferencesKey("visible_rows")

    /** TODAY rows built from the user's tracked shows (see [TrackedShowsRepository]); null until synced. */
    val TRACKED_RELEASES = stringPreferencesKey("tracked_releases")

    private val favoritesSerializer = ListSerializer(FavoriteEpisode.serializer())
    private val rewatchSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val anticipatedSerializer = ListSerializer(AnticipatedShow.serializer())
    private val releaseSerializer = ListSerializer(Release.serializer())

    fun tab(prefs: Preferences): Tab =
        prefs[TAB]?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.TODAY

    fun favorites(prefs: Preferences): List<FavoriteEpisode> =
        decode(prefs[FAVORITES], favoritesSerializer) ?: SampleData.defaultFavorites()

    fun rewatchLog(prefs: Preferences): Map<String, List<String>> =
        decode(prefs[REWATCH_LOG], rewatchSerializer) ?: SampleData.defaultRewatchLog()

    fun anticipated(prefs: Preferences): List<AnticipatedShow> =
        decode(prefs[ANTICIPATED], anticipatedSerializer) ?: SampleData.anticipated()

    /**
     * TODAY's rows. Once the user has tracked at least one show, real TVMaze-sourced releases take
     * over completely; until then the bundled demo content keeps the tab non-empty.
     */
    fun releases(prefs: Preferences): List<Release> =
        decode(prefs[TRACKED_RELEASES], releaseSerializer)?.takeIf { it.isNotEmpty() }
            ?: SampleData.releases()

    fun lastSync(prefs: Preferences): Long = prefs[LAST_SYNC] ?: 0L

    /** Rows currently revealed on the active tab. Reset to one page whenever the tab changes. */
    fun visibleRows(prefs: Preferences): Int =
        (prefs[VISIBLE_ROWS] ?: Dimens.RowPage).coerceIn(Dimens.RowPage, Dimens.MaxWidgetRows)

    fun encodeFavorites(value: List<FavoriteEpisode>): String = json.encodeToString(favoritesSerializer, value)

    fun encodeRewatchLog(value: Map<String, List<String>>): String = json.encodeToString(rewatchSerializer, value)

    fun encodeAnticipated(value: List<AnticipatedShow>): String = json.encodeToString(anticipatedSerializer, value)

    fun encodeReleases(value: List<Release>): String = json.encodeToString(releaseSerializer, value)

    /**
     * Groups favourites into shows in first-seen order, which is the order the app's Favorites view
     * lists them in.
     */
    fun favoriteShows(
        favorites: List<FavoriteEpisode>,
        rewatchLog: Map<String, List<String>>,
    ): List<FavoriteShow> = favorites
        .groupBy { it.showTitle }
        .map { (title, episodes) -> FavoriteShow(title, episodes, rewatchLog[title].orEmpty()) }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }
}
