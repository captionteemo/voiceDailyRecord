package com.echolog.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.echolog.app.domain.model.SummaryRunStatus

/**
 * 当天总结实体（架构 3.1，FR-011）。
 *
 * 一天一行（主键即 `day_id`，外键级联）；
 * `timeline_json` 为关键时间线条目的 JSON 数组；
 * `model_name` 记录产出来源（LLM 模型名或规则兜底标识）。
 */
@Entity(
    tableName = "day_summary",
    foreignKeys = [
        ForeignKey(
            entity = RecordingDayEntity::class,
            parentColumns = ["day_id"],
            childColumns = ["day_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DaySummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "day_id")
    val dayId: String,

    /** 总结任务状态（NONE/PENDING/RUNNING/DONE/FAILED）。 */
    @ColumnInfo(name = "status")
    val status: SummaryRunStatus = SummaryRunStatus.NONE,

    /** 全天文字摘要。 */
    @ColumnInfo(name = "summary_text")
    val summaryText: String = "",

    /** 关键时间线 JSON（`[{"atMs":..,"text":..}]`）。 */
    @ColumnInfo(name = "timeline_json")
    val timelineJson: String = "[]",

    /** 产出模型标识（如 `qwen2.5-1.5b-instruct-q4` / `rule-based`）。 */
    @ColumnInfo(name = "model_name")
    val modelName: String = "",

    /** 失败原因（成功时为 null）。 */
    @ColumnInfo(name = "error")
    val error: String? = null,
)
