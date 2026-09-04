package com.example.tvwidget.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.example.tvwidget.data.AnticipatedShow
import com.example.tvwidget.data.AnticipatedSource
import com.example.tvwidget.data.BundledAnticipatedSource
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.Release
import com.example.tvwidget.data.ReleaseStatus
import com.example.tvwidget.data.SampleData
import com.example.tvwidget.data.TrackedShow
import com.example.tvwidget.data.TrackedShowsRepository
import com.example.tvwidget.data.TvMazeAnticipatedSource
import com.example.tvwidget.data.TvMazeApi
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.widget.TvWidget
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The widget's one background sync point. On each run it:
 *  1. Refreshes ANTICIPATED (the curated premiere list — never the user's own shows).
 *  2. Rebuilds TODAY from [TrackedShowsRepository] by pulling each tracked show's nearest
 *     previous/next episode from TVMaze.
 *  3. Caches poster art for everything above via [PosterStore], since widgets can't fetch images at
 *     draw time.
 *
 * CATALOGUE's RECOMMENDED browse list is *not* synced here — it's fetched live by
 * `MainActivity` when that screen opens, since it's a plain Activity list with no widget-side
 * state or poster-preload constraint to work around.
 *
 * Runs on a daily timer, plus once immediately whenever [runOnce] is called (tracking/untracking a
 * show from the Catalogue screen).
 */
class AnticipatedSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val anticipated = runCatching { source.fetch() }.getOrElse { SampleData.anticipated() }
        val tracked = TrackedShowsRepository.list(applicationContext)
        val trackedReleases = buildTrackedReleases(tracked)

        cachePosters(anticipated, tracked)

        val encodedAnticipated = WidgetState.encodeAnticipated(anticipated)
        val encodedReleases = WidgetState.encodeReleases(trackedReleases)
        val now = System.currentTimeMillis()

        val glanceIds = GlanceAppWidgetManager(applicationContext).getGlanceIds(TvWidget::class.java)
        glanceIds.forEach { glanceId ->
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[WidgetState.ANTICIPATED] = encodedAnticipated
                prefs[WidgetState.TRACKED_RELEASES] = encodedReleases
                prefs[WidgetState.LAST_SYNC] = now
            }
        }
        TvWidget().updateAll(applicationContext)

        // Book a redraw for when the next release actually airs, so the live countdown is not left
        // ticking past zero into negative time. See WidgetRefreshWorker.
        WidgetRefreshWorker.scheduleAt(
            applicationContext,
            trackedReleases.mapNotNull { it.airEpochMillis }
                .filter { it > now }
                .minOrNull(),
        )
        return Result.success()
    }

    /** One [Release] each for a tracked show's most recent aired episode and its next scheduled one. */
    private suspend fun buildTrackedReleases(tracked: List<TrackedShow>): List<Release> {
        val today = LocalDate.now()
        val dayFormat = DateTimeFormatter.ofPattern("EEE dd", Locale.US)
        return tracked.flatMap { show ->
            val schedule = runCatching { TvMazeApi.schedule(show.tvMazeId) }
                .getOrElse { TvMazeApi.ShowSchedule(null, null) }

            // Backfills shows tracked before TrackedShow carried an imdbId — without this, an
            // already-tracked show would need to be untracked and re-tracked to ever get one.
            if (show.imdbId == null && schedule.imdbId != null) {
                TrackedShowsRepository.updateImdbId(applicationContext, show.tvMazeId, schedule.imdbId)
            }
            val imdbId = show.imdbId ?: schedule.imdbId

            listOfNotNull(schedule.previous, schedule.next).map { episode ->
                val offset = java.time.temporal.ChronoUnit.DAYS.between(today, episode.airDate).toInt()
                Release(
                    showTitle = show.title,
                    episodeCode = episode.code,
                    dayOffset = offset,
                    dayLabel = if (offset == 0) "TODAY" else episode.airDate.format(dayFormat)
                        .uppercase(Locale.US),
                    dateLabel = episode.airDate.format(DateTimeFormatter.ofPattern("EEE dd MMM", Locale.US))
                        .uppercase(Locale.US),
                    airTime = episode.airTime,
                    network = show.network,
                    status = when {
                        offset < 0 -> ReleaseStatus.WATCHED
                        offset == 0 -> ReleaseStatus.AIRS_TONIGHT
                        else -> ReleaseStatus.SCHEDULED
                    },
                    imdbId = imdbId,
                    airEpochMillis = airInstantOf(episode),
                    episodeNumber = episode.number,
                    seasonEpisodeCount = schedule.seasonLengths[episode.season] ?: 0,
                )
            }
        }.sortedWith(compareBy({ it.dayOffset }, { it.airTime }))
    }

    /**
     * The episode's air time as an absolute instant, for the widget's live countdown.
     *
     * TVMaze leaves `airTime` empty for plenty of streaming releases, which is a real answer rather
     * than missing data — a show that "drops Thursday" has no broadcast slot. Those default to 20:00
     * local, since a countdown to an approximate evening hour is far more useful than no countdown,
     * and the widget stops showing it 24 hours out anyway.
     */
    private fun airInstantOf(episode: TvMazeApi.EpisodeInfo): Long? = runCatching {
        val time = if (episode.airTime.isBlank()) LocalTime.of(20, 0) else LocalTime.parse(episode.airTime)
        episode.airDate.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /**
     * Tracked shows are cached first and in parallel, ahead of the (more numerous, one-request-each)
     * demo/anticipated titles. [runOnce] uses `ExistingWorkPolicy.REPLACE`, which cancels an in-flight
     * run outright — every track/untrack tap enqueues one, so tracking several shows back-to-back
     * used to be able to cancel an earlier run before it ever reached that show's poster download,
     * leaving it stuck on the placeholder. Doing the few tracked posters first, concurrently, secures
     * them well before a follow-up tap has any chance to cancel the run.
     */
    private suspend fun cachePosters(anticipated: List<AnticipatedShow>, tracked: List<TrackedShow>) = coroutineScope {
        tracked.map { show ->
            async { PosterStore.ensureCached(applicationContext, PosterStore.keyFor(show.title), show.posterUrl) }
        }.awaitAll()

        // POPULAR rows now arrive from the schedule feed with their artwork already attached, so
        // these cost a download and nothing else. That matters at this list's new length: resolving
        // forty posters by title would have meant forty `singlesearch` round trips before the first
        // image was even requested.
        anticipated.filter { it.posterUrl != null }.map { show ->
            async { PosterStore.ensureCached(applicationContext, PosterStore.keyFor(show.title), show.posterUrl) }
        }.awaitAll()

        // Whatever is left has no known URL — the bundled seed data, and the bundled POPULAR list on
        // a device that has never reached the network. Those still need a lookup by title.
        val unresolved = (SampleData.releases().map { it.showTitle } +
            anticipated.filter { it.posterUrl == null }.map { it.title }).distinct()
        unresolved.map { title ->
            async {
                val key = PosterStore.keyFor(title)
                if (!PosterStore.has(applicationContext, key)) {
                    val url = runCatching { TvMazeApi.posterFor(title) }.getOrNull()
                    PosterStore.ensureCached(applicationContext, key, url)
                }
            }
        }.awaitAll()
    }

    companion object {
        private const val WORK_NAME = "anticipated-sync"
        private const val WORK_NAME_ONE_OFF = "anticipated-sync-once"

        /**
         * Where POPULAR's rows come from. Live by default; `doWork` falls back to
         * [BundledAnticipatedSource] when a fetch fails, so a device that has never reached the
         * network still shows something rather than an empty tab.
         */
        var source: AnticipatedSource = TvMazeAnticipatedSource

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnticipatedSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP so re-adding a widget does not reset the daily cadence.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Immediate one-off run — used right after a show is tracked/untracked from the Catalogue screen. */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnticipatedSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_ONE_OFF, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
