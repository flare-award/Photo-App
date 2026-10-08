package com.flareaward.serendip.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flareaward.serendip.domain.model.AutoPhotoSettings
import com.flareaward.serendip.domain.model.ScheduleMode
import com.flareaward.serendip.domain.repository.AppFlagsRepository
import com.flareaward.serendip.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "serendip_settings")

/**
 * User settings and first-run flags in a Preferences DataStore. Every write is
 * atomic and every emission is sanitized, so the rest of the app never sees an
 * invalid combination (e.g. min interval > max interval).
 */
class DataStoreSettingsRepository(context: Context) : SettingsRepository, AppFlagsRepository {

    private val store: DataStore<Preferences> = context.applicationContext.settingsDataStore

    private val safeData: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    override val settings: Flow<AutoPhotoSettings> =
        safeData.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun current(): AutoPhotoSettings = settings.first()

    override suspend fun update(transform: (AutoPhotoSettings) -> AutoPhotoSettings): AutoPhotoSettings {
        val updated = store.edit { prefs ->
            val next = transform(prefs.toSettings()).sanitized()
            prefs[Keys.ENABLED] = next.enabled
            prefs[Keys.DAILY_LIMIT] = next.dailyPhotoLimit
            prefs[Keys.MODE] = next.scheduleMode.name
            prefs[Keys.MIN_INTERVAL] = next.minIntervalMinutes
            prefs[Keys.MAX_INTERVAL] = next.maxIntervalMinutes
            prefs[Keys.NOTIFY_ON_SUCCESS] = next.notifyOnSuccess
            prefs[Keys.SKIP_WHEN_SCREEN_OFF] = next.skipWhenScreenOff
        }
        return updated.toSettings()
    }

    override val onboardingCompleted: Flow<Boolean> =
        safeData.map { it[Keys.ONBOARDING_COMPLETED] ?: false }.distinctUntilChanged()

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    private fun Preferences.toSettings(): AutoPhotoSettings {
        val defaults = AutoPhotoSettings()
        return AutoPhotoSettings(
            enabled = this[Keys.ENABLED] ?: defaults.enabled,
            dailyPhotoLimit = this[Keys.DAILY_LIMIT] ?: defaults.dailyPhotoLimit,
            scheduleMode = this[Keys.MODE]?.let { raw -> ScheduleMode.entries.firstOrNull { it.name == raw } }
                ?: defaults.scheduleMode,
            minIntervalMinutes = this[Keys.MIN_INTERVAL] ?: defaults.minIntervalMinutes,
            maxIntervalMinutes = this[Keys.MAX_INTERVAL] ?: defaults.maxIntervalMinutes,
            notifyOnSuccess = this[Keys.NOTIFY_ON_SUCCESS] ?: defaults.notifyOnSuccess,
            skipWhenScreenOff = this[Keys.SKIP_WHEN_SCREEN_OFF] ?: defaults.skipWhenScreenOff,
        ).sanitized()
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("auto_photos_enabled")
        val DAILY_LIMIT = intPreferencesKey("daily_photo_limit")
        val MODE = stringPreferencesKey("schedule_mode")
        val MIN_INTERVAL = intPreferencesKey("min_interval_minutes")
        val MAX_INTERVAL = intPreferencesKey("max_interval_minutes")
        val NOTIFY_ON_SUCCESS = booleanPreferencesKey("notify_on_success")
        val SKIP_WHEN_SCREEN_OFF = booleanPreferencesKey("skip_when_screen_off")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
