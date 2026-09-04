package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.example.tvwidget.MainActivity
import com.example.tvwidget.data.FavoriteEpisode
import com.example.tvwidget.data.PosterStore
import com.example.tvwidget.data.Release
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The TODAY tab: the next release as a hero, then everything after it as a stack of soft cards.
 *
 * Aired rows are dropped upstream (see `TvWidget.WidgetContent`) so the list always starts at
 * today's first release — Glance's `LazyColumn` has no scroll-to-index API, so the only way to
 * guarantee landing on today is for nothing to be rendered above it.
 *
 * The hero sits *outside* the `LazyColumn` rather than as its first item, for two reasons. A list
 * whose first row is a different shape is a list with an exception in it; a subject with a list
 * beneath it is a composition. And practically, the hero embeds a `RemoteViews` subtree for its live
 * countdown (see [LiveViews]), which has no business inside a collection adapter's item views.
 */
@Composable
fun TodayFeed(
    releases: List<Release>,
    favorites: List<FavoriteEpisode>,
    posters: Map<String, Bitmap>,
    accents: Map<String, Int>,
) {
    if (releases.isEmpty()) return

    val hero = releases.first()
    HeroRow(
        release = hero,
        favorited = favorites.any { it.showTitle == hero.showTitle && it.episodeCode == hero.episodeCode },
        posters = posters,
        accents = accents,
    )

    val rest = releases.drop(1)
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        items(rest.size) { index ->
            val release = rest[index]
            ReleaseRow(
                release = release,
                // +1 because the hero already occupies depth 0 — the fade has to continue from
                // where the hero left off, not restart under it.
                depth = index + 1,
                favorited = favorites.any {
                    it.showTitle == release.showTitle && it.episodeCode == release.episodeCode
                },
                posters = posters,
                accents = accents,
            )
        }
        // Trailing air so the last card can settle clear of the widget's edge.
        item { Spacer(GlanceModifier.fillMaxWidth().height(Dimens.listRowHeight())) }
    }
}

/**
 * The next release, given its own scale and a live countdown.
 *
 * The countdown is the reason this row exists in this form: a `Chronometer` embedded through
 * [AndroidRemoteViews] ticks in the launcher's own process, so the seconds to tonight's episode run
 * down on the home screen without this app being awake at all. It cross-fades with the air time and
 * network every few seconds, driven by a `ViewFlipper` for the same free-motion reason.
 */
@Composable
private fun HeroRow(
    release: Release,
    favorited: Boolean,
    posters: Map<String, Bitmap>,
    accents: Map<String, Int>,
) {
    val context = LocalContext.current
    val rowHeight = Dimens.heroRowHeight()

    RowSurface(
        today = release.isToday,
        depth = 0,
        edgeAccent = accents[PosterStore.keyFor(release.showTitle)],
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(
                title = release.showTitle,
                posters = posters,
                width = Dimens.heroPosterWidth(),
                height = Dimens.heroPosterHeight(),
            )
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${release.dayLabel} · ${release.status.label}",
                    style = Tokens.label(Dimens.metaSize(), Tokens.Accent),
                    maxLines = 1,
                )
                Text(
                    text = release.showTitle,
                    style = Tokens.display(Dimens.HeroTitleSize),
                    maxLines = 1,
                    modifier = GlanceModifier.clickable(
                        actionStartActivity<MainActivity>(
                            actionParametersOf(
                                ActionKeys.showTitle to release.showTitle,
                                ActionKeys.episodeCode to release.episodeCode,
                                ActionKeys.imdbId to (release.imdbId ?: ""),
                            )
                        )
                    ),
                )
                Text(
                    text = release.episodeCode,
                    style = Tokens.numeric(Dimens.metaSize(), Tokens.TextTertiary),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            AndroidRemoteViews(
                remoteViews = LiveViews.hero(
                    context = context,
                    release = release,
                    primarySizeSp = Dimens.heroCountdownSize(),
                    secondarySizeSp = Dimens.heroSecondarySize(),
                ),
            )
            StarToggle(
                favorited = favorited,
                targetHeight = rowHeight,
                onClick = actionRunCallback<ToggleFavoriteAction>(
                    actionParametersOf(
                        ActionKeys.showTitle to release.showTitle,
                        ActionKeys.episodeCode to release.episodeCode,
                        ActionKeys.episodeLabel to release.favoriteLabel(),
                    )
                ),
            )
        }
    }
}

/** One release card: poster, meta line, title, the numeric column, and the star. */
@Composable
private fun ReleaseRow(
    release: Release,
    depth: Int,
    favorited: Boolean,
    posters: Map<String, Bitmap>,
    accents: Map<String, Int>,
) {
    val dimmed = release.hasAired
    val metaColor = Tokens.dim(
        if (release.isToday) Tokens.Accent else Tokens.TextSecondary,
        dimmed,
    )
    val statusColor = Tokens.dim(
        if (release.isToday) Tokens.Accent else Tokens.TextTertiary,
        dimmed,
    )
    val rowHeight = Dimens.listRowHeight()

    RowSurface(
        today = release.isToday,
        depth = depth,
        edgeAccent = accents[PosterStore.keyFor(release.showTitle)],
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(title = release.showTitle, posters = posters, dimmed = dimmed)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${release.dayLabel} · ${release.airTime} · ${release.network}",
                    style = Tokens.label(Dimens.metaSize(), metaColor),
                    maxLines = 1,
                )
                Text(
                    text = release.showTitle,
                    style = Tokens.display(
                        Dimens.TitleSize,
                        Tokens.dim(Tokens.TextPrimary, dimmed),
                    ),
                    maxLines = 1,
                    // Only the title opens IMDb (via MainActivity's pass-through — Glance can't
                    // launch an arbitrary Intent) so a poster or meta tap can't be mistaken for it.
                    modifier = GlanceModifier.clickable(
                        actionStartActivity<MainActivity>(
                            actionParametersOf(
                                ActionKeys.showTitle to release.showTitle,
                                ActionKeys.episodeCode to release.episodeCode,
                                ActionKeys.imdbId to (release.imdbId ?: ""),
                            )
                        )
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // A future episode leads with its countdown instead of its episode number —
                    // days-away is the more useful glance value while it's still ahead; today's and
                    // aired rows keep the code.
                    text = if (release.dayOffset > 0) release.countdownLabel else release.episodeCode,
                    style = Tokens.numeric(
                        Dimens.accentLabelSize(),
                        Tokens.dim(Tokens.TextPrimary, dimmed),
                        TextAlign.End,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = release.status.label,
                    style = Tokens.label(Dimens.statusSize(), statusColor, TextAlign.End),
                    maxLines = 1,
                )
            }
            StarToggle(
                favorited = favorited,
                dimmed = dimmed,
                targetHeight = rowHeight,
                onClick = actionRunCallback<ToggleFavoriteAction>(
                    actionParametersOf(
                        ActionKeys.showTitle to release.showTitle,
                        ActionKeys.episodeCode to release.episodeCode,
                        ActionKeys.episodeLabel to release.favoriteLabel(),
                    )
                ),
            )
        }
    }
}
