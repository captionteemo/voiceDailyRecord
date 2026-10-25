package com.echolog.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SummaryStatus

/**
 * 录音日实体（架构 3.1）。
 *
 * 状态机 + 结算进度的唯一真相源：
 * 主键 `day_id` 为本地时区日期 `yyyy-MM-dd`，一天一行；
 * 进程死亡后由 HealthCheckWorker 依据 `state` 字段恢复监听。
 */
@Entity(tableName = "recording_day")
data class RecordingDayEntity(
    @PrimaryKey
    @ColumnInfo(name = "day_id")
    val dayId: String,

    /** 日级状态机（IDLE/MONITORING/SETTLING/DONE）。 */
    @ColumnInfo(name = "state")
    val state: DayState = DayState.IDLE,

    /** 本次监听开始时间（epoch millis）。 */
    @ColumnInfo(name = "monitor_started_at")
    val monitorStartedAt: Long = 0L,

    /** 本次监听结束时间（epoch millis；0 表示仍在监听）。 */
    @ColumnInfo(name = "monitor_ended_at")
    val monitorEndedAt: Long = 0L,

    /** 当天累计录音时长（epoch millis，合并后回写）。 */
    @ColumnInfo(name = "total_record_ms")
    val totalRecordMs: Long = 0L,

    /** 当天采用的录音模式（用户需求 4：全天/自动/模板/手动）。 */
    @ColumnInfo(name = "mode")
    val mode: RecordingMode = RecordingMode.AUTO_VAD,

    /** 全天合并音频路径（`{filesDir}/audio/day_{dayId}.wav`）。 */
    @ColumnInfo(name = "merged_audio_path")
    val mergedAudioPath: String? = null,

    /** 日级总结状态（NONE/PENDING/RUNNING/DONE/FAILED）。 */
    @ColumnInfo(name = "summary_status")
    val summaryStatus: SummaryStatus = SummaryStatus.NONE,

    /** 结算进度 0~100（架构 7.5：UI 唯一进度真相源）。 */
    @ColumnInfo(name = "settle_progress")
    val settleProgress: Int = 0,

    /** 结算失败原因（成功时为 null）。 */
    @ColumnInfo(name = "settle_error")
    val settleError: String? = null,
)
