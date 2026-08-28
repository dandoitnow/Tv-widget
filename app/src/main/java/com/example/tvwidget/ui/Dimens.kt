package com.example.tvwidget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.LocalSize

/**
 * Size-dependent metrics for [com.example.tvwidget.widget.TvWidget]'s `SizeMode.Responsive`
 * breakpoints (see `TvWidget.sizeMode`). At [Tier.COMPACT] the widget renders at the original 5x2
 * design's density; past that, the user has dragged the widget taller, and everything — row height,
 * poster size, tab pills, and every text size — scales up together. Growing only the row height
 * while leaving text at its original size (an earlier version of this file) just produced a huge row
 * with tiny type floating in the middle of it; the whole point of a resize is for the *content* to
 * use the extra space, not merely the container around it.
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
        Tier.COMPACT -> Tokens.ListRowHeight
        Tier.ROOMY -> 64.dp
        Tier.XL -> 84.dp
    }

    @Composable
    fun posterWidth(): Dp = when (tier()) {
        Tier.COMPACT -> Tokens.PosterWidth
        Tier.ROOMY -> 40.dp
        Tier.XL -> 54.dp
    }

    @Composable
    fun posterHeight(): Dp = when (tier()) {
        Tier.COMPACT -> Tokens.PosterHeight
        Tier.ROOMY -> 56.dp
        Tier.XL -> 76.dp
    }

    @Composable
    fun tabPillHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 30.dp
        Tier.ROOMY -> 42.dp
        Tier.XL -> 54.dp
    }

    /** Tab labels (TODAY / ANTICIPATED / ...) and the header's leading glyphs. */
    @Composable
    fun tabLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 8f
        Tier.ROOMY -> 12f
        Tier.XL -> 15f
    }

    /** A row's show/episode title — the most prominent text in the row. */
    @Composable
    fun titleSize(): Float = when (tier()) {
        Tier.COMPACT -> Tokens.TitleSize
        Tier.ROOMY -> 19f
        Tier.XL -> 24f
    }

    /** Meta lines: day/time/network, rank + kind, status labels underneath a title. */
    @Composable
    fun metaSize(): Float = when (tier()) {
        Tier.COMPACT -> 6.5f
        Tier.ROOMY -> 10f
        Tier.XL -> 13f
    }

    /** Secondary numeric/status text: episode codes, countdown labels, track buttons. */
    @Composable
    fun accentLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 8f
        Tier.ROOMY -> 12f
        Tier.XL -> 15f
    }

    /** Small status text under the accent label: AIRS TONIGHT, IN 7D's premiere date, etc. */
    @Composable
    fun statusSize(): Float = when (tier()) {
        Tier.COMPACT -> 6f
        Tier.ROOMY -> 9f
        Tier.XL -> 11f
    }

    /** The smallest text in the widget: hype-bar premiere dates, rewatch counts. */
    @Composable
    fun smallLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 5.5f
        Tier.ROOMY -> 8f
        Tier.XL -> 10f
    }
}
