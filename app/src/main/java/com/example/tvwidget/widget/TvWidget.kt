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
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
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
        // This snapshot exists *only* to decide which tab's posters are worth preloading — the
        // actual UI below reads state reactively via `currentState()` inside the composable, same as
        // it always has. An earlier attempt to also thread this snapshot's decoded lists into the
        // composable as plain parameters (skipping `currentState()` entirely) caused tab switching to
        // get stuck: Glance's `update()` doesn't guarantee provideGlance fully re-runs on every
        // action the way a fresh session's initial call does, so those parameters could go stale.
        // `currentState()` is what stays correctly reactive across however Glance actually schedules
        // recomposition; this snapshot is a best-effort read only, safe to be occasionally a redraw
        // behind since the worst case is just missing posters for one frame after a tab switch.
        val snapshot = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val snapshotTab = WidgetState.tab(snapshot)
        val posterTitles = when (snapshotTab) {
            Tab.TODAY -> WidgetState.releases(snapshot).map(Release::showTitle)
            Tab.ANTICIPATED -> WidgetState.anticipated(snapshot).map(AnticipatedShow::title)
        }
        // PosterStore keeps a process-lifetime in-memory cache, so repeated redraws (e.g. a star
        // toggle) don't re-decode the same PNGs from disk every time.
        val posters = PosterStore.loadBitmaps(context, posterTitles.map(PosterStore::keyFor))

        provideContent { WidgetContent(posters) }
    }
}

@Composable
private fun WidgetContent(posters: Map<String, Bitmap>) {
    val prefs = currentState<Preferences>()
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
            }
        }
    }
}
