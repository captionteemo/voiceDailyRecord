package com.echolog.app.ai.llm

import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.TodoType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleBasedSummarizer] 单元测试（QA：架构决策 2 兜底总结 / FR-011 永不空白）。
 *
 * 纯 Kotlin 规则引擎，无 Android 依赖；验证关键词分类、时间前缀解析、
 * 摘要非空、时间线限量等兜底契约。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleBasedSummarizerTest {

    private val summarizer = RuleBasedSummarizer(UnconfinedTestDispatcher())

    @Test
    fun `空输入返回占位摘要而非空白`() = runTest {
        val draft = summarizer.summarize("   \n  \n ", emptyList())
        assertTrue(draft.summaryText.isNotBlank())
        assertTrue(draft.todos.isEmpty())
        assertTrue(draft.timeline.isEmpty())
    }

    @Test
    fun `待办关键词被分类为TODO`() = runTest {
        val text = "[09:30] 记得明天交报告\n[10:00] 今天天气不错"
        val draft = summarizer.summarize(text, emptyList())
        assertEquals(1, draft.todos.size)
        assertEquals(TodoType.TODO, draft.todos[0].type)
        assertTrue(draft.todos[0].content.contains("记得明天交报告"))
    }

    @Test
    fun `决策与约定关键词分别分类`() = runTest {
        val text = "[11:00] 大家同意预算上限五万\n[12:00] 我们说好下周一对齐"
        val draft = summarizer.summarize(text, emptyList())
        val byType = draft.todos.associateBy { it.type }
        assertTrue(byType.containsKey(TodoType.DECISION))
        assertTrue(byType.containsKey(TodoType.AGREEMENT))
    }

    @Test
    fun `决策关键词优先于约定关键词`() = runTest {
        // 同一行含「决定」与「约定」：按 DECISION > AGREEMENT > TODO 的固定优先级
        val text = "[11:00] 我们决定按约定时间交付"
        val draft = summarizer.summarize(text, emptyList())
        assertEquals(1, draft.todos.size)
        assertEquals(TodoType.DECISION, draft.todos[0].type)
    }

    @Test
    fun `无关键词行不产生待办`() = runTest {
        val text = "[09:00] 早上好\n[09:05] 今天开会"
        val draft = summarizer.summarize(text, emptyList())
        assertTrue(draft.todos.isEmpty())
    }

    @Test
    fun `时间前缀解析为当天本地时间`() = runTest {
        // 长句（≥20 字）才会进入时间线；验证 [HH:mm] 前缀 → 当天本地 epoch
        val text = "[14:30] 下午的讨论内容需要安排会议室和投影设备等事项"
        val draft = summarizer.summarize(text, emptyList())
        val expected = java.time.LocalDate.now()
            .atTime(14, 30)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(1, draft.timeline.size)
        assertEquals(expected, draft.timeline[0].atMs)
        // 该行含「安排」→ 同时产出 TODO，时间来源一致
        assertEquals(1, draft.todos.size)
        assertEquals(TodoType.TODO, draft.todos[0].type)
        assertEquals(expected, draft.todos[0].atMs)
    }

    @Test
    fun `无时间前缀的行时间为null但不丢失内容`() = runTest {
        val text = "没有时间前缀的一句话"
        val draft = summarizer.summarize(text, emptyList())
        assertTrue(draft.summaryText.contains("没有时间前缀的一句话"))
        assertTrue(draft.todos.isEmpty())
    }

    @Test
    fun `摘要包含段数统计且非空`() = runTest {
        val text = "[09:00] 第一段内容\n[10:00] 第二段内容\n[11:00] 第三段内容"
        val draft = summarizer.summarize(text, emptyList())
        assertTrue(draft.summaryText.contains("全天共 3 段"))
        assertFalse(draft.summaryText.isEmpty())
    }

    @Test
    fun `时间线最多5条且按时间排序`() = runTest {
        val text = (1..10).joinToString("\n") { "[%02d:00] 第%d段需要安排的讨论内容".format(it, it) }
        val draft = summarizer.summarize(text, emptyList())
        assertTrue("时间线应限量 5 条，实际 ${draft.timeline.size}", draft.timeline.size <= 5)
        val sorted = draft.timeline.map { it.atMs }.sorted()
        assertEquals(sorted, draft.timeline.map { it.atMs })
    }

    @Test
    fun `标记参数不影响规则输出`() = runTest {
        val text = "[09:30] 记得交报告"
        val markers = listOf(Marker(dayId = "2026-10-24", atMs = 1L, note = "重点"))
        val withMarkers = summarizer.summarize(text, markers)
        val withoutMarkers = summarizer.summarize(text, emptyList())
        assertEquals(withoutMarkers.summaryText, withMarkers.summaryText)
        assertEquals(withoutMarkers.todos.size, withMarkers.todos.size)
    }
}
