package com.example.tvwidget.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.example.tvwidget.data.AnticipatedSource
import com.example.tvwidget.data.BundledAnticipatedSource
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.widget.TvWidget
import java.util.concurrent.TimeUnit

/**
 * Refreshes the ANTICIPATED list once a day and caches it in the widget state, so the tab renders
 * from disk and never blocks on the network at draw time. The header shows the resulting sync time.
 */
class AnticipatedSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shows = runCatching { source.fetch() }.getOrElse { return Result.retry() }
        val encoded = WidgetState.encodeAnticipated(shows)
        val now = System.currentTimeMillis()

        val glanceIds = GlanceAppWidgetManager(applicationContext).getGlanceIds(TvWidget::class.java)
        glanceIds.forEach { glanceId ->
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[WidgetState.ANTICIPATED] = encoded
                prefs[WidgetState.LAST_SYNC] = now
            }
        }
        TvWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "anticipated-sync"

        /** Swap this for a TMDB/Trakt-backed implementation once the app's API client exists. */
        var source: AnticipatedSource = BundledAnticipatedSource

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

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
