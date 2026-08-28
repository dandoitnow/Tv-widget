package com.example.tvwidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.item
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
) {
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        if (shows.isEmpty()) {
            item { EmptyState() }
        }
        shows.forEach { show ->
            item { ShowRow(show = show, expanded = show.title == openShow, logOpen = show.title == openRewatchLog) }
            if (show.title == openRewatchLog) {
                item { RewatchLogPanel(show) }
            }
            if (show.title == openShow) {
                show.episodes.forEach { episode ->
                    item { EpisodeRow(episode) }
                }
            }
        }
        item { Spacer(GlanceModifier.fillMaxWidth().height(Tokens.ListRowHeight)) }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = GlanceModifier.fillMaxWidth().height(Tokens.ListRowHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "NO FAVORITES YET", style = Tokens.mono65(Tokens.white(0.35f)))
    }
}

@Composable
private fun ShowRow(show: FavoriteShow, expanded: Boolean, logOpen: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(if (expanded) Tokens.accent(0.09f) else Tokens.Background),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Tokens.ListRowHeight - 1.dp)
                .padding(horizontal = Tokens.RowPaddingHorizontal)
                .clickable(
                    actionRunCallback<ToggleShowExpandedAction>(
                        actionParametersOf(ActionKeys.showTitle to show.title)
                    )
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster()
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = show.title,
                style = Tokens.display(Tokens.ShowNameSize),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            RewatchControl(show = show, logOpen = logOpen)
            Spacer(GlanceModifier.width(4.dp))
            Text(
                text = show.episodeCountLabel,
                style = Tokens.mono65(Tokens.TextTertiary),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(4.dp))
            // The mock rotates a caret 90 degrees over 300ms. RemoteViews cannot animate a
            // rotation, so the two states are two glyphs.
            Text(text = if (expanded) "⌄" else "›", style = Tokens.mono8(Tokens.Accent))
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
            style = Tokens.mono55(Tokens.Accent),
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
            style = Tokens.mono7(Tokens.TextPrimary, TextAlign.Center),
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
    Box(
        modifier = GlanceModifier
            .size(Tokens.TouchTargetCompact)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .size(9.dp)
                .cornerRadiusCompat(Tokens.RadiusPill)
                .background(Tokens.white(0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = glyph, style = Tokens.mono7(Tokens.TextPrimary, TextAlign.Center))
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
        Column(
            modifier = GlanceModifier
                .width(96.dp)
                .cornerRadiusCompat(Tokens.RadiusPanel)
                .background(Tokens.Surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(text = "REWATCH LOG", style = Tokens.mono5(Tokens.TextTertiary))
            Spacer(GlanceModifier.height(2.dp))
            if (show.rewatchDates.isEmpty()) {
                Text(text = "NO REWATCHES YET", style = Tokens.mono6(Tokens.white(0.35f)))
            } else {
                show.rewatchDates.forEachIndexed { index, date ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "#${index + 1}", style = Tokens.mono6(Tokens.Accent))
                        Spacer(GlanceModifier.width(6.dp))
                        Text(text = date, style = Tokens.mono6(Tokens.TextPrimary))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            text = if (index == 0) "FIRST" else "REWATCH",
                            style = Tokens.mono6(Tokens.TextTertiary),
                        )
                    }
                }
            }
        }
    }
}

/** 30dp episode row under an expanded show; the star un-favourites that episode. */
@Composable
private fun EpisodeRow(episode: FavoriteEpisode) {
    Column(modifier = GlanceModifier.fillMaxWidth().background(Tokens.accent(0.05f))) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Tokens.EpisodeRowHeight - 1.dp)
                .padding(start = Tokens.EpisodeRowInset, end = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = episode.episodeCode, style = Tokens.mono8(Tokens.TextPrimary), maxLines = 1)
            Spacer(GlanceModifier.width(7.dp))
            Text(
                text = episode.label,
                style = Tokens.mono65(Tokens.TextSecondary),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            StarToggle(
                favorited = true,
                iconSize = Tokens.StarIconSmall,
                targetHeight = Tokens.EpisodeRowHeight - 1.dp,
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
