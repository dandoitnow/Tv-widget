package com.example.tvwidget.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Everything the widget remembers, persisted in the Glance `DataStore` so the widget restores
 * identically after a launcher restart.
 *
 * Structured values are stored as JSON strings because `Preferences` only holds primitives.
 */
object WidgetState {

    private val json = Json { ignoreUnknownKeys = true }

    val TAB = stringPreferencesKey("tab")
    val FAVORITES = stringPreferencesKey("favorites")
    val REWATCH_LOG = stringPreferencesKey("rewatch_log")
    val OPEN_SHOW = stringPreferencesKey("open_show")
    val OPEN_REWATCH_LOG = stringPreferencesKey("open_rewatch_log")
    val ANTICIPATED = stringPreferencesKey("anticipated")
    val LAST_SYNC = longPreferencesKey("last_sync")

    /**
     * TODAY rests on today's first release. Glance's `LazyColumn` has no scroll-position API, so
     * the aired rows above today are collapsed behind a header row instead; this flag says whether
     * the user has opened them. It resets when the tab is left.
     */
    val SHOW_PAST = booleanPreferencesKey("show_past")

    private val favoritesSerializer = ListSerializer(FavoriteEpisode.serializer())
    private val rewatchSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val anticipatedSerializer = ListSerializer(AnticipatedShow.serializer())

    fun tab(prefs: Preferences): Tab =
        prefs[TAB]?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.TODAY

    fun favorites(prefs: Preferences): List<FavoriteEpisode> =
        decode(prefs[FAVORITES], favoritesSerializer) ?: SampleData.defaultFavorites()

    fun rewatchLog(prefs: Preferences): Map<String, List<String>> =
        decode(prefs[REWATCH_LOG], rewatchSerializer) ?: SampleData.defaultRewatchLog()

    fun anticipated(prefs: Preferences): List<AnticipatedShow> =
        decode(prefs[ANTICIPATED], anticipatedSerializer) ?: SampleData.anticipated()

    fun openShow(prefs: Preferences): String? = prefs[OPEN_SHOW]?.ifEmpty { null }

    fun openRewatchLog(prefs: Preferences): String? = prefs[OPEN_REWATCH_LOG]?.ifEmpty { null }

    fun showPast(prefs: Preferences): Boolean = prefs[SHOW_PAST] ?: false

    fun lastSync(prefs: Preferences): Long = prefs[LAST_SYNC] ?: 0L

    fun encodeFavorites(value: List<FavoriteEpisode>): String = json.encodeToString(favoritesSerializer, value)

    fun encodeRewatchLog(value: Map<String, List<String>>): String = json.encodeToString(rewatchSerializer, value)

    fun encodeAnticipated(value: List<AnticipatedShow>): String = json.encodeToString(anticipatedSerializer, value)

    /**
     * Groups favourites into shows in first-seen order, which is the order the FAVORITES tab lists
     * them in.
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
