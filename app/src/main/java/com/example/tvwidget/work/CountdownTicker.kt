package com.example.tvwidget.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.example.tvwidget.data.Tab
import com.example.tvwidget.widget.TvWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Redraws the widget once a minute so the TODAY countdown stays current.
 *
 * The mock re-renders every second; on device that would be a battery cost for a readout nobody is
 * staring at, so the header renders `HH:MM` and this ticker re-arms itself on each minute boundary
 * with an inexact alarm. The alarm only runs while the TODAY tab is showing — [sync] drops it as
 * soon as the user switches away.
 */
object CountdownTicker {

    const val ACTION_TICK = "com.example.tvwidget.action.COUNTDOWN_TICK"

    private const val REQUEST_CODE = 4201
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact: the OS may batch this with other wakeups, which is exactly what we want.
        alarmManager.set(AlarmManager.RTC, nextMinuteBoundary(), pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /** Keeps the ticker running only on the tab that needs it. */
    fun sync(context: Context, tab: Tab) {
        if (tab == Tab.TODAY) schedule(context) else cancel(context)
    }

    private fun nextMinuteBoundary(): Long {
        val now = System.currentTimeMillis()
        return now + (60_000L - now % 60_000L)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, Receiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Redraws every instance of the widget, then arms the next tick. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_TICK) return
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            scope.launch {
                try {
                    TvWidget().updateAll(appContext)
                    schedule(appContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
