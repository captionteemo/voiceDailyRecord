package com.echolog.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.echolog.app.data.local.entity.RecordingDayEntity
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.SummaryStatus
import kotlinx.coroutines.flow.Flow

/**
 * 录音日 DAO（架构 3.2 类图）。
 *
 * 查询一律返回 [Flow]（Room 自动在数据变更时重新发射）；
 * 写操作为挂起函数，由仓库实现决定事务边界。
 */
@Dao
interface RecordingDayDao {

    /** 全部录音日，按日期倒序（历史列表页）。 */
    @Query("SELECT * FROM recording_day ORDER BY day_id DESC")
    fun observeDays(): Flow<List<RecordingDayEntity>>

    /** 观察单个录音日；当天未建档时发射 null。 */
    @Query("SELECT * FROM recording_day WHERE day_id = :dayId LIMIT 1")
    fun observeDay(dayId: String): Flow<RecordingDayEntity?>

    /** 一次性读取单个录音日（无 Flow 开销，用于写入前判断）。 */
    @Query("SELECT * FROM recording_day WHERE day_id = :dayId LIMIT 1")
    suspend fun getDay(dayId: String): RecordingDayEntity?

    /** 一次性读取全部录音日（清理缓存等批量操作遍历用）。 */
    @Query("SELECT * FROM recording_day ORDER BY day_id DESC")
    suspend fun getAllDays(): List<RecordingDayEntity>

    /** 跨天归档 / 健康检查：取当前处于 MONITORING 的录音日。 */
    @Query("SELECT * FROM recording_day WHERE state = 'MONITORING' LIMIT 1")
    suspend fun getMonitoringDay(): RecordingDayEntity?

    /** 插入或更新（按主键冲突处理）。 */
    @Upsert
    suspend fun upsert(day: RecordingDayEntity)

    /** 仅更新日级状态。 */
    @Query("UPDATE recording_day SET state = :state WHERE day_id = :dayId")
    suspend fun updateState(dayId: String, state: DayState)

    /** 更新结算进度与失败原因（架构 7.5：UI 进度真相源）。 */
    @Query("UPDATE recording_day SET settle_progress = :progress, settle_error = :error WHERE day_id = :dayId")
    suspend fun updateSettle(dayId: String, progress: Int, error: String?)

    /** 更新日级总结状态。 */
    @Query("UPDATE recording_day SET summary_status = :status WHERE day_id = :dayId")
    suspend fun updateSummaryStatus(dayId: String, status: SummaryStatus)

    /** 合并完成后回写全天时长与合并文件路径。 */
    @Query(
        "UPDATE recording_day SET total_record_ms = :totalRecordMs, merged_audio_path = :mergedAudioPath " +
            "WHERE day_id = :dayId",
    )
    suspend fun updateMergeResult(dayId: String, totalRecordMs: Long, mergedAudioPath: String?)

    /** 删除录音日（子表由外键级联清除）。 */
    @Query("DELETE FROM recording_day WHERE day_id = :dayId")
    suspend fun deleteDay(dayId: String)
}
