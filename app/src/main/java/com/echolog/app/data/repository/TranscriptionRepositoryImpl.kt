package com.echolog.app.data.repository

import androidx.room.withTransaction
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.data.local.EchoDatabase
import com.echolog.app.data.local.dao.AudioSegmentDao
import com.echolog.app.data.local.dao.TranscriptSegmentDao
import com.echolog.app.data.mapper.toDomain
import com.echolog.app.data.mapper.toEntity
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.model.TranscriptStatus
import com.echolog.app.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TranscriptionRepository] 实现（架构 3.2）。
 *
 * upsert 语义：同事务内「先删后插」，保证重转写幂等（无重复行）。
 */
@Singleton
class TranscriptionRepositoryImpl @Inject constructor(
    private val segmentDao: AudioSegmentDao,
    private val transcriptDao: TranscriptSegmentDao,
    private val database: EchoDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TranscriptionRepository {

    override fun observeTranscriptsForDay(dayId: String): Flow<List<TranscriptSegment>> =
        transcriptDao.observeForDay(dayId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getTranscriptsForDay(dayId: String): List<TranscriptSegment> =
        withContext(ioDispatcher) {
            transcriptDao.getForDay(dayId).map { it.toDomain() }
        }

    override suspend fun upsertTranscripts(segmentId: Long, items: List<TranscriptSegment>) {
        // withTransaction 自带事务执行器调度，无需再包 withContext
        database.withTransaction {
            transcriptDao.deleteForSegment(segmentId)
            transcriptDao.insertAll(items.map { it.toEntity(segmentId) })
        }
    }

    override suspend fun updateSegmentTranscriptStatus(
        segmentId: Long,
        status: TranscriptStatus,
        error: String?,
    ) = withContext(ioDispatcher) {
        segmentDao.updateTranscriptStatus(segmentId, status, error)
    }

    override suspend fun getPendingSegments(
        dayId: String,
        limit: Int,
    ): List<AudioSegment> = withContext(ioDispatcher) {
        segmentDao.getPendingSegments(dayId, limit).map { it.toDomain() }
    }

    override suspend fun getAllPendingSegments(limit: Int): List<AudioSegment> =
        withContext(ioDispatcher) {
            segmentDao.getAllPendingSegments(limit).map { it.toDomain() }
        }

    override suspend fun searchTranscripts(
        keyword: String,
        limit: Int,
    ): List<TranscriptSegment> = withContext(ioDispatcher) {
        transcriptDao.search(keyword.trim(), limit).map { it.toDomain() }
    }
}
