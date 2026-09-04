package com.example.tvwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import com.example.tvwidget.data.AnticipatedShow
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.Release
import com.example.tvwidget.data.Tab
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Surfaces
import com.example.tvwidget.ui.Tokens

/**
 * The 5x2 TV release tracker widget.
 *
 * All widget state lives in the Glance `DataStore` ([PreferencesGlanceStateDefinition]) so the
 * widget restores identically after a launcher restart. CATALOG (Favorites + Trending + search)
 * isn't a tab here at all — see [Header] — so there are only two.
 */
class TvWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition

    // Glance picks whichever of these fits the actual bounds it's given; `Dimens` reads `LocalSize`
    // to tell them apart inside composition and scales rows, posters, tabs, *and every text size*
    // accordingly — a taller widget needs bigger content, not just a bigger empty container around
    // the same small text.
    // Two breakpoints, not three. Responsive composes the widget once per breakpoint and packs all
    // of them into a single RemoteViews, so a third size was a flat 50% surcharge on every redraw —
    // paid on the path between a tap and the screen changing, for a size most placements never use.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(320.dp, 110.dp), DpSize(320.dp, 300.dp))
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
    val allToday = releases.filterNot { it.hasAired }
    val allAnticipated = WidgetState.anticipated(prefs)

    // Only a page of rows is rendered at a time. A LazyColumn parcels every item it is given and the
    // whole RemoteViews has to cross a Binder transaction, so each row costs at compose time *and*
    // in the parcel — both of which land between a tap and the screen changing. The rest are one tap
    // away at the end of the list; see Dimens.RowPage and ShowMoreRow.
    val visibleRows = WidgetState.visibleRows(prefs)
    val todayReleases = allToday.take(visibleRows)
    val anticipated = allAnticipated.take(visibleRows)
    val hidden = when (tab) {
        Tab.TODAY -> allToday.size - todayReleases.size
        Tab.ANTICIPATED -> allAnticipated.size - anticipated.size
    }.coerceAtMost(Dimens.MaxWidgetRows - visibleRows)

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
    val context = LocalContext.current
    val keys = posterTitles.map(PosterStore::keyFor)
    // Downscaled: these bitmaps get parcelled into the RemoteViews, once per row.
    val posters = PosterStore.loadBitmapsBlocking(context, keys, Dimens.WidgetPosterWidthPx)
    // Dominant colours for the per-row edge light. Memoized alongside the bitmaps above, so for
    // anything already being drawn this costs a map lookup.
    val accents = PosterStore.loadAccentsBlocking(context, keys)

    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadiusCompat(Tokens.RadiusWidget)
                // Marks this as the widget's background surface. Without it, Android 12+ launchers
                // can't apply the system corner radius or their own background treatment, which is
                // why the widget's corners didn't quite agree with everything else on the screen.
                .appWidgetBackground()
                // A warm near-black ground that drifts a few points warmer through the evening —
                // generated rather than loaded, because a drawable can't know what time it is.
                .background(
                    ImageProvider(Surfaces.ground()),
                    contentScale = ContentScale.FillBounds,
                )
                // Content is inset from the widget's edge on every side. The old design ran rows
                // edge to edge, which is what made it read as a table dropped onto the home screen
                // rather than an object sitting on it.
                .padding(horizontal = Tokens.EdgeInset, vertical = Tokens.EdgeInset - 2.dp),
        ) {
            Header(selected = tab, todayCount = todayCount)

            if (Dimens.showWeekStrip()) WeekStrip(allToday)

            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (Dimens.showSpine()) {
                    Spine()
                    Spacer(GlanceModifier.width(Dimens.SpineGutter))
                }
                // A Column, not a Box: TODAY emits the hero and the list as siblings, and Box
                // children stack rather than flow — the hero would have been drawn underneath the
                // list rather than above it.
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    when (tab) {
                        Tab.TODAY -> TodayFeed(
                            releases = todayReleases,
                            favorites = favorites,
                            posters = posters,
                            accents = accents,
                            hidden = hidden,
                        )

                        Tab.ANTICIPATED -> if (anticipated.isEmpty()) {
                            EmptyState(
                                headline = "No premieres found",
                                detail = "This fills itself from the schedule once the app can reach the network.",
                            )
                        } else {
                            AnticipatedList(
                                shows = anticipated,
                                posters = posters,
                                accents = accents,
                                hidden = hidden,
                            )
                        }
                    }
                }
            }

            if (tab == Tab.TODAY && Dimens.showTicker()) {
                ComingUpStrip(releases = todayReleases, posters = posters)
            }
        }
    }
}

/**
 * Seven dots for the next seven days, lit where something airs.
 *
 * The list answers "what is next"; this answers "how is my week shaped", which is a different
 * question and one no amount of scrolling a list resolves. Borrowed wholesale from the logic behind
 * Apple's activity rings: a small, dense, non-numeric summary that is read in one glance and never
 * actually studied.
 */
@Composable
private fun WeekStrip(releases: List<Release>) {
    val days = BooleanArray(7)
    releases.forEach { release ->
        if (release.dayOffset in 0..6) days[release.dayOffset] = true
    }
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(Dimens.WeekStripHeight)
            .padding(bottom = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Image(
            provider = ImageProvider(Surfaces.weekStrip(days, today = 0)),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxSize(),
        )
    }
}

/**
 * The hairline of light down the left gutter. It belongs to the widget, not to any row: the cards
 * and their edge lights change with the content, and this stays put, which gives the list something
 * to hang off. Fading at both ends is what keeps it from reading as a border.
 */
@Composable
private fun Spine() {
    Box(
        modifier = GlanceModifier
            .width(Dimens.SpineWidth)
            .fillMaxHeight()
            .background(ImageProvider(Surfaces.spine()), contentScale = ContentScale.FillBounds),
    ) {}
}

/**
 * The COMING UP ticker: releases further out than the list has room for, cycling one at a time.
 *
 * It is additive rather than decorative — every frame is a release the list is not currently
 * showing — which is the bar a moving element has to clear to earn a permanent place on screen.
 * The motion comes from a `ViewFlipper` running in the launcher's process, so it costs nothing.
 */
@Composable
private fun ComingUpStrip(releases: List<Release>, posters: Map<String, android.graphics.Bitmap>) {
    val upcoming = releases.drop(Dimens.TickerStartsAfter)
    if (upcoming.isEmpty()) return

    val views = LiveViews.comingUp(
        context = LocalContext.current,
        items = upcoming.map { release ->
            LiveViews.Upcoming(
                title = release.showTitle,
                meta = listOf(release.countdownLabel(), release.dayLabel, release.network)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                poster = posters[PosterStore.keyFor(release.showTitle)],
            )
        },
    ) ?: return

    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            text = "COMING UP",
            style = Tokens.label(Dimens.smallLabelSize(), Tokens.TextTertiary),
            maxLines = 1,
        )
        Spacer(GlanceModifier.fillMaxWidth().height(6.dp))
        AndroidRemoteViews(remoteViews = views, modifier = GlanceModifier.fillMaxWidth())
    }
}
