package com.example.tvwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.state.updateAppWidgetState
import com.example.tvwidget.data.Tab
import com.example.tvwidget.data.TrackedShow
import com.example.tvwidget.data.TrackedShowsRepository
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.work.AnticipatedSyncWorker

/** Parameter keys shared by the widget's action callbacks. */
object ActionKeys {
    val tab = ActionParameters.Key<String>("tab")
    val showTitle = ActionParameters.Key<String>("show_title")
    val episodeCode = ActionParameters.Key<String>("episode_code")
    val imdbId = ActionParameters.Key<String>("imdb_id")
    val tvMazeId = ActionParameters.Key<Int>("tvmaze_id")
    val network = ActionParameters.Key<String>("network")
    val posterUrl = ActionParameters.Key<String>("poster_url")
    val openCatalog = ActionParameters.Key<Boolean>("open_catalog")
}

/**
 * Applies [edit] to the widget's persisted state and redraws it. Every interaction goes through
 * here: state is written locally first so the widget responds immediately, and any remote sync
 * happens afterwards.
 */
private suspend fun mutate(
    context: Context,
    glanceId: GlanceId,
    edit: MutablePreferences.() -> Unit,
) {
    updateAppWidgetState(context, glanceId) { prefs -> prefs.edit() }
    redrawNow(context, glanceId)
}

/**
 * Composes the widget and hands the result straight to [AppWidgetManager], instead of asking Glance
 * to redraw through its background session.
 *
 * `GlanceAppWidget.update()` does not draw anything itself. It signals a long-lived `SessionWorker`
 * that owns the live composition, and if that worker is gone it asks WorkManager to start a new one.
 * On this device neither step is dependable: Samsung's Freecess freezes the app process in the
 * background (`FZ : com.example.tvwidget reason: Bg`), which kills the session and defers the
 * WorkManager job that would rebuild it. The result was a tab switch that wrote its new state to the
 * datastore and then never appeared — the file's timestamp moved on every tap while the widget sat
 * unchanged, and opening the app was what finally unfroze the process and let the queued redraw run.
 * Which is exactly the symptom: pressing CATALOG and coming back "pushed" the switch through.
 *
 * Composing here removes every one of those moving parts. The work happens inside the broadcast
 * that the tap already started, in a process that is by definition running, and the RemoteViews go
 * to the host directly. [GlanceAppWidget.update] stays as the fallback for the unlikely case that
 * composing throws — better a late redraw than none.
 */
private suspend fun redrawNow(context: Context, glanceId: GlanceId) {
    val widget = TvWidget()
    val startedAt = SystemClock.elapsedRealtime()
    runCatching {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val manager = AppWidgetManager.getInstance(context)
        val views = widget.compose(
            context = context,
            id = glanceId,
            // The widget's real options, so SizeMode.Responsive still picks the right breakpoint —
            // composing without them would size the result for a default that may not be this
            // widget's actual footprint.
            options = manager.getAppWidgetOptions(appWidgetId),
        )
        manager.updateAppWidget(appWidgetId, views)
        // The tap-to-redraw budget, in one number. This path runs on a cold, just-unfrozen process
        // where nothing is cached, so it is the only honest measure of how responsive the widget
        // feels — and the only way to tell an actual regression from an impression of one.
        Log.d("TvWidget", "redraw took ${SystemClock.elapsedRealtime() - startedAt}ms")
    }.onFailure { error ->
        Log.w("TvWidget", "Direct compose failed, falling back to session update", error)
        widget.update(context, glanceId)
    }
}

/** Switches tabs. */
class SwitchTabAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tab = parameters[ActionKeys.tab] ?: Tab.TODAY.name
        mutate(context, glanceId) {
            this[WidgetState.TAB] = tab
            // A tab arrives collapsed. Carrying an expansion across tabs would make every later
            // switch pay for rows the new tab was never asked to show, which is the cost this
            // paging exists to avoid in the first place.
            this[WidgetState.VISIBLE_ROWS] = Dimens.RowPage
        }
    }
}

/**
 * Reveals another page of rows on the current tab.
 *
 * RemoteViews gives no scroll position and no scroll callback, so a list cannot notice that it has
 * been scrolled to the end and quietly extend itself. The control at the end of the list *is* the
 * scroll trigger: reaching it requires having scrolled that far, and tapping it is the only signal
 * the platform will actually deliver.
 */
class ExpandRowsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        mutate(context, glanceId) {
            val current = WidgetState.visibleRows(this)
            this[WidgetState.VISIBLE_ROWS] =
                (current + Dimens.RowPage).coerceAtMost(Dimens.MaxWidgetRows)
        }
    }
}


/**
 * Tracks — or untracks — a show straight from a POPULAR row.
 *
 * Without this, following something the widget just recommended meant opening the app, searching for
 * the title it had just shown you, and tapping TRACK: three steps to act on information already on
 * screen. The row knows the id; the tap should be enough.
 *
 * A sync is kicked off afterwards so the show's episodes reach TODAY without waiting for the daily
 * run — tracking something and seeing nothing change for a day reads as the button not working.
 */
class TrackShowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ActionKeys.tvMazeId] ?: return
        val title = parameters[ActionKeys.showTitle] ?: return
        if (TrackedShowsRepository.isTracked(context, id)) {
            TrackedShowsRepository.remove(context, id)
        } else {
            TrackedShowsRepository.add(
                context,
                TrackedShow(
                    tvMazeId = id,
                    title = title,
                    network = parameters[ActionKeys.network].orEmpty(),
                    posterUrl = parameters[ActionKeys.posterUrl]?.ifBlank { null },
                ),
            )
        }
        redrawNow(context, glanceId)
        AnticipatedSyncWorker.runOnce(context)
    }
}
