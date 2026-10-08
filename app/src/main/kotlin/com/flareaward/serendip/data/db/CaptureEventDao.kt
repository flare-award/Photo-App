package com.flareaward.serendip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureEventDao {

    @Insert
    suspend fun insert(event: CaptureEventEntity): Long

    @Query("SELECT * FROM capture_events ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<CaptureEventEntity>>

    @Query(
        "SELECT * FROM capture_events WHERE outcome = 'SUCCESS' AND photoUri IS NOT NULL " +
            "ORDER BY timestamp DESC, id DESC",
    )
    fun observePhotos(): Flow<List<CaptureEventEntity>>

    @Query(
        "SELECT * FROM capture_events WHERE outcome = 'SUCCESS' AND photoUri IS NOT NULL " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1",
    )
    fun observeLatestPhoto(): Flow<CaptureEventEntity?>

    @Query("SELECT * FROM capture_events WHERE id = :id")
    suspend fun getById(id: Long): CaptureEventEntity?

    @Query("SELECT COUNT(*) FROM capture_events WHERE outcome = 'SUCCESS' AND localDate = :localDate")
    suspend fun countSuccessful(localDate: String): Int

    @Query("UPDATE capture_events SET photoUri = NULL WHERE id = :id")
    suspend fun clearPhoto(id: Long): Int

    @Query("SELECT id, photoUri FROM capture_events WHERE photoUri IS NOT NULL")
    suspend fun photoRefs(): List<PhotoRef>

    @Query("UPDATE capture_events SET photoUri = NULL WHERE id IN (:ids)")
    suspend fun clearPhotos(ids: List<Long>): Int

    /** Only non-photo rows are pruned: successful captures are the gallery and are kept forever. */
    @Query("DELETE FROM capture_events WHERE timestamp < :olderThan AND outcome != 'SUCCESS'")
    suspend fun deleteOlderThan(olderThan: Long): Int
}
