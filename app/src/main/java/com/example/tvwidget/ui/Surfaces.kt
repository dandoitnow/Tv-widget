package com.example.tvwidget.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalTime
import java.util.Collections
import android.graphics.Color as AndroidColor

/**
 * Surfaces the widget paints at runtime instead of loading from a drawable.
 *
 * Everything here exists because a `<shape>` resource is a *constant*, and these surfaces all need
 * to vary with something only known at draw time: the hour of day, a row's depth in the list, or the
 * dominant colour of a poster. RemoteViews has no runtime drawable API and no shader access, so the
 * only way to vary a fill is to hand it a [Bitmap] — which `ImageProvider(Bitmap)` accepts, and
 * which the widget can then stretch behind content like any other background.
 *
 * These are aggressively memoized, because a Glance composable body re-runs on every interaction and
 * generating a fresh gradient per recomposition is the kind of waste that shows up as a stutter.
 *
 * They are also deliberately *tiny* — a few pixels in the direction the gradient does not run — and
 * that is a hard requirement, not tidiness. Every bitmap handed to `ImageProvider` is parcelled into
 * the widget's RemoteViews and crosses a Binder transaction with a hard size limit. The first
 * version of this file drew rows at 128x64 ARGB_8888, 32KB each, one per row; combined with a longer
 * POPULAR list that overran the limit and took the launcher's widget host down with it
 * (`TransactionTooLargeException: data parcel size 779984 bytes`). Stretched by
 * `ContentScale.FillBounds`, a linear gradient looks identical at 1/30th the pixels.
 */
object Surfaces {

    private val cache = Collections.synchronizedMap(HashMap<String, Bitmap>())

    private fun cached(key: String, build: () -> Bitmap): Bitmap {
        cache[key]?.let { return it }
        // Not atomic, deliberately: two threads racing here both build a correct bitmap and one
        // wins. A lock held across bitmap generation would be the more expensive mistake.
        return build().also { cache[key] = it }
    }

