package com.flareaward.serendip.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.domain.model.SchedulerState
import com.flareaward.serendip.domain.repository.SchedulerStateRepository
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.schedulerDataStore: DataStore<Preferences> by preferencesDataStore(name = "serendip_scheduler")

/**
 * Persists [SchedulerState] in its own DataStore file so that scheduler writes
 * never race with settings writes. This file is the single source of truth for
 * "what happens next"; it survives process death, service restarts and reboots.
 */
class DataStoreSchedulerStateRepository(context: Context) : SchedulerStateRepository {

    private val store: DataStore<Preferences> = context.applicationContext.schedulerDataStore

    override val state: Flow<SchedulerState> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toState() }
        .distinctUntilChanged()

    override suspend fun current(): SchedulerState = state.first()

    override suspend fun save(state: SchedulerState) {
        store.edit { it.write(state) }
    }

    override suspend fun update(transform: (SchedulerState) -> SchedulerState): SchedulerState =
        store.edit { prefs -> prefs.write(transform(prefs.toState())) }.toState()

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: SchedulerState) {
        clear()
        s.dayKey?.let { this[Keys.DAY_KEY] = it.toString() }
        s.zoneId?.let { this[Keys.ZONE_ID] = it }
        s.planSignature?.let { this[Keys.PLAN_SIGNATURE] = it }
        this[Keys.PHOTOS_TAKEN_TODAY] = s.photosTakenToday
        this[Keys.PLANNED_SLOTS] = s.plannedSlots.joinToString(",")
        s.nextCaptureAt?.let { this[Keys.NEXT_CAPTURE_AT] = it }
        s.lastSuccessAt?.let { this[Keys.LAST_SUCCESS_AT] = it }
        s.lastAttemptAt?.let { this[Keys.LAST_ATTEMPT_AT] = it }
        this[Keys.CONSECUTIVE_FAILURES] = s.consecutiveFailures
        s.captureInProgressSince?.let { this[Keys.CAPTURE_IN_PROGRESS_SINCE] = it }
        s.pauseReason?.let { this[Keys.PAUSE_REASON] = it.name }
    }

    private fun Preferences.toState(): SchedulerState = SchedulerState(
        dayKey = this[Keys.DAY_KEY]?.let { raw ->
            try {
                LocalDate.parse(raw)
            } catch (_: DateTimeParseException) {
                null
            }
        },
        zoneId = this[Keys.ZONE_ID],
        planSignature = this[Keys.PLAN_SIGNATURE],
        photosTakenToday = this[Keys.PHOTOS_TAKEN_TODAY] ?: 0,
        plannedSlots = this[Keys.PLANNED_SLOTS]
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.sorted()
            ?: emptyList(),
        nextCaptureAt = this[Keys.NEXT_CAPTURE_AT],
        lastSuccessAt = this[Keys.LAST_SUCCESS_AT],
        lastAttemptAt = this[Keys.LAST_ATTEMPT_AT],
        consecutiveFailures = this[Keys.CONSECUTIVE_FAILURES] ?: 0,
        captureInProgressSince = this[Keys.CAPTURE_IN_PROGRESS_SINCE],
        pauseReason = this[Keys.PAUSE_REASON]?.let { raw -> PauseReason.entries.firstOrNull { it.name == raw } },
    )

    private object Keys {
        val DAY_KEY = stringPreferencesKey("day_key")
        val ZONE_ID = stringPreferencesKey("zone_id")
        val PLAN_SIGNATURE = stringPreferencesKey("plan_signature")
        val PHOTOS_TAKEN_TODAY = intPreferencesKey("photos_taken_today")
        val PLANNED_SLOTS = stringPreferencesKey("planned_slots")
        val NEXT_CAPTURE_AT = longPreferencesKey("next_capture_at")
        val LAST_SUCCESS_AT = longPreferencesKey("last_success_at")
        val LAST_ATTEMPT_AT = longPreferencesKey("last_attempt_at")
        val CONSECUTIVE_FAILURES = intPreferencesKey("consecutive_failures")
        val CAPTURE_IN_PROGRESS_SINCE = longPreferencesKey("capture_in_progress_since")
        val PAUSE_REASON = stringPreferencesKey("pause_reason")
    }
}
