package com.echolog.app.data.local

import androidx.room.TypeConverter
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TodoType
import com.echolog.app.domain.model.TranscriptStatus

/**
 * Room 类型转换器（架构 2.2）。
 *
 * 枚举一律以 `name` 字符串存储，读取时按名称解析；
 * 遇未知值（如未来版本新增枚举项）回落到默认值，避免崩溃。
 */
class Converters {

    // ── DayState ──
    @TypeConverter
    fun fromDayState(value: DayState): String = value.name

    @TypeConverter
    fun toDayState(value: String): DayState = decodeEnum(value, DayState.IDLE)

    // ── RecordingMode ──
    @TypeConverter
    fun fromRecordingMode(value: RecordingMode): String = value.name

    @TypeConverter
    fun toRecordingMode(value: String): RecordingMode = decodeEnum(value, RecordingMode.AUTO_VAD)

    // ── SummaryStatus（录音日维度） ──
    @TypeConverter
    fun fromSummaryStatus(value: SummaryStatus): String = value.name

    @TypeConverter
    fun toSummaryStatus(value: String): SummaryStatus = decodeEnum(value, SummaryStatus.NONE)

    // ── TranscriptStatus ──
    @TypeConverter
    fun fromTranscriptStatus(value: TranscriptStatus): String = value.name

    @TypeConverter
    fun toTranscriptStatus(value: String): TranscriptStatus =
        decodeEnum(value, TranscriptStatus.PENDING)

    // ── SummaryRunStatus（总结任务维度） ──
    @TypeConverter
    fun fromSummaryRunStatus(value: SummaryRunStatus): String = value.name

    @TypeConverter
    fun toSummaryRunStatus(value: String): SummaryRunStatus =
        decodeEnum(value, SummaryRunStatus.NONE)

    // ── TodoType ──
    @TypeConverter
    fun fromTodoType(value: TodoType): String = value.name

    @TypeConverter
    fun toTodoType(value: String): TodoType = decodeEnum(value, TodoType.TODO)
}

/** 按名称解析枚举，未知值回落 [default]。 */
private inline fun <reified E : Enum<E>> decodeEnum(value: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == value } ?: default
