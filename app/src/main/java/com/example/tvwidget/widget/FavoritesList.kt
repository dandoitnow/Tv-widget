package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.example.tvwidget.data.FavoriteEpisode
import com.example.tvwidget.data.FavoriteShow
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The FAVORITES tab: saved episodes grouped by show, one show expandable at a time (accordion),
 * each with a rewatch counter whose value is derived from its log.
 */
@Composable
fun FavoritesList(
    shows: List<FavoriteShow>,
    openShow: String?,
    openRewatchLog: String?,
    posters: Map<String, Bitmap>,
) {
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        if (shows.isEmpty()) {
            item { EmptyState() }
        }
        shows.forEach { show ->
            item {
                ShowRow(
                    show = show,
                    expanded = show.title == openShow,
                    logOpen = show.title == openRewatchLog,
                    posters = posters,
                )
            }
            if (show.title == openRewatchLog) {
                item { RewatchLogPanel(show) }
            }
            if (show.title == openShow) {
                show.episodes.forEach { episode ->
                    item { EpisodeRow(episode) }
                }
            }
        }
        item { Spacer(GlanceModifier.fillMaxWidth().height(Dimens.listRowHeight())) }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = GlanceModifier.fillMaxWidth().height(Dimens.listRowHeight()),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "NO FAVORITES YET", style = Tokens.mono(Dimens.metaSize(), Tokens.white(0.35f)))
    }
}

@Composable
private fun ShowRow(show: FavoriteShow, expanded: Boolean, logOpen: Boolean, posters: Map<String, Bitmap>) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(if (expanded) Tokens.accent(0.09f) else Tokens.Background),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Dimens.listRowHeight() - 1.dp)
                .padding(horizontal = Tokens.RowPaddingHorizontal)
                .clickable(
                    actionRunCallback<ToggleShowExpandedAction>(
                        actionParametersOf(ActionKeys.showTitle to show.title)
                    )
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(title = show.title, posters = posters)
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = show.title,
                style = Tokens.display(Dimens.titleSize() + 1f),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            RewatchControl(show = show, logOpen = logOpen)
            Spacer(GlanceModifier.width(4.dp))
            Text(
                text = show.episodeCountLabel,
                style = Tokens.mono(Dimens.metaSize(), Tokens.TextTertiary),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(4.dp))
            // The mock rotates a caret 90 degrees over 300ms. RemoteViews cannot animate a
            // rotation, so the two states are two glyphs.
            Text(text = if (expanded) "⌄" else "›", style = Tokens.mono(Dimens.accentLabelSize(), Tokens.Accent))
        }
        Hairline()
    }
}

/**
 * `REWATCHED` pill: label opens the log, `-` and `+` walk the count.
 *
 * The three controls share a 46dp row with the show name, so they cannot each take a 40dp tap
 * target; they get [Tokens.TouchTargetCompact], the widest the row affords, while the label itself
 * is comfortably wide.
 */
@Composable
private fun RewatchControl(show: FavoriteShow, logOpen: Boolean) {
    Row(
        modifier = GlanceModifier
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(Tokens.accent(0.12f))
            .padding(start = 5.dp, end = 3.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (logOpen) "REWATCHED ▴" else "REWATCHED ▾",
            style = Tokens.mono(Dimens.smallLabelSize(), Tokens.Accent),
            modifier = GlanceModifier.clickable(
                actionRunCallback<ToggleRewatchLogAction>(
                    actionParametersOf(ActionKeys.showTitle to show.title)
                )
            ),
        )
        Spacer(GlanceModifier.width(4.dp))
        StepperButton(
            glyph = "−",
            onClick = actionRunCallback<RemoveRewatchAction>(
                actionParametersOf(ActionKeys.showTitle to show.title)
            ),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = "×${show.rewatchCount}",
            style = Tokens.mono(Dimens.metaSize(), Tokens.TextPrimary, TextAlign.Center),
            modifier = GlanceModifier.width(9.dp),
        )
        Spacer(GlanceModifier.width(4.dp))
        StepperButton(
            glyph = "+",
            onClick = actionRunCallback<AddRewatchAction>(
                actionParametersOf(ActionKeys.showTitle to show.title)
            ),
        )
    }
}

