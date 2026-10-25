package com.echolog.app.data.media

import com.echolog.app.core.util.WavIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [AudioMerger] 单元测试（QA：架构 1.4 决策 3 PCM 直拼 / FR-007）。
 *
 * 覆盖：PCM 顺序拼接、单一 WAV 头重写、进度回调、空结果失败语义。
 */
class AudioMergerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    // ── 正常合并 ──

    @Test
    fun `合并两个片段为PCM直拼且头部正确`() {
        val seg1 = writeWav("seg1.wav", shortArrayOf(1, 2, 3, -1, -2, -3))
        val seg2 = writeWav("seg2.wav", shortArrayOf(10, 20, -10, -20))
        val out = File(tempFolder.root, "day_2026-10-24.wav")

        val result = AudioMerger().mergeSegments(listOf(seg1.path, seg2.path), out.path)

        assertEquals(out.path, result)
        val format = WavIo.readHeader(out.path)
        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        // 10 样本 × 2 字节 = 20 字节 PCM（不含 44 字节头）
        assertEquals(20L, format.dataSize)

        val merged = WavIo.readFloatSamples(out.path)
        assertEquals(10, merged.size)
        assertArrayEquals(
            floatArrayOf(
                1f / 32768f, 2f / 32768f, 3f / 32768f,
                -1f / 32768f, -2f / 32768f, -3f / 32768f,
                10f / 32768f, 20f / 32768f,
                -10f / 32768f, -20f / 32768f,
            ),
            merged,
            1e-6f,
        )
    }

    @Test
    fun `合并单片段等于原样拷贝`() {
        val seg = writeWav("only.wav", shortArrayOf(100, -100, 200))
        val out = File(tempFolder.root, "day_single.wav")

        AudioMerger().mergeSegments(listOf(seg.path), out.path)

        assertEquals(seg.length(), out.length()) // 头 + 数据等长
        assertArrayEquals(seg.readBytes(), out.readBytes())
    }

    @Test
    fun `合并三个片段保持顺序与进度回调`() {
        val paths = (1..3).map { writeWav("s$it.wav", shortArrayOf(it.toShort(), (-it).toShort())).path }
        val out = File(tempFolder.root, "day_three.wav")
        val progresses = mutableListOf<Int>()

        AudioMerger().mergeSegments(paths, out.path) { progresses.add(it) }

        assertEquals(listOf(33, 66, 100), progresses)
        val samples = WavIo.readFloatSamples(out.path)
        assertEquals(6, samples.size)
        assertEquals(1f / 32768f, samples[0], 1e-6f)
        assertEquals(2f / 32768f, samples[2], 1e-6f)
        assertEquals(3f / 32768f, samples[4], 1e-6f)
    }

    // ── 失败语义 ──

    @Test(expected = IllegalArgumentException::class)
    fun `空片段列表抛异常`() {
        AudioMerger().mergeSegments(emptyList(), File(tempFolder.root, "x.wav").path)
    }

    @Test(expected = IOException::class)
    fun `所有片段均无音频数据时删除输出并抛异常`() {
        val empty = writeWav("empty.wav", shortArrayOf())
        val out = File(tempFolder.root, "day_empty.wav")

        try {
            AudioMerger().mergeSegments(listOf(empty.path), out.path)
        } finally {
            assertTrue("失败后应删除残留输出文件", !out.exists())
        }
    }

    @Test(expected = IOException::class)
    fun `全部片段均损坏时抛异常`() {
        // BUG-11 修复后的契约：单个损坏片段会被跳过（不拖垮整天），
        // 只有**全部**片段都损坏/格式不一致时才抛异常。
        val bad = tempFolder.newFile("bad.wav")
        bad.writeBytes(ByteArray(100) { 0x11 })
        AudioMerger().mergeSegments(listOf(bad.path), File(tempFolder.root, "y.wav").path)
    }

    @Test
    fun `损坏片段被跳过其余片段正常合并`() {
        // BUG-11 修复：占位头未回填（进程被杀）的片段不应拖垮整天的合并。
        val broken = tempFolder.newFile("broken.wav")
        broken.writeBytes(ByteArray(100)) // 44 字节全零占位头 + 杂数据 → 头部非法
        val good1 = writeWav("good1.wav", shortArrayOf(1, 2, 3))
        val good2 = writeWav("good2.wav", shortArrayOf(4, 5))
        val out = File(tempFolder.root, "day_partial.wav")

        AudioMerger().mergeSegments(listOf(broken.path, good1.path, good2.path), out.path)

        val format = WavIo.readHeader(out.path)
        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
        // 仅含两个有效片段的 5 个样本 = 10 字节
        assertEquals(10L, format.dataSize)
        val merged = WavIo.readFloatSamples(out.path)
        assertEquals(5, merged.size)
        assertEquals(1f / 32768f, merged[0], 1e-6f)
        assertEquals(4f / 32768f, merged[3], 1e-6f)
    }

    @Test
    fun `采样率不一致的片段被跳过其余正常合并`() {
        // BUG-11 修复：同一天内采样率混用（用户中途改设置 + 服务重启）不应让
        // 整天的合并失败——PCM 直拼会产生变速音频，故跳过不一致的片段。
        val seg16k = writeWav("seg16k.wav", shortArrayOf(1, 2, 3), sampleRate = 16000)
        val seg48k = writeWav("seg48k.wav", shortArrayOf(9, 9, 9), sampleRate = 48000)
        val out = File(tempFolder.root, "day_mixed.wav")

        AudioMerger().mergeSegments(listOf(seg16k.path, seg48k.path), out.path)

        val format = WavIo.readHeader(out.path)
        assertEquals(16000, format.sampleRate)
        // 3 个 16bit 样本 = 6 字节 PCM（48kHz 的那个片段已被跳过）
        assertEquals(6L, format.dataSize)
        val merged = WavIo.readFloatSamples(out.path)
        assertEquals(3, merged.size)
        assertEquals(1f / 32768f, merged[0], 1e-6f)
    }

    // ── 工具 ──

    private fun writeWav(name: String, pcm: ShortArray, sampleRate: Int = 16000): File {
        val file = tempFolder.newFile(name)
        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            val buffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buffer.putShort(it) }
            raf.write(buffer.array())
            WavIo.patchHeader(raf, pcm.size * 2L, sampleRate, 1)
        }
        return file
    }
}
