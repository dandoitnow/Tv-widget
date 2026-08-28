package com.example.tvwidget.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stand-in content so the widget renders a complete design before the app database and the remote
 * premiere feed are wired up. Dates are generated relative to the current day, so TODAY always has
 * releases on it.
 *
 * Replace [releases] with a query against the app's watchlist (episode-level air dates from
 * TMDB/Trakt) and [anticipated] with the cached remote list; nothing else in the widget changes.
 */
object SampleData {

    private val dayFormat = DateTimeFormatter.ofPattern("EEE dd", Locale.US)
    private val dateFormat = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.US)
    private val logFormat = DateTimeFormatter.ofPattern("dd MMM yy", Locale.US)

    fun dayLabel(date: LocalDate): String = date.format(dayFormat).uppercase(Locale.US)

    fun dateLabel(date: LocalDate): String = date.format(dateFormat).uppercase(Locale.US)

    /** Entry stamp used by the rewatch log, e.g. `12 JUL 26`. */
    fun logDateLabel(date: LocalDate = LocalDate.now()): String =
        date.format(logFormat).uppercase(Locale.US)

    private data class Seed(
        val offset: Int,
        val title: String,
        val episode: String,
        val time: String,
        val network: String,
        val status: ReleaseStatus,
    )

    private val seeds = listOf(
        Seed(-2, "Silo", "S03E04", "21:00", "APPLE TV+", ReleaseStatus.WATCHED),
        Seed(-2, "The Diplomat", "S03E06", "03:00", "NETFLIX", ReleaseStatus.WATCHED),
        Seed(-1, "The Bear", "S05E02", "03:00", "HULU", ReleaseStatus.UNWATCHED),
        Seed(0, "Severance", "S03E01", "21:00", "APPLE TV+", ReleaseStatus.AIRS_TONIGHT),
        Seed(0, "Fallout", "S02E07", "00:01", "PRIME", ReleaseStatus.AVAILABLE),
        Seed(0, "Dark Winds", "S04E03", "22:00", "AMC", ReleaseStatus.AIRS_TONIGHT),
        Seed(1, "Dune: Prophecy", "S02E02", "21:00", "HBO", ReleaseStatus.SCHEDULED),
        Seed(2, "The Last of Us", "S03E05", "21:00", "HBO", ReleaseStatus.SCHEDULED),
        Seed(2, "Slow Horses", "S06E04", "03:00", "APPLE TV+", ReleaseStatus.SCHEDULED),
        Seed(3, "Foundation", "S04E08", "03:00", "APPLE TV+", ReleaseStatus.SCHEDULED),
    )

    /** Chronological: aired days first, then today, then scheduled. */
    fun releases(today: LocalDate = LocalDate.now()): List<Release> = seeds
        .sortedWith(compareBy({ it.offset }, { it.time }))
        .map { seed ->
            val date = today.plusDays(seed.offset.toLong())
            Release(
                showTitle = seed.title,
                episodeCode = seed.episode,
                dayOffset = seed.offset,
                dayLabel = if (seed.offset == 0) "TODAY" else dayLabel(date),
                dateLabel = dateLabel(date),
                airTime = seed.time,
                network = seed.network,
                status = seed.status,
            )
        }

    fun anticipated(): List<AnticipatedShow> = listOf(
        AnticipatedShow("Stranger Things", "S05 PREMIERE", "NETFLIX", "04 SEP", 7, 96),
        AnticipatedShow("Euphoria", "S03 PREMIERE", "HBO", "11 SEP", 14, 88),
        AnticipatedShow("The Devil in the White City", "NEW SERIES", "HULU", "19 SEP", 22, 74),
        AnticipatedShow("Wednesday", "S03 PREMIERE", "NETFLIX", "02 OCT", 35, 69),
        AnticipatedShow("House of the Dragon", "S04 PREMIERE", "HBO", "12 OCT", 45, 63),
        AnticipatedShow("Pluribus", "NEW SERIES", "APPLE TV+", "24 OCT", 57, 51),
    )

    fun defaultFavorites(today: LocalDate = LocalDate.now()): List<FavoriteEpisode> {
        val byKey = releases(today).associateBy { it.showTitle to it.episodeCode }
        fun favorite(title: String, episode: String, fallbackLabel: String) = FavoriteEpisode(
            showTitle = title,
            episodeCode = episode,
            label = byKey[title to episode]?.favoriteLabel() ?: fallbackLabel,
        )
        return listOf(
            favorite("Severance", "S03E01", "APPLE TV+"),
            favorite("Severance", "S02E07", "THE COLD HARBOR · S2"),
            favorite("The Bear", "S05E02", "HULU"),
            favorite("Silo", "S03E04", "APPLE TV+"),
            favorite("The Last of Us", "S03E05", "HBO"),
        )
    }

    fun defaultRewatchLog(today: LocalDate = LocalDate.now()): Map<String, List<String>> = mapOf(
        "Severance" to listOf(
            logDateLabel(today.minusDays(47)),
            logDateLabel(today.minusDays(25)),
        ),
        "Silo" to listOf(logDateLabel(today.minusDays(61))),
    )
}
