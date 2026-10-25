package com.echolog.app.core.util

import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TranscriptMarkdown] 单元测试（QA：用户需求 1 转写 .md 文案契约）。
 *
 * 覆盖：标题/元信息头、音频文件名对齐、相对片段起点的时间戳行、
 * 段内打点、空转写占位、序号省略。
 */
class TranscriptMarkdownTest {

    private val dayId = "2026-10-25"
    private val audioFileName = "2026-10-25_第3次.wav"

    @Test
    fun `标题与时间轴与音频文件名对齐`() {
        val md = TranscriptMarkdown.build(
            dayId = dayId,
            ordinal = 3,
            startMs = 1_000L,
            endMs = 7_000L,
            audioFileName = audioFileName,
            transcripts = emptyList(),
            markers = emptyList(),
        )
        assertTrue(md.startsWith("# $dayId 第3次录音"))
        assertTrue(md.contains("- 日期：$dayId"))
        assertTrue(md.contains("- 音频文件：$audioFileName"))
        // 时长 = 6000ms → HH:MM:SS = 00:00:06
        assertTrue(md.contains("时长：00:00:06"))
    }

    @Test
    fun `转写行按相对片段起点生成MMSS时间戳`() {
        val md = TranscriptMarkdown.build(
            dayId = dayId,
            ordinal = 1,
            startMs = 10_000L,
            endMs = 0L,
            audioFileName = "2026-10-25_第1次.wav",
            transcripts = listOf(
                TranscriptSegment(segmentId = 0L, startMs = 10_500L, endMs = 11_000L, text = "上午好"),
                TranscriptSegment(segmentId = 0L, startMs = 14_000L, endMs = 14_500L, text = "开会"),
            ),
            markers = emptyList(),
        )
        // 相对起点 10_000：500ms → 00:00，4000ms → 00:04
        assertTrue(md.contains("- [00:00] 上午好"))
        assertTrue(md.contains("- [00:04] 开会"))
    }

    @Test
    fun `段内打点以相对时刻呈现并含备注`() {
        val md = TranscriptMarkdown.build(
            dayId = dayId,
            ordinal = 2,
            startMs = 0L,
            endMs = 0L,
            audioFileName = "2026-10-25_第2次.wav",
            transcripts = emptyList(),
            markers = listOf(
                Marker(dayId = dayId, atMs = 65_000L, note = "重要决定"),
                Marker(dayId = dayId, atMs = 3_000L, note = ""),
            ),
        )
        assertTrue(md.contains("## 打点标记"))
        assertTrue(md.contains("- [01:05] 重要决定")) // 65s → 01:05
        assertTrue(md.contains("- [00:03] （标记）")) // 空备注占位
    }

    @Test
    fun `无转写内容显示占位且不崩溃`() {
        val md = TranscriptMarkdown.build(
            dayId = dayId,
            ordinal = 5,
            startMs = 0L,
            endMs = 0L,
            audioFileName = "2026-10-25_第5次.wav",
            transcripts = emptyList(),
            markers = emptyList(),
        )
        assertTrue(md.contains("（无转写内容）"))
    }

    @Test
    fun `序号为0时标题省略第N次`() {
        val md = TranscriptMarkdown.build(
            dayId = dayId,
            ordinal = 0,
            startMs = 0L,
            endMs = 0L,
            audioFileName = "$dayId.wav",
            transcripts = emptyList(),
            markers = emptyList(),
        )
        assertTrue(md.startsWith("# $dayId 录音"))
    }
}
