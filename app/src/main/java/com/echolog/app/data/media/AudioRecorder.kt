package com.echolog.app.data.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.WavIo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioRecord 封装（架构 2.3/1.6/3.3）。
 *
 * 职责：
 * 1. 16kHz / mono / PCM16 采集（ASR 原生输入）；
 * 2. 300ms 预录环形缓冲（[pushPreBuffer]）——VAD 触发开录时回吐，保证突变起点不被吞；
 * 3. 片段 WAV 落盘（[beginSegmentFile]/[writeFrame]/[endSegmentFile]）——头部占位、收尾回填长度。
 *
 * 线程约定（架构 7.3）：所有方法只允许从 `EchoAudioThread` 调用，
 * 内部 [lock] 仅防御 [release] 与音频线程的竞态；禁止在调用方做 DB/网络。
 */
class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var segmentFile: RandomAccessFile? = null
    private var segmentDataBytes: Long = 0L
    private var segmentOpen: Boolean = false
    private val preBuffer = FrameRingBuffer()
    private val lock = Any()

    var sampleRate: Int = Constants.SAMPLE_RATE
        private set
    var channels: Int = Constants.CHANNEL_COUNT
        private set

    /**
     * 打开并启动采集；失败（如麦克风被占用）返回 false，调用方按 E-1001 处理。
     *
     * @param sampleRate 采样率（默认 16000）。
     * @param channels 声道数（默认单声道）。
     */
    fun open(
        sampleRate: Int = Constants.SAMPLE_RATE,
        channels: Int = Constants.CHANNEL_COUNT,
    ): Boolean {
        synchronized(lock) {
            closeSegmentLocked()
            audioRecord?.let { existing ->
                if (existing.state == AudioRecord.STATE_INITIALIZED) {
                    runCatching { existing.stop() }
                }
                existing.release()
                audioRecord = null
            }
            val channelConfig =
                if (channels <= 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) return false
            // 缓冲放大到约 2 秒，容忍 DB 写入等短时抖动
            val bufferBytes = maxOf(minBufferBytes, sampleRate * channels * BYTES_PER_SAMPLE * 2)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }
            record.startRecording()
            audioRecord = record
            this.sampleRate = sampleRate
            this.channels = channels
            preBuffer.reset()
            return true
        }
    }

    /**
     * 阻塞读取一帧（建议帧长 25ms/400 样本）。
     *
     * @return 实际读取样本数；负数表示 [AudioRecord] 错误码。
     */
    fun readFrame(buffer: ShortArray): Int {
        val record = audioRecord ?: return ERROR_NOT_OPENED
        return record.read(buffer, 0, buffer.size)
    }

    /** 未开录时喂入预录环形缓冲（内部拷贝，调用方可复用缓冲区）。 */
    fun pushPreBuffer(frame: ShortArray) {
        preBuffer.push(frame)
    }

    /**
     * 开始片段文件：先写占位头，再把环形缓冲中最近 300ms 音频回吐入文件。
     *
     * @return 是否成功打开（已打开时返回 false）。
     */
    fun beginSegmentFile(path: String): Boolean {
        synchronized(lock) {
            if (segmentOpen) return false
            val file = File(path)
            file.parentFile?.mkdirs()
            val raf = try {
                RandomAccessFile(file, "rw")
            } catch (_: IOException) {
                return false
            }
            raf.setLength(0)
            WavIo.writePlaceholderHeader(raf)
            segmentFile = raf
            segmentDataBytes = 0L
            segmentOpen = true
            preBuffer.drain { frame -> writeRawLocked(frame) }
            return true
        }
    }

    /** 写入一帧 PCM（未开录时静默丢弃）。 */
    fun writeFrame(frame: ShortArray) {
        synchronized(lock) {
            if (segmentOpen) writeRawLocked(frame)
        }
    }

    /**
     * 结束片段文件并回填 WAV 头。
     *
     * @return 写入的 PCM 字节数（未开录返回 0）。
     */
    fun endSegmentFile(): Long {
        synchronized(lock) {
            return closeSegmentLocked()
        }
    }

    /** 释放采集资源（服务销毁时调用；未关闭的片段会被安全收尾）。 */
    fun release() {
        synchronized(lock) {
            closeSegmentLocked()
            audioRecord?.let { record ->
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    runCatching { record.stop() }
                }
                record.release()
            }
            audioRecord = null
        }
    }

    private fun closeSegmentLocked(): Long {
        if (!segmentOpen) return 0L
        val raf = segmentFile ?: return 0L
        val dataBytes = segmentDataBytes
        WavIo.patchHeader(raf, dataBytes, sampleRate, channels)
        runCatching { raf.close() }
        segmentFile = null
        segmentOpen = false
        preBuffer.reset()
        return dataBytes
    }

    private fun writeRawLocked(frame: ShortArray) {
        val raf = segmentFile ?: return
        val bytes = ByteBuffer.allocate(frame.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in frame) {
            bytes.putShort(sample)
        }
        val array = bytes.array()
        raf.write(array)
        segmentDataBytes += array.size.toLong()
    }

    private class FrameRingBuffer(private val capacityFrames: Int = DEFAULT_CAPACITY_FRAMES) {

        private val frames = arrayOfNulls<ShortArray>(capacityFrames)
        private var head: Int = 0
        private var size: Int = 0

        fun push(frame: ShortArray) {
            frames[head] = frame.copyOf()
            head = (head + 1) % capacityFrames
            if (size < capacityFrames) size++
        }

        /** 按时间顺序（旧→新）消费全部缓冲帧。 */
        fun drain(consumer: (ShortArray) -> Unit) {
            var cursor = (head - size + capacityFrames) % capacityFrames
            repeat(size) {
                frames[cursor]?.let { consumer(it) }
                cursor = (cursor + 1) % capacityFrames
            }
        }

        fun reset() {
            frames.fill(null)
            head = 0
            size = 0
        }

        companion object {
            /** 默认 16 帧 = 400ms，覆盖架构要求的 300ms 预录。 */
            const val DEFAULT_CAPACITY_FRAMES = 16
        }
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2

        /** [readFrame] 在采集器未打开时返回。 */
        const val ERROR_NOT_OPENED = -100
    }
}
