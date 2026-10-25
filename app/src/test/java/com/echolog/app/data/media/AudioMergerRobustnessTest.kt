package com.echolog.app.data.media

import com.echolog.app.core.util.WavIo
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
 * [AudioMerger] 容错回归测试（QA：BUG-11 修复验证）。
 *
 * 对应根因：真机上「结束录音后进度卡在 5%、无法翻译成文字」的三条并发/数据损坏路径：
 * 1. 录音进程被杀 → 片段只剩 44 字节全零占位头（[WavIo.writePlaceholderHeader] 未被
 *    [WavIo.patchHeader] 回填）；
 * 2. 用户中途修改采样率设置 + 服务重启 → 同一天内片段采样率混用；
 * 3. 上述两类叠加 → 全天片段全部不可用。
 *
 * 契约（BUG-11 修复 5）：
 * - 单个损坏/格式不一致片段被**跳过**，其余片段正常合并；
 * - 全部片段都不可用时才抛 [IOException]，且错误信息可辨识。
 */
class AudioMergerRobustnessTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    // ── 1. 占位头未回填的片段被跳过 ──

    @Test
    fun `占位头未回填的片段被跳过其余片段正常合并`() {
        // 模拟 beginSegmentFile 后进程被杀：文件只有 44 字节全零占位头
        val broken = tempFolder.newFile("broken_placeholder.wav")
        broken.writeBytes(ByteArray(PLACEHOLDER_HEADER_BYTES))
        val good1 = writeWav("good1.wav", shortArrayOf(1, 2, 3))
        val good2 = writeWav("good2.wav", shortArrayOf(4, 5))
        val out = File(tempFolder.root, "day_placeholder.wav")

        val result = AudioMerger().mergeSegments(
            listOf(broken.path, good1.path, good2.path),
            out.path,
        )

        assertEquals(out.path, result)
        assertTrue("合并输出文件应存在", out.isFile)

        val format = WavIo.readHeader(out.path)
        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        // dataSize = 另外两个片段的数据总长（各去掉 44 字节头）
        val expectedDataBytes = (good1.length() + good2.length()) - 2L * PLACEHOLDER_HEADER_BYTES
        assertEquals(expectedDataBytes, format.dataSize)
        assertEquals(10L, format.dataSize)

        val merged = WavIo.readFloatSamples(out.path)
        assertEquals(5, merged.size)
        assertEquals(1f / 32768f, merged[0], 1e-6f)
        assertEquals(3f / 32768f, merged[2], 1e-6f)
        assertEquals(4f / 32768f, merged[3], 1e-6f)
    }

    @Test
    fun `损坏片段不计入合并进度分母`() {
        // 被跳过的片段不应占用进度分母（否则进度永远到不了 100）
        val broken = tempFolder.newFile("broken2.wav")
        broken.writeBytes(ByteArray(PLACEHOLDER_HEADER_BYTES))
        val good = writeWav("good.wav", shortArrayOf(1, 2))
        val out = File(tempFolder.root, "day_progress.wav")
        val progresses = mutableListOf<Int>()

        AudioMerger().mergeSegments(listOf(broken.path, good.path), out.path) { progresses.add(it) }

        assertEquals(listOf(100), progresses)
    }

    // ── 2. 采样率不一致的片段被跳过 ──

    @Test
    fun `采样率不一致的片段被跳过输出只含首个采样率的片段`() {
        val seg16k = writeWav("seg16k.wav", shortArrayOf(1, 2, 3), sampleRate = 16000)
        val seg48k = writeWav("seg48k.wav", shortArrayOf(9, 9, 9, 9), sampleRate = 48000)
        val out = File(tempFolder.root, "day_mixed_rate.wav")

        val result = AudioMerger().mergeSegments(listOf(seg16k.path, seg48k.path), out.path)

        assertEquals(out.path, result)
        val format = WavIo.readHeader(out.path)
        assertEquals("输出应保持首个有效片段的采样率", 16000, format.sampleRate)
        assertEquals(1, format.channels)
        // 只含 16kHz 片段的 3 个样本 = 6 字节
        assertEquals(6L, format.dataSize)

        val merged = WavIo.readFloatSamples(out.path)
        assertEquals("48kHz 片段内容不得混入", 3, merged.size)
        assertEquals(1f / 32768f, merged[0], 1e-6f)
        assertEquals(2f / 32768f, merged[1], 1e-6f)
        assertEquals(3f / 32768f, merged[2], 1e-6f)
    }

    @Test
    fun `声道不一致的片段被跳过`() {
        val mono = writeWav("mono.wav", shortArrayOf(1, 2, 3), sampleRate = 16000, channels = 1)
        val stereo = writeWav("stereo.wav", shortArrayOf(7, 7, 7, 7), sampleRate = 16000, channels = 2)
        val out = File(tempFolder.root, "day_mixed_channel.wav")

        AudioMerger().mergeSegments(listOf(mono.path, stereo.path), out.path)

        val format = WavIo.readHeader(out.path)
        assertEquals(1, format.channels)
        assertEquals(6L, format.dataSize)
    }

    // ── 3. 全部片段损坏 ──

    @Test
    fun `全部片段损坏时抛出可辨识的IOException`() {
        val bad1 = tempFolder.newFile("bad1.wav")
        bad1.writeBytes(ByteArray(PLACEHOLDER_HEADER_BYTES))
        val bad2 = tempFolder.newFile("bad2.wav")
        bad2.writeBytes(ByteArray(PLACEHOLDER_HEADER_BYTES))
        val out = File(tempFolder.root, "day_all_bad.wav")

        val error = runCatching {
            AudioMerger().mergeSegments(listOf(bad1.path, bad2.path), out.path)
        }.exceptionOrNull()

        assertTrue("应抛 IOException，实际：$error", error is IOException)
        assertTrue(
            "错误信息应含可辨识文案，实际：${error?.message}",
            error?.message?.contains("没有可合并的有效片段") == true,
        )
        assertTrue("全部无效时不应产出输出文件", !out.exists())
    }

    @Test
    fun `只有一个片段有效且另一个采样率不同时仍成功合并`() {
        // 48kHz 在前 → 以 48kHz 为基准；16kHz 与之不一致 → 被跳过；
        // 剩余一个有效片段 → 合并成功。
        val seg48k = writeWav("only48.wav", shortArrayOf(1, 2), sampleRate = 48000)
        val seg16k = writeWav("only16.wav", shortArrayOf(3, 4), sampleRate = 16000)
        val out = File(tempFolder.root, "day_single_valid.wav")

        AudioMerger().mergeSegments(listOf(seg48k.path, seg16k.path), out.path)

        val format = WavIo.readHeader(out.path)
        assertEquals(48000, format.sampleRate)
        assertEquals(4L, format.dataSize)
    }

    // ── 工具 ──

    private fun writeWav(
        name: String,
        pcm: ShortArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
    ): File {
        val file = tempFolder.newFile(name)
        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            val buffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buffer.putShort(it) }
            raf.write(buffer.array())
            WavIo.patchHeader(raf, pcm.size * 2L, sampleRate, channels)
        }
        return file
    }

    private companion object {
        /** WAV 头长度（占位头字节数）。 */
        const val PLACEHOLDER_HEADER_BYTES = 44
    }
}
