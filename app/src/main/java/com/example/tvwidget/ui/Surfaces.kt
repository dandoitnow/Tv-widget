package com.example.tvwidget.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RadialGradient
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
                // A darker band right at the top edge reads as an inner shadow, so the widget sits
                // *in* the home screen rather than on top of it. Two or three points is enough;
                // any more and it stops being light and starts being a stripe.
                warmed(0xFF090707.toInt(), warmth),
                warmed(0xFF14110E.toInt(), warmth),
                warmed(0xFF0E0C0A.toInt(), warmth),
                warmed(0xFF0A0908.toInt(), warmth),
            ),
            stops = floatArrayOf(0f, 0.05f, 0.55f, 1f),
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
    fun row(today: Boolean, depth: Int, accent: Int? = null, hero: Boolean = false): Bitmap {
        val fade = fadeAt(depth)
        return cached("row:$today:${(fade * 100).toInt()}:$accent:$hero") {
            val colors = if (today) {
                intArrayOf(0xFF2A2115.toInt(), 0xFF201A14.toInt(), 0xFF171310.toInt())
            } else {
                intArrayOf(0xFF221D18.toInt(), 0xFF1D1915.toInt(), 0xFF191512.toInt())
            }
            // The hero gets vertical resolution the other rows do not need, because it carries a
            // radial spotlight; a 4px-tall bitmap has nowhere to put one.
            val w = 64f
            val h = if (hero) 32f else 4f
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

            if (hero) {
                // A soft pool of light where the poster sits, so the subject looks lit rather than
                // placed. Stretching an ellipse out of it horizontally is the intent, not a
                // side effect — a circular highlight in a wide row reads as a blemish.
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        w * 0.16f, h * 0.5f, w * 0.42f,
                        intArrayOf(0x1FFFF3D6, 0x00FFF3D6),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, w, h, glow)
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
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shape = squircle(w, h, cornerRadiusPx)

        // Drawn as a shader-filled path rather than clipped, because clipPath is not antialiased and
        // the whole point of this shape is the quality of its edge.
        canvas.drawPath(
            shape,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            },
        )

        canvas.save()
        canvas.clipPath(shape)
        canvas.drawRect(
            0f, h * 0.55f, w, h,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, h * 0.55f, 0f, h,
                    intArrayOf(0x00000000, 0x59000000), null, Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.restore()

        canvas.drawPath(
            squircle(w - 2f, h - 2f, cornerRadiusPx).also { it.offset(1f, 1f) },
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(0x2EFFFFFF, 0x0AFFFFFF), null, Shader.TileMode.CLAMP,
                )
            },
        )
        return out
    }

    /**
     * A rounded rectangle with *continuous* corner curvature — a squircle — rather than the circular
     * arc `cornerRadius` gives you.
     *
     * A circular corner meets the straight edge at a discontinuity in curvature: the radius jumps
     * from infinite to r in no distance at all, and the eye reads that as a faint kink. A continuous
     * corner starts turning earlier and eases into the arc, so the transition disappears. It is the
     * difference between an Android rounded rect and an iOS one, and it is most of why the latter
     * looks softer at identical radii.
     *
     * Approximated here by starting the curve about 1.5x the radius from the corner and easing in
     * with cubics. A true superellipse is a nicer piece of mathematics and an indistinguishable
     * result at the sizes this draws.
     */
    fun squircle(w: Float, h: Float, radius: Float): Path {
        val r = radius.coerceAtMost(kotlin.math.min(w, h) / 2f)
        val d = (r * 1.5f).coerceAtMost(kotlin.math.min(w, h) / 2f)
        val c = r * 0.55f
        return Path().apply {
            moveTo(d, 0f)
            lineTo(w - d, 0f)
            cubicTo(w - d + c, 0f, w, d - c, w, d)
            lineTo(w, h - d)
            cubicTo(w, h - d + c, w - d + c, h, w - d, h)
            lineTo(d, h)
            cubicTo(d - c, h, 0f, h - d + c, 0f, h - d)
            lineTo(0f, d)
            cubicTo(0f, d - c, d - c, 0f, d, 0f)
            close()
        }
    }

    // -- The hero's poster stack -----------------------------------------------------------------

    /**
     * The hero poster with the next couple of releases fanned behind it.
     *
     * Two things at once: depth, and the fact that there *is* a queue. A single poster says what is
     * next; a stack says what is next and that more is coming, which is the whole proposition of a
     * release tracker. Apple's Up Next carousel makes the same move for the same reason.
     *
     * Composited into one bitmap rather than laid out as three overlapping views, and that is not an
     * implementation detail. Every bitmap in a widget is parcelled separately across a Binder
     * transaction with a hard size limit, so three views would cost three posters; one composite
     * costs one, and overlapping views in a `FrameLayout` would also eat the taps underneath them.
     */
    fun heroStack(main: Bitmap, behind: List<Bitmap>, cacheKey: String): Bitmap = cached("stack:$cacheKey") {
        val mw = main.width
        val mh = main.height
        val out = Bitmap.createBitmap((mw * 1.34f).toInt(), mh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Farthest first: smaller, further right, and dimmer, so the stack recedes properly.
        behind.take(2).reversed().forEachIndexed { reverseIndex, poster ->
            val depth = if (behind.size > 1 && reverseIndex == 0) 2 else 1
            val scale = 1f - 0.10f * depth
            val cardW = mw * scale
            val cardH = mh * scale
            val left = mw * (1f + 0.10f * depth) - cardW * 0.72f
            val top = (mh - cardH) / 2f
            val dst = RectF(left, top, left + cardW, top + cardH)
            canvas.drawBitmap(poster, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            // Pushed back with a scrim rather than alpha, so it darkens into the ground instead of
            // going translucent and letting the surface pattern show through the artwork.
            canvas.drawPath(
                squircle(cardW, cardH, cardW * 0.12f).also { it.offset(dst.left, dst.top) },
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = AndroidColor.argb(if (depth == 2) 168 else 120, 10, 9, 8)
                },
            )
        }
        canvas.drawBitmap(main, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        out
    }

    // -- Typography as pixels --------------------------------------------------------------------

    /**
     * A short label rendered to a bitmap so it can have things RemoteViews will not give text:
     * real letter-spacing, and a gold ramp instead of a flat fill.
     *
     * Tracking is most of what separates a considered small-caps label from a small line of text,
     * and flat gold is exactly what makes gold look like paint rather than metal. Neither is
     * expressible in a Glance `TextStyle`, so the header's labels are drawn instead of typeset.
     *
     * Strictly limited to the header. Text baked into an image cannot respond to the system font
     * scale, which is a real accessibility cost — worth paying for three fixed words of chrome, not
     * for anything a person actually needs to read.
     */
    fun label(text: String, sizeSp: Float, tracking: Float, gold: Boolean, flatColor: Int): Bitmap =
        cached("label:$text:$sizeSp:$tracking:$gold:$flatColor") {
            val density = android.content.res.Resources.getSystem().displayMetrics.density
            val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sizeSp * density
                letterSpacing = tracking
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    android.graphics.Typeface.BOLD,
                )
                color = flatColor
            }
            val metrics = paint.fontMetrics
            val width = kotlin.math.ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
            val height = kotlin.math.ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (gold) {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0xFFF0DCA0.toInt(), 0xFFD8B45F.toInt(), 0xFFA98328.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            Canvas(out).drawText(text, 0f, -metrics.ascent, paint)
            out
        }

    // -- Small indicators ------------------------------------------------------------------------

    /**
     * Seven dots, lit on the days that have a release. Activity-ring logic applied to a schedule:
     * it answers "how busy is my week" in one glance, which no amount of scrolling a list does.
     */
    fun weekStrip(days: BooleanArray, today: Int): Bitmap =
        cached("week:${days.joinToString("") { if (it) "1" else "0" }}:$today") {
            val dot = 7f
            val gap = 9f
            val count = 7
            val width = (count * dot + (count - 1) * gap).toInt()
            val out = Bitmap.createBitmap(width, dot.toInt() + 6, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until count) {
                val cx = i * (dot + gap) + dot / 2f
                val cy = 3f + dot / 2f
                paint.color = when {
                    days.getOrElse(i) { false } -> 0xFFD8B45F.toInt()
                    else -> 0x1FEDE6DA
                }
                canvas.drawCircle(cx, cy, dot / 2f, paint)
                // Today gets a ring rather than a brighter fill, so "which day is it" and "does this
                // day have a release" stay two separate readings instead of one ambiguous one.
                if (i == today) {
                    paint.color = 0x8AEDE6DA.toInt()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f
                    canvas.drawCircle(cx, cy, dot / 2f + 2f, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            out
        }

    /**
     * How far through its season an episode sits. Quantised to twentieths, because the bar is a few
     * dp long and a cache entry per exact fraction would be a cache entry per row for no visible
     * difference.
     */
    fun seasonBar(fraction: Float): Bitmap {
        val step = (fraction.coerceIn(0f, 1f) * 20).toInt()
        return cached("season:$step") {
            val width = 80
            val height = 3
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x1FEDE6DA
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 1.5f, 1.5f, paint)
            val filled = width * (step / 20f)
            if (filled > 0f) {
                paint.color = 0xB3D8B45F.toInt()
                canvas.drawRoundRect(RectF(0f, 0f, filled, height.toFloat()), 1.5f, 1.5f, paint)
            }
            out
        }
    }
}
