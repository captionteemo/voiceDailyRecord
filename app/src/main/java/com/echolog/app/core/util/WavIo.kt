package com.echolog.app.core.util

import com.echolog.app.core.common.Constants
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV（PCM_16）读写工具（架构 1.6：同格式片段可直接 PCM 拼接）。
 *
 * 统一支撑三类场景：
 * 1. [AudioRecorder] 写片段时的头部占位与收尾修补；
 * 2. [com.echolog.app.data.media.AudioMerger] 合并时的头部解析与流式拷贝；
 * 3. `SenseVoiceAsrEngine` 转写时的样本读取（PCM16 → FloatArray）。
 *
 * 仅支持 RIFF/WAVE、audioFormat=1（PCM）、16bit；其余格式抛 [IOException]。
 */
object WavIo {

    /** WAV 文件格式信息（偏移与大小为绝对字节值）。 */
    data class WavFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    )

    private const val HEADER_BYTES = 44

    /**
     * RIFF 块长度上限（4GB - 1 字节）。
     *
     * RIFF/WAVE 的块长度字段为**无符号** 32 位整数，故单文件 PCM 上限约 4GB；
     * 而 Java `Int` 为有符号（上限 2GB-1），直接写入会在 >2GB 时溢出成负数，
     * 播放器将无法解析。本对象按无符号语义手工写 4 字节小端序。
     */
    const val MAX_RIFF_BYTES: Long = 0xFFFF_FFFFL

    /**
     * 单次读取的最大样本数（内存护栏）。
     *
     * ≈ 5 分钟 @16kHz 单声道（4.8M 样本 → FloatArray 约 19MB）。
     * 录音侧已通过 [Constants.maxSegmentDurationMs] 保证片段不超过此时长，
     * 此处为兜底防御（防止历史文件或异常文件导致 OOM）。
     */
    const val MAX_SAMPLES_PER_READ: Long = Constants.MAX_SAMPLES_PER_READ

    /** 写 44 字节占位头（收尾时由 [patchHeader] 回填真实长度）。 */
    fun writePlaceholderHeader(raf: RandomAccessFile) {
        raf.seek(0)
        raf.write(ByteArray(HEADER_BYTES))
    }

    /** 回填 WAV 头：RIFF/data 长度 + fmt 参数。 */
    fun patchHeader(
        raf: RandomAccessFile,
        dataBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int = 16,
    ) {
        require(channels in 1..2) { "仅支持单/双声道" }
        require(dataBytes in 0..MAX_RIFF_BYTES) { "PCM 数据超出 WAV 4GB 上限: $dataBytes" }
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        putUint32LE(header, 36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        putUint32LE(header, dataBytes)
        raf.seek(0)
        raf.write(header.array())
    }

    /** 写入无符号 32 位小端序整数（绕过 Java `Int` 的有符号上限 2GB）。 */
    private fun putUint32LE(buffer: ByteBuffer, value: Long) {
        buffer.put((value and 0xFF).toByte())
        buffer.put(((value shr 8) and 0xFF).toByte())
        buffer.put(((value shr 16) and 0xFF).toByte())
        buffer.put(((value shr 24) and 0xFF).toByte())
    }

    /** 解析 WAV 头；非 PCM16 或结构损坏抛 [IOException]。 */
    @Throws(IOException::class)
    fun readHeader(path: String): WavFormat {
        RandomAccessFile(path, "r").use { raf ->
            val riff = ByteArray(4).also { raf.readFully(it) }
            if (String(riff, Charsets.US_ASCII) != "RIFF") throw IOException("非 RIFF 文件: $path")
            raf.seek(8)
            val wave = ByteArray(4).also { raf.readFully(it) }
            if (String(wave, Charsets.US_ASCII) != "WAVE") throw IOException("非 WAVE 文件: $path")
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1L
            var dataSize = -1L
            // 遍历子块：fmt / data 可能夹带 LIST 等附加块
            while (true) {
                val chunkHeader = ByteArray(8)
                if (raf.read(chunkHeader) < 8) break
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                // 块长度为无符号 32 位：Java Int 有符号，需转无符号长整型
                val chunkSize = Integer.toUnsignedLong(
                    ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int,
                )
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(16).also { raf.readFully(it) }
                        val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = buffer.short.toInt()
                        channels = buffer.short.toInt()
                        sampleRate = buffer.int
                        buffer.int // byteRate
                        buffer.short // blockAlign
                        bits = buffer.short.toInt()
                        if (audioFormat != 1) throw IOException("仅支持 PCM（audioFormat=1）: $path")
                        if (bits != 16) throw IOException("仅支持 PCM16: $path")
                    }

                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = chunkSize
                        break
                    }

                    else -> {
                        val skip = chunkSize + (chunkSize % 2) // 块按 2 字节对齐
                        raf.seek(raf.filePointer + skip)
                    }
                }
            }
            if (dataOffset < 0 || dataSize < 0) throw IOException("缺少 data 块: $path")
            return WavFormat(sampleRate, channels, bits, dataOffset, dataSize)
        }
    }

    /** 打开 PCM 数据流（已跳过文件头；调用方负责关闭）。 */
    @Throws(IOException::class)
    fun openPcmStream(path: String): InputStream {
        val format = readHeader(path)
        val stream = BufferedInputStream(FileInputStream(path))
        var skipped = 0L
        while (skipped < format.dataOffset) {
            val n = stream.skip(format.dataOffset - skipped)
            if (n <= 0) break
            skipped += n
        }
        return stream
    }

    /**
     * 读取全部 PCM 样本并归一化为 [-1, 1] FloatArray（ASR 输入）。
     *
     * 内存护栏：结果数组长度与输入字节数同阶（16bit PCM → Float 放大 2 倍），
     * 超大文件会直接 OOM。此处限制最多 [MAX_SAMPLES_PER_READ] 个样本
     * （≈ 5 分钟 @16kHz 单声道），超出抛 [IOException] 由调用方标记片段失败。
     */
    @Throws(IOException::class)
    fun readFloatSamples(path: String): FloatArray {
        val format = readHeader(path)
        val sampleCountLong = format.dataSize / (format.channels * 2)
        if (sampleCountLong > MAX_SAMPLES_PER_READ) {
            throw IOException("音频样本数超限（$sampleCountLong > $MAX_SAMPLES_PER_READ）: $path")
        }
        val sampleCount = sampleCountLong.toInt()
        val result = FloatArray(sampleCount)
        openPcmStream(path).use { stream ->
            val buffer = ByteArray(sampleCount * format.channels * 2)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            val shorts = ByteBuffer.wrap(buffer, 0, filled).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            // BUG-18 修复：取首声道——每读一个样本后跳过 channels-1 个 short。
            // 旧实现用 index += channels 推进 result 下标却顺序读 shorts，
            // 双声道时 result 半数帧为 0 且混入右声道样本
            var index = 0
            while (index < sampleCount && shorts.remaining() >= format.channels) {
                result[index] = shorts.get() / 32768f
                repeat(format.channels - 1) { shorts.get() }
                index += 1
            }
        }
        return result
    }

    /** 文件是否为可解析的 PCM16 WAV（存在性 + 头部校验）。 */
    fun isValidPcm16Wav(file: File): Boolean =
        file.isFile && file.length() > HEADER_BYTES && runCatching { readHeader(file.path) }.isSuccess
}
