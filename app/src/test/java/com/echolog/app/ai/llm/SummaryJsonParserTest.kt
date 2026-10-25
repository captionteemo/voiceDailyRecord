package com.echolog.app.ai.llm

import com.echolog.app.domain.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SummaryJsonParser] 单元测试（QA：架构 2.4 容错设计 / FR-011 / E-4002）。
 *
 * 覆盖：完整 JSON、Markdown 围栏、截断修复、畸形输入、单条容错、
 * 时间线 JSON 与 [com.echolog.app.domain.model.DaySummary.timelineJson] 序列化互逆。
 */
class SummaryJsonParserTest {

    // ── 完整 JSON ──

    @Test
    fun `完整JSON解析出摘要时间线与三类条目`() {
        val raw = """
            {"summary":"上午讨论了发布会流程，确定了预算上限。",
             "timeline":[{"time":"09:30","text":"确认发布会流程"},
                         {"time":"14:00","text":"评审设计稿"}],
             "items":[{"type":"TODO","time":"10:00","content":"周三前提交预算表"},
                      {"type":"DECISION","time":"11:20","content":"预算上限5万"},
                      {"type":"AGREEMENT","time":"16:00","content":"下周一对齐渠道"}]}
        """.trimIndent()

        val draft = SummaryJsonParser.parse(raw)

        assertTrue(draft.summaryText.contains("发布会流程"))
        assertEquals(2, draft.timeline.size)
        assertEquals("确认发布会流程", draft.timeline[0].text)
        assertEquals(3, draft.todos.size)
        assertEquals(TodoType.TODO, draft.todos[0].type)
        assertEquals(TodoType.DECISION, draft.todos[1].type)
        assertEquals(TodoType.AGREEMENT, draft.todos[2].type)
        assertEquals("周三前提交预算表", draft.todos[0].content)
    }

    @Test
    fun `items与todos双键合并解析`() {
        val raw = """
            {"summary":"x","items":[{"type":"TODO","content":"a"}],"todos":[{"type":"TODO","content":"b"}]}
        """.trimIndent()
        val draft = SummaryJsonParser.parse(raw)
        assertEquals(2, draft.todos.size)
    }

    @Test
    fun `条目type缺省按TODO处理`() {
        val raw = """{"summary":"s","items":[{"content":"没写类型的条目"}]}"""
        val draft = SummaryJsonParser.parse(raw)
        assertEquals(1, draft.todos.size)
        assertEquals(TodoType.TODO, draft.todos[0].type)
    }

    @Test
    fun `时间标签非法时回落0`() {
        val raw = """{"summary":"s","timeline":[{"time":"25:99","text":"坏时间"}],"items":[]}"""
        val draft = SummaryJsonParser.parse(raw)
        assertEquals(1, draft.timeline.size)
        assertEquals(0L, draft.timeline[0].atMs)
    }

