package com.flareaward.serendip.presentation.photos

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.domain.model.CaptureEvent
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface PhotoViewerUiState {
    data object Loading : PhotoViewerUiState

    data class Ready(val event: CaptureEvent, val fileExists: Boolean) : PhotoViewerUiState

    data object NotFound : PhotoViewerUiState

    data object Deleted : PhotoViewerUiState
}

class PhotoViewerViewModel(private val graph: AppGraph, private val eventId: Long) : ViewModel() {

    private val _uiState = MutableStateFlow<PhotoViewerUiState>(PhotoViewerUiState.Loading)
    val uiState: StateFlow<PhotoViewerUiState> = _uiState

    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed: StateFlow<Boolean> = _deleteFailed

    init {
        viewModelScope.launch {
            val event = graph.events.get(eventId)
            val uri = event?.photoUri
            _uiState.value = when {
                event == null || uri == null -> PhotoViewerUiState.NotFound
                else -> PhotoViewerUiState.Ready(event, fileExists = graph.photoStorage.exists(Uri.parse(uri)))
            }
        }
    }

    fun delete() {
        val ready = _uiState.value as? PhotoViewerUiState.Ready ?: return
        viewModelScope.launch {
            val uri = ready.event.photoUri ?: return@launch
            val removed = graph.photoStorage.delete(Uri.parse(uri))
            if (removed || !ready.fileExists) {
                graph.events.clearPhoto(ready.event.id)
                _uiState.value = PhotoViewerUiState.Deleted
            } else {
                AppLog.w("Photo $uri could not be deleted")
                _deleteFailed.value = true
            }
        }
    }

    fun consumeDeleteFailed() {
        _deleteFailed.value = false
    }

    fun shareIntent() = (_uiState.value as? PhotoViewerUiState.Ready)?.event?.photoUri?.let { graph.photoStorage.shareIntent(Uri.parse(it)) }
}
