package com.flareaward.serendip.system

import android.content.Context
import android.os.PowerManager

/** Short partial wake locks that keep the CPU on while a capture is in flight. */
class WakeLocks(private val context: Context) {

    fun acquire(tag: String, timeoutMillis: Long): Lease {
        val pm = context.getSystemService(PowerManager::class.java)
        val lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "serendip:$tag")
        try {
            lock?.setReferenceCounted(false)
            lock?.acquire(timeoutMillis)
        } catch (error: RuntimeException) {
            AppLog.w("Wake lock could not be acquired", error)
        }
        return Lease(lock)
    }

    class Lease internal constructor(private val lock: PowerManager.WakeLock?) {
        fun release() {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (error: RuntimeException) {
                AppLog.w("Wake lock could not be released", error)
            }
        }
    }
}
