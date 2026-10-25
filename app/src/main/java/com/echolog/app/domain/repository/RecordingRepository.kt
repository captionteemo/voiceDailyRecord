package com.echolog.app.domain.repository

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SummaryStatus
import kotlinx.coroutines.flow.Flow

/**
 * 录音仓库（架构 3.1/3.2）：录音日 / 音频片段 / 打点标记。
 *
 * 实现见 `data/repository/RecordingRepositoryImpl.kt`（Room + 文件系统）。
 */
interface RecordingRepository {

    /** 全部录音日，按日期倒序（历史列表）。 */
    fun observeDays(): Flow<List<RecordingDay>>

    /** 观察单个录音日；未建档时发射 null。 */
    fun observeDay(dayId: String): Flow<RecordingDay?>

    /** 一次性读取单个录音日。 */
    suspend fun getDay(dayId: String): RecordingDay?

    /**
     * 建档或恢复监听（FR-001）。
     *
     * 语义：
     * - 当天无记录 → 新建 MONITORING 行（`monitorStartedAt = now`，模式取 [mode]）；
     * - 已存在且为 MONITORING → 原样返回（断点恢复，保留起始时间与模式）；
     * - 已存在且为 IDLE → 转 MONITORING（保留原 `monitorStartedAt`，首次为 0 时补 now）；
     * - SETTLING/DONE → 以新的 `monitorStartedAt` 重新进入 MONITORING（当天重复记录，模式取 [mode]）。
     *
     * @param mode 本次监听采用的录音模式（用户需求 4），仅新建/重录时写入。
     */
    suspend fun createOrResumeDay(dayId: String, mode: RecordingMode = RecordingMode.AUTO_VAD): RecordingDay

    /**
     * 更新日级状态。
     *
     * 从 MONITORING 迁出时自动回写 `monitorEndedAt`（now）。
     */
    suspend fun updateDayState(dayId: String, state: DayState)

    /** 更新结算进度（0~100，自动截断）与失败原因。 */
    suspend fun updateSettleProgress(dayId: String, progressPercent: Int, error: String?)

    /** 更新日级总结状态。 */
    suspend fun updateSummaryStatus(dayId: String, status: SummaryStatus)

    /** 合并完成后回写全天时长与合并文件路径。 */
    suspend fun updateMergeResult(dayId: String, totalRecordMs: Long, mergedAudioPath: String?)

    /** 当天片段流，按起始时间升序（时间线页实时刷新）。 */
    fun observeSegmentsForDay(dayId: String): Flow<List<AudioSegment>>

    /** 一次性读取当天片段（删除前取文件路径）。 */
    suspend fun getSegmentsForDay(dayId: String): List<AudioSegment>

    /** 开录建档：插入片段行（PENDING），返回 segmentId。 */
    suspend fun insertSegment(dayId: String, startMs: Long, filePath: String): Long

    /** 收尾片段：写结束时间与文件体积（时长由 DB 派生）。 */
    suspend fun finishSegment(segmentId: Long, endMs: Long, sizeBytes: Long)

    /** 当天标记流，按打点时刻升序。 */
    fun observeMarkersForDay(dayId: String): Flow<List<Marker>>

    /** 一次性读取当天标记（转写 .md 导出用，按打点时刻升序）。 */
    suspend fun getMarkersForDay(dayId: String): List<Marker>

    /** 一键打点（FR-005），返回 markerId。 */
    suspend fun insertMarker(dayId: String, atMs: Long, note: String): Long

    /** 删除单个标记。 */
    suspend fun deleteMarker(markerId: Long)

    /**
     * 按天删除（FR-106，音频/文字可分别删）。
     *
     * @param deleteAudio 删除片段文件与全天合并文件（行由文字策略决定）。
     * @param deleteText 删除文字类数据（转写/标记/总结/待办 + 录音日行）；
     *                   为 false 时保留录音日行及其文字（合并音频路径保留）。
     *                   两者均为 false 时为空操作。
     */
    suspend fun deleteDay(dayId: String, deleteAudio: Boolean, deleteText: Boolean)

    /**
     * 一键删除所有录音原文件（用户需求 2：清理缓存）。
     *
     * 遍历全部录音日，删除所有片段 `.wav` 与全天合并 `.wav`；
     * **保留**转写文字（`.md`）、DB 文字数据与总结内容。
     *
     * @return 实际删除的文件数。
     */
    suspend fun deleteAllOriginalAudio(): Int

    /**
     * 清除某天「转化后」的全部产物（用户需求 2：清理缓存）。
     *
     * 删除该天的全天合并 `.wav`、各片段同名转写 `.md` 与 DB 中的转写分段；
     * 片段转写态重置为 PENDING（可重新转写），录音原文件与总结内容保留。
     *
     * @return 实际删除的文件数。
     */
    suspend fun clearDayConvertedArtifacts(dayId: String): Int
}
