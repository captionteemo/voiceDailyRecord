package com.echolog.app.data.repository

import androidx.room.withTransaction
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.FileUtils
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.data.local.EchoDatabase
import com.echolog.app.data.local.dao.AudioSegmentDao
import com.echolog.app.data.local.dao.DaySummaryDao
import com.echolog.app.data.local.dao.MarkerDao
import com.echolog.app.data.local.dao.RecordingDayDao
import com.echolog.app.data.local.dao.TodoItemDao
import com.echolog.app.data.local.dao.TranscriptSegmentDao
import com.echolog.app.data.local.entity.RecordingDayEntity
import com.echolog.app.data.mapper.toDomain
import com.echolog.app.data.mapper.toEntity
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.repository.RecordingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RecordingRepository] 实现（架构 3.2）：Room + 文件系统。
 *
 * - 实体 ↔ 领域映射经 `data/mapper/EntityMappers.kt`（单一事实源）；
 * - 跨 DAO 的写操作（按天删除）包在 [EchoDatabase.withTransaction] 中；
 * - Flow 查询由 Room 在查询线程发射，`map` 在收集线程执行，无需额外调度。
 */
@Singleton
class RecordingRepositoryImpl @Inject constructor(
    private val dayDao: RecordingDayDao,
    private val segmentDao: AudioSegmentDao,
    private val markerDao: MarkerDao,
    private val transcriptDao: TranscriptSegmentDao,
    private val summaryDao: DaySummaryDao,
    private val todoDao: TodoItemDao,
    private val database: EchoDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : RecordingRepository {

    override fun observeDays(): Flow<List<RecordingDay>> =
        dayDao.observeDays().map { rows -> rows.map { it.toDomain() } }

    override fun observeDay(dayId: String): Flow<RecordingDay?> =
        dayDao.observeDay(dayId).map { it?.toDomain() }

    override suspend fun getDay(dayId: String): RecordingDay? = withContext(ioDispatcher) {
        dayDao.getDay(dayId)?.toDomain()
    }

    override suspend fun createOrResumeDay(
        dayId: String,
        mode: RecordingMode,
    ): RecordingDay = withContext(ioDispatcher) {
        val now = TimeUtils.currentEpochMs()
        val existing = dayDao.getDay(dayId)
        val entity = when {
            existing == null -> RecordingDayEntity(
                dayId = dayId,
                state = DayState.MONITORING,
                monitorStartedAt = now,
                mode = mode,
            )
            // 断点恢复：保留原有起始时间与模式（当天已用过的模式不因设置变更而漂移）
            existing.state == DayState.MONITORING -> existing
            // IDLE 待机：恢复监听，采用当前模式
            existing.state == DayState.IDLE -> existing.copy(
                state = DayState.MONITORING,
                monitorStartedAt = if (existing.monitorStartedAt > 0L) existing.monitorStartedAt else now,
                mode = mode,
            )
            // SETTLING/DONE：当天重复记录，以新起始时间与新模式重新监听
            else -> existing.copy(state = DayState.MONITORING, monitorStartedAt = now, mode = mode)
        }
        dayDao.upsert(entity)
        entity.toDomain()
    }

    override suspend fun updateDayState(dayId: String, state: DayState) = withContext(ioDispatcher) {
        val current = dayDao.getDay(dayId) ?: return@withContext
        if (current.state == DayState.MONITORING && state != DayState.MONITORING) {
            // 迁出监听态时回写结束时间（其余字段不动，用 upsert 保证复合更新原子性）
            dayDao.upsert(
                current.copy(state = state, monitorEndedAt = TimeUtils.currentEpochMs()),
            )
        } else {
            dayDao.updateState(dayId, state)
        }
    }

    override suspend fun updateSettleProgress(
        dayId: String,
        progressPercent: Int,
        error: String?,
    ) = withContext(ioDispatcher) {
        dayDao.updateSettle(dayId, progressPercent.coerceIn(0, 100), error)
    }

    override suspend fun updateSummaryStatus(dayId: String, status: SummaryStatus) =
        withContext(ioDispatcher) {
            dayDao.updateSummaryStatus(dayId, status)
        }

    override suspend fun updateMergeResult(
        dayId: String,
        totalRecordMs: Long,
        mergedAudioPath: String?,
    ) = withContext(ioDispatcher) {
        dayDao.updateMergeResult(dayId, totalRecordMs.coerceAtLeast(0L), mergedAudioPath)
    }

    override fun observeSegmentsForDay(dayId: String): Flow<List<AudioSegment>> =
        segmentDao.observeSegmentsForDay(dayId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getSegmentsForDay(dayId: String): List<AudioSegment> =
        withContext(ioDispatcher) {
            segmentDao.getSegmentsForDay(dayId).map { it.toDomain() }
        }

    override suspend fun insertSegment(dayId: String, startMs: Long, filePath: String): Long =
        withContext(ioDispatcher) {
            segmentDao.insert(
                AudioSegment(dayId = dayId, filePath = filePath, startMs = startMs).toEntity(),
            )
        }

    override suspend fun finishSegment(segmentId: Long, endMs: Long, sizeBytes: Long) =
        withContext(ioDispatcher) {
            val segment = segmentDao.getSegment(segmentId) ?: return@withContext
            // 防御时钟回拨：结束时间不得早于起始时间（DB 会派生 duration）
            val safeEndMs = if (endMs >= segment.startMs) endMs else segment.startMs
            segmentDao.finishSegment(segmentId, safeEndMs, sizeBytes.coerceAtLeast(0L))
        }

    override fun observeMarkersForDay(dayId: String): Flow<List<Marker>> =
        markerDao.observeMarkersForDay(dayId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getMarkersForDay(dayId: String): List<Marker> =
        withContext(ioDispatcher) {
            markerDao.getMarkersForDay(dayId).map { it.toDomain() }
        }

    override suspend fun insertMarker(dayId: String, atMs: Long, note: String): Long =
        withContext(ioDispatcher) {
            markerDao.insert(
                Marker(
                    dayId = dayId,
                    atMs = atMs,
                    note = note,
                    createdAt = TimeUtils.currentEpochMs(),
                ).toEntity(),
            )
        }

    override suspend fun deleteMarker(markerId: Long) = withContext(ioDispatcher) {
        markerDao.delete(markerId)
    }

    override suspend fun deleteDay(dayId: String, deleteAudio: Boolean, deleteText: Boolean) =
        withContext(ioDispatcher) {
            if (!deleteAudio && !deleteText) return@withContext

            val day = dayDao.getDay(dayId)

            if (deleteAudio) {
                // 先删文件再删行；文件缺失视为已删
                segmentDao.getSegmentsForDay(dayId).forEach { segment ->
                    FileUtils.deleteFile(segment.filePath)
                    // 同步删除同名转写 .md（用户需求 1：转写与录音成对）
                    FileUtils.deleteFile(
                        segment.filePath.removeSuffix(".wav") + ".md",
                    )
                }
                FileUtils.deleteFile(day?.mergedAudioPath)
            }

            when {
                // 全删：行级联清除片段/标记/转写/总结/待办
                deleteAudio && deleteText -> dayDao.deleteDay(dayId)

                // 仅删文字：保留音频与录音日行，显式清除文字类数据
                deleteText -> database.withTransaction {
                    transcriptDao.deleteForDay(dayId)
                    // BUG-8：重置片段转写态为 PENDING，兜底 Worker 才能重建文字
                    segmentDao.resetTranscriptStatusForDay(dayId)
                    markerDao.deleteForDay(dayId)
                    summaryDao.deleteByDayId(dayId)
                    todoDao.deleteForDay(dayId)
                    dayDao.updateSummaryStatus(dayId, SummaryStatus.NONE)
                }

            // 仅删音频：保留全部文字与录音日行
            else -> Unit
        }
    }

    /**
     * 一键删除所有录音原文件（用户需求 2：清理缓存）。
     *
     * 只删 `.wav`（片段与全天合并）；`.md` 转写稿与 DB 文字数据全部保留，
     * 避免「清缓存」误伤已转写内容。文件缺失视为已删。
     */
    override suspend fun deleteAllOriginalAudio(): Int = withContext(ioDispatcher) {
        var deleted = 0
        dayDao.getAllDays().forEach { day ->
            segmentDao.getSegmentsForDay(day.dayId).forEach { segment ->
                if (FileUtils.deleteFile(segment.filePath)) deleted++
            }
            val mergedPath = day.mergedAudioPath
            if (!mergedPath.isNullOrBlank() && FileUtils.deleteFile(mergedPath)) {
                deleted++
            }
        }
        deleted
    }

    /**
     * 清除某天「转化后」的产物（用户需求 2：清理缓存）。
     *
     * 删除全天合并 `.wav` 与各片段同名 `.md`，并清除 DB 中的转写分段；
     * 片段转写态重置为 PENDING —— 录音原文件仍在，可随时重新转写。
     */
    override suspend fun clearDayConvertedArtifacts(dayId: String): Int = withContext(ioDispatcher) {
        var deleted = 0
        val day = dayDao.getDay(dayId)
        segmentDao.getSegmentsForDay(dayId).forEach { segment ->
            val mdPath = segment.filePath.removeSuffix(".wav") + ".md"
            if (FileUtils.deleteFile(mdPath)) deleted++
        }
        day?.mergedAudioPath?.let { mergedPath ->
            if (FileUtils.deleteFile(mergedPath)) deleted++
        }
        database.withTransaction {
            transcriptDao.deleteForDay(dayId)
            segmentDao.resetTranscriptStatusForDay(dayId)
        }
        deleted
    }
}
