package com.example.tvwidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.example.tvwidget.R
import com.example.tvwidget.data.Tab
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Tokens

/**
 * The tab switcher: three pills side by side, one row. Used to be a 2x2 grid when FAVORITES was a
 * fourth top-level tab; now that it lives inside CATALOGUE as a sub-tab, three tabs fit comfortably
 * in one row, and a single row leaves more vertical space for the actual content below it than the
 * grid did. There is no trailing readout (the old `AUTO HH:MM` / `SAVED` text) — it named a mechanism
 * the user doesn't act on, so it just cost space.
 *
 * @param todayCount number of watchlist releases dated today, shown in the first pill.
 */
@Composable
fun Header(selected: Tab, todayCount: Int) {
    Column {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(top = 7.dp, start = 10.dp, end = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                label = "$todayCount TODAY",
                selected = selected == Tab.TODAY,
                leading = { LiveDot() },
                modifier = GlanceModifier.defaultWeight(),
                onClick = actionRunCallback<SwitchTabAction>(actionParametersOf(ActionKeys.tab to Tab.TODAY.name)),
            )
            Spacer(GlanceModifier.width(6.dp))
            Pill(
                label = "POPULAR",
                selected = selected == Tab.ANTICIPATED,
                modifier = GlanceModifier.defaultWeight(),
                onClick = actionRunCallback<SwitchTabAction>(
                    actionParametersOf(ActionKeys.tab to Tab.ANTICIPATED.name)
                ),
            )
            Spacer(GlanceModifier.width(6.dp))
            Pill(
                label = "CATALOGUE",
                selected = selected == Tab.CATALOGUE,
                modifier = GlanceModifier.defaultWeight(),
                onClick = actionRunCallback<SwitchTabAction>(
                    actionParametersOf(ActionKeys.tab to Tab.CATALOGUE.name)
                ),
            )
        }
        Hairline(Tokens.white(0.07f))
    }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    modifier: GlanceModifier,
    onClick: Action,
    leading: (@Composable () -> Unit)? = null,
) {
    val background = if (selected) Tokens.accent(0.16f) else Tokens.white(0.06f)
    val foreground = if (selected) Tokens.TextPrimary else Tokens.TextMuted
    Row(
        modifier = modifier
            .height(Dimens.tabPillHeight())
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(background)
            .padding(horizontal = 10.dp)
            .clickable(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (leading != null) {
            leading()
            Spacer(GlanceModifier.width(5.dp))
        }
        Text(text = label, style = Tokens.mono(Dimens.tabLabelSize(), foreground))
    }
}

/**
 * The live indicator. The mock pulses it (opacity .25 -> 1 over 2s); RemoteViews cannot animate, so
 * on device it is a static accent dot.
 */
@Composable
private fun LiveDot() {
    val size = Dimens.tabGlyphSize() * 0.55f
    Spacer(
        GlanceModifier
            .size(size)
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(Tokens.Accent)
    )
}

/** The star glyph used by CATALOGUE's FAVORITES sub-tab pill (see [CatalogueTab]). */
@Composable
fun StarGlyph(size: Dp = Dimens.tabGlyphSize()) {
    Image(
        provider = ImageProvider(R.drawable.ic_star_filled),
        contentDescription = null,
        colorFilter = ColorFilter.tint(Tokens.provider(Tokens.Accent)),
        modifier = GlanceModifier.size(size),
    )
}
