package com.flareaward.serendip.presentation.common

import androidx.annotation.StringRes
import com.flareaward.serendip.R
import com.flareaward.serendip.domain.model.FailureReason
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.domain.model.ScheduleMode

/** Maps domain enums to the human-readable strings shown in the UI. No technical text ever leaks here. */
object HumanText {

    @StringRes
    fun failureReason(reason: FailureReason?): Int = when (reason) {
        FailureReason.CAMERA_PERMISSION_MISSING -> R.string.failure_camera_permission
        FailureReason.NO_BACK_CAMERA -> R.string.failure_no_camera
        FailureReason.CAMERA_ACCESS_BLOCKED -> R.string.failure_camera_blocked
        FailureReason.CAMERA_IN_USE -> R.string.failure_camera_in_use
        FailureReason.CAMERA_UNAVAILABLE -> R.string.failure_camera_unavailable
        FailureReason.CAMERA_ERROR -> R.string.failure_camera_error
        FailureReason.CAPTURE_TIMEOUT -> R.string.failure_timeout
        FailureReason.STORAGE_LOW -> R.string.failure_storage_low
        FailureReason.STORAGE_WRITE_FAILED -> R.string.failure_storage_write
        FailureReason.IMAGE_INVALID -> R.string.failure_image_invalid
        FailureReason.UNKNOWN, null -> R.string.failure_unknown
    }

    @StringRes
    fun pauseReasonTitle(reason: PauseReason): Int = when (reason) {
        PauseReason.REBOOT -> R.string.pause_title_reboot
        PauseReason.APP_UPDATED -> R.string.pause_title_app_updated
        PauseReason.CAMERA_PERMISSION_MISSING -> R.string.pause_title_camera_permission
        PauseReason.CAMERA_ACCESS_BLOCKED -> R.string.pause_title_camera_blocked
        PauseReason.SERVICE_NOT_ALLOWED -> R.string.pause_title_service_not_allowed
        PauseReason.NO_BACK_CAMERA -> R.string.pause_title_no_camera
        PauseReason.SERVICE_NOT_RUNNING -> R.string.pause_title_service_not_running
    }

    @StringRes
    fun pauseReasonText(reason: PauseReason): Int = when (reason) {
        PauseReason.REBOOT -> R.string.pause_reason_reboot
        PauseReason.APP_UPDATED -> R.string.pause_reason_app_updated
        PauseReason.CAMERA_PERMISSION_MISSING -> R.string.pause_reason_camera_permission
        PauseReason.CAMERA_ACCESS_BLOCKED -> R.string.pause_reason_camera_blocked
        PauseReason.SERVICE_NOT_ALLOWED -> R.string.pause_reason_service_not_allowed
        PauseReason.NO_BACK_CAMERA -> R.string.pause_reason_no_camera
        PauseReason.SERVICE_NOT_RUNNING -> R.string.pause_reason_service_not_running
    }

    @StringRes
    fun scheduleMode(mode: ScheduleMode): Int = when (mode) {
        ScheduleMode.FULLY_RANDOM -> R.string.mode_fully_random
        ScheduleMode.RANDOM_INTERVAL -> R.string.mode_random_interval
    }
}
