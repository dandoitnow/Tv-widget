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
 * Design tokens from the 5x2 widget handoff. Every value here is dp/sp at 1x widget scale —
 * the HTML mock renders at 2x for review only.
 *
 * The yellow-on-black palette deliberately overrides the project's Organic design system; if the
 * host app grows its own dark theme, remap [Accent] to the app accent and keep the neutral ramp.
 */
object Tokens {

    // -- Palette -----------------------------------------------------------------------------
    val Background = Color(0xFF0B0B0B)
    val Surface = Color(0xFF171717)
    val PosterPlaceholder = Color(0xFF262626)

    /** A warmer, more muted metallic gold — the original was a flatter, brighter yellow. */
    val Accent = Color(0xFFD4AF37)
    val TextPrimary = Color(0xFFFFFFFF)

    /** Accent at the tint percentages used by the design (5/7/9/12/14/16/22/45%). */
    fun accent(alpha: Float): Color = Accent.copy(alpha = alpha)

    /** White at a given alpha — the neutral text/hairline ramp. */
    fun white(alpha: Float): Color = Color.White.copy(alpha = alpha)

    val TextSecondary = white(0.45f)
    val TextTertiary = white(0.40f)
    val TextMuted = white(0.42f)
    val Hairline = white(0.06f)

    /**
     * Aired rows render at 45% in the mock. RemoteViews has no view-level alpha we can rely on
     * across launchers, so the dim is folded into each colour instead.
     */
    const val DimmedRowAlpha = 0.45f

    fun dim(color: Color, dimmed: Boolean): Color =
        if (dimmed) color.copy(alpha = color.alpha * DimmedRowAlpha) else color

    fun provider(color: Color): ColorProvider = ColorProvider(color)

    // -- Radii -------------------------------------------------------------------------------
    val RadiusWidget = 26.dp
    val RadiusPanel = 8.dp
    val RadiusPoster = 5.dp
    val RadiusPill = 999.dp

    // -- Metrics -----------------------------------------------------------------------------
    val HeaderHeight = 22.dp
    val ListRowHeight = 46.dp
    val EpisodeRowHeight = 30.dp
    val PosterWidth = 26.dp
    val PosterHeight = 36.dp
    val RowPaddingHorizontal = 12.dp
    val EpisodeRowInset = 46.dp

    /** Visual size of the star glyph; the tap target around it is [TouchTarget]. */
    val StarIcon = 11.dp
    val StarIconSmall = 10.dp

    /**
     * Widget density is high enough that the mock's controls are as small as 9dp. On device every
     * interactive area is padded out to at least this, keeping the visuals at documented sizes.
     */
    val TouchTarget = 40.dp

    /**
     * The rewatch pill packs three controls into a 46dp row, so its -/+ buttons cannot each reach
     * 40dp without breaking the layout. They get the widest target the row affords.
     */
    val TouchTargetCompact = 20.dp

    // -- Type --------------------------------------------------------------------------------
    // Glance/RemoteViews exposes no letter-spacing, so the mock's 0.08-0.18em tracking on mono
    // text is not reproducible; sizes and weights are exact.
    fun mono(size: Float, color: Color, align: TextAlign? = null) = TextStyle(
        color = provider(color),
        fontSize = size.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = align,
    )

    fun mono5(color: Color, align: TextAlign? = null) = mono(5f, color, align)
    fun mono55(color: Color, align: TextAlign? = null) = mono(5.5f, color, align)
    fun mono6(color: Color, align: TextAlign? = null) = mono(6f, color, align)
    fun mono65(color: Color, align: TextAlign? = null) = mono(6.5f, color, align)
    fun mono7(color: Color, align: TextAlign? = null) = mono(7f, color, align)
    fun mono8(color: Color, align: TextAlign? = null) = mono(8f, color, align)

    /** Display face — the mock uses Caprasimo; Glance can only ask for the platform serif. */
    fun display(size: Float, color: Color = TextPrimary, align: TextAlign? = null) = TextStyle(
        color = provider(color),
        fontSize = size.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Serif,
        textAlign = align,
    )

    const val TitleSize = 14f
    const val ShowNameSize = 15f
}
