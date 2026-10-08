package com.flareaward.serendip.domain.model

import java.time.LocalDate

/**
 * Why automatic captures are currently paused and what is needed to continue.
 * A paused scheduler never arms wake-ups; it is resumed by the user (opening the
 * app or tapping the "Resume" notification action) or, for [SERVICE_NOT_RUNNING],
 * simply by the service coming back.
 */
enum class PauseReason(val requiresUserAction: Boolean) {
    /** Device rebooted: Android does not allow a camera foreground service to start from BOOT_COMPLETED. */
    REBOOT(requiresUserAction = true),

    /** The app was updated; its foreground service is gone and cannot be restarted from the background. */
    APP_UPDATED(requiresUserAction = true),

    /** The CAMERA runtime permission is missing or has been revoked. */
    CAMERA_PERMISSION_MISSING(requiresUserAction = true),

    /** The OS refused camera access to the background service (while-in-use rules, device policy). */
    CAMERA_ACCESS_BLOCKED(requiresUserAction = true),

    /** Android refused to start / promote the foreground service from the current state. */
    SERVICE_NOT_ALLOWED(requiresUserAction = true),

    /** The device reports no usable back camera. */
    NO_BACK_CAMERA(requiresUserAction = true),

    /** A trigger fired while the foreground service was not running (process killed, service stopped). */
    SERVICE_NOT_RUNNING(requiresUserAction = false),
}

/**
 * Persisted state of the scheduler — the single source of truth for "what happens
 * next". Every field is required to restore the schedule after process death,
 * service restart or reboot without re-generating it needlessly.
 */
data class SchedulerState(
    /** Local calendar date the counters and plan belong to. */
    val dayKey: LocalDate? = null,
    /** Zone id the plan was built in; a change triggers a rebuild. */
    val zoneId: String? = null,
    /** [AutoPhotoSettings.planSignature] the plan was built for. */
    val planSignature: String? = null,
    /** Successful photos on [dayKey]. */
    val photosTakenToday: Int = 0,
    /** Remaining planned moments for [dayKey] (epoch millis, ascending). Only used by [ScheduleMode.FULLY_RANDOM]. */
    val plannedSlots: List<Long> = emptyList(),
    /** The one and only next capture moment (epoch millis). `null` = nothing planned today. */
    val nextCaptureAt: Long? = null,
    /** Epoch millis of the last successful photo. */
    val lastSuccessAt: Long? = null,
    /** Epoch millis of the last attempt of any outcome. */
    val lastAttemptAt: Long? = null,
    /** Failed attempts in a row (drives the short retry policy). */
    val consecutiveFailures: Int = 0,
    /** Set while a capture is running; protects against double captures across triggers / restarts. */
    val captureInProgressSince: Long? = null,
    /** Non-null while captures are paused and a wake-up must not be armed. */
    val pauseReason: PauseReason? = null,
) {
    companion object {
        val EMPTY = SchedulerState()
    }
}
