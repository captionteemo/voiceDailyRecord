package com.echolog.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.echolog.app.domain.model.TodoType

/**
 * 待办/决策/约定条目实体（架构 3.1，FR-011）。
 *
 * 总结产出时整体替换当天条目（仓库实现保证同事务）；
 * 随所属录音日级联清除。
 */
@Entity(
    tableName = "todo_item",
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
data class TodoItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "todo_id")
    val todoId: Long = 0L,

    @ColumnInfo(name = "day_id")
    val dayId: String,

    /** 条目类型（TODO/DECISION/AGREEMENT）。 */
    @ColumnInfo(name = "type")
    val type: TodoType,

    /** 条目内容。 */
    @ColumnInfo(name = "content")
    val content: String,

    /** 内容在录音中的时间来源（epoch millis）。 */
    @ColumnInfo(name = "source_ms")
    val sourceMs: Long,

    /** 是否已完成（勾选）。 */
    @ColumnInfo(name = "done")
    val done: Boolean = false,
)
