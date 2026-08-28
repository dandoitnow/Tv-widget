package com.example.tvwidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
 * The tab switcher: a 2x2 grid of full-width pills rather than a single cramped row, so each tab is
 * a large, easy-to-hit target and there's room for a fourth tab (CATALOGUE) without shrinking text.
 * There is no trailing readout (the old `AUTO HH:MM` / `SAVED` text) — it named a mechanism the user
 * doesn't act on, so it just cost space.
 *
 * @param todayCount number of watchlist releases dated today, shown in the first pill.
 */
@Composable
fun Header(selected: Tab, todayCount: Int) {
    Column {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 7.dp, start = 10.dp, end = 10.dp, bottom = 6.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TabPill(
                    label = "$todayCount TODAY",
                    tab = Tab.TODAY,
                    selected = selected == Tab.TODAY,
                    leading = { LiveDot() },
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(6.dp))
                TabPill(
                    label = "ANTICIPATED",
                    tab = Tab.ANTICIPATED,
                    selected = selected == Tab.ANTICIPATED,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TabPill(
                    label = "FAVORITES",
                    tab = Tab.FAVORITES,
                    selected = selected == Tab.FAVORITES,
                    leading = { StarGlyph() },
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(6.dp))
                TabPill(
                    label = "CATALOGUE",
                    tab = Tab.CATALOGUE,
                    selected = selected == Tab.CATALOGUE,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        Hairline(Tokens.white(0.07f))
    }
}

@Composable
private fun TabPill(
    label: String,
    tab: Tab,
    selected: Boolean,
    modifier: GlanceModifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val background = if (selected) Tokens.accent(0.16f) else Tokens.white(0.06f)
    val foreground = if (selected) Tokens.TextPrimary else Tokens.TextMuted
    Row(
        modifier = modifier
            // Roughly double the previous pill's tap height (was ~15dp of padding + text); a bit
            // taller again once the widget is resized past the roomy breakpoint.
            .height(Dimens.tabPillHeight())
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(background)
            .padding(horizontal = 10.dp)
            .clickable(
                actionRunCallback<SwitchTabAction>(
                    actionParametersOf(ActionKeys.tab to tab.name)
                )
            ),
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
    Spacer(
        GlanceModifier
            .size(5.dp)
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(Tokens.Accent)
    )
}

@Composable
private fun StarGlyph() {
    Image(
        provider = ImageProvider(R.drawable.ic_star_filled),
        contentDescription = null,
        colorFilter = ColorFilter.tint(Tokens.provider(Tokens.Accent)),
        modifier = GlanceModifier.size(9.dp),
    )
}
