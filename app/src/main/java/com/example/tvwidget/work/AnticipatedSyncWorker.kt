package com.example.tvwidget.work

import android.content.Context
import androidx.work.BackoffPolicy
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
import com.example.tvwidget.data.AnticipatedSource
import com.example.tvwidget.data.BundledAnticipatedSource
import com.example.tvwidget.data.CatalogueShow
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.Release
import com.example.tvwidget.data.ReleaseStatus
import com.example.tvwidget.data.SampleData
import com.example.tvwidget.data.TrackedShow
import com.example.tvwidget.data.TrackedShowsRepository
import com.example.tvwidget.data.TvMazeApi
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.widget.TvWidget
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The widget's one background sync point. On each run it:
 *  1. Refreshes ANTICIPATED (the curated premiere list — never the user's own shows).
 *  2. Rebuilds TODAY from [TrackedShowsRepository] by pulling each tracked show's nearest
 *     previous/next episode from TVMaze.
 *  3. Refreshes CATALOGUE's RECOMMENDED browse list.
 *  4. Caches poster art for everything above via [PosterStore], since widgets can't fetch images at
 *     draw time.
 *
 * Runs on a daily timer, plus once immediately whenever [runOnce] is called (tracking/untracking a
 * show, or RECOMMENDED's own "TAP TO RETRY" empty state after a failed sync).
 */
class AnticipatedSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val anticipated = runCatching { source.fetch() }.getOrElse { SampleData.anticipated() }
        val tracked = TrackedShowsRepository.list(applicationContext)
        val trackedReleases = buildTrackedReleases(tracked)

        // A failed browse() must not blank out whatever RECOMMENDED was already showing — on
        // failure the RECOMMENDED key below is simply left untouched, so a transient network error
        // never wipes an otherwise-populated sub-tab.
        val browseResult = runCatching { TvMazeApi.browse() }
        val recommended = browseResult.getOrNull()?.let { browsed -> mergeTracked(browsed, tracked) }

        cachePosters(anticipated.map { it.title }, tracked, recommended.orEmpty())

        val encodedAnticipated = WidgetState.encodeAnticipated(anticipated)
        val encodedReleases = WidgetState.encodeReleases(trackedReleases)
        val now = System.currentTimeMillis()

        val glanceIds = GlanceAppWidgetManager(applicationContext).getGlanceIds(TvWidget::class.java)
        glanceIds.forEach { glanceId ->
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[WidgetState.ANTICIPATED] = encodedAnticipated
                prefs[WidgetState.TRACKED_RELEASES] = encodedReleases
                if (recommended != null) {
                    prefs[WidgetState.RECOMMENDED] = WidgetState.encodeRecommended(recommended)
                }
                prefs[WidgetState.LAST_SYNC] = now
            }
        }
        TvWidget().updateAll(applicationContext)

        // Retrying (with WorkManager's exponential backoff) is what lets RECOMMENDED recover from a
        // failed first sync on its own — without this, a network blip on first widget-add left the
        // sub-tab stuck on "LOADING…" until the next daily tick, with no way back short of the
        // user's manual retry tap.
        return if (browseResult.isFailure) Result.retry() else Result.success()
    }

    /**
     * Shows the user has tracked always stay visible in RECOMMENDED, even once they scroll out of
     * today's browse feed — otherwise there would be no way to find (and untrack) a show that isn't
     * airing today. Tracked shows are pinned first, then the rest of the browse list.
     */
    private fun mergeTracked(browsed: List<CatalogueShow>, tracked: List<TrackedShow>): List<CatalogueShow> {
        val browsedIds = browsed.map { it.tvMazeId }.toSet()
        val pinned = tracked.filterNot { it.tvMazeId in browsedIds }.map { show ->
            CatalogueShow(
                tvMazeId = show.tvMazeId,
                title = show.title,
                network = show.network,
                status = "TRACKED",
                posterUrl = show.posterUrl,
                tracked = true,
            )
        }
        val rest = browsed.map { show -> show.copy(tracked = tracked.any { it.tvMazeId == show.tvMazeId }) }
        return pinned + rest
    }

    /** One [Release] each for a tracked show's most recent aired episode and its next scheduled one. */
    private suspend fun buildTrackedReleases(tracked: List<TrackedShow>): List<Release> {
        val today = LocalDate.now()
        val dayFormat = DateTimeFormatter.ofPattern("EEE dd", Locale.US)
        return tracked.flatMap { show ->
            val schedule = runCatching { TvMazeApi.schedule(show.tvMazeId) }
                .getOrElse { TvMazeApi.ShowSchedule(null, null) }
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
                )
            }
        }.sortedWith(compareBy({ it.dayOffset }, { it.airTime }))
    }

    private suspend fun cachePosters(
        anticipatedTitles: List<String>,
        tracked: List<TrackedShow>,
        recommended: List<CatalogueShow>,
    ) {
        // Sample/demo titles (TODAY's bundled seed data, ANTICIPATED) have no known poster URL —
        // resolve one by title via TVMaze's single-show search.
        val demoTitles = (SampleData.releases().map { it.showTitle } + anticipatedTitles).distinct()
        demoTitles.forEach { title ->
            val key = PosterStore.keyFor(title)
            if (!PosterStore.has(applicationContext, key)) {
                val url = runCatching { TvMazeApi.posterFor(title) }.getOrNull()
                PosterStore.ensureCached(applicationContext, key, url)
            }
        }
        // Tracked and RECOMMENDED shows already carry their own poster URL from TVMaze.
        (tracked.map { it.title to it.posterUrl } + recommended.map { it.title to it.posterUrl })
            .distinctBy { it.first }
            .forEach { (title, url) -> PosterStore.ensureCached(applicationContext, PosterStore.keyFor(title), url) }
    }

    companion object {
        private const val WORK_NAME = "anticipated-sync"
        private const val WORK_NAME_ONE_OFF = "anticipated-sync-once"

        /** Minimum WorkManager allows; caps how fast a failed sync can retry. */
        private const val RETRY_BACKOFF_SECONDS = 30L

        /** Swap this for a TMDB/Trakt-backed implementation once the app's API client exists. */
        var source: AnticipatedSource = BundledAnticipatedSource

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnticipatedSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP so re-adding a widget does not reset the daily cadence.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Immediate one-off run — used right after a show is tracked/untracked, and by RECOMMENDED's
         * own "TAP TO RETRY" empty state after a failed first sync.
         */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnticipatedSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_ONE_OFF, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
