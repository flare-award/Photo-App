package com.flareaward.serendip.scheduler

import android.net.Uri
import com.flareaward.serendip.domain.camera.CameraCaptureSource
import com.flareaward.serendip.domain.camera.CaptureRequest
import com.flareaward.serendip.domain.camera.CaptureResult
import com.flareaward.serendip.domain.model.AutoPhotoSettings
import com.flareaward.serendip.domain.model.CaptureEvent
import com.flareaward.serendip.domain.model.CaptureOutcome
import com.flareaward.serendip.domain.model.FailureReason
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.domain.model.SchedulerState
import com.flareaward.serendip.domain.repository.CaptureEventRepository
import com.flareaward.serendip.domain.repository.SchedulerStateRepository
import com.flareaward.serendip.domain.repository.SettingsRepository
import com.flareaward.serendip.domain.scheduler.AttemptResult
import com.flareaward.serendip.domain.scheduler.CaptureScheduler
import com.flareaward.serendip.domain.time.TimeSource
import com.flareaward.serendip.notifications.SerendipNotifications
import com.flareaward.serendip.storage.MediaStorePhotoStorage
import com.flareaward.serendip.system.AppLog
import com.flareaward.serendip.system.PermissionChecker
import com.flareaward.serendip.system.ScreenStateProvider
import com.flareaward.serendip.system.WakeLocks
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why the engine was started; user-initiated starts may clear a pause and defer an immediate capture. */
enum class StartReason(val userInitiated: Boolean) {
    USER(true),
    NOTIFICATION(true),
    SYSTEM_RESTART(false),
}

/** What the engine is doing right now (for the dashboard and the service notification). */
sealed interface EngineStatus {
    data object Stopped : EngineStatus

    data class Running(val nextWakeAt: Long?) : EngineStatus

    data object Capturing : EngineStatus

    data class Paused(val reason: PauseReason) : EngineStatus
}

/**
 * The one place where captures are decided and executed. There is a single
 * instance per process, every decision runs under one [Mutex] and every
 * change of plan is persisted before the next step — so alarms, the in-process
 * timer, settings changes and service restarts can all poke it concurrently
 * without ever producing two captures for one moment.
 *
 * The engine does not start the foreground service itself: it is *hosted* by
 * [com.flareaward.serendip.service.AutoCaptureService] while that runs, and
 * degrades to "tell the user" when a trigger arrives without it.
 */
