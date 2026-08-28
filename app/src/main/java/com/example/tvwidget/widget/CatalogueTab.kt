package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.example.tvwidget.MainActivity
import com.example.tvwidget.data.CatalogueShow
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The CATALOGUE tab: browse TVMaze's shows and track/untrack any of them, which starts (or stops)
 * feeding their episodes into TODAY.
 *
 * Widgets can't host a text field (`RemoteViews` has no `EditText`), so free-text search happens in
 * `MainActivity` instead — the pill at the top deep-links there. What's browsable directly in the
 * widget, no typing required, is today's currently-airing shows with a one-tap track/untrack.
 *
 * Tracked shows are always pinned in their own section at the top (see
 * `AnticipatedSyncWorker.mergeTracked`) — otherwise a show that isn't airing today would scroll out
 * of the browse list with no way back to untrack it.
 */
@Composable
fun CatalogueTab(shows: List<CatalogueShow>, posters: Map<String, Bitmap>) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        SearchRow()
        if (shows.isEmpty()) {
            EmptyState()
        } else {
            val tracked = shows.filter { it.tracked }
            val browsable = shows.filterNot { it.tracked }
            LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                if (tracked.isNotEmpty()) {
                    item { SectionHeader("TRACKING (${tracked.size})") }
                    items(tracked.size) { index -> CatalogueRow(show = tracked[index], posters = posters) }
                }
                if (browsable.isNotEmpty()) {
                    item { SectionHeader("BROWSE") }
                    items(browsable.size) { index -> CatalogueRow(show = browsable[index], posters = posters) }
                }
                item { Spacer(GlanceModifier.fillMaxWidth().height(Tokens.ListRowHeight)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(18.dp)
            .padding(horizontal = Tokens.RowPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = Tokens.mono(Dimens.statusSize(), Tokens.white(0.4f)))
    }
}

@Composable
private fun SearchRow() {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.RowPaddingHorizontal, vertical = 6.dp)
            .cornerRadiusCompat(Tokens.RadiusPanel)
            .background(Tokens.white(0.06f))
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(ActionKeys.openSearch to true))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "🔍", style = Tokens.mono(Dimens.accentLabelSize(), Tokens.TextPrimary))
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = "SEARCH ALL SHOWS",
            style = Tokens.mono(Dimens.metaSize(), Tokens.TextSecondary),
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

/**
 * Shown before the first successful sync, and again if every sync since has failed (a failed sync
 * never overwrites a previously-good list — see `AnticipatedSyncWorker.doWork` — so this state only
 * ever means "no catalogue data has loaded yet"). Tapping forces another attempt immediately rather
 * than waiting for the next automatic retry.
 */
@Composable
private fun EmptyState() {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(Tokens.ListRowHeight * 2)
            .clickable(actionRunCallback<RetryCatalogueSyncAction>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "COULDN'T LOAD THE CATALOGUE", style = Tokens.mono(Dimens.metaSize(), Tokens.white(0.4f)))
        Spacer(GlanceModifier.height(4.dp))
        Text(text = "TAP TO RETRY", style = Tokens.mono(Dimens.metaSize(), Tokens.Accent))
    }
}

@Composable
private fun CatalogueRow(show: CatalogueShow, posters: Map<String, Bitmap>) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Dimens.listRowHeight() - 1.dp)
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(title = show.title, posters = posters)
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${show.status} · ${show.network}",
                    style = Tokens.mono(Dimens.metaSize(), Tokens.TextSecondary),
                    maxLines = 1,
                )
                Text(text = show.title, style = Tokens.display(Dimens.titleSize()), maxLines = 1)
            }
            Spacer(GlanceModifier.width(6.dp))
            TrackToggle(show)
        }
        Hairline()
    }
}

/**
 * One button does both jobs — tapping an untracked show adds it, tapping a tracked one removes it —
 * so the label always states the action a tap performs, not the current status: "TRACK" invites
 * adding, "UNTRACK" invites removing. A same-looking-but-ambiguous "✓ TRACKING" label would leave it
 * unclear that tapping again does anything.
 */
@Composable
private fun TrackToggle(show: CatalogueShow) {
    val background = if (show.tracked) Tokens.accent(0.18f) else Tokens.white(0.08f)
    val foreground = if (show.tracked) Tokens.Accent else Tokens.TextSecondary
    Box(
        modifier = GlanceModifier
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clickable(
                actionRunCallback<ToggleTrackedAction>(
                    actionParametersOf(
                        ActionKeys.tvMazeId to show.tvMazeId,
                        ActionKeys.showTitle to show.title,
                        ActionKeys.network to show.network,
                        ActionKeys.posterUrl to (show.posterUrl ?: ""),
                        ActionKeys.wasTracked to show.tracked,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (show.tracked) "− UNTRACK" else "+ TRACK",
            style = Tokens.mono(Dimens.statusSize(), foreground, TextAlign.Center),
        )
    }
}

