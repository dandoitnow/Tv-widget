package com.example.tvwidget.data

import kotlinx.serialization.Serializable

/**
 * Which tab the widget is showing. Persisted by [name].
 *
 * CATALOGUE is deliberately not a tab: there's no widget-side browsable list, just a header button
 * that opens the app straight into its search screen (see [com.example.tvwidget.widget.Header]).
 */
enum class Tab { TODAY, ANTICIPATED, FAVORITES }

/** Status shown in the right column of a TODAY row. */
enum class ReleaseStatus(val label: String) {
    AIRS_TONIGHT("AIRS TONIGHT"),
    AVAILABLE("AVAILABLE"),
    UNWATCHED("UNWATCHED"),
    WATCHED("WATCHED"),
    SCHEDULED("SCHEDULED"),
}

/**
 * One episode release in the TODAY feed. These come from the user's watchlist — episode-level air
 * dates held in the app database and filled from TMDB/Trakt.
 *
 * @param dayOffset days from today: negative aired, 0 today, positive scheduled.
 * @param dayLabel short feed label, e.g. `WED 26`.
 * @param dateLabel long label used when the episode is favourited, e.g. `WED 26 AUG`.
 * @param airTime local air time as `HH:mm`.
 */
@Serializable
data class Release(
    val showTitle: String,
    val episodeCode: String,
    val dayOffset: Int,
    val dayLabel: String,
    val dateLabel: String,
    val airTime: String,
    val network: String,
    val status: ReleaseStatus,
) {
    val isToday: Boolean get() = dayOffset == 0
    val hasAired: Boolean get() = dayOffset < 0

    /** Label stored alongside a favourite, e.g. `FRI 28 AUG · APPLE TV+`. */
    fun favoriteLabel(): String = "$dateLabel · $network"
}

/**
 * One entry of the auto-curated premiere list. Never the user's chosen shows — this is populated
 * from a trending/premiere source (TMDB `tv/on_the_air` + `trending`, or Trakt `shows/anticipated`).
 *
 * @param hypePercent 0..100, drives the hype bar fill.
 */
@Serializable
data class AnticipatedShow(
    val title: String,
    val kind: String,
    val network: String,
    val premiereDate: String,
    val daysAway: Int,
    val hypePercent: Int,
) {
    /** `TODAY` on the day itself, otherwise `IN 7D`. */
    val awayLabel: String get() = if (daysAway <= 0) "TODAY" else "IN ${daysAway}D"
}

/** A favourited episode. The identity of a favourite is show title + episode code. */
@Serializable
data class FavoriteEpisode(
    val showTitle: String,
    val episodeCode: String,
    val label: String,
)

/** A favourite show plus the episodes saved under it, as rendered by the FAVORITES tab. */
data class FavoriteShow(
    val title: String,
    val episodes: List<FavoriteEpisode>,
    val rewatchDates: List<String>,
) {
    /** The rewatch count is always derived from the log, never stored separately. */
    val rewatchCount: Int get() = rewatchDates.size
    val episodeCountLabel: String get() = "%02d EP".format(episodes.size)
}

/**
 * One TVMaze show shown in [com.example.tvwidget.MainActivity]'s search screen — either a search hit
 * or an entry from the user's tracked-shows list, both rendered by the same row/adapter there.
 */
@Serializable
data class CatalogueShow(
    val tvMazeId: Int,
    val title: String,
    val network: String,
    val status: String,
    val posterUrl: String?,
    val tracked: Boolean,
)

/**
 * A show the user added from CATALOGUE. Stored app-wide (not per-widget-instance) in
 * [TrackedShowsRepository]; the sync worker turns this list into TODAY's [Release] rows by pulling
 * each show's previous/next episode from TVMaze.
 */
@Serializable
data class TrackedShow(
    val tvMazeId: Int,
    val title: String,
    val network: String,
    val posterUrl: String?,
)
