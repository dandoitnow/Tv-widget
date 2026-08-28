package com.example.tvwidget.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.Release
import com.example.tvwidget.data.Tab
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.ui.Tokens

/**
 * The 5x2 TV release tracker widget.
 *
 * All widget state lives in the Glance `DataStore` ([PreferencesGlanceStateDefinition]) so the
 * widget restores identically after a launcher restart.
 */
class TvWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition

    // The design is fixed at 360 x 152dp; a single layout is rendered at whatever size the launcher
    // hands us rather than swapping layouts per breakpoint.
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Posters have to already be on disk by draw time — widgets can't fetch images inside
        // composition — so every title the widget could show is resolved to a bitmap here, before
        // `provideContent` runs, using the same `Preferences` composition will read via `currentState`.
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val releases = WidgetState.releases(prefs)
        val anticipated = WidgetState.anticipated(prefs)
        val favoriteShows = WidgetState.favoriteShows(
            WidgetState.favorites(prefs),
            WidgetState.rewatchLog(prefs),
        )
        val catalogue = WidgetState.catalogue(prefs)
        val posterKeys = (releases.map(Release::showTitle) + anticipated.map { it.title } +
            favoriteShows.map { it.title } + catalogue.map { it.title })
            .map(PosterStore::keyFor)
        val posters = PosterStore.loadBitmaps(context, posterKeys)

        provideContent { WidgetContent(posters) }
    }
}

@Composable
private fun WidgetContent(posters: Map<String, Bitmap>) {
    val prefs = androidx.glance.currentState<Preferences>()
    val tab = WidgetState.tab(prefs)
    val releases = WidgetState.releases(prefs)
    val favorites = WidgetState.favorites(prefs)
    val todayCount = releases.count { it.isToday }

    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadiusCompat(Tokens.RadiusWidget)
                .background(Tokens.Background),
        ) {
            Header(selected = tab, todayCount = todayCount)
            when (tab) {
                Tab.TODAY -> TodayFeed(releases = releases, favorites = favorites, posters = posters)

                Tab.ANTICIPATED -> AnticipatedList(shows = WidgetState.anticipated(prefs), posters = posters)

                Tab.FAVORITES -> FavoritesList(
                    shows = WidgetState.favoriteShows(favorites, WidgetState.rewatchLog(prefs)),
                    openShow = WidgetState.openShow(prefs),
                    openRewatchLog = WidgetState.openRewatchLog(prefs),
                    posters = posters,
                )

                Tab.CATALOGUE -> CatalogueTab(shows = WidgetState.catalogue(prefs), posters = posters)
            }
        }
    }
}
