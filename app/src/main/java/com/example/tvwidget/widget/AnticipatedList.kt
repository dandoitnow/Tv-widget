package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
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
import com.example.tvwidget.data.AnticipatedShow
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The ANTICIPATED tab: an auto-curated premiere list, never the user's chosen shows. The content is
 * cached locally and refreshed once a day by `AnticipatedSyncWorker`; the header shows the last
 * sync time.
 */
@Composable
fun AnticipatedList(shows: List<AnticipatedShow>, posters: Map<String, Bitmap>) {
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        items(shows.size) { index -> AnticipatedRow(rank = index + 1, show = shows[index], posters = posters) }
        item { Spacer(GlanceModifier.fillMaxWidth().height(Tokens.ListRowHeight)) }
    }
}

@Composable
private fun AnticipatedRow(rank: Int, show: AnticipatedShow, posters: Map<String, Bitmap>) {
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
                // The rank is accent-coloured; the rest of the meta line is secondary text. Glance
                // has no inline spans, so the two colours are two Text nodes in a Row.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "#$rank", style = Tokens.mono(Dimens.metaSize(), Tokens.Accent), maxLines = 1)
                    Spacer(GlanceModifier.width(5.dp))
                    Text(
                        text = "${show.kind} · ${show.network}",
                        style = Tokens.mono(Dimens.metaSize(), Tokens.TextSecondary),
                        maxLines = 1,
                    )
                }
                Text(
                    text = show.title,
                    style = Tokens.display(Dimens.titleSize()),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(6.dp))
            // No fixed width here (the compact design used 46dp): the accent label and hype-bar row
            // both grow with the text-size tier, so a fixed width would clip "IN 57D" once it's
            // rendered at the XL tier's larger font.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = show.awayLabel,
                    style = Tokens.mono(Dimens.accentLabelSize(), Tokens.TextPrimary, TextAlign.End),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HypeBar(percent = show.hypePercent)
                    Spacer(GlanceModifier.width(3.dp))
                    Text(
                        text = show.premiereDate,
                        style = Tokens.mono(Dimens.smallLabelSize(), Tokens.TextTertiary),
                        maxLines = 1,
                    )
                }
            }
        }
        Hairline()
    }
}

/**
 * 26 x 2dp track (wider at bigger size tiers) with an accent fill at the hype percentage. Glance has
 * no fractional widths inside a row, so the fill and the remainder are two fixed-width spacers.
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
            .background(Tokens.white(0.14f)),
    ) {
        Spacer(GlanceModifier.width(filled).height(2.dp).background(Tokens.Accent))
    }
}
