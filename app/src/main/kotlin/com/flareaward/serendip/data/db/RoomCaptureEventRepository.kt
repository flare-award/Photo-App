package com.flareaward.serendip.data.db

import com.flareaward.serendip.domain.model.CaptureEvent
import com.flareaward.serendip.domain.repository.CaptureEventRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomCaptureEventRepository(private val dao: CaptureEventDao) : CaptureEventRepository {

    override val events: Flow<List<CaptureEvent>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override val photos: Flow<List<CaptureEvent>> =
        dao.observePhotos().map { list -> list.map { it.toDomain() } }

    override val latestPhoto: Flow<CaptureEvent?> =
        dao.observeLatestPhoto().map { it?.toDomain() }

    override suspend fun record(event: CaptureEvent): Long = dao.insert(event.toEntity())

    override suspend fun get(id: Long): CaptureEvent? = dao.getById(id)?.toDomain()

    override suspend fun countSuccessful(localDate: LocalDate): Int = dao.countSuccessful(localDate.toString())

    override suspend fun clearPhoto(eventId: Long) {
        dao.clearPhoto(eventId)
    }

    override suspend fun reconcilePhotos(existingUris: Set<String>): Int {
        val gone = dao.photoRefs().filter { it.photoUri !in existingUris }.map { it.id }
        if (gone.isEmpty()) return 0
        return gone.chunked(500).sumOf { dao.clearPhotos(it) }
    }

    override suspend fun pruneOlderThan(olderThanMillis: Long): Int = dao.deleteOlderThan(olderThanMillis)
}
