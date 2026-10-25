package com.echolog.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 转写分段实体（架构 3.1，FR-009）。
 *
 * 带时间戳的分段文字：`start_ms/end_ms` 已叠加所属片段偏移，为全天绝对时间；
 * 随所属片段级联清除。
 */
@Entity(
    tableName = "transcript_segment",
    foreignKeys = [
        ForeignKey(
            entity = AudioSegmentEntity::class,
            parentColumns = ["segment_id"],
            childColumns = ["segment_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["segment_id"])],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ts_id")
    val tsId: Long = 0L,

    @ColumnInfo(name = "segment_id")
    val segmentId: Long,

    /** 分段起始时间（epoch millis，全天绝对时间）。 */
    @ColumnInfo(name = "start_ms")
    val startMs: Long,

    /** 分段结束时间（epoch millis，全天绝对时间）。 */
    @ColumnInfo(name = "end_ms")
    val endMs: Long,

    /** 分段文字。 */
    @ColumnInfo(name = "text")
    val text: String,
)
