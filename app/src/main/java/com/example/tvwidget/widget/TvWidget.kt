package com.example.tvwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.example.tvwidget.data.Countdown
import com.example.tvwidget.data.SampleData
import com.example.tvwidget.data.Tab
import com.example.tvwidget.data.WidgetState
import com.example.tvwidget.ui.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The 5x2 TV release tracker widget.
 *
 * All widget state lives in the Glance `DataStore` ([PreferencesGlanceStateDefinition]) so the
 * widget restores identically after a launcher restart.
 */
class TvWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition

    // The design is fixed at 360 x 152dp; a single layout is rendered at whatever size the launcher
    // hands us rather than swapping layouts per breakpoint.
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }
}

@Composable
private fun WidgetContent() {
    val prefs = currentState<Preferences>()
    val tab = WidgetState.tab(prefs)
    val releases = SampleData.releases()
    val favorites = WidgetState.favorites(prefs)
    val todayCount = releases.count { it.isToday }

    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadiusCompat(Tokens.RadiusWidget)
                .background(Tokens.Background),
        ) {
            Header(
                selected = tab,
                todayCount = todayCount,
                readout = headerReadout(tab, prefs),
            )
            when (tab) {
                Tab.TODAY -> TodayFeed(
                    releases = releases,
                    favorites = favorites,
                    showPast = WidgetState.showPast(prefs),
                )

                Tab.ANTICIPATED -> AnticipatedList(shows = WidgetState.anticipated(prefs))

                Tab.FAVORITES -> FavoritesList(
                    shows = WidgetState.favoriteShows(favorites, WidgetState.rewatchLog(prefs)),
                    openShow = WidgetState.openShow(prefs),
                    openRewatchLog = WidgetState.openRewatchLog(prefs),
                )
            }
        }
    }
}

/**
 * TODAY shows a live countdown to the next air time, ANTICIPATED the last sync of the premiere
 * list, FAVORITES a static `SAVED`.
 */
private fun headerReadout(tab: Tab, prefs: Preferences): String = when (tab) {
    Tab.TODAY -> Countdown.format(Countdown.untilNextRelease(SampleData.releases()))
    Tab.ANTICIPATED -> {
        val lastSync = WidgetState.lastSync(prefs)
        if (lastSync == 0L) "AUTO --:--" else "AUTO " + clockFormat.format(Date(lastSync))
    }

    Tab.FAVORITES -> "SAVED"
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)
