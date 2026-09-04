package com.example.tvwidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.compose.ui.graphics.toArgb
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.example.tvwidget.MainActivity
import com.example.tvwidget.R
import com.example.tvwidget.data.Tab
import com.example.tvwidget.ui.Dimens
import com.example.tvwidget.ui.Surfaces
import com.example.tvwidget.ui.Tokens

/**
 * The tab switcher: three pills, one row.
 *
 * TODAY and POPULAR are real tabs; CATALOG isn't — there's no widget-side content for it, just a
 * button that opens the app's Catalog screen. It never highlights as selected, since there's no
 * CATALOG state to be in.
 *
 * The selected pill is a gold-tinted ember with a gold label rather than a solid gold fill. One of
 * three is selected at all times, so a bright bar would sit permanently across the top of the
 * widget and read loud; warmth plus the label colour carries the signal without shouting. The
 * hairline that used to close the header is gone with all the others — the gap below does that job.
 *
 * @param todayCount number of watchlist releases dated today, shown in the first pill.
 */
@Composable
fun Header(selected: Tab, todayCount: Int) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = Dimens.rowGap() + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            label = "$todayCount TODAY",
            selected = selected == Tab.TODAY,
            leading = { LiveDot() },
            modifier = GlanceModifier.defaultWeight(),
            onClick = actionRunCallback<SwitchTabAction>(actionParametersOf(ActionKeys.tab to Tab.TODAY.name)),
        )
        Spacer(GlanceModifier.width(5.dp))
        Pill(
            label = "POPULAR",
            selected = selected == Tab.ANTICIPATED,
            modifier = GlanceModifier.defaultWeight(),
            onClick = actionRunCallback<SwitchTabAction>(
                actionParametersOf(ActionKeys.tab to Tab.ANTICIPATED.name)
            ),
        )
        Spacer(GlanceModifier.width(5.dp))
        Pill(
            label = "CATALOG",
            selected = false,
            modifier = GlanceModifier.defaultWeight(),
            onClick = actionStartActivity<MainActivity>(
                actionParametersOf(ActionKeys.openCatalog to true)
            ),
        )
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
    Row(
        modifier = modifier
            .height(Dimens.tabPillHeight())
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(
                ImageProvider(if (selected) R.drawable.surface_pill_selected else R.drawable.surface_pill)
            )
            .padding(horizontal = 8.dp)
            .clickable(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (leading != null) {
            leading()
            Spacer(GlanceModifier.width(5.dp))
        }
        // Drawn, not typeset. RemoteViews exposes no letter-spacing and no gradient fill, and both
        // are exactly what a small-caps label needs to look struck rather than typed — so the label
        // is rendered to a bitmap with real tracking and, when selected, a vertical gold ramp.
        // Confined to the header on purpose: baked text cannot follow the system font scale, which
        // is a fair trade for three fixed words of chrome and a bad one for anything else.
        Image(
            provider = ImageProvider(
                Surfaces.label(
                    text = label,
                    sizeSp = Dimens.tabLabelSize(),
                    tracking = 0.14f,
                    gold = selected,
                    flatColor = if (selected) Tokens.Accent.toArgb() else Tokens.TextMuted.toArgb(),
                )
            ),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.height(Dimens.tabLabelHeight()),
        )
    }
}

/**
 * The live indicator on TODAY. The original mock pulses it; RemoteViews cannot animate, so on
 * device it is a static accent dot — small enough to read as a status light rather than a bullet.
 */
@Composable
private fun LiveDot() {
    Spacer(
        GlanceModifier
            .size(Dimens.tabGlyphSize())
            .cornerRadiusCompat(Tokens.RadiusPill)
            .background(Tokens.Accent)
    )
}
