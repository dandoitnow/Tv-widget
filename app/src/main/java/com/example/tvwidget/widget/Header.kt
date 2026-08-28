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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.example.tvwidget.R
import com.example.tvwidget.data.Tab
import com.example.tvwidget.ui.Tokens

/**
 * Fixed tab row across the top of the widget: three pills on the left, a readout on the right.
 *
 * @param todayCount number of watchlist releases dated today, shown in the first pill.
 * @param readout `HH:MM` countdown on TODAY, `AUTO HH:MM` on ANTICIPATED, `SAVED` on FAVORITES.
 */
@Composable
fun Header(selected: Tab, todayCount: Int, readout: String) {
    Column {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(top = 7.dp, start = 10.dp, end = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabPill(
                label = "$todayCount TODAY",
                tab = Tab.TODAY,
                selected = selected == Tab.TODAY,
                leading = { LiveDot() },
            )
            Spacer(GlanceModifier.width(5.dp))
            TabPill(label = "ANTICIPATED", tab = Tab.ANTICIPATED, selected = selected == Tab.ANTICIPATED)
            Spacer(GlanceModifier.width(5.dp))
            TabPill(
                label = "FAVORITES",
                tab = Tab.FAVORITES,
                selected = selected == Tab.FAVORITES,
                leading = { StarGlyph() },
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(text = readout, style = Tokens.mono65(Tokens.Accent))
        }
        Hairline(Tokens.white(0.07f))
    }
}

@Composable
private fun TabPill(
    label: String,
    tab: Tab,
    selected: Boolean,
    leading: (@Composable () -> Unit)? = null,
) {
    val background = if (selected) Tokens.accent(0.16f) else Tokens.white(0.06f)
    val foreground = if (selected) Tokens.TextPrimary else Tokens.TextMuted
    Row(
        modifier = GlanceModifier
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .clickable(
                actionRunCallback<SwitchTabAction>(
                    actionParametersOf(ActionKeys.tab to tab.name)
                )
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(GlanceModifier.width(4.dp))
        }
        Text(text = label, style = Tokens.mono65(foreground))
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
            .size(4.dp)
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
        modifier = GlanceModifier.size(7.dp),
    )
}
