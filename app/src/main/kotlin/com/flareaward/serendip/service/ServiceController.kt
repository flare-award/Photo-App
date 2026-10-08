package com.flareaward.serendip.service

import android.content.Context
import androidx.core.content.ContextCompat
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.domain.repository.SettingsRepository
import com.flareaward.serendip.scheduler.AutoCaptureEngine
import com.flareaward.serendip.scheduler.StartReason
import com.flareaward.serendip.system.AppLog
import com.flareaward.serendip.system.PermissionChecker

/**
 * The only entry points that start or stop the camera foreground service.
 * [startFromVisibleApp] must be called while an activity of the app is
 * visible — the state Android allows a camera foreground service to be
 * started from. The notification "Resume" action is the other allowed path
 * and is wired directly to the service by [com.flareaward.serendip.notifications.SerendipNotifications].
 */
class ServiceController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val permissions: PermissionChecker,
    private val engine: AutoCaptureEngine,
) {

    enum class StartOutcome { STARTED, ALREADY_RUNNING, NO_CAMERA_PERMISSION, NOT_ALLOWED }

    /** Enables the mode and starts the service. Call from a visible activity (user action). */
    suspend fun enable(): StartOutcome {
        settings.update { it.copy(enabled = true) }
        return startFromVisibleApp()
    }

    /** Disables the mode; the running service observes the setting and stops itself. */
    suspend fun disable() {
        settings.update { it.copy(enabled = false) }
        context.stopService(AutoCaptureService.intent(context, AutoCaptureService.ACTION_STOP))
    }

    /**
     * Starts the service if the mode is enabled and it is not running yet.
     * Used when the app comes to the foreground: this is also what resumes a
     * schedule paused after a reboot, an update or a blocked camera.
     */
    suspend fun ensureRunningIfEnabled(): StartOutcome? {
        if (!settings.current().enabled) return null
        return startFromVisibleApp()
    }

    fun startFromVisibleApp(): StartOutcome {
        if (!permissions.hasCameraPermission()) return StartOutcome.NO_CAMERA_PERMISSION
        if (AutoCaptureService.isRunning.value) {
            // Already hosted: a user-initiated start clears any pause and re-checks the plan.
            engine.start(StartReason.USER)
            return StartOutcome.ALREADY_RUNNING
        }
        return try {
            ContextCompat.startForegroundService(context, AutoCaptureService.intent(context, AutoCaptureService.ACTION_START))
            StartOutcome.STARTED
        } catch (error: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException and friends: the app was not visible after all.
            AppLog.e("Could not start the foreground service", error)
            engine.pauseRequiringUser(PauseReason.SERVICE_NOT_ALLOWED)
            StartOutcome.NOT_ALLOWED
        } catch (error: SecurityException) {
            AppLog.e("Could not start the foreground service", error)
            engine.pauseRequiringUser(PauseReason.SERVICE_NOT_ALLOWED)
            StartOutcome.NOT_ALLOWED
        }
    }
}
