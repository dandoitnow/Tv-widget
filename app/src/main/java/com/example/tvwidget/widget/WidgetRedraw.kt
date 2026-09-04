package com.example.tvwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose

/**
 * Composes the widget and hands the result straight to [AppWidgetManager].
 *
 * Every redraw in the app goes through here rather than through `GlanceAppWidget.update()` or
 * `updateAll()`, and the difference is not cosmetic. Those two do not draw anything themselves: they
 * signal a long-lived `SessionWorker` that owns the live composition, and if it is gone they ask
 * WorkManager to start a new one. On this device neither step is dependable, because Samsung's
 * Freecess freezes the app process in the background (`FZ : com.example.tvwidget reason: Bg`), which
 * kills the session and defers the job that would rebuild it.
 *
 * That unreliability has now caused two distinct failures. First, tab switches wrote their new state
 * and never appeared — the datastore's timestamp moved on every tap while the widget sat unchanged.
 * Then, after Glance was upgraded from 1.1.0 to 1.2.0, the widget went blank entirely and only came
 * back when it was removed and re-added: the launcher was still holding RemoteViews built against
 * the *old* Glance's generated layout resources, whose ids the new APK no longer matches, and the
 * update that should have replaced them was sitting behind the same frozen process.
 *
 * Composing here removes every moving part. It runs in whatever already-live context asked for it,
 * and the RemoteViews go to the host directly, with no session to be alive and no job to be
 * scheduled. Glance's own `update` remains the fallback if composing throws — a late redraw beats
 * none.
 */
object WidgetRedraw {

    private const val TAG = "TvWidget"

    /** Redraws one widget instance. */
    suspend fun now(context: Context, glanceId: GlanceId) {
        val widget = TvWidget()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
            val manager = AppWidgetManager.getInstance(context)
            val views = widget.compose(
                context = context,
                id = glanceId,
                // The widget's real options, so SizeMode.Responsive still picks the right breakpoint
                // — composing without them would size the result for a default that may not be this
                // widget's actual footprint.
                options = manager.getAppWidgetOptions(appWidgetId),
            )
            manager.updateAppWidget(appWidgetId, views)
            // The tap-to-redraw budget, in one number. This path runs on a cold, just-unfrozen
            // process where nothing is cached, so it is the only honest measure of how responsive
            // the widget feels — and the only way to tell a real regression from an impression.
            Log.d(TAG, "redraw took ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Direct compose failed, falling back to session update", failure)
            widget.update(context, glanceId)
        }
    }

    /**
     * Redraws every placed instance.
     *
     * This is what `updateAll()` was doing everywhere it used to be called, minus the dependence on
     * a session that may not exist. One instance failing does not stop the rest: a widget that
     * cannot be composed is exactly the one whose neighbours most need replacing.
     */
    suspend fun all(context: Context) {
        GlanceAppWidgetManager(context)
            .getGlanceIds(TvWidget::class.java)
            .forEach { glanceId -> now(context, glanceId) }
    }
}
