package com.echolog.app.core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [WavIo] 单元测试（QA：架构 1.6 PCM16 直拼的头部契约）。
 *
 * 覆盖：占位头/回填 round-trip、RIFF 长度字段、附加块跳过、
 * PCM 流定位、样本归一化、非法格式异常、isValidPcm16Wav 边界。
 */
class WavIoTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    // ── 占位头 + 回填 round-trip ──

    @Test
    fun `占位头回填后头部字段正确`() {
        val file = tempFolder.newFile("seg.wav")
        val pcm = shortArrayOf(100, -200, 300, -400, 500) // 5 样本 = 10 字节
        val dataBytes = pcm.size * 2L

        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            val buffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buffer.putShort(it) }
            raf.write(buffer.array())
            WavIo.patchHeader(raf, dataBytes, 16000, 1)
        }

        val format = WavIo.readHeader(file.path)
        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertEquals(44L, format.dataOffset)
        assertEquals(dataBytes, format.dataSize)
    }

    @Test
    fun `RIFF与data长度字段符合规范`() {
        val file = tempFolder.newFile("riff.wav")
        val dataBytes = 800L // 400 样本
        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            raf.write(ByteArray(dataBytes.toInt()))
            WavIo.patchHeader(raf, dataBytes, 16000, 1)
        }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4) // RIFF 长度字段位于 offset 4（"RIFF" 之后）
            val riffSize = ByteBuffer.wrap(ByteArray(4).also { raf.readFully(it) }, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            raf.seek(40)
            val dataSize = ByteBuffer.wrap(ByteArray(4).also { raf.readFully(it) }, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(36 + dataBytes.toInt(), riffSize)
            assertEquals(dataBytes.toInt(), dataSize)
        }
    }

    @Test
    fun `dataSize为0的合法头部可解析`() {
        val file = tempFolder.newFile("empty.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            WavIo.patchHeader(raf, 0L, 16000, 1)
        }
        val format = WavIo.readHeader(file.path)
        assertEquals(0L, format.dataSize)
        assertTrue(WavIo.readFloatSamples(file.path).isEmpty())
    }

    // ── PCM 流与样本归一化 ──

    @Test
    fun `openPcmStream精确定位到数据区`() {
        val file = tempFolder.newFile("stream.wav")
        val pcm = shortArrayOf(1, 2, 3, 4, -5, -6)
        writeWav(file, pcm, sampleRate = 16000, channels = 1)

        WavIo.openPcmStream(file.path).use { stream ->
            val bytes = stream.readBytes()
            assertArrayEquals(
                ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    pcm.forEach { putShort(it) }
                }.array(),
                bytes,
            )
        }
    }

    @Test
    fun `readFloatSamples归一化到正负1之间`() {
        val file = tempFolder.newFile("float.wav")
        writeWav(file, shortArrayOf(32767, -32768, 16384, 0), sampleRate = 16000, channels = 1)

        val samples = WavIo.readFloatSamples(file.path)
        assertEquals(4, samples.size)
        assertEquals(32767f / 32768f, samples[0], 1e-6f)
        assertEquals(-1.0f, samples[1], 1e-6f)
        assertEquals(0.5f, samples[2], 1e-6f)
        assertEquals(0.0f, samples[3], 1e-6f)
    }

    @Test
    fun `双声道读取取首声道`() {
        val file = tempFolder.newFile("stereo.wav")
        // 交错 L/R：L=1000, R=7
        writeWav(file, shortArrayOf(1000, 7, 2000, 7, -1000, 7), sampleRate = 16000, channels = 2)

        // 契约：readFloatSamples 应取每个帧的首（左）声道，跳过错综的其余声道样本。
        // 注意：当前实现（index += channels 但 shorts 顺序读取）会混入右声道样本并留下 0，
        // 本测试按契约期望值编写，失败即为源码缺陷证据（BUG-18，P2 潜伏：EchoLog 仅产单声道）。
        val samples = WavIo.readFloatSamples(file.path)
        assertEquals(3, samples.size)
        assertEquals(1000f / 32768f, samples[0], 1e-6f)
        assertEquals(2000f / 32768f, samples[1], 1e-6f)
        assertEquals(-1000f / 32768f, samples[2], 1e-6f)
    }

    // ── 附加块与非法格式 ──

    @Test
    fun `fmt与data之间夹带LIST附加块时可正确解析`() {
        val file = tempFolder.newFile("list.wav")
        val pcm = shortArrayOf(10, 20, 30)
        // 附加块负载须为偶数长度（奇数需 1 字节 padding，此处构造偶数负载避免歧义）
        val listPayload = "INFOhello!".toByteArray(Charsets.US_ASCII) // 10 字节

        RandomAccessFile(file, "rw").use { raf ->
            val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(1) // PCM
                putShort(1) // mono
                putInt(16000)
                putInt(16000 * 2)
                putShort(2)
                putShort(16)
            }.array()
            val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                pcm.forEach { putShort(it) }
            }.array()
            val total = 4 + 4 + 4 + (8 + fmt.size) + (8 + listPayload.size) + (8 + data.size)
            val header = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(total - 8)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(fmt.size)
                put(fmt)
                put("LIST".toByteArray(Charsets.US_ASCII))
                putInt(listPayload.size)
                put(listPayload)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(data.size)
                put(data)
            }
            raf.write(header.array())
        }

        val format = WavIo.readHeader(file.path)
        assertEquals(16000, format.sampleRate)
        assertEquals(pcm.size * 2L, format.dataSize)
        val samples = WavIo.readFloatSamples(file.path)
        assertEquals(3, samples.size)
        assertEquals(10f / 32768f, samples[0], 1e-6f)
    }

    @Test(expected = java.io.IOException::class)
    fun `非RIFF文件抛异常`() {
        val file = tempFolder.newFile("garbage.bin")
        file.writeBytes(ByteArray(64) { 0x42 })
        WavIo.readHeader(file.path)
    }

    @Test(expected = java.io.IOException::class)
    fun `非PCM编码抛异常`() {
        val file = tempFolder.newFile("float32.wav")
        RandomAccessFile(file, "rw").use { raf ->
            val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(3) // IEEE float，非 PCM
                putShort(1)
                putInt(16000)
                putInt(16000 * 4)
                putShort(4)
                putShort(32)
            }.array()
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                put(fmt)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(0)
            }
            raf.write(header.array())
        }
        WavIo.readHeader(file.path)
    }

    @Test(expected = java.io.IOException::class)
    fun `缺少data块抛异常`() {
        val file = tempFolder.newFile("nodata.wav")
        RandomAccessFile(file, "rw").use { raf ->
            val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(1)
                putShort(1)
                putInt(16000)
                putInt(32000)
                putShort(2)
                putShort(16)
            }.array()
            val header = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(28)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                put(fmt)
            }
            raf.write(header.array())
        }
        WavIo.readHeader(file.path)
    }

    // ── isValidPcm16Wav 边界 ──

    @Test
    fun `isValidPcm16Wav按存在性与头部校验判定`() {
        val valid = tempFolder.newFile("valid.wav")
        writeWav(valid, shortArrayOf(1, 2, 3), 16000, 1)
        assertTrue(WavIo.isValidPcm16Wav(valid))

        val headerOnly = tempFolder.newFile("header-only.wav")
        RandomAccessFile(headerOnly, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            WavIo.patchHeader(raf, 0L, 16000, 1)
        }
        // 44 字节整：无数据区，判否（length > HEADER_BYTES 不成立）
        assertFalse(WavIo.isValidPcm16Wav(headerOnly))

        val garbage = tempFolder.newFile("garbage2.bin")
        garbage.writeBytes(ByteArray(100) { 0x7F })
        assertFalse(WavIo.isValidPcm16Wav(garbage))

        assertFalse(WavIo.isValidPcm16Wav(File(tempFolder.root, "not-exist.wav")))
    }

    // ── 工具 ──

    private fun writeWav(
        file: File,
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
    ) {
        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            val buffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buffer.putShort(it) }
            raf.write(buffer.array())
            WavIo.patchHeader(raf, pcm.size * 2L, sampleRate, channels)
        }
    }
}
