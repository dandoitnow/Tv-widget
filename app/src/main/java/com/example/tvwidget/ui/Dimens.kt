package com.example.tvwidget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.LocalSize

/**
 * Size-dependent metrics for [com.example.tvwidget.widget.TvWidget]'s two `SizeMode.Responsive`
 * breakpoints (see `TvWidget.sizeMode`). Below [ROOMY_HEIGHT_THRESHOLD] the widget renders at the
 * same density as the original 5x2 design; above it — the user has dragged the widget taller — rows
 * and posters get more breathing room instead of the layout just stretching into empty space.
 *
 * Reading [LocalSize] directly in each composable that needs it (rather than threading a size
 * parameter through every function signature) keeps this out of the call chain entirely.
 */
object Dimens {

    private val ROOMY_HEIGHT_THRESHOLD = 170.dp

    @Composable
    fun isRoomy(): Boolean = LocalSize.current.height > ROOMY_HEIGHT_THRESHOLD

    @Composable
    fun listRowHeight(): Dp = if (isRoomy()) 60.dp else Tokens.ListRowHeight

    @Composable
    fun posterWidth(): Dp = if (isRoomy()) 34.dp else Tokens.PosterWidth

    @Composable
    fun posterHeight(): Dp = if (isRoomy()) 48.dp else Tokens.PosterHeight

    @Composable
    fun tabPillHeight(): Dp = if (isRoomy()) 36.dp else 30.dp
}
