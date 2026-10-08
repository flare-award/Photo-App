package com.flareaward.serendip.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.flareaward.serendip.domain.model.BackgroundRestriction

/**
 * Reads and requests the battery / background state of the app using only
 * public, vendor-agnostic Android APIs. Vendor-specific "auto start" or
 * "app launch" managers cannot be queried; the UI explains them in text.
 */
class BatteryOptimizationChecker(private val context: Context) {

    fun status(): BackgroundRestriction {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager?.isBackgroundRestricted == true) return BackgroundRestriction.RESTRICTED
        val powerManager = context.getSystemService(PowerManager::class.java)
        return if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            BackgroundRestriction.UNRESTRICTED
        } else {
            BackgroundRestriction.OPTIMIZED
        }
    }

    /**
     * Intents to try, in order, to let the user exempt the app from battery
     * optimisation: the direct system dialog, then the system list, then the
     * app's details page. The caller starts the first one that resolves.
     */
    fun requestUnrestrictedIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetailsIntent(),
    )

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
