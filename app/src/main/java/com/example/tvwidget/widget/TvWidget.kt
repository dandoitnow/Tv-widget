package com.example.tvwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.example.tvwidget.R
import com.example.tvwidget.data.AnticipatedShow
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.Release
import com.example.tvwidget.data.Tab
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.ui.Tokens

/**
 * The 5x2 TV release tracker widget.
 *
 * All widget state lives in the Glance `DataStore` ([PreferencesGlanceStateDefinition]) so the
 * widget restores identically after a launcher restart. CATALOGUE (Favorites + Recommended +
 * search) isn't a tab here at all — see [Header] — so there are only two.
 */
class TvWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition

    // Three breakpoints: the original compact 5x2 design, a roomy one, and an XL one for however far
    // past that the user drags the widget. Glance picks whichever of these fits the actual bounds
    // it's given; `Dimens` reads `LocalSize` to tell them apart inside composition and scales rows,
    // posters, tabs, *and every text size* accordingly — a taller widget needs bigger content, not
    // just a bigger empty container around the same small text.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(320.dp, 110.dp), DpSize(320.dp, 260.dp), DpSize(320.dp, 500.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }
}

@Composable
private fun WidgetContent() {
    val prefs = currentState<Preferences>()
    val tab = WidgetState.tab(prefs)
    val releases = WidgetState.releases(prefs)
    val favorites = WidgetState.favorites(prefs)
    val todayCount = releases.count { it.isToday }

    // Aired releases are dropped from TODAY entirely (not just collapsed) so the list always
    // *starts* at today's first release — Glance's LazyColumn has no scroll-to-index API to jump
    // there on a tap, so the only way to guarantee landing on today is for there to be nothing
    // rendered above it to land past.
    val todayReleases = releases.filterNot { it.hasAired }
    val anticipated = WidgetState.anticipated(prefs)

    // Read directly here — reactively, off `tab`/`releases`/`anticipated` above — rather than as a
    // value computed once in `provideGlance` and passed down. `provideGlance`'s suspend body isn't
    // guaranteed to actually re-run on every redraw (Glance's `update()`/`updateAll()` can just
    // recompose an already-alive session via `currentState()` instead — the exact thing that once
    // left tab-switching stuck), so a `posters` map snapshotted there could go stale indefinitely:
    // freshly-cached art from a sync would never make it into the widget until something forced a
    // truly fresh `provideGlance` call. Reading it here keeps it exactly as reactive as `tab` and
    // `releases` already are.
    val posterTitles = when (tab) {
        Tab.TODAY -> todayReleases.map(Release::showTitle)
        Tab.ANTICIPATED -> anticipated.map(AnticipatedShow::title)
    }
    val posters = PosterStore.loadBitmapsBlocking(LocalContext.current, posterTitles.map(PosterStore::keyFor))

    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadiusCompat(Tokens.RadiusWidget)
                // A warm near-black gradient ground rather than flat black — see Tokens.
                .background(ImageProvider(R.drawable.surface_widget))
                // Content is inset from the widget's edge on every side. The old design ran rows
                // edge to edge, which is what made it read as a table dropped onto the home screen
                // rather than an object sitting on it.
                .padding(horizontal = Tokens.EdgeInset, vertical = Tokens.EdgeInset - 2.dp),
        ) {
            Header(selected = tab, todayCount = todayCount)
            when (tab) {
                Tab.TODAY -> TodayFeed(releases = todayReleases, favorites = favorites, posters = posters)

                Tab.ANTICIPATED -> AnticipatedList(shows = anticipated, posters = posters)
            }
        }
    }
}
