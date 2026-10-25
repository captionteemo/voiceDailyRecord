package com.echolog.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.echolog.app.data.local.dao.AudioSegmentDao
import com.echolog.app.data.local.dao.DaySummaryDao
import com.echolog.app.data.local.dao.MarkerDao
import com.echolog.app.data.local.dao.RecordingDayDao
import com.echolog.app.data.local.dao.TodoItemDao
import com.echolog.app.data.local.dao.TranscriptSegmentDao
import com.echolog.app.data.local.entity.AudioSegmentEntity
import com.echolog.app.data.local.entity.DaySummaryEntity
import com.echolog.app.data.local.entity.MarkerEntity
import com.echolog.app.data.local.entity.RecordingDayEntity
import com.echolog.app.data.local.entity.TodoItemEntity
import com.echolog.app.data.local.entity.TranscriptSegmentEntity

/**
 * EchoLog 本地数据库（架构 3.1）。
 *
 * - 6 张表，子表均以 `day_id` / `segment_id` 外键指向父表并 ON DELETE CASCADE
 *   （删除某天即级联清除片段/标记/转写/总结/待办）；
 * - 枚举字段经 [Converters] 以字符串存储；
 * - schema 导出至 `app/schemas/`（架构 5.3 验收项）。
 *
 * v2（用户需求 4）：`recording_day` 新增 `mode` 列（录音模式）。
 */
@Database(
    entities = [
        RecordingDayEntity::class,
        AudioSegmentEntity::class,
        MarkerEntity::class,
        TranscriptSegmentEntity::class,
        DaySummaryEntity::class,
        TodoItemEntity::class,
    ],
    version = EchoDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EchoDatabase : RoomDatabase() {

    abstract fun recordingDayDao(): RecordingDayDao

    abstract fun audioSegmentDao(): AudioSegmentDao

    abstract fun markerDao(): MarkerDao

    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    abstract fun daySummaryDao(): DaySummaryDao

    abstract fun todoItemDao(): TodoItemDao

    companion object {
        const val DATABASE_NAME = "echolog.db"
        const val VERSION = 2
    }
}
