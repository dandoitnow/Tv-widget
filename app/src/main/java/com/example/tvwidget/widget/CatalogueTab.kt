package com.example.tvwidget.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import com.example.tvwidget.data.CatalogueShow
import com.example.tvwidget.data.CatalogueSubTab
import com.example.tvwidget.data.FavoriteShow
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The CATALOGUE tab: a search entry point plus two sub-tabs.
 *
 * FAVORITES is the user's saved episodes (this is where the old top-level FAVORITES tab moved to —
 * it needed a home once the header shrank to three side-by-side tabs). RECOMMENDED is a browseable
 * "trending" list sourced from TVMaze with one-tap track/untrack, feeding tracked shows into TODAY.
 *
 * Free-text search itself can't live here — `RemoteViews` has no `EditText` — so the search row
 * deep-links into `MainActivity`'s fullscreen search screen instead.
 */
@Composable
fun CatalogueTab(
    subTab: CatalogueSubTab,
    favoriteShows: List<FavoriteShow>,
    openShow: String?,
    openRewatchLog: String?,
    recommended: List<CatalogueShow>,
    posters: Map<String, Bitmap>,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        SearchRow()
        SubTabRow(selected = subTab)
        when (subTab) {
            CatalogueSubTab.FAVORITES -> FavoritesList(
                shows = favoriteShows,
                openShow = openShow,
                openRewatchLog = openRewatchLog,
                posters = posters,
            )

            CatalogueSubTab.RECOMMENDED -> RecommendedList(shows = recommended, posters = posters)
        }
    }
}

@Composable
private fun SearchRow() {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.RowPaddingHorizontal, vertical = 6.dp)
            .cornerRadiusCompat(Tokens.RadiusPanel)
            .background(Tokens.white(0.06f))
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(ActionKeys.openSearch to true))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "🔍", style = Tokens.mono(Dimens.accentLabelSize(), Tokens.TextPrimary))
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = "SEARCH ALL SHOWS",
            style = Tokens.mono(Dimens.metaSize(), Tokens.TextSecondary),
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

@Composable
private fun SubTabRow(selected: CatalogueSubTab) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = Tokens.RowPaddingHorizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubTabPill(
            label = "FAVORITES",
            selected = selected == CatalogueSubTab.FAVORITES,
            leading = { StarGlyph(size = Dimens.tabGlyphSize() * 0.8f) },
            modifier = GlanceModifier.defaultWeight(),
            target = CatalogueSubTab.FAVORITES,
        )
        Spacer(GlanceModifier.width(6.dp))
        SubTabPill(
            label = "RECOMMENDED",
            selected = selected == CatalogueSubTab.RECOMMENDED,
            modifier = GlanceModifier.defaultWeight(),
            target = CatalogueSubTab.RECOMMENDED,
        )
    }
}

@Composable
private fun SubTabPill(
    label: String,
    selected: Boolean,
    modifier: GlanceModifier,
    target: CatalogueSubTab,
    leading: (@Composable () -> Unit)? = null,
) {
    val background = if (selected) Tokens.accent(0.14f) else Tokens.white(0.05f)
    val foreground = if (selected) Tokens.TextPrimary else Tokens.TextMuted
    Row(
        modifier = modifier
            .height(Dimens.tabPillHeight() * 0.8f)
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(background)
            .padding(horizontal = 8.dp)
            .clickable(
                actionRunCallback<SwitchCatalogueSubTabAction>(
                    actionParametersOf(ActionKeys.catalogueSubTab to target.name)
                )
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (leading != null) {
            leading()
            Spacer(GlanceModifier.width(4.dp))
        }
        Text(text = label, style = Tokens.mono(Dimens.statusSize(), foreground))
    }
}

@Composable
private fun RecommendedList(shows: List<CatalogueShow>, posters: Map<String, Bitmap>) {
    if (shows.isEmpty()) {
        EmptyState()
        return
    }
    val tracked = shows.filter { it.tracked }
    val browsable = shows.filterNot { it.tracked }
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        if (tracked.isNotEmpty()) {
            item { SectionHeader("TRACKING (${tracked.size})") }
            items(tracked.size) { index -> RecommendedRow(show = tracked[index], posters = posters) }
        }
        if (browsable.isNotEmpty()) {
            item { SectionHeader("TRENDING") }
            items(browsable.size) { index -> RecommendedRow(show = browsable[index], posters = posters) }
        }
        item { Spacer(GlanceModifier.fillMaxWidth().height(Dimens.listRowHeight())) }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(18.dp)
            .padding(horizontal = Tokens.RowPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = Tokens.mono(Dimens.statusSize(), Tokens.white(0.4f)))
    }
}

/**
 * Shown before the first successful sync, and again if every sync since has failed (a failed sync
 * never overwrites a previously-good list — see `AnticipatedSyncWorker.doWork` — so this state only
 * ever means "no recommended data has loaded yet"). Tapping forces another attempt immediately
 * rather than waiting for the next automatic retry.
 */
@Composable
private fun EmptyState() {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(Dimens.listRowHeight() * 2)
            .clickable(actionRunCallback<RetryRecommendedSyncAction>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "COULDN'T LOAD RECOMMENDATIONS", style = Tokens.mono(Dimens.metaSize(), Tokens.white(0.4f)))
        Spacer(GlanceModifier.height(4.dp))
        Text(text = "TAP TO RETRY", style = Tokens.mono(Dimens.metaSize(), Tokens.Accent))
    }
}

@Composable
private fun RecommendedRow(show: CatalogueShow, posters: Map<String, Bitmap>) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(Dimens.listRowHeight() - 1.dp)
                .padding(horizontal = Tokens.RowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(title = show.title, posters = posters)
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "${show.status} · ${show.network}",
                    style = Tokens.mono(Dimens.metaSize(), Tokens.TextSecondary),
                    maxLines = 1,
                )
                Text(text = show.title, style = Tokens.display(Dimens.TitleSize), maxLines = 1)
            }
            Spacer(GlanceModifier.width(6.dp))
            TrackToggle(show)
        }
        Hairline()
    }
}

/**
 * One button does both jobs — tapping an untracked show adds it, tapping a tracked one removes it —
 * so the label always states the action a tap performs, not the current status.
 */
@Composable
private fun TrackToggle(show: CatalogueShow) {
    val background = if (show.tracked) Tokens.accent(0.18f) else Tokens.white(0.08f)
    val foreground = if (show.tracked) Tokens.Accent else Tokens.TextSecondary
    Box(
        modifier = GlanceModifier
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clickable(
                actionRunCallback<ToggleTrackedAction>(
                    actionParametersOf(
                        ActionKeys.tvMazeId to show.tvMazeId,
                        ActionKeys.showTitle to show.title,
                        ActionKeys.network to show.network,
                        ActionKeys.posterUrl to (show.posterUrl ?: ""),
                        ActionKeys.wasTracked to show.tracked,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (show.tracked) "− UNTRACK" else "+ TRACK",
            style = Tokens.mono(Dimens.statusSize(), foreground, TextAlign.Center),
        )
    }
}
