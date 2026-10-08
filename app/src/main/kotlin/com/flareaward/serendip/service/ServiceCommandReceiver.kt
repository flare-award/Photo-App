package com.flareaward.serendip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flareaward.serendip.di.appGraph
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.launch

/** Handles the "Turn off" action of the foreground-service notification. */
class ServiceCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TURN_OFF) return
        val graph = context.appGraph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                graph.serviceController.disable()
                AppLog.i("Automatic photos turned off from the notification")
            } catch (error: Exception) {
                AppLog.e("Turning off from the notification failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TURN_OFF = "com.flareaward.serendip.action.TURN_OFF"
    }
}
