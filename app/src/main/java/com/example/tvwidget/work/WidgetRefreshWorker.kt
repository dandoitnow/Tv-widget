package com.example.tvwidget.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.tvwidget.widget.TvWidget
import java.util.concurrent.TimeUnit

/**
 * A single redraw, scheduled for the moment the next release airs.
 *
 * This exists to clean up after the live countdown. `Chronometer` in countdown mode does not stop at
 * zero — it carries on into negative time — so a widget that is not redrawn shortly after air time
 * sits there reading `-00:14:22`, which looks broken in a way the static label never did. The widget
 * would eventually correct itself on its next periodic update or the next tap, but "eventually" can
 * be half an hour of looking wrong.
 *
 * The cost is one deferred wakeup per upcoming episode, doing nothing but redrawing. That is a
 * fair price for the countdown never being caught counting the wrong way; the alternative — polling
 * often enough to notice air time on its own — would be orders of magnitude worse.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        TvWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget-release-tick"

        /**
         * Queues the redraw for [epochMillis], replacing any previously queued one — only the very
         * next release matters, and a sync that changes what that is should move the appointment
         * rather than add a second one.
         *
         * A moment already past is a no-op: the sync that just ran has already drawn the correct
         * state, so there is nothing left to correct.
         */
        fun scheduleAt(context: Context, epochMillis: Long?) {
            val delay = (epochMillis ?: return) - System.currentTimeMillis()
            if (delay <= 0) return
            // A small grace period past the air instant, so the redraw lands after the countdown
            // reaches zero rather than racing it and leaving the last second on screen.
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setInitialDelay(delay + 5_000L, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
