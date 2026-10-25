package com.echolog.app.data.mapper

import com.echolog.app.data.local.entity.AudioSegmentEntity
import com.echolog.app.data.local.entity.DaySummaryEntity
import com.echolog.app.data.local.entity.MarkerEntity
import com.echolog.app.data.local.entity.RecordingDayEntity
import com.echolog.app.data.local.entity.TodoItemEntity
import com.echolog.app.data.local.entity.TranscriptSegmentEntity
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.model.TodoType
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.model.TranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 实体 ↔ 领域模型映射单测（QA：架构 3.2 单一事实源）。
 *
 * 验证六类模型的往返转换不丢字段、不改语义。
 * 注意：映射函数为 `internal`，测试与主源码同 module，可直接访问。
 */
class EntityMappersTest {

    @Test
    fun `RecordingDay往返转换`() {
        val day = RecordingDay(
            dayId = "2026-10-24",
            state = DayState.SETTLING,
            monitorStartedAt = 1_000L,
            monitorEndedAt = 2_000L,
            totalRecordMs = 3_600_000L,
            mergedAudioPath = "/data/audio/day_2026-10-24.wav",
            summaryStatus = SummaryStatus.RUNNING,
            settleProgress = 80,
            settleError = null,
        )
        val entity = day.toEntity()
        assertEquals("2026-10-24", entity.dayId)
        assertEquals(DayState.SETTLING, entity.state)
        assertEquals(80, entity.settleProgress)
        assertEquals(day, entity.toDomain())
    }

    @Test
    fun `AudioSegment往返转换保留转写状态`() {
        val segment = AudioSegment(
            segmentId = 7L,
            dayId = "2026-10-24",
            filePath = "/data/audio/seg_2026-10-24_1.wav",
            startMs = 100L,
            endMs = 500L,
            durationMs = 400L,
            sizeBytes = 800L,
            transcriptStatus = TranscriptStatus.FAILED,
            transcriptError = "E-3002 推理失败",
        )
        val entity = segment.toEntity()
        assertEquals(TranscriptStatus.FAILED, entity.transcriptStatus)
        assertEquals(segment, entity.toDomain())
    }

    @Test
    fun `Marker往返转换含可空segmentId`() {
        val withSegment = Marker(
            markerId = 3L,
            dayId = "2026-10-24",
            segmentId = 9L,
            atMs = 12345L,
            note = "张总的报价",
            createdAt = 12346L,
        )
        assertEquals(withSegment, withSegment.toEntity().toDomain())

        val withoutSegment = withSegment.copy(segmentId = null, note = "")
        assertEquals(withoutSegment, withoutSegment.toEntity().toDomain())
        assertEquals(null, withoutSegment.toEntity().segmentId)
    }

    @Test
    fun `TranscriptSegment转实体时忽略tsId并由外部提供segmentId`() {
        val domain = TranscriptSegment(
            tsId = 999L, // 入库时应被忽略
            segmentId = 888L, // 转实体时以入参为准
            startMs = 100L,
            endMs = 200L,
            text = "你好世界",
        )
        val entity = domain.toEntity(segmentId = 42L)
        assertEquals(42L, entity.segmentId)
        assertEquals(100L, entity.startMs)
        assertEquals("你好世界", entity.text)
        // toDomain 反向保持 tsId 与外部传入的 segmentId（回读场景）
        assertEquals(domain.copy(segmentId = 42L), entity.copy(tsId = 999L).toDomain())
    }

    @Test
    fun `DaySummary往返转换`() {
        val summary = DaySummary(
            dayId = "2026-10-24",
            status = SummaryRunStatus.DONE,
            summaryText = "全天摘要",
            timelineJson = """[{"atMs":1000,"text":"早"}]""",
            modelName = "rule-based",
            error = null,
        )
        assertEquals(summary, summary.toEntity().toDomain())
    }

    @Test
    fun `TodoItem往返转换保留勾选态`() {
        val todo = TodoItem(
            todoId = 5L,
            dayId = "2026-10-24",
            type = TodoType.DECISION,
            content = "预算上限5万",
            sourceMs = 3_000L,
            done = true,
        )
        assertEquals(todo, todo.toEntity().toDomain())
        assertEquals(true, todo.toEntity().done)
    }

    @Test
    fun `实体默认值与领域模型默认值一致`() {
        val entity = RecordingDayEntity(dayId = "2026-10-24")
        assertEquals(DayState.IDLE, entity.state)
        assertEquals(SummaryStatus.NONE, entity.summaryStatus)
        assertEquals(0, entity.settleProgress)
        assertEquals(entity.toDomain(), RecordingDay(dayId = "2026-10-24"))

        val segmentEntity = AudioSegmentEntity(
            dayId = "2026-10-24",
            filePath = "/x.wav",
            startMs = 0L,
        )
        assertEquals(TranscriptStatus.PENDING, segmentEntity.transcriptStatus)
        assertEquals(segmentEntity.toDomain(), AudioSegment(dayId = "2026-10-24", filePath = "/x.wav", startMs = 0L))

        val summaryEntity = DaySummaryEntity(dayId = "2026-10-24")
        assertEquals(SummaryRunStatus.NONE, summaryEntity.status)
        assertEquals("[]", summaryEntity.timelineJson)
    }
}
