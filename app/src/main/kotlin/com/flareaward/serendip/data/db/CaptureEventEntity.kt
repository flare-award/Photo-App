package com.flareaward.serendip.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flareaward.serendip.domain.model.CaptureEvent
import com.flareaward.serendip.domain.model.CaptureOutcome
import com.flareaward.serendip.domain.model.FailureReason
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Entity(
    tableName = "capture_events",
    indices = [Index(value = ["timestamp"]), Index(value = ["localDate", "outcome"])],
)
data class CaptureEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val zoneId: String,
    /** ISO-8601 local date, e.g. 2026-10-08. */
    val localDate: String,
    val outcome: String,
    val photoUri: String?,
    val failureReason: String?,
    @ColumnInfo(defaultValue = "1") val missedCount: Int = 1,
)

/** Projection used to reconcile stored photo references with MediaStore. */
data class PhotoRef(val id: Long, val photoUri: String)

fun CaptureEvent.toEntity(): CaptureEventEntity = CaptureEventEntity(
    id = id,
    timestamp = timestamp,
    zoneId = zoneId,
    localDate = localDate.toString(),
    outcome = outcome.name,
    photoUri = photoUri,
    failureReason = failureReason?.name,
    missedCount = missedCount,
)

fun CaptureEventEntity.toDomain(): CaptureEvent = CaptureEvent(
    id = id,
    timestamp = timestamp,
    zoneId = zoneId,
    localDate = try {
        LocalDate.parse(localDate)
    } catch (_: DateTimeParseException) {
        LocalDate.ofEpochDay(timestamp / 86_400_000L)
    },
    outcome = CaptureOutcome.entries.firstOrNull { it.name == outcome } ?: CaptureOutcome.FAILED,
    photoUri = photoUri,
    failureReason = failureReason?.let { raw -> FailureReason.entries.firstOrNull { it.name == raw } },
    missedCount = missedCount,
)