class AutoCaptureEngine(
    private val settingsRepository: SettingsRepository,
    private val stateRepository: SchedulerStateRepository,
    private val events: CaptureEventRepository,
    private val camera: CameraCaptureSource,
    private val storage: MediaStorePhotoStorage,
    private val screenState: ScreenStateProvider,
    private val permissions: PermissionChecker,
    private val alarms: CaptureAlarmScheduler,
    private val notifications: SerendipNotifications,
    private val timeSource: TimeSource,
    private val scheduler: CaptureScheduler,
    private val wakeLocks: WakeLocks,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private enum class Trigger { START, ALARM, TIMER, SETTINGS_CHANGED, TIME_CHANGED }

    private val mutex = Mutex()

    @Volatile
    private var hosted = false

    @Volatile
    private var pendingUserInitiated = false

    private var timerJob: Job? = null
    private var settingsJob: Job? = null

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    val status: StateFlow<EngineStatus> = _status

    // ------------------------------------------------------------------ lifecycle (service)

    /** Called by the foreground service once it is in the foreground. Idempotent. */
    fun start(reason: StartReason): Job {
        if (reason.userInitiated) pendingUserInitiated = true
        if (!hosted) {
            hosted = true
            settingsJob?.cancel()
            settingsJob = scope.launch {
                settingsRepository.settings
                    .map { Triple(it.enabled, it.planSignature, it.skipWhenScreenOff) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { requestTick(Trigger.SETTINGS_CHANGED) }
            }
        }
        return requestTick(Trigger.START)
    }

    /** Called when the foreground service goes away. Wake-ups of a disabled mode are removed. */
    fun stop(cancelWakeUps: Boolean) {
        hosted = false
        settingsJob?.cancel()
        settingsJob = null
        timerJob?.cancel()
        timerJob = null
        if (cancelWakeUps) alarms.cancel()
        _status.value = EngineStatus.Stopped
    }

    /** System time / zone / date changed: the plan must be checked against the new clock. */
    fun onClockChanged(): Job = requestTick(Trigger.TIME_CHANGED)

    // ------------------------------------------------------------------ external triggers

    /**
     * An alarm fired. With the service running this is a normal tick; without
     * it nothing can be captured (no camera foreground service, and one cannot
     * be started from here), so the user is asked to resume.
     */
    fun onAlarm(): Job = scope.launch {
        if (hosted) {
            tick(Trigger.ALARM)
        } else {
            mutex.withLock {
                val settings = settingsRepository.current()
                if (!settings.enabled) {
                    alarms.cancel()
                    return@withLock
                }
                val state = stateRepository.current()
                if (state.pauseReason == null) {
                    stateRepository.save(scheduler.pause(state, PauseReason.SERVICE_NOT_RUNNING))
                }
                alarms.cancel()
                notifications.showAttention(PauseReason.SERVICE_NOT_RUNNING)
                _status.value = EngineStatus.Paused(PauseReason.SERVICE_NOT_RUNNING)
                AppLog.w("Alarm fired without the foreground service; asked the user to resume")
            }
        }
    }

    /**
     * The device rebooted or the app was updated while the mode was enabled.
     * Android does not allow a camera foreground service to be started from
     * either state, so the schedule is paused and the user is told.
     */
    fun pauseRequiringUser(reason: PauseReason): Job = scope.launch {
        mutex.withLock {
            val settings = settingsRepository.current()
            if (!settings.enabled) return@withLock
            val state = stateRepository.current()
            stateRepository.save(scheduler.pause(state, reason))
            timerJob?.cancel()
            alarms.cancel()
            notifications.showAttention(reason)
            _status.value = EngineStatus.Paused(reason)
            AppLog.i("Paused: $reason")
        }
    }

    // ------------------------------------------------------------------ the tick

    private fun requestTick(trigger: Trigger): Job = scope.launch { tick(trigger) }

    private suspend fun tick(trigger: Trigger) = mutex.withLock {
        if (!hosted) return@withLock
        val settings = settingsRepository.current()
        if (!settings.enabled) {
            timerJob?.cancel()
            alarms.cancel()
            _status.value = EngineStatus.Stopped
            return@withLock
        }
        val userInitiated = pendingUserInitiated
        pendingUserInitiated = false

        var state = stateRepository.current()
        state.pauseReason?.let { reason ->
            if (userInitiated || !reason.requiresUserAction) {
                state = scheduler.resume(state)
                AppLog.i("Resumed after $reason")
            } else {
                _status.value = EngineStatus.Paused(reason)
                return@withLock
            }
        }

        if (!permissions.hasCameraPermission()) {
            pauseLocked(state, PauseReason.CAMERA_PERMISSION_MISSING)
            return@withLock
        }
        notifications.cancelAttention()

        val zone = timeSource.zone()
        val now = timeSource.nowMillis()
        val reconciliation = scheduler.reconcile(
            settings = settings,
            state = state,
            nowMillis = now,
            zone = zone,
            minLeadMs = if (userInitiated) USER_START_LEAD_MS else 0L,
        )
        state = reconciliation.state
        reconciliation.replanReason?.let { AppLog.d("Plan updated ($it) on $trigger: next=${state.nextCaptureAt}") }
        if (reconciliation.missedSlots > 0) {
            events.record(
                CaptureEvent(
                    timestamp = now,
                    zoneId = zone.id,
                    localDate = CaptureScheduler.localDate(now, zone),
                    outcome = CaptureOutcome.MISSED,
                    missedCount = reconciliation.missedSlots,
                ),
            )
        }

        if (scheduler.isDue(state, now)) {
            state = scheduler.claim(state, now)
            stateRepository.save(state)
            _status.value = EngineStatus.Capturing
            val result = runAttempt(settings, now, zone)
            state = scheduler.onAttemptFinished(settings, state, timeSource.nowMillis(), zone, result)
        }
        stateRepository.save(state)

        state.pauseReason?.let { reason ->
            timerJob?.cancel()
            alarms.cancel()
            notifications.showAttention(reason)
            _status.value = EngineStatus.Paused(reason)
            return@withLock
        }
        val wakeAt = scheduler.nextWakeAt(settings, state, timeSource.nowMillis(), zone)
        armWakeUp(wakeAt)
        _status.value = EngineStatus.Running(wakeAt)
    }

    private suspend fun pauseLocked(state: SchedulerState, reason: PauseReason) {
        stateRepository.save(scheduler.pause(state, reason))
        timerJob?.cancel()
        alarms.cancel()
        notifications.showAttention(reason)
        _status.value = EngineStatus.Paused(reason)
        AppLog.w("Paused: $reason")
    }

    /**
     * The capture pipeline: screen filter → storage check → camera → verified
     * file → history entry → optional notification. Every branch records an
     * event; the scheduler decides what the outcome means for the plan.
     */
    private suspend fun runAttempt(settings: AutoPhotoSettings, now: Long, zone: ZoneId): AttemptResult {
        val localDate = CaptureScheduler.localDate(now, zone)

        if (settings.skipWhenScreenOff && !screenState.isInteractive()) {
            events.record(CaptureEvent(timestamp = now, zoneId = zone.id, localDate = localDate, outcome = CaptureOutcome.SKIPPED_SCREEN_OFF))
            AppLog.d("Skipped: screen off")
            return AttemptResult.SkippedScreenOff
        }
        if (!storage.hasEnoughFreeSpace()) {
            events.record(
                CaptureEvent(
                    timestamp = now, zoneId = zone.id, localDate = localDate,
                    outcome = CaptureOutcome.FAILED, failureReason = FailureReason.STORAGE_LOW,
                ),
            )
            return AttemptResult.RetryableFailure
        }

        val lease = wakeLocks.acquire("capture", CAPTURE_WAKE_LOCK_MS)
        try {
            return when (val result = camera.capture(CaptureRequest(triggeredAt = now))) {
                is CaptureResult.Success -> {
                    val eventId = events.record(
                        CaptureEvent(
                            timestamp = now, zoneId = zone.id, localDate = localDate,
                            outcome = CaptureOutcome.SUCCESS, photoUri = result.photoUri,
                        ),
                    )
                    AppLog.i("Photo saved: ${result.photoUri} (${result.width}x${result.height}, ${result.sizeBytes} B)")
                    if (settings.notifyOnSuccess) {
                        notifications.showPhotoSaved(eventId, Uri.parse(result.photoUri), now)
                    }
                    AttemptResult.Success
                }

                is CaptureResult.Failure -> {
                    AppLog.w("Capture failed: ${result.reason} — ${result.details}")
                    events.record(
                        CaptureEvent(
                            timestamp = now, zoneId = zone.id, localDate = localDate,
                            outcome = CaptureOutcome.FAILED, failureReason = result.reason,
                        ),
                    )
                    result.reason.pauseReason?.let { AttemptResult.Blocking(it) } ?: AttemptResult.RetryableFailure
                }
            }
        } catch (error: Exception) {
            AppLog.e("Capture pipeline crashed", error)
            events.record(
                CaptureEvent(
                    timestamp = now, zoneId = zone.id, localDate = localDate,
                    outcome = CaptureOutcome.FAILED, failureReason = FailureReason.UNKNOWN,
                ),
            )
            return AttemptResult.RetryableFailure
        } finally {
            lease.release()
        }
    }

    /**
     * Arms both the alarm (survives Doze and process death) and an in-process
     * timer (precise while the process is alive). The timer re-checks the wall
     * clock every minute because coroutine delays do not advance in deep sleep.
     */
    private fun armWakeUp(wakeAt: Long?) {
        timerJob?.cancel()
        timerJob = null
        if (wakeAt == null) {
            alarms.cancel()
            return
        }
        alarms.schedule(wakeAt)
        timerJob = scope.launch {
            while (isActive) {
                val remaining = wakeAt - timeSource.nowMillis()
                if (remaining <= 0) break
                delay(minOf(remaining, TIMER_TICK_MS))
            }
            if (isActive) requestTick(Trigger.TIMER)
        }
    }

    private companion object {
        const val USER_START_LEAD_MS = 90_000L
        const val CAPTURE_WAKE_LOCK_MS = 60_000L
        const val TIMER_TICK_MS = 60_000L
    }
}