@Composable
private fun StepperButton(glyph: String, onClick: androidx.glance.action.Action) {
    val glyphBoxSize = when (Dimens.tier()) {
        Dimens.Tier.COMPACT -> 9.dp
        Dimens.Tier.ROOMY -> 13.dp
        Dimens.Tier.XL -> 17.dp
    }
    Box(
        modifier = GlanceModifier
            .size(Tokens.TouchTargetCompact)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .size(glyphBoxSize)
                .cornerRadiusCompat(Tokens.RadiusPill)
                .background(Tokens.white(0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = glyph, style = Tokens.mono(Dimens.metaSize(), Tokens.TextPrimary, TextAlign.Center))
        }
    }
}

/** Right-aligned dropdown listing every rewatch date for the show. */
@Composable
private fun RewatchLogPanel(show: FavoriteShow) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(Tokens.accent(0.05f))
            .padding(start = Tokens.RowPaddingHorizontal, end = 34.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.End,
    ) {
        val panelWidth = when (Dimens.tier()) {
            Dimens.Tier.COMPACT -> 96.dp
            Dimens.Tier.ROOMY -> 140.dp
            Dimens.Tier.XL -> 180.dp
        }
        Column(
            modifier = GlanceModifier
                .width(panelWidth)
                .cornerRadiusCompat(Tokens.RadiusPanel)
                .background(Tokens.Surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(text = "REWATCH LOG", style = Tokens.mono(Dimens.smallLabelSize(), Tokens.TextTertiary))
            Spacer(GlanceModifier.height(2.dp))
            if (show.rewatchDates.isEmpty()) {
                Text(
                    text = "NO REWATCHES YET",
                    style = Tokens.mono(Dimens.metaSize(), Tokens.white(0.35f)),
                )
            } else {
                show.rewatchDates.forEachIndexed { index, date ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "#${index + 1}", style = Tokens.mono(Dimens.metaSize(), Tokens.Accent))
                        Spacer(GlanceModifier.width(6.dp))
                        Text(text = date, style = Tokens.mono(Dimens.metaSize(), Tokens.TextPrimary))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            text = if (index == 0) "FIRST" else "REWATCH",
                            style = Tokens.mono(Dimens.metaSize(), Tokens.TextTertiary),
                        )
                    }
                }
            }
        }
    }
}

/** 30dp episode row under an expanded show (taller at bigger tiers); the star un-favourites it. */
@Composable
private fun EpisodeRow(episode: FavoriteEpisode) {
    val rowHeight = when (Dimens.tier()) {
        Dimens.Tier.COMPACT -> Tokens.EpisodeRowHeight
        Dimens.Tier.ROOMY -> 42.dp
        Dimens.Tier.XL -> 56.dp
    }
    Column(modifier = GlanceModifier.fillMaxWidth().background(Tokens.accent(0.05f))) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(rowHeight - 1.dp)
                .padding(start = Tokens.EpisodeRowInset, end = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = episode.episodeCode,
                style = Tokens.mono(Dimens.accentLabelSize(), Tokens.TextPrimary),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(7.dp))
            Text(
                text = episode.label,
                style = Tokens.mono(Dimens.metaSize(), Tokens.TextSecondary),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            StarToggle(
                favorited = true,
                iconSize = Dimens.starIconSizeSmall(),
                targetHeight = rowHeight - 1.dp,
                onClick = actionRunCallback<ToggleFavoriteAction>(
                    actionParametersOf(
                        ActionKeys.showTitle to episode.showTitle,
                        ActionKeys.episodeCode to episode.episodeCode,
                        ActionKeys.episodeLabel to episode.label,
                    )
                ),
            )
        }
        Hairline(Tokens.white(0.05f))
    }
}
