package com.echolog.app.ui.transcript

import com.echolog.app.core.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TranscriptCopyFormatter] 复制内容格式回归测试（QA：转写全文页「复制全部 / 复制所选」）。
 *
 * 格式必须与项目内既有转写文本口径一致（`SummarizeDayUseCase.buildTranscriptText`
 * 与 `TimelineItem.displayLabel`）：每行 `[HH:mm] 文字`，换行分隔。
 *
 * 时间戳经 [TimeUtils.formatTime] 由本地时区渲染，因此本测试用同一函数生成期望值，
 * 避免测试因运行机器时区不同而失败（断言的是**格式**，不是某个固定时刻）。
 */
class TranscriptCopyFormatterTest {

    private val line1 = line(tsId = 1L, startMs = 10L * 60 * 60 * 1000, text = "第一句")
    private val line2 = line(tsId = 2L, startMs = 14L * 60 * 60 * 1000, text = "第二句")
    private val line3 = line(tsId = 3L, startMs = 15L * 60 * 60 * 1000, text = "第三句")

    private val lines = listOf(line1, line2, line3)

    // ── 复制全部 ──

    @Test
    fun `复制全部输出全部行且每行带 HHmm 时间戳`() {
        val text = TranscriptCopyFormatter.formatAll(lines)

        val expected = listOf(line1, line2, line3)
            .joinToString("\n") { "[${TimeUtils.formatTime(it.startMs)}] ${it.text}" }
        assertEquals(expected, text)
    }

    @Test
    fun `复制全部只有一行时不含换行`() {
        val text = TranscriptCopyFormatter.formatAll(listOf(line1))
        assertEquals("[${TimeUtils.formatTime(line1.startMs)}] 第一句", text)
    }

    @Test
    fun `当天没有转写内容时复制全部返回 null`() {
        assertNull(TranscriptCopyFormatter.formatAll(emptyList()))
    }

    // ── 复制所选 ──

    @Test
    fun `复制所选只输出勾选的行且保持时间顺序`() {
        // 乱序勾选（先 3 后 1），输出仍按页面展示顺序（时间升序）
        val text = TranscriptCopyFormatter.formatSelected(
            lines = lines,
            selectedTsIds = setOf(3L, 1L),
        )

        val expected = "[${TimeUtils.formatTime(line1.startMs)}] 第一句\n" +
            "[${TimeUtils.formatTime(line3.startMs)}] 第三句"
        assertEquals(expected, text)
    }

    @Test
    fun `复制所选只勾一条时与复制全部格式一致`() {
        val text = TranscriptCopyFormatter.formatSelected(lines, setOf(2L))
        assertEquals("[${TimeUtils.formatTime(line2.startMs)}] 第二句", text)
    }

    @Test
    fun `未勾选任何条时复制所选返回 null`() {
        assertNull(TranscriptCopyFormatter.formatSelected(lines, emptySet()))
    }

    @Test
    fun `勾选的 tsId 不存在时复制所选返回 null`() {
        assertNull(TranscriptCopyFormatter.formatSelected(lines, setOf(999L)))
    }

    @Test
    fun `复制所选在空列表上返回 null`() {
        assertNull(TranscriptCopyFormatter.formatSelected(emptyList(), setOf(1L)))
    }

    // ── 单行格式 ──

    @Test
    fun `单行格式为方括号时间戳加空格加文字`() {
        assertEquals(
            "[${TimeUtils.formatTime(line2.startMs)}] 第二句",
            TranscriptCopyFormatter.formatLine(line2),
        )
    }

    @Test
    fun `文字本身含空格与标点时原样保留`() {
        val tricky = line(tsId = 7L, startMs = 1000L, text = "喂 你好，能听见吗？")
        assertEquals("[${TimeUtils.formatTime(1000L)}] 喂 你好，能听见吗？", TranscriptCopyFormatter.formatLine(tricky))
    }

    private fun line(tsId: Long, startMs: Long, text: String): TranscriptLineUi =
        TranscriptLineUi(
            tsId = tsId,
            segmentId = 1L,
            startMs = startMs,
            endMs = startMs + 5L * 1000,
            text = text,
        )
}
