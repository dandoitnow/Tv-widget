package com.example.tvwidget.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.example.tvwidget.work.AnticipatedSyncWorker

/**
 * Host for [TvWidget]. Placing the first widget schedules the daily premiere/catalog/poster
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
        // Otherwise CATALOG and real poster art wouldn't show up until the first daily tick.
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
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        AnticipatedSyncWorker.schedule(context)
        AnticipatedSyncWorker.runOnce(context)

        // Redraw here and now, rather than leaving it to the sync above.
        //
        // An app update is the one moment a widget is guaranteed to be holding RemoteViews that no
        // longer match the app that produced them. Upgrading Glance 1.1.0 to 1.2.0 changed the
        // generated layout resources those views reference, and because the redraw was left to a
        // WorkManager job that the platform is free to defer — and does, since it freezes this
        // process in the background — the launcher kept rendering views whose resource ids the new
        // APK no longer had. The widget went blank, and the only fix from the user's side was to
        // remove it and add it back.
        //
        // goAsync keeps the broadcast alive for the compose. This receiver is running, so the work
        // has a live process by definition; nothing here depends on a job being scheduled or a
        // Glance session being alive.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WidgetRedraw.all(context)
            } catch (failure: Throwable) {
                Log.w("TvWidget", "Post-update redraw failed", failure)
            } finally {
                pending.finish()
            }
        }
    }
}
