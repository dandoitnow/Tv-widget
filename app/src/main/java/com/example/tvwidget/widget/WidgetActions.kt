package com.example.tvwidget.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
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
