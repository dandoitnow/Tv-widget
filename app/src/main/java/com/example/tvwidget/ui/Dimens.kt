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

    enum class Tier { COMPACT, ROOMY }

    @Composable
    fun tier(): Tier {
        val height = LocalSize.current.height
        return if (height > 170.dp) Tier.ROOMY else Tier.COMPACT
    }

    @Composable
    fun listRowHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 44.dp
        Tier.ROOMY -> 84.dp
    }

    /** Air between row surfaces — what replaced the old hairline rules. */
    @Composable
    fun rowGap(): Dp = when (tier()) {
        Tier.COMPACT -> 4.dp
        Tier.ROOMY -> 7.dp
    }

    @Composable
    fun posterWidth(): Dp = when (tier()) {
        Tier.COMPACT -> 28.dp
        Tier.ROOMY -> 54.dp
    }

    @Composable
    fun posterHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 39.dp
        Tier.ROOMY -> 75.dp
    }

    @Composable
    fun tabPillHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 26.dp
        Tier.ROOMY -> 44.dp
    }

    /** Tab labels. */
    @Composable
    fun tabLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 11f
        Tier.ROOMY -> 15f
    }

    /**
     * Render height for the drawn tab labels. They are bitmaps rather than text (see `Header`), so
     * they need an explicit box; a hair over the type size leaves room for descenders without
     * letting the image scale the glyphs up.
     */
    @Composable
    fun tabLabelHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 14.dp
        Tier.ROOMY -> 19.dp
    }

    /** The seven-day rhythm strip under the header. Roomy sizes only — it needs the vertical room. */
    @Composable
    fun showWeekStrip(): Boolean = tier() == Tier.ROOMY

    val WeekStripHeight: Dp = 10.dp

    /** The season-progress bar in a row's numeric column. */
    val SeasonBarWidth: Dp = 34.dp
    val SeasonBarHeight: Dp = 3.dp

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
    }

    /** Episode codes and countdowns — the numeric column. */
    @Composable
    fun accentLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 12f
        Tier.ROOMY -> 17f
    }

    /** Status text under the numeric column: AIRS TONIGHT, SCHEDULED. */
    @Composable
    fun statusSize(): Float = when (tier()) {
        Tier.COMPACT -> 8f
        Tier.ROOMY -> 11f
    }

    /** The smallest text: premiere dates beside the hype bar. */
    @Composable
    fun smallLabelSize(): Float = when (tier()) {
        Tier.COMPACT -> 7.5f
        Tier.ROOMY -> 10f
    }

    @Composable
    fun starIconSize(): Dp = when (tier()) {
        Tier.COMPACT -> 14.dp
        Tier.ROOMY -> 20.dp
    }

    /** The TODAY pill's live dot. */
    @Composable
    fun tabGlyphSize(): Dp = when (tier()) {
        Tier.COMPACT -> 5.dp
        Tier.ROOMY -> 8.dp
    }

    // -- The hero row --------------------------------------------------------------------------
    // The next release is not just the first item in a list, so it is not sized like one. Giving it
    // its own scale is what turns a uniform list into a composition with a subject — and a uniform
    // list is the thing that always ends up reading as a table no matter how it is styled.

    @Composable
    fun heroRowHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 62.dp
        Tier.ROOMY -> 100.dp
    }

    @Composable
    fun heroPosterWidth(): Dp = when (tier()) {
        Tier.COMPACT -> 38.dp
        Tier.ROOMY -> 62.dp
    }

    @Composable
    fun heroPosterHeight(): Dp = when (tier()) {
        Tier.COMPACT -> 53.dp
        Tier.ROOMY -> 86.dp
    }

    /** The live countdown itself — the largest thing in the row after the title. */
    @Composable
    fun heroCountdownSize(): Float = when (tier()) {
        Tier.COMPACT -> 15f
        Tier.ROOMY -> 20f
    }

    /** The line the countdown cross-fades with: air time and network. */
    @Composable
    fun heroSecondarySize(): Float = when (tier()) {
        Tier.COMPACT -> 10f
        Tier.ROOMY -> 13f
    }

    /**
     * A hard width for the countdown block.
     *
     * The embedded `RemoteViews` subtree does not participate in the row's weight distribution the
     * way a Glance composable does, so left unconstrained it took as much width as it liked and
     * collapsed the title column to nothing. Sized generously enough for `23:59:59` at each tier.
     */
    @Composable
    fun heroCountdownWidth(): Dp = when (tier()) {
        Tier.COMPACT -> 76.dp
        Tier.ROOMY -> 100.dp
    }

    /**
     * The hero's title. Fixed for the same reason [TitleSize] is — it shares a row with a poster and
     * a countdown column that do not shrink to make room — but a little larger, because being
     * larger is most of how the hero says it is the hero.
     */
    const val HeroTitleSize = 22f

    // -- The spine -----------------------------------------------------------------------------

    /**
     * Whether the left gutter gets its hairline of light. Skipped when compact: the spine plus its
     * gutter costs about 10dp of width, and at the smallest size that is width the titles need more
     * than the decoration does.
     */
    @Composable
    fun showSpine(): Boolean = tier() != Tier.COMPACT

    val SpineWidth: Dp = 2.dp

    /** Air between the spine and the cards. The spine is architecture; it should not touch content. */
    val SpineGutter: Dp = 9.dp

    // -- The COMING UP ticker ------------------------------------------------------------------

    /**
     * Only the larger tier gets the ticker. On a compact widget every dp belongs to the list, and a
     * strip that cost a whole visible row would be trading information for decoration — the wrong
     * trade even when the decoration moves.
     */
    @Composable
    fun showTicker(): Boolean = tier() == Tier.ROOMY

    /** How many rows the list shows before the rest are handed to the ticker. */
    const val TickerStartsAfter = 4

    // -- The RemoteViews budget ------------------------------------------------------------------

    /**
     * How many rows a tab starts with, and how many each SHOW MORE reveals.
     *
     * Every row costs twice: once at compose time and again in the parcel, and both land between a
     * tap and the screen changing, on a process the platform has usually just unfrozen. Rendering
     * twenty rows up front made that delay noticeable for everyone, in exchange for rows almost
     * nobody scrolls to. A page of ten keeps the common interaction quick and lets the people who
     * do scroll pay for the rest, when they ask for it.
     */
    const val RowPage = 10

    /**
     * The ceiling SHOW MORE stops at, whatever the data holds.
     *
     * This is a hard platform constraint wearing the clothes of a design decision. A widget's
     * RemoteViews crosses a Binder transaction with a size limit, and Glance's `LazyColumn` parcels
     * *every* item rather than only the ones on screen — so cost scales with the length of the list,
     * not with what fits. Rendering the full POPULAR feed overran it and killed the launcher's
     * widget host outright (`TransactionTooLargeException: data parcel size 779984 bytes`), which
     * took the whole widget down rather than just truncating it.
     *
     * The full list is still fetched and still lives in the Catalog screen, which is an ordinary
     * Activity and has no such ceiling.
     */
    const val MaxWidgetRows = 20

    /**
     * Target poster width in pixels for widget rows.
     *
     * Small on purpose, and smaller than it was: posters now carry an alpha channel for their
     * squircle corners, which doubles their bytes per pixel, and every one of them is parcelled once
     * per row. Trading a little sharpness for a shape that reads correctly is the right way round.
     */
    const val WidgetPosterWidthPx = 64
}
