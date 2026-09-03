package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.example.tvwidget.R
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * One list row's surface: a soft-cornered card with air around it, replacing the hairline-separated
 * rows the list used to be. [today] warms it from the leading edge (see `surface_row_today`).
 *
 * The gap is applied as bottom padding *outside* the surface rather than as a spacer item, so the
 * air belongs to the row and a list never ends on a stray gap.
 */
@Composable
fun RowSurface(
    today: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = Dimens.rowGap())) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadiusCompat(Tokens.RadiusRow)
                .background(
                    ImageProvider(if (today) R.drawable.surface_row_today else R.drawable.surface_row)
                ),
        ) {
            content()
        }
    }
}

/**
 * The poster block. Draws real cached art for [title] when [posters] has it — populated by
 * `AnticipatedSyncWorker` via [PosterStore], since widgets can't fetch images at draw time.
 *
 * The fallback is a quiet gradient tile with no label on it. The old version stamped the word
 * "POSTER" across it, which drew the eye straight to the one thing that was missing; art that isn't
 * there yet should recede, not announce itself.
 */
@Composable
fun Poster(title: String, posters: Map<String, Bitmap>, dimmed: Boolean = false) {
    val width = Dimens.posterWidth()
    val height = Dimens.posterHeight()
    val bitmap = posters[PosterStore.keyFor(title)]
    val shape = GlanceModifier.size(width, height).cornerRadiusCompat(Tokens.RadiusPoster)

    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = shape,
        )
    } else {
        Box(modifier = shape.background(ImageProvider(R.drawable.poster_placeholder))) {}
    }
}

/**
 * A star toggle. The glyph scales with [Dimens]; the tap target is padded out to at least
 * [Tokens.TouchTarget], which is why the caller passes the row height as [targetHeight].
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
    val tint = if (favorited) Tokens.Accent else Tokens.white(0.32f)
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
