package com.echolog.app.data.local

import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TodoType
import com.echolog.app.domain.model.TranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Room 枚举转换器单测（QA：架构 2.2）。
 *
 * 枚举一律以 name 字符串存储；未知值（未来版本新增项）回落默认值而非崩溃。
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `DayState按name往返`() {
        DayState.entries.forEach { state ->
            assertEquals(state, converters.toDayState(converters.fromDayState(state)))
        }
        assertEquals(DayState.IDLE, converters.toDayState("UNKNOWN_STATE"))
        assertEquals("MONITORING", converters.fromDayState(DayState.MONITORING))
    }

    @Test
    fun `SummaryStatus按name往返`() {
        SummaryStatus.entries.forEach { status ->
            assertEquals(status, converters.toSummaryStatus(converters.fromSummaryStatus(status)))
        }
        assertEquals(SummaryStatus.NONE, converters.toSummaryStatus("WEIRD"))
    }

    @Test
    fun `TranscriptStatus按name往返`() {
        TranscriptStatus.entries.forEach { status ->
            assertEquals(
                status,
                converters.toTranscriptStatus(converters.fromTranscriptStatus(status)),
            )
        }
        assertEquals(TranscriptStatus.PENDING, converters.toTranscriptStatus("WEIRD"))
    }

    @Test
    fun `SummaryRunStatus按name往返`() {
        SummaryRunStatus.entries.forEach { status ->
            assertEquals(
                status,
                converters.toSummaryRunStatus(converters.fromSummaryRunStatus(status)),
            )
        }
        assertEquals(SummaryRunStatus.NONE, converters.toSummaryRunStatus("WEIRD"))
    }

    @Test
    fun `TodoType按name往返`() {
        TodoType.entries.forEach { type ->
            assertEquals(type, converters.toTodoType(converters.fromTodoType(type)))
        }
        assertEquals(TodoType.TODO, converters.toTodoType("WEIRD"))
    }
}
