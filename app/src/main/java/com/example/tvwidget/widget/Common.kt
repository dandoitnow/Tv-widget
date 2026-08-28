package com.example.tvwidget.widget

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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.example.tvwidget.R
import com.example.tvwidget.ui.Tokens

/** The 1dp `#FFFFFF` @ 6% rule that closes every list row. */
@Composable
fun Hairline(color: Color = Tokens.Hairline) {
    Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * The 26 x 36dp poster block: placeholder art plus a `POSTER` caption. Swap [ImageProvider] for the
 * cached key art bitmap — widgets cannot fetch images at draw time, so the bitmap has to be on disk
 * already (portrait, ~78 x 108px for 3x) and drawn `ContentScale.Crop`.
 */
@Composable
fun Poster(dimmed: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .size(Tokens.PosterWidth, Tokens.PosterHeight)
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
 * A star toggle. The glyph stays at its documented size while the tap target is padded out to
 * [Tokens.TouchTarget], which is why the caller passes the row height as [targetHeight].
 */
@Composable
fun StarToggle(
    favorited: Boolean,
    onClick: Action,
    iconSize: Dp = Tokens.StarIcon,
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
