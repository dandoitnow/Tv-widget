package com.example.tvwidget.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The `HH:MM` readout in the header while the TODAY tab is showing. */
object Countdown {

    /**
     * Time until the next release that has not aired yet, searching today first and then the
     * scheduled days. Returns `null` when nothing is left in the window, which the header renders
     * as `--:--`.
     */
    fun untilNextRelease(
        releases: List<Release>,
        now: LocalDateTime = LocalDateTime.now(),
    ): Duration? {
        val next = releases
            .mapNotNull { release -> airsAt(release, now.toLocalDate())?.let { it to release } }
            .filter { (at, _) -> at.isAfter(now) }
            .minByOrNull { (at, _) -> at }
            ?: return null
        return Duration.between(now, next.first)
    }

    /**
     * Formats a duration as `HH:MM`. The mock ticks `HH:MM:SS` every second; on device the widget
     * updates at most once a minute, so seconds would be stale as often as not.
     */
    fun format(duration: Duration?): String {
        if (duration == null || duration.isNegative) return "--:--"
        val totalMinutes = duration.toMinutes()
        return "%02d:%02d".format(totalMinutes / 60, totalMinutes % 60)
    }

    private fun airsAt(release: Release, today: LocalDate): LocalDateTime? {
        val time = runCatching { LocalTime.parse(release.airTime) }.getOrNull() ?: return null
        return today.plusDays(release.dayOffset.toLong()).atTime(time)
    }
}
