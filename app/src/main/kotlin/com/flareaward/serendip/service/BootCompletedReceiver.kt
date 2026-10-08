package com.flareaward.serendip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flareaward.serendip.di.appGraph
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.launch

/**
 * After a reboot (or an app update) the foreground service is gone, and
 * Android does not allow a camera foreground service to be started from
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED. This receiver therefore never starts
 * the service: it records that user action is needed and posts a notification
 * whose "Resume" action is an allowed way to start it.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> PauseReason.REBOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> PauseReason.APP_UPDATED
            else -> return
        }
        AppLog.i("${intent.action}: schedule paused until the user resumes")
        val pending = goAsync()
        val work = context.appGraph.engine.pauseRequiringUser(reason)
        context.appGraph.appScope.launch {
            try {
                work.join()
            } finally {
                pending.finish()
            }
        }
    }
}
