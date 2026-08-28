package com.example.tvwidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.example.tvwidget.data.FavoriteEpisode
import com.example.tvwidget.MainActivity
import com.example.tvwidget.data.Release
import com.example.tvwidget.ui.Tokens

/**
 * The TODAY tab: the user's watchlist releases in chronological order, aired above and scheduled
 * below.
 *
 * The design asks for the list to rest on today's first release, scrollable back into aired
 * episodes. Glance's `LazyColumn` exposes no scroll position, so instead the aired rows start
 * collapsed behind a single header row and today's first release is the first thing drawn. Tapping
 * the header expands them in place, which keeps the chronology intact; leaving the tab re-collapses
 * them so returning re-pins today.
 */
@Composable
fun TodayFeed(
    releases: List<Release>,
    favorites: List<FavoriteEpisode>,
    showPast: Boolean,
) {
    val past = releases.filter { it.hasAired }
    val rest = releases.filterNot { it.hasAired }
    val visible = if (showPast) past + rest else rest

    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        if (past.isNotEmpty()) {
            item { EarlierRow(count = past.size, expanded = showPast) }
        }
        items(visible.size) { index ->
            val release = visible[index]
            ReleaseRow(
                release = release,
                favorited = favorites.any {
                    it.showTitle == release.showTitle && it.episodeCode == release.episodeCode
                },
            )
        }
        // Bottom padding so the last row can settle clear of the widget edge.
        item { Spacer(GlanceModifier.fillMaxWidth().height(Tokens.ListRowHeight)) }
    }
}

/** Collapsed stand-in for the aired rows above today. */
@Composable
private fun EarlierRow(count: Int, expanded: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionRunCallback<TogglePastRowsAction>()),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(21.dp)
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "HIDE AIRED" else "$count AIRED",
                style = Tokens.mono6(Tokens.white(0.4f)),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(text = if (expanded) "-" else "+", style = Tokens.mono8(Tokens.Accent))
        }
        Hairline()
    }
}

/** One 46dp release row: poster, meta line, title, status column and star. */
@Composable
private fun ReleaseRow(release: Release, favorited: Boolean) {
    val dimmed = release.hasAired
    val metaColor = Tokens.dim(
        if (release.isToday) Tokens.Accent else Tokens.TextSecondary,
        dimmed,
    )
    val statusColor = Tokens.dim(
        if (release.isToday) Tokens.Accent else Tokens.TextTertiary,
        dimmed,
    )

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            // Today's rows carry a full-row accent tint.
            .background(if (release.isToday) Tokens.accent(0.07f) else Tokens.Background),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Tokens.ListRowHeight - 1.dp)
                .padding(horizontal = Tokens.RowPaddingHorizontal)
                // A tap anywhere but on the star deep-links into the app's episode screen.
                .clickable(
                    actionStartActivity<MainActivity>(
                        actionParametersOf(
                            ActionKeys.showTitle to release.showTitle,
                            ActionKeys.episodeCode to release.episodeCode,
                        )
                    )
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(dimmed = dimmed)
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${release.dayLabel} · ${release.airTime} · ${release.network}",
                    style = Tokens.mono65(metaColor),
                    maxLines = 1,
                )
                Text(
                    text = release.showTitle,
                    style = Tokens.display(
                        Tokens.TitleSize,
                        Tokens.dim(Tokens.TextPrimary, dimmed),
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = release.episodeCode,
                    style = Tokens.mono8(Tokens.dim(Tokens.TextPrimary, dimmed), TextAlign.End),
                    maxLines = 1,
                )
                Text(
                    text = release.status.label,
                    style = Tokens.mono6(statusColor, TextAlign.End),
                    maxLines = 1,
                )
            }
            StarToggle(
                favorited = favorited,
                dimmed = dimmed,
                targetHeight = Tokens.ListRowHeight - 1.dp,
                onClick = actionRunCallback<ToggleFavoriteAction>(
                    actionParametersOf(
                        ActionKeys.showTitle to release.showTitle,
                        ActionKeys.episodeCode to release.episodeCode,
                        ActionKeys.episodeLabel to release.favoriteLabel(),
                    )
                ),
            )
        }
        Hairline()
    }
}
