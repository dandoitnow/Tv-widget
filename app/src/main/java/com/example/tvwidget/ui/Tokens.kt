package com.example.tvwidget.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * The widget's design tokens.
 *
 * Three things carry the whole look, and each was picked over alternatives:
 *
 *  1. **A warm ground, not neutral black.** Pure #000/#0B0B0B is what an unstyled dark app looks
 *     like. Every surface here sits a few points warm, so black reads as intentional (near-black
 *     ink) rather than absent.
 *  2. **Air instead of rules.** The old list separated rows with a 1dp hairline — a hard edge, and
 *     the most tabular thing in the design. Rows are now soft-cornered surfaces separated by
 *     spacing, which is the single change that stops the widget reading as a spreadsheet.
 *  3. **Serif titles, sans everything else.** Titles were already serif; the meta lines were
 *     monospace, which reads *terminal*, not editorial. Monospace is gone everywhere except where
 *     digits need to hold a column.
 *
 * RemoteViews has no shadows, no blur, and no animation, so depth here is entirely implied — light
 * from above via two-stop gradients in the surface drawables. That's the honest ceiling of what a
 * home-screen widget can express; the app screen (see `ui/AppTheme.kt`) is where real motion lives.
 */
object Tokens {

    // -- Palette -----------------------------------------------------------------------------
    val Background = Color(0xFF0E0C0A)
    val Surface = Color(0xFF1A1714)

    /**
     * Metallic gold, not yellow. Flat #F2C81E read as highlighter; this is the pigment of the
     * metal itself. Surfaces that carry it use a two-stop gradient (see `surface_pill_selected`)
     * because a single flat gold is exactly what makes gold look like paint instead of metal —
     * text can't gradient in Glance, so text gets the solid value and surfaces do the shimmer.
     */
    val Accent = Color(0xFFD8B45F)
    val AccentDeep = Color(0xFFA98328)

    /**
     * Warm off-white rather than #FFF. Pure white against a warm ground reads slightly blue and
     * clinical; this keeps the whole surface in one temperature.
     */
    val TextPrimary = Color(0xFFF3EDE3)

    fun accent(alpha: Float): Color = Accent.copy(alpha = alpha)

    /** Warm neutral at a given alpha — the secondary text ramp. */
    fun white(alpha: Float): Color = Color(0xFFEDE6DA).copy(alpha = alpha)

    val TextSecondary = white(0.52f)
    val TextTertiary = white(0.40f)
    val TextMuted = white(0.46f)

    /**
     * Aired rows render dimmed. RemoteViews has no view-level alpha we can rely on across
     * launchers, so the dim is folded into each colour instead.
     */
    const val DimmedRowAlpha = 0.45f

    fun dim(color: Color, dimmed: Boolean): Color =
        if (dimmed) color.copy(alpha = color.alpha * DimmedRowAlpha) else color

    fun provider(color: Color): ColorProvider = ColorProvider(color)

    // -- Radii -------------------------------------------------------------------------------
    // Nothing in the widget is square. The scale is deliberately large relative to element size —
    // an 18dp radius on a 46dp row is most of the way to a capsule, which is what makes the list
    // feel soft rather than boxed.
    val RadiusWidget = 28.dp
    val RadiusRow = 18.dp
    val RadiusPoster = 8.dp
    val RadiusPill = 999.dp

    // -- Metrics -----------------------------------------------------------------------------
    /** Gutter from the widget's edge to its content. Generous on purpose: space is the luxury. */
    val EdgeInset = 12.dp

    /** Air between rows, replacing the old hairline rules. */
    val RowGap = 5.dp

    val RowPaddingHorizontal = 12.dp

    /** Every interactive area is padded out to at least this regardless of its visual size. */
    val TouchTarget = 40.dp

    // -- Type --------------------------------------------------------------------------------
    // Glance/RemoteViews exposes no letter-spacing, so the tracking the type would ideally carry
    // isn't reproducible; weight and family do that work instead.

    /** Meta lines, labels, status text — the workhorse. */
    fun label(size: Float, color: Color, align: TextAlign? = null) = TextStyle(
        color = provider(color),
        fontSize = size.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
        textAlign = align,
    )

    /**
     * Numerals that need to hold a column (episode codes, countdowns). The one place monospace
     * still earns its keep: `S04E06` and `IN 7D` stacked in a right-aligned column shift around
     * distractingly in a proportional face.
     */
    fun numeric(size: Float, color: Color, align: TextAlign? = null) = TextStyle(
        color = provider(color),
        fontSize = size.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = align,
    )

    /** Show titles. Serif is what makes the list read editorial rather than administrative. */
    fun display(size: Float, color: Color = TextPrimary, align: TextAlign? = null) = TextStyle(
        color = provider(color),
        fontSize = size.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Serif,
        textAlign = align,
    )
}
