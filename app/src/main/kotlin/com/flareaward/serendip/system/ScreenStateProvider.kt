package com.flareaward.serendip.system

import android.content.Context
import android.os.PowerManager

class ScreenStateProvider(private val context: Context) {
    /** `true` when the display is on (the device is "interactive"). */
    fun isInteractive(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
}
