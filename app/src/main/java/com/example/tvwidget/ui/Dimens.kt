package com.example.tvwidget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.LocalSize

/**
 * Size-dependent metrics for [com.example.tvwidget.widget.TvWidget]'s `SizeMode.Responsive`
 * breakpoints (see `TvWidget.sizeMode`). [Tier.COMPACT] is deliberately kept dense: a real 4x2/5x2
 * placement is short (well under 170dp), and an earlier, much larger COMPACT scale only fit one or
 * two rows on screen — most of the point of a home-screen widget is seeing several rows of upcoming
 * releases at a glance, so row count wins over per-row size at this tier. ROOMY and XL, used once the
 * user drags the widget taller, scale everything up together from there.
 *
 * Reading [LocalSize] directly in each composable that needs it (rather than threading a size
 * parameter through every function signature) keeps this out of the call chain entirely.
 */
object Dimens {

    enum class Tier { COMPACT, ROOMY, XL }

    @Composable
    fun tier(): Tier {
        val height = LocalSize.current.height
        return when {
            height > 400.dp -> Tier.XL
            height > 170.dp -> Tier.ROOMY
            else -> Tier.COMPACT
        }
    }

    @Composable
    fun listRowHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 46.dp
        Tier.ROOMY -> 96.dp
        Tier.XL -> 120.dp
    }

    @Composable
    fun posterWidth(): Dp = when (tier()) {
        Tier.COMPACT -> 28.dp
        Tier.ROOMY -> 60.dp
        Tier.XL -> 76.dp
    }

    @Composable
    fun posterHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 40.dp
        Tier.ROOMY -> 84.dp
        Tier.XL -> 106.dp
    }

    @Composable
    fun tabPillHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 26.dp
        Tier.ROOMY -> 60.dp
        Tier.XL -> 74.dp
    }

    /** Tab labels (TODAY / ANTICIPATED / ...) and the header's leading glyphs. */
    @Composable
    fun tabLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 13f
        Tier.ROOMY -> 18f
        Tier.XL -> 22f
    }

    /**
     * A row's show/episode title. Deliberately fixed, not tier-scaled: it's a `maxLines = 1` field
     * next to a poster and a status column that don't shrink to make room, so letting it keep
     * growing at ROOMY/XL — as an earlier version of this file did — pushed titles past what the
     * row could actually fit.
     */
    const val TitleSize = 19f

    /** Meta lines: day/time/network, rank + kind, status labels underneath a title. */
    @Composable
    fun metaSize(): Float = when (tier()) {
        Tier.COMPACT -> 9f
        Tier.ROOMY -> 15f
        Tier.XL -> 18f
    }

    /** Secondary numeric/status text: episode codes, countdown labels, track buttons. */
    @Composable
    fun accentLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 12f
        Tier.ROOMY -> 19f
        Tier.XL -> 23f
    }

    /** Small status text under the accent label: AIRS TONIGHT, IN 7D's premiere date, etc. */
    @Composable
    fun statusSize(): Float = when (tier()) {
        Tier.COMPACT -> 8f
        Tier.ROOMY -> 13f
        Tier.XL -> 16f
    }

    /** The smallest text in the widget: hype-bar premiere dates, rewatch counts. */
    @Composable
    fun smallLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 7f
        Tier.ROOMY -> 11f
        Tier.XL -> 13f
    }

    /** The favorite-star glyph in a full release row. */
    @Composable
    fun starIconSize(): Dp = when (tier()) {
        Tier.COMPACT -> 14.dp
        Tier.ROOMY -> 22.dp
        Tier.XL -> 26.dp
    }

    /** The favorite-star glyph in a compact episode row (under an expanded FAVORITES show). */
    @Composable
    fun starIconSizeSmall(): Dp = when (tier()) {
        Tier.COMPACT -> 12.dp
        Tier.ROOMY -> 18.dp
        Tier.XL -> 21.dp
    }

    /** The header tab pills' leading glyphs: the TODAY live dot and the FAVORITES star. */
    @Composable
    fun tabGlyphSize(): Dp = when (tier()) {
        Tier.COMPACT -> 8.dp
        Tier.ROOMY -> 12.dp
        Tier.XL -> 15.dp
    }
}
