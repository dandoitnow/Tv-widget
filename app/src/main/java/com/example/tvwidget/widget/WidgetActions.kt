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
import com.example.tvwidget.data.FavoriteEpisode
import com.example.tvwidget.data.Tab
import com.example.tvwidget.data.WidgetState

/** Parameter keys shared by the widget's action callbacks. */
object ActionKeys {
    val tab = ActionParameters.Key<String>("tab")
    val showTitle = ActionParameters.Key<String>("show_title")
    val episodeCode = ActionParameters.Key<String>("episode_code")
    val episodeLabel = ActionParameters.Key<String>("episode_label")
    val imdbId = ActionParameters.Key<String>("imdb_id")
    val openCatalogue = ActionParameters.Key<Boolean>("open_catalogue")
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
 * Which is exactly the symptom: pressing CATALOGUE and coming back "pushed" the switch through.
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
        }
    }
}

/**
 * Toggles the favourite for one specific episode, keyed on show title + episode code. Optimistic:
 * the widget state is the source of truth until a sync writes it back. Unfavoriting can also happen
 * from `MainActivity`'s Catalogue > Favorites screen, which mutates this same Glance state directly
 * rather than going through this ActionCallback (an Activity has no `GlanceId`-scoped action to run).
 */
class ToggleFavoriteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val title = parameters[ActionKeys.showTitle] ?: return
        val episode = parameters[ActionKeys.episodeCode] ?: return
        val label = parameters[ActionKeys.episodeLabel].orEmpty()
        mutate(context, glanceId) {
            val current = WidgetState.favorites(this)
            val existing = current.firstOrNull { it.showTitle == title && it.episodeCode == episode }
            val updated = if (existing != null) {
                current - existing
            } else {
                current + FavoriteEpisode(title, episode, label)
            }
            this[WidgetState.FAVORITES] = WidgetState.encodeFavorites(updated)
        }
    }
}
