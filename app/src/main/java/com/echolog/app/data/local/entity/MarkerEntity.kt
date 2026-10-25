package com.echolog.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 打点标记实体（架构 3.1，FR-005）。
 *
 * 监听/录音态均可一键打点并附加文字备注；
 * `segment_id` 为可空快照（打点时刻正在录制的片段），
 * 随所属片段/录音日级联清除，不单独悬挂。
 */
@Entity(
    tableName = "marker",
    foreignKeys = [
        ForeignKey(
            entity = RecordingDayEntity::class,
            parentColumns = ["day_id"],
            childColumns = ["day_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AudioSegmentEntity::class,
            parentColumns = ["segment_id"],
            childColumns = ["segment_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["day_id"]), Index(value = ["segment_id"])],
)
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "marker_id")
    val markerId: Long = 0L,

    @ColumnInfo(name = "day_id")
    val dayId: String,

    /** 可空快照：打点时刻正在录制的片段；监听态打点为 null。 */
    @ColumnInfo(name = "segment_id")
    val segmentId: Long? = null,

    /** 打点时刻（epoch millis）。 */
    @ColumnInfo(name = "at_ms")
    val atMs: Long,

    /** 文字备注（可空字符串）。 */
    @ColumnInfo(name = "note")
    val note: String = "",

    /** 打点创建时间（epoch millis）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
