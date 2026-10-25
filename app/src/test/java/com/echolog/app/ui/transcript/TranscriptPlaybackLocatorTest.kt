package com.echolog.app.ui.transcript

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [TranscriptPlaybackLocator] 合并音频偏移换算回归测试（QA：转写全文页播放定位）。
 *
 * **为什么必须单测**：合并音频内的时间轴 = 各片段时长**累加**
 * （`AudioMerger` 是 PCM 纯直拼，不插静音间隙），而转写分段的 `startMs`
 * 是**全天绝对时间**（含片段间空闲间隔）。直接用绝对时间 seek 合并音频会
 * 跳到错误位置（跳到第 N 小时的空白处）。本测试锁定换算公式，
 * 防止「顺手改成绝对时间」这类回归。
 *
 * 覆盖主理人要求的三条边界：
 * 1. 目标是第一个片段 → mergedOffset == withinSegmentOffset；
 * 2. 目标是第二个且前面有空闲间隔 → mergedOffset == 片段1.durationMs + withinSegmentOffset
 *    （**不是绝对时间**）；
 * 3. 片段列表为空 / 找不到所属片段 → 安全回退值，不抛异常。
 */
class TranscriptPlaybackLocatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── 固定时间点（毫秒，仅用于可读性，值本身不影响算式） ──

    /** 片段 A：10:00:00 起。 */
    private val segAStart = 10L * 60 * 60 * 1000

    /** 片段 B：14:00:00 起（与 A 之间隔了 4 小时空闲）。 */
    private val segBStart = 14L * 60 * 60 * 1000

    /** 片段 A 时长 5 分钟。 */
    private val segADurationMs = 5L * 60 * 1000

    /** 片段 B 时长 3 分钟。 */
    private val segBDurationMs = 3L * 60 * 1000

    /** 片段 A 里的一句：开始于片段内第 1 分钟处。 */
    private val lineInAStartMs = segAStart + 60L * 1000

    /** 片段 B 里的一句：开始于片段内第 2 分钟处。 */
    private val lineInBStartMs = segBStart + 2L * 60 * 1000

    private val segmentA = AudioSegment(
        segmentId = 1L,
        dayId = "2026-10-24",
        filePath = "/tmp/segA.wav",
        startMs = segAStart,
        endMs = segAStart + segADurationMs,
        durationMs = segADurationMs,
    )

    private val segmentB = AudioSegment(
        segmentId = 2L,
        dayId = "2026-10-24",
        filePath = "/tmp/segB.wav",
        startMs = segBStart,
        endMs = segBStart + segBDurationMs,
        durationMs = segBDurationMs,
    )

    private val lineInA = TranscriptSegment(
        tsId = 11L,
        segmentId = segmentA.segmentId,
        startMs = lineInAStartMs,
        endMs = lineInAStartMs + 5L * 1000,
        text = "第一句",
    )

    private val lineInB = TranscriptSegment(
        tsId = 22L,
        segmentId = segmentB.segmentId,
        startMs = lineInBStartMs,
        endMs = lineInBStartMs + 5L * 1000,
        text = "第二句",
    )

    // ── 边界 1：目标是第一个片段 ──

    @Test
    fun `目标是第一个片段时 mergedOffset 等于片段内偏移`() {
        val offset = TranscriptPlaybackLocator.mergedOffsetMs(
            transcript = lineInA,
            segments = listOf(segmentA, segmentB),
        )

        val within = TranscriptPlaybackLocator.withinSegmentOffsetMs(lineInA, segmentA)
        assertEquals("前缀时长为 0，应等于片段内偏移", within, offset)
        assertEquals(60L * 1000, offset)
    }

    // ── 边界 2：目标是第二个且前面有空闲间隔 ──

    @Test
    fun `目标是第二个片段时 mergedOffset 等于前缀时长加片段内偏移而非绝对时间`() {
        val offset = TranscriptPlaybackLocator.mergedOffsetMs(
            transcript = lineInB,
            segments = listOf(segmentA, segmentB),
        )

        val within = TranscriptPlaybackLocator.withinSegmentOffsetMs(lineInB, segmentB)
        val expected = segADurationMs + within

        assertEquals("应是片段1时长 + 片段内偏移", expected, offset)
        // 5 分钟（A 的时长）+ 2 分钟（B 内偏移）= 7 分钟
        assertEquals(7L * 60 * 1000, offset)
    }

    @Test
    fun `mergedOffset 不是全天绝对时间`() {
        val offset = TranscriptPlaybackLocator.mergedOffsetMs(
            transcript = lineInB,
            segments = listOf(segmentA, segmentB),
        )

        // 回归护栏：绝对时间是 14:02（约 842 分钟），远大于合并文件内偏移 7 分钟。
        // 若有人把实现改成直接用 transcript.startMs，这里立刻失败。
        assertTrue(
            "换算后的偏移必须远小于绝对时间（实际 $offset vs ${lineInB.startMs}）",
            offset < lineInB.startMs / 100,
        )
    }

    @Test
    fun `片段顺序不影响前缀时长计算`() {
        // 入参乱序：内部按 startMs 升序排序
        val shuffled = listOf(segmentB, segmentA)
        val offset = TranscriptPlaybackLocator.mergedOffsetMs(lineInB, shuffled)
        assertEquals(segADurationMs + 2L * 60 * 1000, offset)
    }

    // ── 边界 3：空列表 / 找不到所属片段 ──

    @Test
    fun `片段列表为空时返回零且不抛异常`() {
        val offset = TranscriptPlaybackLocator.mergedOffsetMs(lineInA, emptyList())
        assertEquals(0L, offset)
    }

    @Test
    fun `找不到所属片段时返回零且不抛异常`() {
        val orphan = lineInB.copy(segmentId = 999L)
        val offset = TranscriptPlaybackLocator.mergedOffsetMs(
            transcript = orphan,
            segments = listOf(segmentA, segmentB),
        )
        assertEquals(0L, offset)
    }

    @Test
    fun `durationMs 为负数时按零处理不抛异常`() {
        val weird = segmentA.copy(durationMs = -1L)
        val offset = TranscriptPlaybackLocator.mergedOffsetMs(
            transcript = lineInB,
            segments = listOf(weird, segmentB),
        )
        // 负时长按 0 计 → 只剩片段内偏移 2 分钟
        assertEquals(2L * 60 * 1000, offset)
    }

    // ── 片段内偏移 ──

    @Test
    fun `片段内偏移为绝对时间减片段起点`() {
        assertEquals(
            60L * 1000,
            TranscriptPlaybackLocator.withinSegmentOffsetMs(lineInA, segmentA),
        )
        assertEquals(
            2L * 60 * 1000,
            TranscriptPlaybackLocator.withinSegmentOffsetMs(lineInB, segmentB),
        )
    }

    @Test
    fun `片段内偏移为负数时按零处理`() {
        // 数据异常：转写起点早于片段起点
        val beforeStart = lineInA.copy(startMs = segmentA.startMs - 10L * 1000)
        assertEquals(
            0L,
            TranscriptPlaybackLocator.withinSegmentOffsetMs(beforeStart, segmentA),
        )
    }

    // ── 播放源选择：合并音频优先 ──

    @Test
    fun `合并音频存在时使用合并音频并给出累计偏移`() {
        val mergedFile = tempFolder.newFile("merged.wav")

        val target = TranscriptPlaybackLocator.resolveTarget(
            transcript = lineInB,
            segments = listOf(segmentA, segmentB),
            mergedAudioPath = mergedFile.absolutePath,
            mergedAudioExists = TranscriptPlaybackLocator.mergedAudioFileExists(
                mergedFile.absolutePath,
            ),
        )

        requireNotNull(target)
        assertEquals(TranscriptPlaybackSource.MERGED_AUDIO, target.source)
        assertEquals(mergedFile.absolutePath, target.path)
        assertEquals(segADurationMs + 2L * 60 * 1000, target.offsetMs)
    }

    @Test
    fun `合并音频路径为空时回退片段 WAV 与片段内偏移`() {
        val target = TranscriptPlaybackLocator.resolveTarget(
            transcript = lineInB,
            segments = listOf(segmentA, segmentB),
            mergedAudioPath = null,
            mergedAudioExists = false,
        )

        requireNotNull(target)
        assertEquals(TranscriptPlaybackSource.SEGMENT_AUDIO, target.source)
        assertEquals("/tmp/segB.wav", target.path)
        assertEquals(2L * 60 * 1000, target.offsetMs)
    }

    @Test
    fun `合并音频路径非空但文件不存在时回退片段 WAV`() {
        val target = TranscriptPlaybackLocator.resolveTarget(
            transcript = lineInA,
            segments = listOf(segmentA, segmentB),
            mergedAudioPath = "/tmp/does-not-exist.wav",
            mergedAudioExists = TranscriptPlaybackLocator.mergedAudioFileExists(
                "/tmp/does-not-exist.wav",
            ),
        )

        requireNotNull(target)
        assertEquals(TranscriptPlaybackSource.SEGMENT_AUDIO, target.source)
        assertEquals("/tmp/segA.wav", target.path)
        assertEquals(60L * 1000, target.offsetMs)
    }

    @Test
    fun `合并音频与所属片段都不可用时返回 null 且不抛异常`() {
        val orphan = lineInA.copy(segmentId = 999L)
        val target = TranscriptPlaybackLocator.resolveTarget(
            transcript = orphan,
            segments = listOf(segmentA, segmentB),
            mergedAudioPath = null,
            mergedAudioExists = false,
        )

        assertNull("所属片段缺失且无合并音频可用时应返回 null", target)
    }

    @Test
    fun `合并音频存在但所属片段缺失时仍用合并音频与零偏移回退`() {
        val orphan = lineInA.copy(segmentId = 999L)
        val mergedFile = tempFolder.newFile("merged-orphan.wav")

        val target = TranscriptPlaybackLocator.resolveTarget(
            transcript = orphan,
            segments = listOf(segmentA, segmentB),
            mergedAudioPath = mergedFile.absolutePath,
            mergedAudioExists = true,
        )

        requireNotNull(target)
        assertEquals(TranscriptPlaybackSource.MERGED_AUDIO, target.source)
        // 找不到所属片段 → mergedOffsetMs 安全回退 0（不抛异常）
        assertEquals(0L, target.offsetMs)
    }

    // ── 文件存在性判定 ──

    @Test
    fun `mergedAudioFileExists 对真实文件返回 true`() {
        val mergedFile = tempFolder.newFile("real.wav")
        assertTrue(TranscriptPlaybackLocator.mergedAudioFileExists(mergedFile.absolutePath))
    }

    @Test
    fun `mergedAudioFileExists 对空白与不存在的路径返回 false`() {
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists(null))
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists(""))
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists("   "))
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists("/tmp/nope-xyz.wav"))
    }

    @Test
    fun `mergedAudioFileExists 对目录返回 false`() {
        val dir: File = tempFolder.newFolder("adir")
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists(dir.absolutePath))
    }

    // ── 所属片段查找 ──

    @Test
    fun `locateSegment 按 segmentId 匹配且不依赖入参顺序`() {
        val found = TranscriptPlaybackLocator.locateSegment(lineInB, listOf(segmentB, segmentA))
        assertEquals(segmentB, found)
    }

    @Test
    fun `locateSegment 找不到时返回 null`() {
        val orphan = lineInA.copy(segmentId = 999L)
        assertNull(TranscriptPlaybackLocator.locateSegment(orphan, listOf(segmentA, segmentB)))
    }
}
