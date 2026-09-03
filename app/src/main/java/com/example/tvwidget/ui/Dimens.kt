package com.example.tvwidget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.LocalSize

/**
 * Size-dependent metrics for [com.example.tvwidget.widget.TvWidget]'s `SizeMode.Responsive`
 * breakpoints (see `TvWidget.sizeMode`).
 *
 * [Tier.COMPACT] stays dense on purpose: a real 4x2/5x2 placement is short, and seeing several
 * upcoming releases at a glance is most of why a release tracker is a widget at all. Note the row
 * *pitch* (height + gap) is what density actually costs — introducing air between rows was paid for
 * by taking those dp back out of the row height, so the redesign reads far softer at essentially
 * the same rows-per-screen as before.
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
        Tier.COMPACT -> 44.dp
        Tier.ROOMY -> 84.dp
        Tier.XL -> 108.dp
    }

    /** Air between row surfaces — what replaced the old hairline rules. */
    @Composable
    fun rowGap(): Dp = when (tier()) {
        Tier.COMPACT -> 4.dp
        Tier.ROOMY -> 7.dp
        Tier.XL -> 9.dp
    }

    @Composable
    fun posterWidth(): Dp = when (tier()) {
        Tier.COMPACT -> 28.dp
        Tier.ROOMY -> 54.dp
        Tier.XL -> 68.dp
    }

    @Composable
    fun posterHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 39.dp
        Tier.ROOMY -> 75.dp
        Tier.XL -> 95.dp
    }

    @Composable
    fun tabPillHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 26.dp
        Tier.ROOMY -> 44.dp
        Tier.XL -> 56.dp
    }

    /** Tab labels. */
    @Composable
    fun tabLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 11f
        Tier.ROOMY -> 15f
        Tier.XL -> 19f
    }

    /**
     * A row's show title. Deliberately fixed, not tier-scaled: it's a `maxLines = 1` field next to a
     * poster and a status column that don't shrink to make room, so letting it grow with the widget
     * pushed titles past what the row could fit.
     */
    const val TitleSize = 19f

    /** Meta lines: day/time/network, rank + kind. */
    @Composable
    fun metaSize(): Float = when (tier()) {
        Tier.COMPACT -> 9.5f
        Tier.ROOMY -> 13f
        Tier.XL -> 16f
    }

    /** Episode codes and countdowns — the numeric column. */
    @Composable
    fun accentLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 12f
        Tier.ROOMY -> 17f
        Tier.XL -> 21f
    }

    /** Status text under the numeric column: AIRS TONIGHT, SCHEDULED. */
    @Composable
    fun statusSize(): Float = when (tier()) {
        Tier.COMPACT -> 8f
        Tier.ROOMY -> 11f
        Tier.XL -> 14f
    }

    /** The smallest text: premiere dates beside the hype bar. */
    @Composable
    fun smallLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 7.5f
        Tier.ROOMY -> 10f
        Tier.XL -> 12f
    }

    @Composable
    fun starIconSize(): Dp = when (tier()) {
        Tier.COMPACT -> 14.dp
        Tier.ROOMY -> 20.dp
        Tier.XL -> 24.dp
    }

    /** The TODAY pill's live dot. */
    @Composable
    fun tabGlyphSize(): Dp = when (tier()) {
        Tier.COMPACT -> 5.dp
        Tier.ROOMY -> 8.dp
        Tier.XL -> 10.dp
    }
}
