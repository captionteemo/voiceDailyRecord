package com.echolog.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RecordingNamer] 单元测试（QA：用户需求 1 命名规范）。
 *
 * 覆盖：片段基名/文件名、转写同名 .md、全天合并文件名、序号解析边界。
 */
class RecordingNamerTest {

    @Test
    fun `片段基名格式为日期加第N次`() {
        assertEquals("2026-10-25_第3次", RecordingNamer.segmentBaseName("2026-10-25", 3))
    }

    @Test
    fun `序号最小为1即使传入0或负数`() {
        assertEquals("2026-10-25_第1次", RecordingNamer.segmentBaseName("2026-10-25", 0))
        assertEquals("2026-10-25_第1次", RecordingNamer.segmentBaseName("2026-10-25", -5))
    }

    @Test
    fun `WAV与MD文件名仅扩展名不同且严格对应`() {
        assertEquals("2026-10-25_第3次.wav", RecordingNamer.segmentFileName("2026-10-25", 3))
        assertEquals("2026-10-25_第3次.md", RecordingNamer.transcriptFileName("2026-10-25", 3))
        // 同名约束：剥离扩展名后基名必须一致（保证「一键打开目录」音频与文字稿成对）
        val wav = RecordingNamer.segmentFileName("2026-10-25", 3)
        val md = RecordingNamer.transcriptFileName("2026-10-25", 3)
        assertEquals(wav.substringBeforeLast('.'), md.substringBeforeLast('.'))
    }

    @Test
    fun `全天合并文件名带全天合并后缀`() {
        assertEquals("2026-10-25_全天合并.wav", RecordingNamer.mergedFileName("2026-10-25"))
    }

    @Test
    fun `解析序号从基名提取第N次`() {
        assertEquals(3, RecordingNamer.parseOrdinal("2026-10-25_第3次"))
        assertEquals(12, RecordingNamer.parseOrdinal("2026-10-25_第12次.wav"))
    }

    @Test
    fun `旧命名或非法基名解析为null`() {
        assertNull(RecordingNamer.parseOrdinal("seg_2026-10-25_1"))
        assertNull(RecordingNamer.parseOrdinal("2026-10-25_全天合并"))
        assertNull(RecordingNamer.parseOrdinal("随便一段"))
    }
}
