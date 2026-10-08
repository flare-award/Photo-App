package com.flareaward.serendip.domain.repository

import com.flareaward.serendip.domain.model.AutoPhotoSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    /** Always emits sanitized settings; the first emission reflects persisted values. */
    val settings: Flow<AutoPhotoSettings>

    suspend fun current(): AutoPhotoSettings

    /** Atomically transforms the persisted settings. The result is sanitized before being stored. */
    suspend fun update(transform: (AutoPhotoSettings) -> AutoPhotoSettings): AutoPhotoSettings
}
