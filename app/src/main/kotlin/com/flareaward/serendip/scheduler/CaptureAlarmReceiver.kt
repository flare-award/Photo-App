package com.flareaward.serendip.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flareaward.serendip.di.appGraph
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives the wake-up alarms. It never starts the camera service itself —
 * Android forbids starting a camera foreground service from a broadcast in
 * the background. It hands the trigger to the engine, which captures if the
 * service is running and otherwise asks the user to resume.
 */
class CaptureAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE_UP) return
        val graph = context.appGraph
        val pending = goAsync()
        // The work runs in the engine's own scope; only the *wait* is bounded by the
        // broadcast budget, so a capture in flight is never cancelled from here.
        val work = graph.engine.onAlarm()
        graph.appScope.launch {
            try {
                withTimeout(RECEIVER_BUDGET_MS) { work.join() }
            } catch (_: TimeoutCancellationException) {
                // The capture continues in the engine (it holds its own wake lock);
                // only the broadcast budget is over.
                AppLog.d("Alarm handling exceeded the receiver budget; continuing in the engine")
            } catch (error: Exception) {
                AppLog.e("Alarm handling failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WAKE_UP = "com.flareaward.serendip.action.WAKE_UP"
        private const val RECEIVER_BUDGET_MS = 8_000L
    }
}
