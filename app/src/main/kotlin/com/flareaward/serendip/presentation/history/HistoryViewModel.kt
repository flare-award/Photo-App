package com.flareaward.serendip.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.domain.model.CaptureEvent
import com.flareaward.serendip.domain.model.CaptureOutcome
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One row of the history. Consecutive screen-off skips of the same day are
 * folded into a single row so a night does not produce a wall of entries.
 */
data class HistoryItem(
    val id: Long,
    val event: CaptureEvent,
    /** For folded rows: how many events the row represents and the time span they cover. */
    val count: Int = 1,
    val firstTimestamp: Long = event.timestamp,
)

data class HistoryDay(val date: LocalDate, val items: List<HistoryItem>, val successes: Int)

data class HistoryUiState(
    val loading: Boolean = true,
    val days: List<HistoryDay> = emptyList(),
)

class HistoryViewModel(graph: AppGraph) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = graph.events.events
        .map { events ->
            HistoryUiState(
                loading = false,
                days = events.groupBy { it.localDate }
                    .map { (date, list) ->
                        HistoryDay(
                            date = date,
                            items = fold(list),
                            successes = list.count { it.outcome == CaptureOutcome.SUCCESS },
                        )
                    }
                    .sortedByDescending { it.date },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** [events] are newest first; folds runs of SKIPPED_SCREEN_OFF. */
    private fun fold(events: List<CaptureEvent>): List<HistoryItem> {
        val result = ArrayList<HistoryItem>(events.size)
        var run: MutableList<CaptureEvent>? = null
        fun flush() {
            run?.let { r ->
                val newest = r.first()
                result += HistoryItem(id = newest.id, event = newest, count = r.size, firstTimestamp = r.last().timestamp)
            }
            run = null
        }
        for (event in events) {
            if (event.outcome == CaptureOutcome.SKIPPED_SCREEN_OFF) {
                val current = run
                if (current == null) run = mutableListOf(event) else current += event
            } else {
                flush()
                result += HistoryItem(id = event.id, event = event)
            }
        }
        flush()
        return result
    }
}
