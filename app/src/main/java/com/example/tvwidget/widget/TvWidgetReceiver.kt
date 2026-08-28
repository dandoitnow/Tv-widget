package com.example.tvwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.example.tvwidget.work.AnticipatedSyncWorker
import com.example.tvwidget.work.CountdownTicker

/**
 * Host for [TvWidget]. Placing the first widget schedules the daily premiere refresh and the
 * per-minute countdown tick; removing the last one tears both down.
 */
class TvWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TvWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AnticipatedSyncWorker.schedule(context)
        // Otherwise CATALOGUE and real poster art wouldn't show up until the first daily tick.
        AnticipatedSyncWorker.runOnce(context)
        CountdownTicker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        AnticipatedSyncWorker.cancel(context)
        CountdownTicker.cancel(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // A launcher restart drops any pending alarm, so re-arm on every update broadcast.
        CountdownTicker.schedule(context)
    }
}
