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
import com.example.tvwidget.MainActivity
import com.example.tvwidget.data.FavoriteEpisode
import com.example.tvwidget.data.Release
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The TODAY tab: upcoming releases as a stack of soft cards.
 *
 * Aired rows are dropped upstream (see `TvWidget.WidgetContent`) so the list always starts at
 * today's first release — Glance's `LazyColumn` has no scroll-to-index API, so the only way to
 * guarantee landing on today is for nothing to be rendered above it.
 */
@Composable
fun TodayFeed(
    releases: List<Release>,
    favorites: List<FavoriteEpisode>,
    posters: Map<String, Bitmap>,
) {
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        items(releases.size) { index ->
            val release = releases[index]
            ReleaseRow(
                release = release,
                favorited = favorites.any {
                    it.showTitle == release.showTitle && it.episodeCode == release.episodeCode
                },
                posters = posters,
            )
        }
        // Trailing air so the last card can settle clear of the widget's edge.
        item { Spacer(GlanceModifier.fillMaxWidth().height(Dimens.listRowHeight())) }
    }
}

/** One release card: poster, meta line, title, the numeric column, and the star. */
@Composable
private fun ReleaseRow(release: Release, favorited: Boolean, posters: Map<String, Bitmap>) {
    val dimmed = release.hasAired
    val metaColor = Tokens.dim(
        if (release.isToday) Tokens.Accent else Tokens.TextSecondary,
        dimmed,
    )
    val statusColor = Tokens.dim(
        if (release.isToday) Tokens.Accent else Tokens.TextTertiary,
        dimmed,
    )
    val rowHeight = Dimens.listRowHeight()

    RowSurface(today = release.isToday) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(title = release.showTitle, posters = posters, dimmed = dimmed)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${release.dayLabel} · ${release.airTime} · ${release.network}",
                    style = Tokens.label(Dimens.metaSize(), metaColor),
                    maxLines = 1,
                )
                Text(
                    text = release.showTitle,
                    style = Tokens.display(
                        Dimens.TitleSize,
                        Tokens.dim(Tokens.TextPrimary, dimmed),
                    ),
                    maxLines = 1,
                    // Only the title opens IMDb (via MainActivity's pass-through — Glance can't
                    // launch an arbitrary Intent) so a poster or meta tap can't be mistaken for it.
                    modifier = GlanceModifier.clickable(
                        actionStartActivity<MainActivity>(
                            actionParametersOf(
                                ActionKeys.showTitle to release.showTitle,
                                ActionKeys.episodeCode to release.episodeCode,
                                ActionKeys.imdbId to (release.imdbId ?: ""),
                            )
                        )
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // A future episode leads with its countdown instead of its episode number —
                    // days-away is the more useful glance value while it's still ahead; today's and
                    // aired rows keep the code.
                    text = if (release.dayOffset > 0) release.countdownLabel else release.episodeCode,
                    style = Tokens.numeric(
                        Dimens.accentLabelSize(),
                        Tokens.dim(Tokens.TextPrimary, dimmed),
                        TextAlign.End,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = release.status.label,
                    style = Tokens.label(Dimens.statusSize(), statusColor, TextAlign.End),
                    maxLines = 1,
                )
            }
            StarToggle(
                favorited = favorited,
                dimmed = dimmed,
                targetHeight = rowHeight,
                onClick = actionRunCallback<ToggleFavoriteAction>(
                    actionParametersOf(
                        ActionKeys.showTitle to release.showTitle,
                        ActionKeys.episodeCode to release.episodeCode,
                        ActionKeys.episodeLabel to release.favoriteLabel(),
                    )
                ),
            )
        }
    }
}
