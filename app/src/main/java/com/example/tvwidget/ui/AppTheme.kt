package com.example.tvwidget.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView

/**
 * The app screen's design system — the native-views counterpart to [Tokens].
 *
 * The widget and the app share one visual language (warm near-black ground, metallic gold, serif
 * titles, soft-cornered surfaces, air instead of rules), but the app is where the parts of that
 * language RemoteViews physically can't express actually live: real letter-spacing, gradient-filled
 * text, ripples, and motion. Rather than flatten the app down to the widget's ceiling, it carries
 * the same design *further* — the widget reads as the quiet home-screen face of the same product.
 */
object AppTheme {

    // -- Palette (mirrors Tokens, as Android colour ints) --------------------------------------
    const val Background = 0xFF0E0C0A.toInt()
    const val BackgroundLift = 0xFF14110E.toInt()
    const val Surface = 0xFF1A1714.toInt()
    const val SurfaceRaised = 0xFF221E1A.toInt()

    const val Accent = 0xFFD8B45F.toInt()
    const val AccentHighlight = 0xFFF0DCA0.toInt()
    const val AccentDeep = 0xFFA98328.toInt()

    const val TextPrimary = 0xFFF3EDE3.toInt()
    const val TextSecondary = 0xFF9A9187.toInt()
    const val TextMuted = 0xFF6E675F.toInt()

    /** Warm neutral at a given alpha, for strokes and washes. */
    fun neutral(alpha: Float): Int = Color.argb((alpha * 255).toInt(), 0xED, 0xE6, 0xDA)

    fun accent(alpha: Float): Int = Color.argb((alpha * 255).toInt(), 0xD8, 0xB4, 0x5F)

    // -- Metrics -------------------------------------------------------------------------------
    val Int.dp: Int get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    val Float.dp: Float get() = this * android.content.res.Resources.getSystem().displayMetrics.density

    // -- Surfaces ------------------------------------------------------------------------------

    /** A flat rounded surface, optionally outlined. */
    fun surface(
        color: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 1.dp,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

    /**
     * A two-stop vertical surface. Every raised surface in the app uses one of these rather than a
     * flat fill: a few points of luminance top-to-bottom is what makes a dark card read as lit
     * rather than as a hole cut in the background.
     */
    fun liftedSurface(top: Int, bottom: Int, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

    /** Wraps a surface in a ripple, so every tappable thing answers the finger. */
    fun tappable(content: GradientDrawable, rippleColor: Int = accent(0.16f)): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    /**
     * Clips a view (an ImageView holding poster art, say) to a rounded rect. Cheaper and sharper
     * than compositing a rounded bitmap, and it keeps the corner radius in one place.
     */
    fun View.clipToRoundedRect(radius: Float) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    // -- Typography ----------------------------------------------------------------------------

    /**
     * Display type: serif, for titles. Serif is what keeps a list of shows reading editorial rather
     * than administrative — the same call the widget makes, carried through here.
     */
    fun TextView.display(sizeSp: Float, color: Int = TextPrimary) {
        typeface = android.graphics.Typeface.SERIF
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
    }

    /**
     * Label type: sans, with real tracking. Letter-spacing is one of the things the widget simply
     * cannot do (RemoteViews exposes none), and it's most of what separates a considered small-caps
     * label from a small line of text.
     */
    fun TextView.label(
        sizeSp: Float,
        color: Int = TextSecondary,
        tracking: Float = 0.06f,
        bold: Boolean = false,
    ) {
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        letterSpacing = tracking
    }

    /**
     * Fills text with a vertical gold ramp — highlight, base, shadow — so it reads as struck metal
     * rather than yellow paint. Flat gold is exactly what makes gold look cheap; a real metal has a
     * light edge and a dark one. Applied post-layout because the shader needs the view's height.
     */
    fun TextView.goldLeaf() {
        post {
            if (height == 0) return@post
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(AccentHighlight, Accent, AccentDeep),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            invalidate()
        }
    }

    // -- Motion --------------------------------------------------------------------------------

    /**
     * The app's one content transition: content lifts a few dp as it fades in. Short (220ms) and
     * small (10dp) on purpose — motion at this scale should be felt rather than watched, and
     * anything longer turns a tab switch into a wait.
     */
    fun View.enterSoftly(delayMs: Long = 0L) {
        alpha = 0f
        translationY = 10.dp.toFloat()
        animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(220L)
            .start()
    }
}