    private fun verticalGradient(width: Int, height: Int, colors: IntArray, stops: FloatArray?): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), colors, stops, Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    // -- The ground ------------------------------------------------------------------------------

    /**
     * Time-of-day warmth: the ground drifts a few points warmer through the evening and settles
     * cooler overnight, tracking roughly how room light actually changes.
     *
     * The shift is deliberately small — three or four points per channel. The goal is that the
     * widget feels alive rather than printed, not that anyone can point at the colour and name it. A
     * shift big enough to notice directly would just read as an inconsistent palette.
     *
     * Costs nothing: computed from the clock at draw time, keyed per hour. The widget already
     * redraws far more often than hourly for other reasons, so nothing is scheduled for this.
     */
    fun ground(hour: Int = LocalTime.now().hour): Bitmap = cached("ground:$hour") {
        val warmth = warmthAt(hour)
        verticalGradient(
            width = 8,
            height = 64,
            colors = intArrayOf(
                warmed(0xFF14110E.toInt(), warmth),
                warmed(0xFF0E0C0A.toInt(), warmth),
                warmed(0xFF0A0908.toInt(), warmth),
            ),
            stops = floatArrayOf(0f, 0.55f, 1f),
        )
    }

    /**
     * -1 (coolest, small hours) to +1 (warmest, mid-evening). One smooth cycle peaking around 21:00
     * and bottoming around 05:00, so there is never a visible step between two adjacent hours.
     */
    private fun warmthAt(hour: Int): Float {
        val phase = ((hour - 5 + 24) % 24) / 24.0
        return -kotlin.math.cos(phase * 2 * Math.PI).toFloat()
    }

    /** Pushes red up and blue down by a few points, keeping perceived lightness roughly fixed. */
    private fun warmed(color: Int, warmth: Float): Int {
        val amount = (warmth * 4f).toInt()
        return AndroidColor.argb(
            AndroidColor.alpha(color),
            (AndroidColor.red(color) + amount).coerceIn(0, 255),
            (AndroidColor.green(color) + amount / 2).coerceIn(0, 255),
            (AndroidColor.blue(color) - amount).coerceIn(0, 255),
        )
    }

    // -- Row surfaces ----------------------------------------------------------------------------

    /**
     * A list row's fill, at a given [depth] down the list.
     *
     * Rows fade as they descend, so the list appears to fall away into the widget rather than
     * sitting flat on it. This effect does double duty: it reads as depth, and it *is* hierarchy —
     * the next release ends up the most solid thing on screen without needing a highlight, a border,
     * or any other explicit marker to say so.
     *
     * Only the surface fades. Posters stay at full strength, because the artwork is the reward and
     * dimming it would fight the edge light drawn from that same artwork.
     *
     * [accent] is that edge light: the dominant colour of the row's own poster, washed in from the
     * leading edge, so every row is lit by its own artwork and a shelf of shows reads as a shelf of
     * *different* shows rather than as repeated furniture.
     *
     * It is composited into this one bitmap rather than drawn as an overlay view, which was the
     * first attempt and was a mistake twice over. A `fillMaxSize` child inside a wrap-content
     * `FrameLayout` resolves against the parent's *available* height, which dragged the row's
     * background out to fill the entire widget; and any view stacked above the row's content would
     * have quietly eaten every tap in it. Baked in, the wash has no layout or touch consequences at
     * all.
     *
     * The wash is kept low and gone by about a third of the way across, so it reads as light falling
     * on a surface. The moment it reads as a coloured panel it stops looking like lighting and
     * starts looking like a mistake.
     */
    fun row(today: Boolean, depth: Int, accent: Int? = null): Bitmap {
        val fade = fadeAt(depth)
        return cached("row:$today:${(fade * 100).toInt()}:$accent") {
            val colors = if (today) {
                intArrayOf(0xFF2A2115.toInt(), 0xFF201A14.toInt(), 0xFF171310.toInt())
            } else {
                intArrayOf(0xFF221D18.toInt(), 0xFF1D1915.toInt(), 0xFF191512.toInt())
            }
            val w = 64f
            val h = 4f
            val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // TODAY's row is lit from the leading edge (horizontal); the rest from above.
                shader = if (today) {
                    LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.CLAMP)
                }
                alpha = (fade * 255).toInt()
            }
            canvas.drawRect(0f, 0f, w, h, base)

            if (accent != null) {
                val lit = AndroidColor.argb(
                    (72 * fade).toInt(),
                    AndroidColor.red(accent),
                    AndroidColor.green(accent),
                    AndroidColor.blue(accent),
                )
                val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, w, 0f,
                        intArrayOf(lit, lit and 0x00FFFFFF),
                        floatArrayOf(0f, 0.34f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, w, h, wash)
            }
            bitmap
        }
    }

    /**
     * How solid a row at [depth] is. The first two rows are full strength — the top of the list is
     * the part actually being read — and everything below steps back, bottoming out rather than
     * fading to nothing so a long list never trails off into unreadability.
     */
    fun fadeAt(depth: Int): Float = when {
        depth <= 1 -> 1f
        else -> (1f - (depth - 1) * 0.11f).coerceAtLeast(0.55f)
    }

    // -- Edge light ------------------------------------------------------------------------------

    // -- The spine -------------------------------------------------------------------------------

    /**
     * A hairline of light down the widget's left gutter, fading out at both ends.
     *
     * It belongs to the widget rather than to any row: the cards and their edge lights change with
     * the content, and the spine stays put, which gives the list something to hang off. Fading at
     * both ends is the whole trick — a rule that stops abruptly reads as a border, and a border is
     * precisely the hard edge this design is trying not to have.
     */
    fun spine(): Bitmap = cached("spine") {
        verticalGradient(
            width = 2,
            height = 64,
            colors = intArrayOf(0x00D8B45F, 0x33D8B45F, 0x1AD8B45F, 0x00D8B45F),
            stops = floatArrayOf(0f, 0.18f, 0.72f, 1f),
        )
    }

    // -- Poster finishing ------------------------------------------------------------------------

    /**
     * Gives a poster a physical edge: a light inner stroke and a weighted bottom vignette, baked in
     * once at cache time.
     *
     * Without these a poster is a flat rectangle of colour pasted onto the row. The stroke gives it
     * a lit top edge, the vignette gives it weight at the bottom, and together they make it read as
     * an object sitting on the surface — the same implied-light trick the rest of the design uses,
     * applied to artwork this app does not control.
     *
     * The stroke follows a rounded rect rather than the bitmap's square edge, because the widget
     * clips the poster to [Tokens.RadiusPoster] when it draws it; a square stroke inside a rounded
     * clip shows up as four cut corners.
     */
    fun finishPoster(source: Bitmap, cornerRadiusPx: Float): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        val vignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.55f, 0f, h,
                intArrayOf(0x00000000, 0x59000000),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, h * 0.55f, w, h, vignette)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0x2EFFFFFF, 0x0AFFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), cornerRadiusPx, cornerRadiusPx, stroke)
        return out
    }
}
