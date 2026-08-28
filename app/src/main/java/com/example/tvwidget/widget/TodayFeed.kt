package com.example.tvwidget.widget

import android.graphics.Bitmap
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
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The TODAY tab: the user's watchlist releases in one continuous chronological list — aired above,
 * today in the middle, scheduled below. No collapse toggle: whatever the launcher's `ListView`
 * remembers as the user's scroll offset is all the "resting position" a Glance widget can offer, so
 * the list is just left complete and in order rather than faking a jump-to-today.
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
        // Bottom padding so the last row can settle clear of the widget edge.
        item { Spacer(GlanceModifier.fillMaxWidth().height(Dimens.listRowHeight())) }
    }
}

/** One 46dp release row: poster, meta line, title, status column and star. */
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

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            // Today's rows carry a full-row accent tint.
            .background(if (release.isToday) Tokens.accent(0.07f) else Tokens.Background),
    ) {
        val rowHeight = Dimens.listRowHeight()
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(rowHeight - 1.dp)
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
            Poster(title = release.showTitle, posters = posters, dimmed = dimmed)
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${release.dayLabel} · ${release.airTime} · ${release.network}",
                    style = Tokens.mono(Dimens.metaSize(), metaColor),
                    maxLines = 1,
                )
                Text(
                    text = release.showTitle,
                    style = Tokens.display(
                        Dimens.titleSize(),
                        Tokens.dim(Tokens.TextPrimary, dimmed),
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = release.episodeCode,
                    style = Tokens.mono(
                        Dimens.accentLabelSize(),
                        Tokens.dim(Tokens.TextPrimary, dimmed),
                        TextAlign.End,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = release.status.label,
                    style = Tokens.mono(Dimens.statusSize(), statusColor, TextAlign.End),
                    maxLines = 1,
                )
            }
            StarToggle(
                favorited = favorited,
                dimmed = dimmed,
                targetHeight = rowHeight - 1.dp,
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
