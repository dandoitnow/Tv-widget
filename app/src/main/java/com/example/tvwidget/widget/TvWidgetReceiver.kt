package com.example.tvwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.example.tvwidget.work.AnticipatedSyncWorker

/**
 * Host for [TvWidget]. Placing the first widget schedules the daily premiere/catalogue/poster
 * refresh; removing the last one tears it down.
 *
 * There used to also be a per-minute `CountdownTicker` alarm here to keep a live `HH:MM` countdown
 * in the header current. That readout was removed from the header entirely (it was one of the
 * "useless top-right corner" items), which left the ticker waking the process and redrawing the
 * whole widget every 60 seconds for a value nothing displayed anymore — pure battery cost with no
 * visible effect. Removed along with it.
 */
class TvWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TvWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AnticipatedSyncWorker.schedule(context)
        // Otherwise CATALOGUE and real poster art wouldn't show up until the first daily tick.
        AnticipatedSyncWorker.runOnce(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        AnticipatedSyncWorker.cancel(context)
    }

    /**
     * Resyncs after the app itself is updated.
     *
     * [onEnabled] only fires for the *first* widget ever placed, and the periodic refresh is daily,
     * so without this an update could leave the widget showing pre-update data for the better part
     * of a day. That is not hypothetical: an update is exactly when cached data changes shape —
     * `PosterStore.CACHE_VERSION` deliberately wipes the poster cache when the art's finishing
     * changes, and `Release` gained an air timestamp the live countdown needs and older persisted
     * rows do not carry. Both heal on the next sync, so the next sync should be now.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AnticipatedSyncWorker.schedule(context)
            AnticipatedSyncWorker.runOnce(context)
        }
    }
}