    @Test
    fun `时间标签合法时转换为当天本地时间epoch`() {
        val raw = """{"summary":"s","timeline":[{"time":"09:30","text":"早上"}],"items":[]}"""
        val draft = SummaryJsonParser.parse(raw)
        val expected = java.time.LocalDate.now()
            .atTime(9, 30)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, draft.timeline[0].atMs)
    }

    @Test
    fun `摘要超长时截断到2000字符`() {
        val longSummary = "摘".repeat(5000)
        val raw = """{"summary":"$longSummary","timeline":[],"items":[]}"""
        val draft = SummaryJsonParser.parse(raw)
        assertEquals(2000, draft.summaryText.length)
    }

    // ── 围栏与杂质 ──

    @Test
    fun `剥离markdown代码围栏`() {
        val raw = """
            好的，以下是结果：
            ```json
            {"summary":"带围栏的摘要","timeline":[],"items":[]}
            ```
            希望有帮助。
        """.trimIndent()
        val draft = SummaryJsonParser.parse(raw)
        assertEquals("带围栏的摘要", draft.summaryText)
    }

    @Test
    fun `截取首个花括号到末个花括号之间内容`() {
        val raw = """前言文字 {"summary":"夹在中间的JSON","timeline":[],"items":[]} 尾部废话"""
        val draft = SummaryJsonParser.parse(raw)
        assertEquals("夹在中间的JSON", draft.summaryText)
    }

    // ── 截断修复（LLM 达到 max_tokens 的高频场景） ──

    @Test
    fun `数组截断时修复并保留完整元素`() {
        val raw =
            """{"summary":"开头还在","timeline":[{"time":"09:00","text":"第一条"},"time":"10:00","text":"第二条"""
        val draft = SummaryJsonParser.parse(raw)
        assertEquals("开头还在", draft.summaryText)
        assertEquals(1, draft.timeline.size)
        assertEquals("第一条", draft.timeline[0].text)
    }

    @Test
    fun `对象截断时修复并保留已闭合字段`() {
        val raw = """{"summary":"完整摘要","timeline":[{"time":"09:00","text":"唯一一条"}],"items":[{"type":"TODO","cont"""
        val draft = SummaryJsonParser.parse(raw)
        assertEquals("完整摘要", draft.summaryText)
        assertEquals(1, draft.timeline.size)
        assertTrue(draft.todos.isEmpty())
    }

    // ── 彻底失败 ──

    @Test
    fun `无JSON对象时抛解析异常`() {
        assertThrows(SummaryParseException::class.java) {
            SummaryJsonParser.parse("模型今天心情不好，什么也没输出。")
        }
    }

    @Test
    fun `结构有效但内容为空时抛解析异常`() {
        assertThrows(SummaryParseException::class.java) {
            SummaryJsonParser.parse("""{"summary":"","timeline":[],"items":[]}""")
        }
    }

    @Test
    fun `完全畸形JSON抛解析异常而非崩溃`() {
        assertThrows(SummaryParseException::class.java) {
            SummaryJsonParser.parse("""{"summary": "未闭合的字符串, "timeline": [""")
        }
    }

    // ── 单条容错 ──

    @Test
    fun `单条畸形元素被跳过不拖垮整体`() {
        val raw = """
            {"summary":"整体摘要",
             "timeline":[{"time":"09:00","text":"好条目"}, "我不是对象", {"text":""}],
             "items":[{"type":"TODO","content":"好待办"}, {"type":"TODO"}, 42]}
        """.trimIndent()
        val draft = SummaryJsonParser.parse(raw)
        assertEquals(1, draft.timeline.size)
        assertEquals("好条目", draft.timeline[0].text)
        assertEquals(1, draft.todos.size)
        assertEquals("好待办", draft.todos[0].content)
    }

    // ── 落库时间线 JSON（与 SummarizeDayUseCase.encodeTimeline 互逆） ──

    @Test
    fun `parseTimelineJson解析落库格式并按时间升序`() {
        val json = """[{"atMs":3000,"text":"晚"},{"atMs":1000,"text":"早"},{"atMs":2000,"text":"中"}]"""
        val items = SummaryJsonParser.parseTimelineJson(json)
        assertEquals(listOf("早", "中", "晚"), items.map { it.text })
        assertEquals(listOf(1000L, 2000L, 3000L), items.map { it.atMs })
    }

    @Test
    fun `parseTimelineJson处理转义字符`() {
        val json = """[{"atMs":1000,"text":"他说\"你好\"\n第二行"}]"""
        val items = SummaryJsonParser.parseTimelineJson(json)
        assertEquals(1, items.size)
        assertEquals("他说\"你好\"\n第二行", items[0].text)
    }

    @Test
    fun `parseTimelineJson对畸形输入静默回落空列表`() {
        assertTrue(SummaryJsonParser.parseTimelineJson(null).isEmpty())
        assertTrue(SummaryJsonParser.parseTimelineJson("").isEmpty())
        assertTrue(SummaryJsonParser.parseTimelineJson("不是JSON").isEmpty())
        assertTrue(SummaryJsonParser.parseTimelineJson("""[{"atMs":1}]""").isEmpty()) // 缺 text
    }

    @Test
    fun `parseTimelineJson缺atMs时按0兜底`() {
        val items = SummaryJsonParser.parseTimelineJson("""[{"text":"缺atMs"}]""")
        assertEquals(1, items.size)
        assertEquals(0L, items[0].atMs)
    }

    @Test
    fun `parseTimelineJson接受空数组`() {
        assertTrue(SummaryJsonParser.parseTimelineJson("[]").isEmpty())
    }
}
