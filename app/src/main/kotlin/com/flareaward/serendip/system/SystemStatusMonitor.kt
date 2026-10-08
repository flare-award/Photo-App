package com.flareaward.serendip.system

import com.flareaward.serendip.domain.model.SystemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Current permission / battery state as a [StateFlow]. Android offers no
 * callbacks for most of these, so screens call [refresh] when they resume.
 */
class SystemStatusMonitor(
    private val permissions: PermissionChecker,
    private val battery: BatteryOptimizationChecker,
) {
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SystemStatus> = _status

    fun refresh(): SystemStatus = read().also { _status.value = it }

    private fun read(): SystemStatus = SystemStatus(
        cameraPermissionGranted = permissions.hasCameraPermission(),
        notificationsAllowed = permissions.notificationsAllowed(),
        notificationPermissionRequired = permissions.notificationPermissionRequired,
        backgroundRestriction = battery.status(),
        hasBackCamera = permissions.hasBackCamera(),
    )
}
