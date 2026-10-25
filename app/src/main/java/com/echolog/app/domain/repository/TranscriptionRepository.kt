package com.echolog.app.domain.repository

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.model.TranscriptStatus
import kotlinx.coroutines.flow.Flow

/**
 * 转写仓库（架构 3.1/3.2）：转写查询与落库。
 *
 * 实现见 `data/repository/TranscriptionRepositoryImpl.kt`。
 */
interface TranscriptionRepository {

    /** 当天的转写分段流，按全天绝对时间升序。 */
    fun observeTranscriptsForDay(dayId: String): Flow<List<TranscriptSegment>>

    /**
     * 一次性读取当天全部转写分段（按全天绝对时间升序）。
     *
     * 用于按天导出（用户需求 3）：调用方按 `segmentId` 分组还原片段结构。
     */
    suspend fun getTranscriptsForDay(dayId: String): List<TranscriptSegment>

    /**
     * 幂等写入某片段的转写结果（先删后插，同事务）。
     *
     * @param items 领域模型列表（`tsId` 忽略，由自增主键生成）。
     */
    suspend fun upsertTranscripts(segmentId: Long, items: List<TranscriptSegment>)

    /** 更新片段的转写状态与失败原因。 */
    suspend fun updateSegmentTranscriptStatus(
        segmentId: Long,
        status: TranscriptStatus,
        error: String?,
    )

    /** 当天的待转写片段（PENDING/FAILED，按时间升序）。 */
    suspend fun getPendingSegments(dayId: String, limit: Int = DEFAULT_PENDING_LIMIT): List<AudioSegment>

    /** 跨天扫描的待转写片段（充电兜底 Worker 用）。 */
    suspend fun getAllPendingSegments(limit: Int = DEFAULT_PENDING_LIMIT): List<AudioSegment>

    /** 关键词检索（P2 FR-201），按时间倒序。 */
    suspend fun searchTranscripts(
        keyword: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<TranscriptSegment>

    companion object {
        const val DEFAULT_PENDING_LIMIT = 50
        const val DEFAULT_SEARCH_LIMIT = 100
    }
}
