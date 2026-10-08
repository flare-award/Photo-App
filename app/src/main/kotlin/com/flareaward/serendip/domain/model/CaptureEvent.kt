package com.flareaward.serendip.domain.model

import java.time.LocalDate

/** Result of one scheduled capture moment. */
enum class CaptureOutcome {
    /** A photo was taken, verified and stored permanently. */
    SUCCESS,

    /** The attempt failed; see [CaptureEvent.failureReason]. */
    FAILED,

    /** Skipped because the screen was off and the user asked not to shoot in that case. */
    SKIPPED_SCREEN_OFF,

    /** A planned moment passed while the app could not run (device off, process killed, ...). Never caught up. */
    MISSED,
}

/** Human-classifiable reasons of a failed attempt. Technical details go to the debug log only. */
enum class FailureReason(val retryable: Boolean, val pauseReason: PauseReason? = null) {
    CAMERA_PERMISSION_MISSING(retryable = false, pauseReason = PauseReason.CAMERA_PERMISSION_MISSING),
    NO_BACK_CAMERA(retryable = false, pauseReason = PauseReason.NO_BACK_CAMERA),
    CAMERA_ACCESS_BLOCKED(retryable = false, pauseReason = PauseReason.CAMERA_ACCESS_BLOCKED),
    CAMERA_IN_USE(retryable = true),
    CAMERA_UNAVAILABLE(retryable = true),
    CAMERA_ERROR(retryable = true),
    CAPTURE_TIMEOUT(retryable = true),
    STORAGE_LOW(retryable = true),
    STORAGE_WRITE_FAILED(retryable = true),
    IMAGE_INVALID(retryable = true),
    UNKNOWN(retryable = true),
}

/** One entry of the "History" screen. Successful entries are also the "Photos" gallery. */
data class CaptureEvent(
    val id: Long = 0L,
    /** Epoch millis of the attempt. */
    val timestamp: Long,
    /** Zone the device was in at [timestamp]; used to render local date/time consistently. */
    val zoneId: String,
    /** Local calendar date of the attempt. */
    val localDate: LocalDate,
    val outcome: CaptureOutcome,
    /** MediaStore content URI of the stored image (SUCCESS only; `null` once the user deleted the photo). */
    val photoUri: String? = null,
    val failureReason: FailureReason? = null,
    /** For [CaptureOutcome.MISSED]: how many planned moments the entry summarises. */
    val missedCount: Int = 1,
)
