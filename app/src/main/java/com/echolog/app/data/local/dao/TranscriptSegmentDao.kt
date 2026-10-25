package com.echolog.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.echolog.app.data.local.entity.TranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 转写分段 DAO（架构 3.2 类图）。
 *
 * 按天查询通过 INNER JOIN `audio_segment` 过滤；
 * [search] 为 P2 全文检索（FR-201，LIKE 实现）。
 */
@Dao
interface TranscriptSegmentDao {

    /** 单个片段的转写分段，按时间升序。 */
    @Query("SELECT * FROM transcript_segment WHERE segment_id = :segmentId ORDER BY start_ms ASC")
    fun observeForSegment(segmentId: Long): Flow<List<TranscriptSegmentEntity>>

    /** 当天的转写分段（跨片段 JOIN），按全天绝对时间升序。 */
    @Query(
        "SELECT transcript_segment.* FROM transcript_segment " +
            "INNER JOIN audio_segment ON transcript_segment.segment_id = audio_segment.segment_id " +
            "WHERE audio_segment.day_id = :dayId " +
            "ORDER BY transcript_segment.start_ms ASC",
    )
    fun observeForDay(dayId: String): Flow<List<TranscriptSegmentEntity>>

    /** 一次性读取当天转写（总结生成用）。 */
    @Query(
        "SELECT transcript_segment.* FROM transcript_segment " +
            "INNER JOIN audio_segment ON transcript_segment.segment_id = audio_segment.segment_id " +
            "WHERE audio_segment.day_id = :dayId " +
            "ORDER BY transcript_segment.start_ms ASC",
    )
    suspend fun getForDay(dayId: String): List<TranscriptSegmentEntity>

    /** 批量插入转写分段（配合仓库层的「先删后插」实现幂等 upsert）。 */
    @Insert
    suspend fun insertAll(items: List<TranscriptSegmentEntity>)

    /**
     * 修改单条转写文字（转写全文页的「编辑文字」）。
     *
     * 只覆盖 `text` 一列：**不动** `start_ms/end_ms/segment_id`——
     * 用户改的是「说了什么」，不是「什么时候说的」，时间轴必须保持原样，
     * 否则该行在时间线上的位置会与音频对不上。
     *
     * 产品决策：编辑即覆盖，**不保留原文副本**（无历史版本表）。
     *
     * @param tsId 转写分段主键。
     * @param text 新的文字内容。
     * @return 受影响行数（0 = 该行已不存在，例如所属片段已被删除）。
     */
    @Query("UPDATE transcript_segment SET text = :text WHERE ts_id = :tsId")
    suspend fun updateText(tsId: Long, text: String): Int

    /** 删除某片段的全部转写（重转写前清空旧结果）。 */
    @Query("DELETE FROM transcript_segment WHERE segment_id = :segmentId")
    suspend fun deleteForSegment(segmentId: Long)

    /** 删除某天的全部转写（「删除文字」场景）。 */
    @Query(
        "DELETE FROM transcript_segment " +
            "WHERE segment_id IN (SELECT segment_id FROM audio_segment WHERE day_id = :dayId)",
    )
    suspend fun deleteForDay(dayId: String)

    /** 关键词检索（P2 FR-201），按时间倒序返回命中分段。 */
    @Query(
        "SELECT transcript_segment.* FROM transcript_segment " +
            "INNER JOIN audio_segment ON transcript_segment.segment_id = audio_segment.segment_id " +
            "WHERE transcript_segment.text LIKE '%' || :keyword || '%' " +
            "ORDER BY transcript_segment.start_ms DESC LIMIT :limit",
    )
    suspend fun search(keyword: String, limit: Int): List<TranscriptSegmentEntity>
}
