package com.flareaward.serendip.presentation.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.flareaward.serendip.system.AppLog

/** Starts the first intent the device can handle. Returns `false` when none could be started. */
fun Context.startFirstAvailable(intents: List<Intent>): Boolean {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // try the next one
        } catch (error: SecurityException) {
            AppLog.w("Not allowed to start ${intent.action}", error)
        }
    }
    return false
}

fun Context.startSafely(intent: Intent): Boolean = startFirstAvailable(listOf(intent))
