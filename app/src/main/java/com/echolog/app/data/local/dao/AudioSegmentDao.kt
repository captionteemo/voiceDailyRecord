package com.echolog.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.echolog.app.data.local.entity.AudioSegmentEntity
import com.echolog.app.domain.model.TranscriptStatus
import kotlinx.coroutines.flow.Flow

/**
 * 音频片段 DAO（架构 3.2 类图）。
 *
 * 兜底 Worker 通过 [getPendingSegments] / [getAllPendingSegments]
 * 扫描待转写片段（PENDING 与 FAILED 均可重试）。
 */
@Dao
interface AudioSegmentDao {

    /** 当天全部片段，按起始时间升序（时间线页）。 */
    @Query("SELECT * FROM audio_segment WHERE day_id = :dayId ORDER BY start_ms ASC")
    fun observeSegmentsForDay(dayId: String): Flow<List<AudioSegmentEntity>>

    /** 一次性读取当天片段（删除/合并前取文件路径）。 */
    @Query("SELECT * FROM audio_segment WHERE day_id = :dayId ORDER BY start_ms ASC")
    suspend fun getSegmentsForDay(dayId: String): List<AudioSegmentEntity>

    /** 按 ID 读取片段。 */
    @Query("SELECT * FROM audio_segment WHERE segment_id = :segmentId LIMIT 1")
    suspend fun getSegment(segmentId: Long): AudioSegmentEntity?

    /** 插入片段（开录时建档），返回自增 segmentId。 */
    @Insert
    suspend fun insert(segment: AudioSegmentEntity): Long

    /** 收尾片段：写结束时间并派生时长（duration = endMs - startMs）。 */
    @Query(
        "UPDATE audio_segment SET end_ms = :endMs, duration_ms = :endMs - start_ms, size_bytes = :sizeBytes " +
            "WHERE segment_id = :segmentId",
    )
    suspend fun finishSegment(segmentId: Long, endMs: Long, sizeBytes: Long)

    /** 更新转写状态与失败原因。 */
    @Query("UPDATE audio_segment SET transcript_status = :status, transcript_error = :error WHERE segment_id = :segmentId")
    suspend fun updateTranscriptStatus(segmentId: Long, status: TranscriptStatus, error: String?)

    /**
     * 重置当天全部片段的转写态为 PENDING（「仅删文字」场景，BUG-8）。
     *
     * 删除转写文字后必须重置片段状态，否则兜底转写 Worker 扫不到待转写片段，
     * 文字永远无法重建。
     */
    @Query("UPDATE audio_segment SET transcript_status = 'PENDING', transcript_error = NULL WHERE day_id = :dayId")
    suspend fun resetTranscriptStatusForDay(dayId: String)

    /** 当天待转写片段（PENDING/FAILED，按时间升序）。 */
    @Query(
        "SELECT * FROM audio_segment WHERE day_id = :dayId AND transcript_status IN ('PENDING', 'FAILED') " +
            "ORDER BY start_ms ASC LIMIT :limit",
    )
    suspend fun getPendingSegments(dayId: String, limit: Int): List<AudioSegmentEntity>

    /** 跨天扫描：全部待转写片段（充电兜底 Worker 用）。 */
    @Query(
        "SELECT * FROM audio_segment WHERE transcript_status IN ('PENDING', 'FAILED') " +
            "ORDER BY start_ms ASC LIMIT :limit",
    )
    suspend fun getAllPendingSegments(limit: Int): List<AudioSegmentEntity>
}
