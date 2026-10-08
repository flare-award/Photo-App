package com.flareaward.serendip.presentation.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.domain.model.CaptureEvent
import com.flareaward.serendip.system.AppLog
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PhotoDay(val date: LocalDate, val photos: List<CaptureEvent>)

data class PhotosUiState(
    val loading: Boolean = true,
    val days: List<PhotoDay> = emptyList(),
    val total: Int = 0,
)

class PhotosViewModel(private val graph: AppGraph) : ViewModel() {

    val uiState: StateFlow<PhotosUiState> = graph.events.photos
        .map { photos ->
            PhotosUiState(
                loading = false,
                total = photos.size,
                days = photos.groupBy { it.localDate }
                    .map { (date, list) -> PhotoDay(date, list) }
                    .sortedByDescending { it.date },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotosUiState())

    /** Drops references to photos the user deleted outside the app (system gallery, file manager). */
    fun reconcileWithMediaStore() {
        viewModelScope.launch {
            try {
                val existing = graph.photoStorage.listExistingPhotoUris()
                val cleared = graph.events.reconcilePhotos(existing)
                if (cleared > 0) AppLog.i("$cleared photo references pointed to deleted files and were cleared")
            } catch (error: Exception) {
                AppLog.w("Photo reconciliation failed", error)
            }
        }
    }
}
