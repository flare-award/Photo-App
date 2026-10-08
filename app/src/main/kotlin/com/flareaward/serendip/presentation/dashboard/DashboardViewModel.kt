package com.flareaward.serendip.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.domain.model.AutoPhotoSettings
import com.flareaward.serendip.domain.model.CaptureEvent
import com.flareaward.serendip.domain.model.SchedulerState
import com.flareaward.serendip.domain.model.SystemStatus
import com.flareaward.serendip.scheduler.EngineStatus
import com.flareaward.serendip.service.AutoCaptureService
import com.flareaward.serendip.service.ServiceController
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val loading: Boolean = true,
    val settings: AutoPhotoSettings = AutoPhotoSettings(),
    val scheduler: SchedulerState = SchedulerState.EMPTY,
    val system: SystemStatus = SystemStatus.UNKNOWN,
    val serviceRunning: Boolean = false,
    val engine: EngineStatus = EngineStatus.Stopped,
    val latestPhoto: CaptureEvent? = null,
    val now: Long = System.currentTimeMillis(),
)

/** One-off messages for the snackbar. */
sealed interface DashboardMessage {
    data object CameraPermissionNeeded : DashboardMessage

    data object ServiceStartNotAllowed : DashboardMessage
}

class DashboardViewModel(private val graph: AppGraph) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    private val _messages = MutableSharedFlow<DashboardMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<DashboardMessage> = _messages

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(graph.settings.settings, graph.schedulerState.state, graph.systemStatus.status) { s, st, sys -> Triple(s, st, sys) },
        combine(AutoCaptureService.isRunning, graph.engine.status, graph.events.latestPhoto) { r, e, p -> Triple(r, e, p) },
        ticker,
    ) { (settings, scheduler, system), (running, engine, latest), now ->
        DashboardUiState(
            loading = false,
            settings = settings,
            scheduler = scheduler,
            system = system,
            serviceRunning = running,
            engine = engine,
            latestPhoto = latest,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** The big switch. Enabling starts the foreground service — legal here because the activity is visible. */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                when (graph.serviceController.enable()) {
                    ServiceController.StartOutcome.NO_CAMERA_PERMISSION -> _messages.tryEmit(DashboardMessage.CameraPermissionNeeded)
                    ServiceController.StartOutcome.NOT_ALLOWED -> _messages.tryEmit(DashboardMessage.ServiceStartNotAllowed)
                    else -> Unit
                }
            } else {
                graph.serviceController.disable()
            }
        }
    }

    /** "Resume" after a pause that needs the user; also re-arms a stopped service. */
    fun resume() {
        viewModelScope.launch {
            when (graph.serviceController.ensureRunningIfEnabled()) {
                ServiceController.StartOutcome.NO_CAMERA_PERMISSION -> _messages.tryEmit(DashboardMessage.CameraPermissionNeeded)
                ServiceController.StartOutcome.NOT_ALLOWED -> _messages.tryEmit(DashboardMessage.ServiceStartNotAllowed)
                else -> Unit
            }
        }
    }

    /** Called on resume of the screen: permissions and battery state may have changed in Settings. */
    fun refreshSystemStatus() {
        graph.systemStatus.refresh()
        viewModelScope.launch { graph.serviceController.ensureRunningIfEnabled() }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
