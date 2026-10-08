package com.flareaward.serendip.domain.repository

import com.flareaward.serendip.domain.model.SchedulerState
import kotlinx.coroutines.flow.Flow

interface SchedulerStateRepository {
    val state: Flow<SchedulerState>

    suspend fun current(): SchedulerState

    suspend fun save(state: SchedulerState)

    /** Atomic read-modify-write. */
    suspend fun update(transform: (SchedulerState) -> SchedulerState): SchedulerState
}
