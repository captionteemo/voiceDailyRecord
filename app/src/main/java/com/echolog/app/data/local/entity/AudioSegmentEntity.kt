package com.echolog.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.echolog.app.domain.model.TranscriptStatus

/**
 * 音频片段实体（架构 3.1/1.6）。
 *
 * 一个 VAD 突变语音片段一个文件：`{filesDir}/audio/seg_{dayId}_{startEpochMs}.wav`；
 * 删除所属录音日时级联清除（外键 ON DELETE CASCADE）。
 */
@Entity(
    tableName = "audio_segment",
    foreignKeys = [
        ForeignKey(
            entity = RecordingDayEntity::class,
            parentColumns = ["day_id"],
            childColumns = ["day_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["day_id"])],
)
data class AudioSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "segment_id")
    val segmentId: Long = 0L,

    @ColumnInfo(name = "day_id")
    val dayId: String,

    /** 片段 WAV 文件绝对路径。 */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** 片段起始时间（epoch millis，含预录缓冲回退后的真实语音起点）。 */
    @ColumnInfo(name = "start_ms")
    val startMs: Long,

    /** 片段结束时间（epoch millis；0 表示尚未收尾）。 */
    @ColumnInfo(name = "end_ms")
    val endMs: Long = 0L,

    /** 片段时长（epoch millis）。 */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    /** 文件体积（字节）。 */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,

    /** 转写状态（PENDING/RUNNING/DONE/FAILED）。 */
    @ColumnInfo(name = "transcript_status")
    val transcriptStatus: TranscriptStatus = TranscriptStatus.PENDING,

    /** 转写失败原因（成功时为 null）。 */
    @ColumnInfo(name = "transcript_error")
    val transcriptError: String? = null,
)
