package com.flareaward.serendip.presentation.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.domain.model.SystemStatus
import com.flareaward.serendip.service.ServiceController
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {

    val system: StateFlow<SystemStatus> = graph.systemStatus.status

    /** Explicit confirmation that automatic photos are wanted; survives configuration changes via the ViewModel. */
    var consentGiven: Boolean by mutableStateOf(false)

    fun refresh() = graph.systemStatus.refresh()

    /**
     * Marks onboarding as done and, if requested, turns automatic photos on.
     * Enabling happens from the visible Activity, which is exactly the state in
     * which Android allows a camera foreground service to be started.
     */
    fun complete(enableNow: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            graph.appFlags.setOnboardingCompleted(true)
            if (enableNow) {
                val outcome = graph.serviceController.enable()
                if (outcome != ServiceController.StartOutcome.STARTED && outcome != ServiceController.StartOutcome.ALREADY_RUNNING) {
                    AppLog.w("Onboarding: auto photos requested but service start outcome is $outcome")
                }
            }
            onDone()
        }
    }
}
