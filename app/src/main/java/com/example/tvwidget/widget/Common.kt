package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.example.tvwidget.R
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/** The 1dp `#FFFFFF` @ 6% rule that closes every list row. */
@Composable
fun Hairline(color: Color = Tokens.Hairline) {
    Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * The poster block — 26 x 36dp at the compact breakpoint, larger under [Dimens] once the widget is
 * resized taller. Draws the real cached art for [title] when [posters] has it — populated by
 * `AnticipatedSyncWorker` via [PosterStore], since widgets cannot fetch images at draw time. Falls
 * back to the placeholder tile for anything not cached yet (e.g. right after a show is first
 * tracked, before the sync worker has run).
 */
@Composable
fun Poster(title: String, posters: Map<String, Bitmap>, dimmed: Boolean = false) {
    val width = Dimens.posterWidth()
    val height = Dimens.posterHeight()
    val bitmap = posters[PosterStore.keyFor(title)]
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier
                .size(width, height)
                .cornerRadiusCompat(Tokens.RadiusPoster),
        )
        return
    }
    Box(
        modifier = GlanceModifier
            .size(width, height)
            .cornerRadiusCompat(Tokens.RadiusPoster)
            .background(ImageProvider(R.drawable.poster_placeholder)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = "POSTER",
            style = Tokens.mono(4.5f, Tokens.dim(Tokens.white(0.55f), dimmed), TextAlign.Center),
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 1.dp),
        )
    }
}

/**
 * A star toggle. The glyph scales with [Dimens] like everything else in the row; the tap target is
 * padded out to at least [Tokens.TouchTarget], which is why the caller passes the row height as
 * [targetHeight].
 */
@Composable
fun StarToggle(
    favorited: Boolean,
    onClick: Action,
    iconSize: Dp = Dimens.starIconSize(),
    targetWidth: Dp = Tokens.TouchTarget,
    targetHeight: Dp = Tokens.TouchTarget,
    dimmed: Boolean = false,
) {
    val tint = if (favorited) Tokens.Accent else Tokens.white(0.45f)
    Box(
        modifier = GlanceModifier
            .width(targetWidth)
            .height(targetHeight)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(
                if (favorited) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            ),
            contentDescription = if (favorited) "Remove from favorites" else "Add to favorites",
            colorFilter = ColorFilter.tint(Tokens.provider(Tokens.dim(tint, dimmed))),
            modifier = GlanceModifier.size(iconSize),
        )
    }
}
