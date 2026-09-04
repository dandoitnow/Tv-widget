package com.example.tvwidget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Suggests shows based on what the user already tracks.
 *
 * TVMaze has no "similar shows" endpoint, so this is a content-based recommender built from what
 * the API does expose: genres. It scores candidates against a profile of the tracked list and ranks
 * them. That is a real recommender, not a rebranded popularity list — the same tab for a user who
 * tracks documentaries and a user who tracks horror returns different shows.
 *
 * The candidate pool is the schedule window [TvMazeApi.browse] already fetches for TRENDING, which
 * makes this tab essentially free: it re-ranks data that is fetched and cached anyway, rather than
 * pulling anything of its own.
 */
object Recommender {

    /**
     * A suggestion, with the reason it was suggested.
     *
     * The reason is not decoration. A recommendation nobody can explain reads as an advertisement,
     * and the fastest way to make one trustworthy is to say plainly which of *your* shows it came
     * from — the move Netflix and Spotify both settled on for the same reason.
     */
    data class Suggestion(val show: CatalogShow, val reason: String)

    /**
     * Genres shared by so much television that matching on them says nothing about taste. Without
     * this filter the list collapses into "popular drama", which is exactly the generic result this
     * tab exists to avoid.
     */
    private val WEAK_GENRES = setOf("Drama", "Comedy", "Action", "Adventure")

    suspend fun forTracked(
        context: Context,
        tracked: List<TrackedShow>,
        limit: Int = 40,
    ): List<Suggestion> = withContext(Dispatchers.IO) {
        if (tracked.isEmpty()) return@withContext emptyList()

        val withGenres = backfillGenres(context, tracked)
        // A weighted profile rather than a flat set: tracking four horror shows and one cooking show
        // should not make the two equally strong signals about what to suggest next.
        val profile = HashMap<String, Int>()
        withGenres.forEach { show ->
            show.genres.forEach { genre -> profile[genre] = (profile[genre] ?: 0) + 1 }
        }
        if (profile.isEmpty()) return@withContext emptyList()

        val trackedIds = tracked.map { it.tvMazeId }.toSet()
        // try/catch rather than runCatching: runCatching would also swallow CancellationException,
        // which is how a cancelled coroutine tells its children to stop.
        val candidates = try {
            TvMazeApi.browse(limit = 400)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            emptyList()
        }

        // Scored once and carried, rather than scored to filter and scored again to sort. The
        // duplicate call was cheap but it also let the two diverge, which is the kind of bug that
        // only ever shows up as "the ordering looks slightly wrong".
        candidates
            .filter { it.tvMazeId !in trackedIds && it.genres.isNotEmpty() }
            .map { candidate -> candidate to score(candidate, profile) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (candidate, _) -> Suggestion(candidate, reasonFor(candidate, withGenres)) }
    }

    /**
     * Overlap with the taste profile, with distinctive genres counting for more.
     *
     * A shared "Thriller" or "Science-Fiction" is a real signal about what someone likes; a shared
     * "Drama" is barely information at all, since most scripted television carries it. Weighting
     * them equally is what turns a recommender into a popularity chart.
     */
    private fun score(show: CatalogShow, profile: Map<String, Int>): Int =
        show.genres.sumOf { genre ->
            val weight = profile[genre] ?: return@sumOf 0
            if (genre in WEAK_GENRES) weight else weight * 3
        }

    /** The tracked show this candidate has most in common with — the "because you track X" line. */
    private fun reasonFor(candidate: CatalogShow, tracked: List<TrackedShow>): String {
        val best = tracked.maxByOrNull { source ->
            source.genres.count { it in candidate.genres && it !in WEAK_GENRES } * 3 +
                source.genres.count { it in candidate.genres }
        }
        val shared = best?.genres.orEmpty().filter { it in candidate.genres }
        return when {
            best == null || shared.isEmpty() -> candidate.genres.take(2).joinToString(" · ").uppercase()
            else -> "BECAUSE YOU TRACK ${best.title.uppercase()}"
        }
    }

    /**
     * Fills in genres for shows tracked before they were stored.
     *
     * Only the shows actually missing them are fetched, in parallel, and [TvMazeApi.schedule] is
     * already memoized — so this costs nothing on the common path and a handful of cached requests
     * once, for a list that is typically a few shows long.
     */
    private suspend fun backfillGenres(
        context: Context,
        tracked: List<TrackedShow>,
    ): List<TrackedShow> = coroutineScope {
        val resolved = tracked.map { show ->
            async {
                if (show.genres.isNotEmpty()) return@async show
                val genres = try {
                    TvMazeApi.schedule(show.tvMazeId).genres
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    emptyList()
                }
                if (genres.isEmpty()) show else show.copy(genres = genres)
            }
        }.awaitAll()

        // Written back so the next run is free, and so the recommender stops re-fetching the same
        // genres every time the tab is opened.
        resolved.filter { it.genres.isNotEmpty() }.forEach { show ->
            TrackedShowsRepository.updateGenres(context, show.tvMazeId, show.genres)
        }
        resolved
    }
}
