package com.flareaward.serendip.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.domain.model.AutoPhotoSettings
import com.flareaward.serendip.domain.model.ScheduleMode
import com.flareaward.serendip.domain.model.SchedulerState
import com.flareaward.serendip.domain.model.SystemStatus
import com.flareaward.serendip.service.AutoCaptureService
import com.flareaward.serendip.service.ServiceController
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AutoPhotoSettings = AutoPhotoSettings(),
    val scheduler: SchedulerState = SchedulerState.EMPTY,
    val system: SystemStatus = SystemStatus.UNKNOWN,
    val serviceRunning: Boolean = false,
)

sealed interface SettingsMessage {
    data object CameraPermissionNeeded : SettingsMessage

    data object ServiceStartNotAllowed : SettingsMessage
}

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _messages = MutableSharedFlow<SettingsMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<SettingsMessage> = _messages

    val uiState: StateFlow<SettingsUiState> = combine(
        graph.settings.settings,
        graph.schedulerState.state,
        graph.systemStatus.status,
        AutoCaptureService.isRunning,
    ) { settings, scheduler, system, running ->
        SettingsUiState(loading = false, settings = settings, scheduler = scheduler, system = system, serviceRunning = running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refreshSystemStatus() {
        graph.systemStatus.refresh()
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                when (graph.serviceController.enable()) {
                    ServiceController.StartOutcome.NO_CAMERA_PERMISSION -> _messages.tryEmit(SettingsMessage.CameraPermissionNeeded)
                    ServiceController.StartOutcome.NOT_ALLOWED -> _messages.tryEmit(SettingsMessage.ServiceStartNotAllowed)
                    else -> Unit
                }
            } else {
                graph.serviceController.disable()
            }
        }
    }

    fun setDailyLimit(limit: Int) = update { it.copy(dailyPhotoLimit = limit) }

    fun setScheduleMode(mode: ScheduleMode) = update { it.copy(scheduleMode = mode) }

    /** Keeps min <= max by pushing the other bound when needed. */
    fun setMinInterval(minutes: Int) = update {
        it.copy(minIntervalMinutes = minutes, maxIntervalMinutes = maxOf(minutes, it.maxIntervalMinutes))
    }

    fun setMaxInterval(minutes: Int) = update {
        it.copy(maxIntervalMinutes = minutes, minIntervalMinutes = minOf(minutes, it.minIntervalMinutes))
    }

    fun setNotifyOnSuccess(enabled: Boolean) = update { it.copy(notifyOnSuccess = enabled) }

    fun setSkipWhenScreenOff(enabled: Boolean) = update { it.copy(skipWhenScreenOff = enabled) }

    /** Restart the service from the visible app (e.g. after it was stopped by the system). */
    fun restartService() {
        viewModelScope.launch {
            when (graph.serviceController.ensureRunningIfEnabled()) {
                ServiceController.StartOutcome.NO_CAMERA_PERMISSION -> _messages.tryEmit(SettingsMessage.CameraPermissionNeeded)
                ServiceController.StartOutcome.NOT_ALLOWED -> _messages.tryEmit(SettingsMessage.ServiceStartNotAllowed)
                else -> Unit
            }
        }
    }

    private fun update(transform: (AutoPhotoSettings) -> AutoPhotoSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }
}
