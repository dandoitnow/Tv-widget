package com.example.tvwidget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.LocalSize

/**
 * Size-dependent metrics for [com.example.tvwidget.widget.TvWidget]'s `SizeMode.Responsive`
 * breakpoints (see `TvWidget.sizeMode`). Even at [Tier.COMPACT] — the smallest size a launcher will
 * actually hand a 4x2/5x2 placement — text was still sized off the original design mock, which
 * targeted a much higher effective density than real launchers render at: rows had a lot of empty
 * vertical padding around tiny type. Every size below is now picked to fill its row edge-to-edge at
 * COMPACT already, roughly double the original mock's sizes; ROOMY and XL then scale further for
 * however far past that the user drags the widget.
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
        Tier.COMPACT -> 72.dp
        Tier.ROOMY -> 96.dp
        Tier.XL -> 120.dp
    }

    @Composable
    fun posterWidth(): Dp = when (tier()) {
        Tier.COMPACT -> 44.dp
        Tier.ROOMY -> 60.dp
        Tier.XL -> 76.dp
    }

    @Composable
    fun posterHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 62.dp
        Tier.ROOMY -> 84.dp
        Tier.XL -> 106.dp
    }

    @Composable
    fun tabPillHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 46.dp
        Tier.ROOMY -> 60.dp
        Tier.XL -> 74.dp
    }

    /** Tab labels (TODAY / ANTICIPATED / ...) and the header's leading glyphs. */
    @Composable
    fun tabLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 14f
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
        Tier.COMPACT -> 12f
        Tier.ROOMY -> 15f
        Tier.XL -> 18f
    }

    /** Secondary numeric/status text: episode codes, countdown labels, track buttons. */
    @Composable
    fun accentLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 15f
        Tier.ROOMY -> 19f
        Tier.XL -> 23f
    }

    /** Small status text under the accent label: AIRS TONIGHT, IN 7D's premiere date, etc. */
    @Composable
    fun statusSize(): Float = when (tier()) {
        Tier.COMPACT -> 10f
        Tier.ROOMY -> 13f
        Tier.XL -> 16f
    }

    /** The smallest text in the widget: hype-bar premiere dates, rewatch counts. */
    @Composable
    fun smallLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 9f
        Tier.ROOMY -> 11f
        Tier.XL -> 13f
    }

    /** The favorite-star glyph in a full release row. */
    @Composable
    fun starIconSize(): Dp = when (tier()) {
        Tier.COMPACT -> 18.dp
        Tier.ROOMY -> 22.dp
        Tier.XL -> 26.dp
    }

    /** The favorite-star glyph in a compact episode row (under an expanded FAVORITES show). */
    @Composable
    fun starIconSizeSmall(): Dp = when (tier()) {
        Tier.COMPACT -> 15.dp
        Tier.ROOMY -> 18.dp
        Tier.XL -> 21.dp
    }

    /** The header tab pills' leading glyphs: the TODAY live dot and the FAVORITES star. */
    @Composable
    fun tabGlyphSize(): Dp = when (tier()) {
        Tier.COMPACT -> 9.dp
        Tier.ROOMY -> 12.dp
        Tier.XL -> 15.dp
    }
}
