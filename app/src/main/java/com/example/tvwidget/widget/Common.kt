package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.example.tvwidget.MainActivity
import com.example.tvwidget.R
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Surfaces
import com.example.tvwidget.ui.Tokens

/**
 * One list row's surface: a soft-cornered card with air around it, replacing the hairline-separated
 * rows the list used to be. [today] warms it from the leading edge.
 *
 * Two things vary per row, which is why the fill is a generated bitmap rather than a drawable:
 *
 *  * [depth] fades the card as it descends the list, so the list falls away into the widget instead
 *    of lying flat on it. That doubles as hierarchy — the top of the list ends up the most solid
 *    thing on screen without any explicit marker saying so.
 *  * [edgeAccent] washes the row's leading edge with the dominant colour of its own poster, so every
 *    row is lit by its own artwork. A shelf of shows then reads as a shelf of *different* shows
 *    rather than as repeated furniture.
 *
 * Both are composited into the *same* bitmap by [Surfaces.row] rather than layered as views. The
 * wash was an overlay `Box` at first and that was wrong twice: `fillMaxSize` inside a wrap-content
 * `FrameLayout` measures against the parent's available height and dragged the row's background out
 * to fill the whole widget, and a view stacked over the content would have swallowed every tap in
 * the row. One bitmap has neither problem.
 *
 * The gap is applied as bottom padding *outside* the surface rather than as a spacer item, so the
 * air belongs to the row and a list never ends on a stray gap.
 */
@Composable
fun RowSurface(
    today: Boolean = false,
    depth: Int = 0,
    edgeAccent: Int? = null,
    hero: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = Dimens.rowGap())) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadiusCompat(Tokens.RadiusRow)
                .background(
                    ImageProvider(Surfaces.row(today = today, depth = depth, accent = edgeAccent, hero = hero)),
                    contentScale = ContentScale.FillBounds,
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
fun Poster(
    title: String,
    posters: Map<String, Bitmap>,
    dimmed: Boolean = false,
    width: Dp = Dimens.posterWidth(),
    height: Dp = Dimens.posterHeight(),
    behind: List<Bitmap> = emptyList(),
) {
    val bitmap = posters[PosterStore.keyFor(title)]

    if (bitmap != null) {
        // No corner clip. The shape is baked into the art itself as a squircle — continuous
        // curvature rather than the circular arc `cornerRadius` gives — and clipping a circle over
        // the top of it would simply shave the corners back off.
        val art = if (behind.isEmpty()) {
            bitmap
        } else {
            Surfaces.heroStack(bitmap, behind, cacheKey = title)
        }
        Image(
            provider = ImageProvider(art),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.size(
                if (behind.isEmpty()) width else width * STACK_WIDTH_FACTOR,
                height,
            ),
        )
    } else {
        Box(
            modifier = GlanceModifier
                .size(width, height)
                .cornerRadiusCompat(Tokens.RadiusPoster)
                .background(ImageProvider(R.drawable.poster_placeholder)),
        ) {}
    }
}

/** Matches the extra width [Surfaces.heroStack] adds for the cards fanned behind the hero. */
private const val STACK_WIDTH_FACTOR = 1.34f

/**
 * The control that ends a truncated list: reveals another page of rows.
 *
 * It sits as the final item *inside* the scrolling list rather than pinned beneath it, because
 * reaching it is the whole point. RemoteViews reports no scroll position and offers no scroll
 * callback, so a list has no way to notice it has been scrolled to the end and extend itself; a row
 * you can only reach by scrolling that far is the closest the platform allows, and it has the
 * advantage of being explicit about what it costs.
 *
 * Styled as a quiet row rather than a button. It is a continuation of the list, not a call to
 * action, and a loud control at the bottom of a list of shows would pull attention away from them.
 */
@Composable
fun ShowMoreRow(remaining: Int) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = Dimens.rowGap())) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Tokens.TouchTarget)
                .cornerRadiusCompat(Tokens.RadiusRow)
                .background(ImageProvider(Surfaces.row(today = false, depth = 3)))
                .clickable(actionRunCallback<ExpandRowsAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "SHOW $remaining MORE",
                style = Tokens.label(Dimens.statusSize() + 1.5f, Tokens.Accent, TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

/**
 * What the widget shows when it has nothing to show.
 *
 * The alternative was returning nothing at all, which left an empty rectangle on the home screen and
 * no way to tell a widget with no releases from a widget that had broken. An empty state that names
 * its own emptiness and points somewhere is the difference between quiet and dead.
 */
@Composable
fun EmptyState(headline: String, detail: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 18.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    actionParametersOf(ActionKeys.openCatalog to true)
                )
            ),
    ) {
        Text(
            text = headline,
            style = Tokens.display(Dimens.TitleSize - 2f, Tokens.TextSecondary),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(5.dp))
        Text(
            text = detail,
            style = Tokens.label(Dimens.metaSize(), Tokens.TextTertiary),
            maxLines = 2,
        )
        Spacer(GlanceModifier.height(9.dp))
        Text(
            text = "OPEN CATALOG",
            style = Tokens.label(Dimens.statusSize() + 1.5f, Tokens.Accent),
            maxLines = 1,
        )
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
