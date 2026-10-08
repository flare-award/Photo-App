package com.flareaward.serendip.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.di.appGraph
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.notifications.SerendipNotifications
import com.flareaward.serendip.notifications.ServiceNotificationModel
import com.flareaward.serendip.scheduler.StartReason
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The camera foreground service. It exists so that the OS treats the app as
 * "in use" while automatic photos are enabled; without it a background camera
 * open is refused on Android 11+.
 *
 * Android rules this class is written against (see README / ARCHITECTURE):
 *  - it is only ever started from a visible activity or from a notification
 *    action (`PendingIntent.getForegroundService`) — the two states in which a
 *    while-in-use (camera) foreground service may be started on Android 14+;
 *  - it is never started from BOOT_COMPLETED, an alarm or any other broadcast;
 *  - a refused `startForeground` (SecurityException / not-allowed) is handled
 *    by pausing the schedule and telling the user, never retried in a loop.
 */
class AutoCaptureService : Service() {

    private lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false
    private var enabledNow = true
    private var clockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        graph = appGraph
        registerClockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A camera foreground service needs the CAMERA permission at promotion time.
        if (!graph.permissions.hasCameraPermission()) {
            graph.engine.pauseRequiringUser(PauseReason.CAMERA_PERMISSION_MISSING)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!inForeground) {
            if (!promoteToForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            inForeground = true
            _isRunning.value = true
            observeState()
        }

        val reason = when (action) {
            ACTION_START -> StartReason.USER
            ACTION_RESUME_FROM_NOTIFICATION -> StartReason.NOTIFICATION
            else -> StartReason.SYSTEM_RESTART
        }
        AppLog.i("Service start: action=${action ?: "restart"} reason=$reason")
        graph.engine.start(reason)
        return START_STICKY
    }

    private fun promoteToForeground(): Boolean {
        val notification = graph.notifications.buildServiceNotification(
            ServiceNotificationModel(photosToday = 0, dailyLimit = 0, nextCaptureAt = null, paused = null),
        )
        return try {
            ServiceCompat.startForeground(
                this,
                SerendipNotifications.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
            true
        } catch (error: SecurityException) {
            // Android 14+: while-in-use permission missing (started from a disallowed state) or
            // the camera permission vanished in between.
            AppLog.e("startForeground(camera) refused", error)
            graph.engine.pauseRequiringUser(PauseReason.SERVICE_NOT_ALLOWED)
            false
        } catch (error: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (Android 12+).
            AppLog.e("Foreground service start not allowed", error)
            graph.engine.pauseRequiringUser(PauseReason.SERVICE_NOT_ALLOWED)
            false
        }
    }

    /** Keeps the persistent notification truthful and stops when the mode is switched off. */
    private fun observeState() {
        scope.launch {
            combine(
                graph.settings.settings,
                graph.schedulerState.state,
            ) { settings, state ->
                settings.enabled to ServiceNotificationModel(
                    photosToday = state.photosTakenToday,
                    dailyLimit = settings.dailyPhotoLimit,
                    nextCaptureAt = state.nextCaptureAt,
                    paused = state.pauseReason,
                )
            }.distinctUntilChanged().collectLatest { (enabled, model) ->
                enabledNow = enabled
                if (!enabled) {
                    AppLog.i("Automatic photos disabled; stopping the service")
                    stopSelf()
                } else {
                    graph.notifications.updateServiceNotification(model)
                }
            }
        }
    }

    private fun registerClockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                AppLog.d("Clock changed: ${intent.action}")
                graph.engine.onClockChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        clockReceiver = receiver
    }

    override fun onDestroy() {
        _isRunning.value = false
        clockReceiver?.let { runCatching { unregisterReceiver(it) } }
        clockReceiver = null
        // Wake-ups stay armed when the service dies while the mode is still enabled:
        // the alarm receiver then asks the user to resume.
        graph.engine.stop(cancelWakeUps = !enabledNow)
        scope.cancel()
        if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.flareaward.serendip.action.START"
        const val ACTION_RESUME_FROM_NOTIFICATION = "com.flareaward.serendip.action.RESUME_FROM_NOTIFICATION"
        const val ACTION_STOP = "com.flareaward.serendip.action.STOP"

        private val _isRunning = MutableStateFlow(false)

        /** `true` while the service is in the foreground (process-local, resets with the process). */
        val isRunning: StateFlow<Boolean> = _isRunning

        fun intent(context: Context, action: String): Intent =
            Intent(context, AutoCaptureService::class.java).setAction(action)
    }
}
