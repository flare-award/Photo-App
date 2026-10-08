package com.flareaward.serendip.di

import android.app.Application
import android.content.Context
import com.flareaward.serendip.SerendipApp
import com.flareaward.serendip.camera.CameraXCaptureSource
import com.flareaward.serendip.data.db.RoomCaptureEventRepository
import com.flareaward.serendip.data.db.SerendipDatabase
import com.flareaward.serendip.data.settings.DataStoreSchedulerStateRepository
import com.flareaward.serendip.data.settings.DataStoreSettingsRepository
import com.flareaward.serendip.domain.camera.CameraCaptureSource
import com.flareaward.serendip.domain.repository.AppFlagsRepository
import com.flareaward.serendip.domain.repository.CaptureEventRepository
import com.flareaward.serendip.domain.repository.SchedulerStateRepository
import com.flareaward.serendip.domain.repository.SettingsRepository
import com.flareaward.serendip.domain.scheduler.CaptureScheduler
import com.flareaward.serendip.domain.time.TimeSource
import com.flareaward.serendip.notifications.SerendipNotifications
import com.flareaward.serendip.scheduler.AutoCaptureEngine
import com.flareaward.serendip.scheduler.CaptureAlarmScheduler
import com.flareaward.serendip.service.ServiceController
import com.flareaward.serendip.storage.MediaStorePhotoStorage
import com.flareaward.serendip.system.AndroidTimeSource
import com.flareaward.serendip.system.BatteryOptimizationChecker
import com.flareaward.serendip.system.PermissionChecker
import com.flareaward.serendip.system.ScreenStateProvider
import com.flareaward.serendip.system.SystemStatusMonitor
import com.flareaward.serendip.system.WakeLocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-wired dependency graph (one instance per process, owned by the
 * [Application]). Small enough that a DI framework would only add build time.
 */
class AppGraph(private val application: Application) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val timeSource: TimeSource = AndroidTimeSource()

    private val settingsStore = DataStoreSettingsRepository(application)
    val settings: SettingsRepository = settingsStore
    val appFlags: AppFlagsRepository = settingsStore
    val schedulerState: SchedulerStateRepository = DataStoreSchedulerStateRepository(application)

    private val database: SerendipDatabase by lazy { SerendipDatabase.create(application) }
    val events: CaptureEventRepository by lazy { RoomCaptureEventRepository(database.captureEventDao()) }

    val permissions = PermissionChecker(application)
    val battery = BatteryOptimizationChecker(application)
    val screenState = ScreenStateProvider(application)
    val systemStatus = SystemStatusMonitor(permissions, battery)
    val wakeLocks = WakeLocks(application)

    val photoStorage = MediaStorePhotoStorage(application)
    val camera: CameraCaptureSource = CameraXCaptureSource(application, photoStorage, permissions)

    val notifications = SerendipNotifications(application, permissions)
    val alarms = CaptureAlarmScheduler(application)
    val scheduler = CaptureScheduler()

    val engine: AutoCaptureEngine by lazy {
        AutoCaptureEngine(
            settingsRepository = settings,
            stateRepository = schedulerState,
            events = events,
            camera = camera,
            storage = photoStorage,
            screenState = screenState,
            permissions = permissions,
            alarms = alarms,
            notifications = notifications,
            timeSource = timeSource,
            scheduler = scheduler,
            wakeLocks = wakeLocks,
        )
    }

    val serviceController: ServiceController by lazy {
        ServiceController(application, settings, permissions, engine)
    }
}

/** Access to the process-wide graph from any context (receivers, services, composables). */
val Context.appGraph: AppGraph
    get() = (applicationContext as SerendipApp).graph
