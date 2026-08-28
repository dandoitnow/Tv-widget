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
import com.example.tvwidget.ui.Tokens

/**
 * The CATALOGUE tab: browse TVMaze's shows and add any of them to tracking, which starts feeding
 * their episodes into TODAY.
 *
 * Widgets can't host a text field (`RemoteViews` has no `EditText`), so free-text search happens in
 * `MainActivity` instead — the pill at the top deep-links there. What's browsable directly in the
 * widget, no typing required, is today's currently-airing shows with a one-tap add/remove.
 */
@Composable
fun CatalogueTab(shows: List<CatalogueShow>, posters: Map<String, Bitmap>) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        SearchRow()
        if (shows.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                items(shows.size) { index -> CatalogueRow(show = shows[index], posters = posters) }
                item { Spacer(GlanceModifier.fillMaxWidth().height(Tokens.ListRowHeight)) }
            }
        }
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
        Text(text = "🔍", style = Tokens.mono8(Tokens.TextPrimary))
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = "SEARCH ALL SHOWS",
            style = Tokens.mono65(Tokens.TextSecondary),
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = GlanceModifier.fillMaxWidth().height(Tokens.ListRowHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "LOADING CATALOGUE…", style = Tokens.mono65(Tokens.white(0.35f)))
    }
}

@Composable
private fun CatalogueRow(show: CatalogueShow, posters: Map<String, Bitmap>) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Tokens.ListRowHeight - 1.dp)
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(title = show.title, posters = posters)
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${show.status} · ${show.network}",
                    style = Tokens.mono65(Tokens.TextSecondary),
                    maxLines = 1,
                )
                Text(text = show.title, style = Tokens.display(Tokens.TitleSize), maxLines = 1)
            }
            Spacer(GlanceModifier.width(6.dp))
            TrackToggle(show)
        }
        Hairline()
    }
}

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
            text = if (show.tracked) "✓ TRACKING" else "+ TRACK",
            style = Tokens.mono6(foreground, TextAlign.Center),
        )
    }
}

