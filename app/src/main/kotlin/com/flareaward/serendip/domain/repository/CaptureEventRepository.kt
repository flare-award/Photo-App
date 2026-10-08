package com.flareaward.serendip.domain.repository

import com.flareaward.serendip.domain.model.CaptureEvent
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface CaptureEventRepository {
    /** All events, newest first. */
    val events: Flow<List<CaptureEvent>>

    /** Successful captures that still have a photo, newest first. */
    val photos: Flow<List<CaptureEvent>>

    /** The newest successful capture that still has a photo, or `null`. */
    val latestPhoto: Flow<CaptureEvent?>

    suspend fun record(event: CaptureEvent): Long

    suspend fun get(id: Long): CaptureEvent?

    suspend fun countSuccessful(localDate: LocalDate): Int

    /** Marks the photo of [eventId] as gone (the history entry itself stays). */
    suspend fun clearPhoto(eventId: Long)

    /** Removes photo references whose URI is not in [existingUris] (deleted outside the app). Returns how many were cleared. */
    suspend fun reconcilePhotos(existingUris: Set<String>): Int

    /**
     * Deletes failed / skipped / missed events older than [olderThanMillis] to
     * keep the history bounded. Successful captures (the gallery) are kept forever
     * and photo files are never touched by this.
     */
    suspend fun pruneOlderThan(olderThanMillis: Long): Int
}
