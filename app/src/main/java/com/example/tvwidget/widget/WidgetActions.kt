package com.example.tvwidget.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.example.tvwidget.data.FavoriteEpisode
import com.example.tvwidget.data.SampleData
import com.example.tvwidget.data.Tab
import com.example.tvwidget.data.WidgetState

/** Parameter keys shared by the widget's action callbacks. */
object ActionKeys {
    val tab = ActionParameters.Key<String>("tab")
    val showTitle = ActionParameters.Key<String>("show_title")
    val episodeCode = ActionParameters.Key<String>("episode_code")
    val episodeLabel = ActionParameters.Key<String>("episode_label")
    val openSearch = ActionParameters.Key<Boolean>("open_search")
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
    TvWidget().update(context, glanceId)
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
 * the widget state is the source of truth until a sync writes it back.
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

            // A show whose last episode was removed drops out of the list, and with it any
            // expansion or open log pointing at it.
            if (updated.none { it.showTitle == title }) {
                if (this[WidgetState.OPEN_SHOW] == title) this[WidgetState.OPEN_SHOW] = ""
                if (this[WidgetState.OPEN_REWATCH_LOG] == title) this[WidgetState.OPEN_REWATCH_LOG] = ""
            }
        }
    }
}

/** Accordion: expands one favourite show and collapses whichever was open. */
class ToggleShowExpandedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val title = parameters[ActionKeys.showTitle] ?: return
        mutate(context, glanceId) {
            this[WidgetState.OPEN_SHOW] = if (this[WidgetState.OPEN_SHOW] == title) "" else title
        }
    }
}

/** Opens or closes the rewatch log dropdown for one show. */
class ToggleRewatchLogAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val title = parameters[ActionKeys.showTitle] ?: return
        mutate(context, glanceId) {
            this[WidgetState.OPEN_REWATCH_LOG] =
                if (this[WidgetState.OPEN_REWATCH_LOG] == title) "" else title
        }
    }
}

/** Appends today's date to the show's rewatch log and opens the log. */
class AddRewatchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val title = parameters[ActionKeys.showTitle] ?: return
        mutate(context, glanceId) {
            val log = WidgetState.rewatchLog(this).toMutableMap()
            log[title] = log[title].orEmpty() + SampleData.logDateLabel()
            this[WidgetState.REWATCH_LOG] = WidgetState.encodeRewatchLog(log)
            this[WidgetState.OPEN_REWATCH_LOG] = title
        }
    }
}

/** Removes the newest rewatch entry. The count floors at zero. */
class RemoveRewatchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val title = parameters[ActionKeys.showTitle] ?: return
        mutate(context, glanceId) {
            val log = WidgetState.rewatchLog(this).toMutableMap()
            log[title] = log[title].orEmpty().dropLast(1)
            this[WidgetState.REWATCH_LOG] = WidgetState.encodeRewatchLog(log)
        }
    }
}
