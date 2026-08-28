package com.example.tvwidget.widget

import androidx.compose.ui.unit.Dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius

/**
 * Rounded corners are an Android 12 feature in RemoteViews. Below API 31 the call is a documented
 * no-op, so this wrapper exists to make that expectation explicit at each call site rather than
 * hiding it behind a bare `cornerRadius`.
 */
fun GlanceModifier.cornerRadiusCompat(radius: Dp): GlanceModifier = cornerRadius(radius)
