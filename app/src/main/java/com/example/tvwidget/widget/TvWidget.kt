package com.example.tvwidget.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import com.example.tvwidget.data.AnticipatedShow
import com.example.tvwidget.data.CatalogueShow
import com.example.tvwidget.data.FavoriteEpisode
import com.example.tvwidget.data.FavoriteShow
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

    // Two breakpoints: the original compact 5x2 design, and a taller one for however far past that
    // the user drags the widget. Glance picks whichever of these fits the actual bounds it's given;
    // `Dimens` reads `LocalSize` to tell the two apart inside composition and size rows/posters/tabs
    // accordingly, so a taller widget gets more breathing room instead of just empty stretched space.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(320.dp, 110.dp), DpSize(320.dp, 260.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val tab = WidgetState.tab(prefs)
        val releases = WidgetState.releases(prefs)
        val favorites = WidgetState.favorites(prefs)
        val todayCount = releases.count { it.isToday }

        // Every interaction (tab switch, star toggle, rewatch +/-, ...) re-invokes provideGlance in
        // full, so only the tab actually on screen gets its list decoded and its posters resolved —
        // this used to decode all four tabs' JSON and load every poster on disk regardless of which
        // one was visible, on every single tap.
        val anticipated = if (tab == Tab.ANTICIPATED) WidgetState.anticipated(prefs) else emptyList()
        val favoriteShows = if (tab == Tab.FAVORITES) {
            WidgetState.favoriteShows(favorites, WidgetState.rewatchLog(prefs))
        } else {
            emptyList()
        }
        val catalogue = if (tab == Tab.CATALOGUE) WidgetState.catalogue(prefs) else emptyList()

        val posterTitles = when (tab) {
            Tab.TODAY -> releases.map(Release::showTitle)
            Tab.ANTICIPATED -> anticipated.map(AnticipatedShow::title)
            Tab.FAVORITES -> favoriteShows.map(FavoriteShow::title)
            Tab.CATALOGUE -> catalogue.map(CatalogueShow::title)
        }
        // PosterStore keeps a process-lifetime in-memory cache, so repeated redraws of the same tab
        // (e.g. a star toggle or rewatch count tap, each of which re-invokes provideGlance) don't
        // re-decode the same PNGs from disk every time.
        val posters = PosterStore.loadBitmaps(context, posterTitles.map(PosterStore::keyFor))

        val openShow = WidgetState.openShow(prefs)
        val openRewatchLog = WidgetState.openRewatchLog(prefs)

        provideContent {
            WidgetContent(
                tab = tab,
                todayCount = todayCount,
                releases = releases,
                favorites = favorites,
                anticipated = anticipated,
                favoriteShows = favoriteShows,
                catalogue = catalogue,
                openShow = openShow,
                openRewatchLog = openRewatchLog,
                posters = posters,
            )
        }
    }
}

@Composable
private fun WidgetContent(
    tab: Tab,
    todayCount: Int,
    releases: List<Release>,
    favorites: List<FavoriteEpisode>,
    anticipated: List<AnticipatedShow>,
    favoriteShows: List<FavoriteShow>,
    catalogue: List<CatalogueShow>,
    openShow: String?,
    openRewatchLog: String?,
    posters: Map<String, Bitmap>,
) {
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

                Tab.ANTICIPATED -> AnticipatedList(shows = anticipated, posters = posters)

                Tab.FAVORITES -> FavoritesList(
                    shows = favoriteShows,
                    openShow = openShow,
                    openRewatchLog = openRewatchLog,
                    posters = posters,
                )

                Tab.CATALOGUE -> CatalogueTab(shows = catalogue, posters = posters)
            }
        }
    }
}
