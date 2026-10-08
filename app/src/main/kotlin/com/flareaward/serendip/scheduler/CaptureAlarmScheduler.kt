package com.flareaward.serendip.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.flareaward.serendip.system.AppLog

/**
 * Wake-ups through [AlarmManager]. Only *inexact* alarms are used — the app
 * neither needs nor requests the exact-alarm permission:
 *
 *  - `setAndAllowWhileIdle` fires even in Doze (at most once per ~9 minutes
 *    per app while idle, which the scheduler's grace period accounts for);
 *  - `setWindow` with a 10-minute window bounds the delay while the device is
 *    awake, where plain inexact alarms may be batched much later.
 *
 * Both deliver the same broadcast; the engine's mutex makes duplicates harmless.
 * A fixed request code per alarm guarantees that at most one pair is pending.
 */
class CaptureAlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    fun schedule(triggerAtMillis: Long) {
        val am = alarmManager ?: return
        val at = maxOf(triggerAtMillis, System.currentTimeMillis() + MIN_DELAY_MS)
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(RC_IDLE))
            am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pendingIntent(RC_WINDOW))
            AppLog.d("Alarm armed for $at")
        } catch (error: SecurityException) {
            // Cannot happen for inexact alarms, but a vendor ROM is not a contract.
            AppLog.e("Arming the alarm failed", error)
        }
    }

    fun cancel() {
        val am = alarmManager ?: return
        am.cancel(pendingIntent(RC_IDLE))
        am.cancel(pendingIntent(RC_WINDOW))
        AppLog.d("Alarms cancelled")
    }

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, CaptureAlarmReceiver::class.java).setAction(CaptureAlarmReceiver.ACTION_WAKE_UP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val RC_IDLE = 1
        const val RC_WINDOW = 2
        const val WINDOW_MS = 10 * 60_000L
        const val MIN_DELAY_MS = 5_000L
    }
}
