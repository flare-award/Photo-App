package com.flareaward.serendip.domain.camera

import com.flareaward.serendip.domain.model.FailureReason

/** Parameters of a single automatic capture. */
data class CaptureRequest(
    /** Epoch millis the capture was triggered at (used for the file name / metadata). */
    val triggeredAt: Long,
)

sealed interface CaptureResult {
    /** The image was captured, written to permanent storage and verified. */
    data class Success(
        val photoUri: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
    ) : CaptureResult

    data class Failure(
        val reason: FailureReason,
        /** Technical details for the debug log; never shown to the user. */
        val details: String? = null,
    ) : CaptureResult
}

/**
 * Takes one photo with the main back camera and stores it permanently. The
 * implementation (CameraX) must never play a shutter sound itself, never use
 * the flash, never record audio and never leave a partially written file behind.
 */
interface CameraCaptureSource {
    suspend fun capture(request: CaptureRequest): CaptureResult
}
