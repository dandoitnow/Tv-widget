package com.example.tvwidget.data

import kotlinx.serialization.Serializable

/**
 * Weekday abbreviation to full name, for [Release.humanDay]. A top-level value rather than a
 * companion on [Release], because `@Serializable` generates its own `Companion` and a private one
 * would collide with it.
 */
private val WEEKDAY_NAMES = mapOf(
    "MON" to "MONDAY", "TUE" to "TUESDAY", "WED" to "WEDNESDAY", "THU" to "THURSDAY",
    "FRI" to "FRIDAY", "SAT" to "SATURDAY", "SUN" to "SUNDAY",
)

/**
 * Which tab the widget is showing. Persisted by [name].
 *
 * CATALOG isn't one of these — there's no widget-side content for it, just a header button that
 * opens the app straight into its Catalog screen (Tracked + Trending + For You + search, all native
 * Android views — see [com.example.tvwidget.MainActivity]).
 */
enum class Tab { TODAY, ANTICIPATED }

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
 * @param dateLabel long label for a specific date, e.g. `WED 26 AUG`.
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
    /** The show's IMDb id (`tt1234567`), when TVMaze has one on file. Null falls back to a search. */
    val imdbId: String? = null,
    /**
     * Absolute air time, for the widget's live countdown. Null for anything without a known clock
     * time (bundled sample rows), which simply falls back to the static `IN 3D` label.
     */
    val airEpochMillis: Long? = null,
    /** Episode number within its season, and how many that season holds. Drives the progress bar. */
    val episodeNumber: Int = 0,
    val seasonEpisodeCount: Int = 0,
) {
    val isToday: Boolean get() = dayOffset == 0
    val hasAired: Boolean get() = dayOffset < 0

    /**
     * Whether this release is close enough for a live ticking countdown.
     *
     * Capped at 24 hours because the widget renders that countdown with a `Chronometer`, which
     * formats as H:MM:SS with unbounded hours — a week out reads as `168:04:22`, which is precise,
     * useless, and ugly. Past a day, `IN 7D` is both prettier and more informative, so the threshold
     * is the design decision, not an implementation limit.
     */
    fun isLive(now: Long = System.currentTimeMillis()): Boolean {
        val at = airEpochMillis ?: return false
        val remaining = at - now
        return remaining > 0 && remaining < 24 * 60 * 60 * 1000L
    }

    /**
     * How long until this airs, in whichever unit is actually meaningful right now: `IN 3D`, then
     * `IN 8H`, then `IN 12M`, then `NOW`.
     *
     * A countdown that only ever counts days is precise and useless on the day itself — "IN 0D" and
     * "IN 1D" are the same sentence to someone deciding whether to wait up. Changing unit as the
     * moment approaches is what makes the number worth reading at every distance from it.
     */
    fun countdownLabel(now: Long = System.currentTimeMillis()): String {
        val at = airEpochMillis
            ?: return if (dayOffset > 0) "IN ${dayOffset}D" else ""
        val remaining = at - now
        val minutes = remaining / 60_000L
        return when {
            remaining <= 0L -> "NOW"
            minutes < 60L -> "IN ${minutes}M"
            minutes < 48L * 60L -> "IN ${minutes / 60L}H"
            else -> "IN ${minutes / (60L * 24L)}D"
        }
    }

    /**
     * The day, said the way a person would: `TONIGHT`, `TOMORROW`, `FRIDAY`, and only a bare date
     * once it is far enough away that the weekday stops being orienting.
     *
     * `WED 26` is a database field. Naming the near days is both warmer and faster to read, which is
     * the entire job of a label someone glances at from across a room.
     */
    val humanDay: String
        get() = when {
            dayOffset < 0 -> dayLabel
            dayOffset == 0 -> "TONIGHT"
            dayOffset == 1 -> "TOMORROW"
            dayOffset < 7 -> WEEKDAY_NAMES[dayLabel.take(3).uppercase()] ?: dayLabel
            else -> dayLabel
        }

    /** 0f..1f through the season, or null when the season's length isn't known. */
    val seasonProgress: Float?
        get() = if (seasonEpisodeCount > 0 && episodeNumber > 0) {
            (episodeNumber.toFloat() / seasonEpisodeCount).coerceIn(0f, 1f)
        } else {
            null
        }

    /** `E4 / 10`, or null when the season's length isn't known. */
    val seasonProgressLabel: String?
        get() = if (seasonEpisodeCount > 0 && episodeNumber > 0) {
            "E$episodeNumber / $seasonEpisodeCount"
        } else {
            null
        }

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
    /**
     * Poster art, when the feed supplied it. The schedule response these are built from already
     * carries the show's image, so taking it here saves a `singlesearch` request per title — which
     * at this list's length was most of the sync's traffic.
     */
    val posterUrl: String? = null,
    /**
     * TVMaze's id, so a POPULAR row can be tracked straight from the widget. Zero for the bundled
     * fallback rows, which are not real shows and so cannot be tracked.
     */
    val tvMazeId: Int = 0,
) {
    /** True when this row came from the live feed and can actually be tracked. */
    val trackable: Boolean get() = tvMazeId > 0

    /** `TODAY` on the day itself, otherwise `IN 7D`. */
    val awayLabel: String get() = if (daysAway <= 0) "TODAY" else "IN ${daysAway}D"
}

/**
 * One TVMaze show shown in [com.example.tvwidget.MainActivity]'s Catalog screen — a search hit, a
 * tracked-list entry, or a TRENDING row (today's popular currently-running shows via
 * [TvMazeApi.browse]).
 */
@Serializable
data class CatalogShow(
    val tvMazeId: Int,
    val title: String,
    val network: String,
    val status: String,
    val posterUrl: String?,
    val tracked: Boolean,
    val imdbId: String? = null,
    /** TVMaze's own genre tags. The whole basis of the FOR YOU tab — see [Recommender]. */
    val genres: List<String> = emptyList(),
    /**
     * The most recent episode code, e.g. `S03E10` — how far along the show is.
     *
     * Null when the source didn't carry one; the Catalog row fills it in lazily rather than making
     * every list wait on a per-show request before it can render at all.
     */
    val latestEpisode: String? = null,
)

/**
 * A show the user added from CATALOG. Stored app-wide (not per-widget-instance) in
 * [TrackedShowsRepository]; the sync worker turns this list into TODAY's [Release] rows by pulling
 * each show's previous/next episode from TVMaze.
 */
@Serializable
data class TrackedShow(
    val tvMazeId: Int,
    val title: String,
    val network: String,
    val posterUrl: String?,
    val imdbId: String? = null,
    /**
     * Genres, stored at track time so recommendations cost nothing to compute. Empty for shows
     * tracked before this existed; [Recommender] backfills those on demand.
     */
    val genres: List<String> = emptyList(),
)
