package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.size
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
import com.example.tvwidget.R
import com.example.tvwidget.data.AnticipatedShow
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The POPULAR tab: an auto-curated premiere list, never the user's own shows. Same card treatment
 * as TODAY so the two tabs read as one system.
 */
@Composable
fun AnticipatedList(
    shows: List<AnticipatedShow>,
    posters: Map<String, Bitmap>,
    accents: Map<String, Int>,
    trackedIds: Set<Int>,
    hidden: Int = 0,
) {
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        items(shows.size) { index ->
            AnticipatedRow(
                rank = index + 1,
                depth = index,
                show = shows[index],
                posters = posters,
                accents = accents,
                tracked = shows[index].tvMazeId in trackedIds,
            )
        }
        // The reveal control is the last thing in the scroll, so reaching it means having scrolled
        // to the end — the nearest thing RemoteViews allows to loading more on scroll.
        if (hidden > 0) item { ShowMoreRow(remaining = hidden) }
        item { Spacer(GlanceModifier.fillMaxWidth().height(Dimens.listRowHeight())) }
    }
}

@Composable
private fun AnticipatedRow(
    rank: Int,
    depth: Int,
    show: AnticipatedShow,
    posters: Map<String, Bitmap>,
    accents: Map<String, Int>,
    tracked: Boolean,
) {
    RowSurface(depth = depth, edgeAccent = accents[PosterStore.keyFor(show.title)]) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Dimens.listRowHeight())
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(title = show.title, posters = posters)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                // The rank is accent-coloured, the rest of the meta line secondary. Glance has no
                // inline spans, so the two colours are two Text nodes in a Row.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$rank",
                        style = Tokens.numeric(Dimens.metaSize(), Tokens.Accent),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = "${show.kind} · ${show.network}",
                        style = Tokens.label(Dimens.metaSize(), Tokens.TextSecondary),
                        maxLines = 1,
                    )
                }
                Text(
                    text = show.title,
                    style = Tokens.display(Dimens.TitleSize),
                    maxLines = 1,
                    // Same as TODAY's title tap: opens the show's IMDb page (MainActivity resolves
                    // the id live, since these curated entries carry no known one).
                    modifier = GlanceModifier.clickable(
                        actionStartActivity<MainActivity>(
                            actionParametersOf(ActionKeys.showTitle to show.title)
                        )
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            // No fixed width: the countdown and hype row both grow with the size tier, and a fixed
            // width would clip "IN 57D" at the larger ones.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = show.awayLabel,
                    style = Tokens.numeric(Dimens.accentLabelSize(), Tokens.TextPrimary, TextAlign.End),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HypeBar(percent = show.hypePercent)
                    Spacer(GlanceModifier.width(5.dp))
                    Text(
                        text = show.premiereDate,
                        style = Tokens.label(Dimens.smallLabelSize(), Tokens.TextTertiary),
                        maxLines = 1,
                    )
                }
            }
            if (show.trackable) {
                Spacer(GlanceModifier.width(4.dp))
                QuickAdd(show = show, tracked = tracked)
            }
        }
    }
}

/**
 * Track this show, from here.
 *
 * A recommendation you cannot act on where you read it is only half a feature: following something
 * POPULAR just showed you otherwise meant opening the app and searching for the title already on
 * screen. The control changes shape rather than only colour when tracked — a plus and a tick are
 * legible at a glance and to anyone who does not separate the two colours.
 */
@Composable
private fun QuickAdd(show: AnticipatedShow, tracked: Boolean) {
    Box(
        modifier = GlanceModifier
            .width(Tokens.TouchTarget)
            .height(Tokens.TouchTarget)
            .clickable(
                actionRunCallback<TrackShowAction>(
                    actionParametersOf(
                        ActionKeys.tvMazeId to show.tvMazeId,
                        ActionKeys.showTitle to show.title,
                        ActionKeys.network to show.network,
                        ActionKeys.posterUrl to (show.posterUrl ?: ""),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (tracked) R.drawable.ic_check else R.drawable.ic_plus),
            contentDescription = if (tracked) "Stop tracking ${show.title}" else "Track ${show.title}",
            colorFilter = ColorFilter.tint(
                Tokens.provider(if (tracked) Tokens.Accent else Tokens.white(0.42f))
            ),
            modifier = GlanceModifier.size(Dimens.starIconSize()),
        )
    }
}

/**
 * The hype meter. Kept to 2dp and fully rounded so it reads as a fine rule rather than a progress
 * bar — a chunky bar next to a serif title is the kind of detail that makes a design look built out
 * of stock components. Glance has no fractional widths inside a row, so the fill and its track are
 * two fixed-width elements.
 */
@Composable
private fun HypeBar(percent: Int) {
    val width = Dimens.posterWidth()
    val filled = width * (percent.coerceIn(0, 100) / 100f)
    Row(
        modifier = GlanceModifier
            .width(width)
            .height(2.dp)
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(Tokens.white(0.10f)),
    ) {
        Spacer(
            GlanceModifier
                .width(filled)
                .height(2.dp)
                .cornerRadiusCompat(Tokens.RadiusPill)
                .background(Tokens.Accent)
        )
    }
}
