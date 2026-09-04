package com.example.tvwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import com.example.tvwidget.R
import com.example.tvwidget.data.Release

/**
 * The two pieces of the widget that move.
 *
 * Glance has no animation API, and that is usually where the conversation about widget motion ends.
 * But `AndroidRemoteViews` lets a Glance composition embed an arbitrary [RemoteViews] subtree, and
 * RemoteViews permits two views that animate *themselves*, in the launcher's process, off their own
 * handlers: `Chronometer` and `ViewFlipper`.
 *
 * That distinction is the whole point. Motion driven by the app — pushing widget updates frame by
 * frame — would drain the battery, jank badly, and still look worse than this. Motion driven by the
 * host costs nothing and keeps running whether or not this app is alive.
 *
 * Nothing here is styled in Kotlin that could be styled in XML; the layouts carry the typography and
 * colour, and these functions only supply content and the size the current tier calls for.
 */
object LiveViews {

    /** How many releases the COMING UP ticker will cycle through before repeating. */
    private const val MAX_TICKER_ITEMS = 6

    /**
     * Whether the user has asked the system to stop animating.
     *
     * Someone who has set the animation scale to zero — for motion sensitivity, for battery, or just
     * by preference — has said something unambiguous, and a widget that keeps cross-fading regardless
     * is overriding a system-wide accessibility setting because it thinks its own animation is
     * special. It isn't.
     */
    private fun motionReduced(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    /** One frame of the COMING UP ticker. */
    data class Upcoming(val title: String, val meta: String, val poster: Bitmap?)

    /**
     * The hero row's right-hand column: a live ticking countdown when the next release is within a
     * day, cross-fading every six seconds with its air time and network.
     *
     * The `Chronometer` is anchored to `elapsedRealtime` rather than wall-clock, because that is the
     * clock it actually counts against — converting through the wall clock once here is what keeps
     * the countdown correct if the user's clock is adjusted while the widget sits on screen.
     */
    fun hero(
        context: Context,
        release: Release,
        primarySizeSp: Float,
        secondarySizeSp: Float,
    ): RemoteViews {
        val live = release.isLive()
        val still = motionReduced(context)
        val views = RemoteViews(
            context.packageName,
            when {
                live && still -> R.layout.widget_hero_live_still
                live -> R.layout.widget_hero_live
                still -> R.layout.widget_hero_static_still
                else -> R.layout.widget_hero_static
            },
        )

        if (live) {
            val remaining = release.airEpochMillis!! - System.currentTimeMillis()
            views.setChronometer(R.id.hero_primary, SystemClock.elapsedRealtime() + remaining, null, true)
            views.setChronometerCountDown(R.id.hero_primary, true)
        } else {
            views.setTextViewText(
                R.id.hero_primary,
                if (release.dayOffset > 0) release.countdownLabel() else release.episodeCode,
            )
        }
        views.setTextViewTextSize(R.id.hero_primary, TypedValue.COMPLEX_UNIT_SP, primarySizeSp)

        views.setTextViewText(R.id.hero_secondary, heroSecondary(release))
        views.setTextViewTextSize(R.id.hero_secondary, TypedValue.COMPLEX_UNIT_SP, secondarySizeSp)
        return views
    }

    /** Air time and network, skipping either half when TVMaze has no value for it. */
    private fun heroSecondary(release: Release): String =
        listOf(release.airTime, release.network)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /**
     * The COMING UP ticker, or null when there is nothing beyond the list worth cycling.
     *
     * Children are added rather than declared, because the count varies and a `ViewFlipper` happily
     * flips to a hidden child — a fixed set with the spares set to GONE would leave the strip blank
     * for five seconds at a stretch.
     */
    fun comingUp(context: Context, items: List<Upcoming>): RemoteViews? {
        if (items.isEmpty()) return null
        val root = RemoteViews(
            context.packageName,
            if (motionReduced(context)) R.layout.widget_comingup_still else R.layout.widget_comingup,
        )
        root.removeAllViews(R.id.comingup_flipper)
        items.take(MAX_TICKER_ITEMS).forEach { item ->
            val child = RemoteViews(context.packageName, R.layout.widget_comingup_item)
            child.setTextViewText(R.id.comingup_title, item.title)
            child.setTextViewText(R.id.comingup_meta, item.meta)
            if (item.poster != null) {
                child.setImageViewBitmap(R.id.comingup_poster, item.poster)
            } else {
                child.setImageViewResource(R.id.comingup_poster, R.drawable.poster_placeholder)
            }
            root.addView(R.id.comingup_flipper, child)
        }
        return root
    }
}
