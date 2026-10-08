package com.flareaward.serendip.domain.repository

import kotlinx.coroutines.flow.Flow

/** Small non-setting flags (first-run state). */
interface AppFlagsRepository {
    val onboardingCompleted: Flow<Boolean>

    suspend fun setOnboardingCompleted(completed: Boolean)
}
