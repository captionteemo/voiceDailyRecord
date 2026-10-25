package com.echolog.app.data.media

import com.echolog.app.core.util.EchoLog
import com.echolog.app.core.util.WavIo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 音频合并器（架构 1.4/2.3）。
 *
 * 将当天所有 PCM16 WAV 片段顺序拼接为全天合并文件（`day_{dayId}.wav`）：
 * 跳过各片段 44 字节文件头，直接流式拷贝 PCM 数据，最后重写单一文件头——
 * 免转码、无损，8h 音频（约 900MB）顺序 IO 约 10~30s。
 */
class AudioMerger {

    /**
     * 合并片段。
     *
     * @param paths 片段文件路径列表（按时间升序）。
     * @param outPath 输出文件路径（`{filesDir}/audio/day_{dayId}.wav`）。
     * @param onProgress 进度回调（0~100，按片段个数估算）。
     * @return 输出文件路径。
     * @throws IOException 片段格式非法、读取失败、合并结果为空、
     *   或合并结果超出 WAV（RIFF）4GB 格式上限。
     */
    @Throws(IOException::class)
    fun mergeSegments(
        paths: List<String>,
        outPath: String,
        onProgress: (Int) -> Unit = {},
    ): String {
        require(paths.isNotEmpty()) { "mergeSegments: 片段列表不能为空" }

        // BUG-11 修复：逐个校验片段，**跳过**损坏/格式不一致的片段，
        // 不让单个坏片段拖垮整天的合并（原实现任一片段异常即整体失败，
        // 叠加「结束录音」时序竞态与采样率混用，真机上表现为结算永久卡 5%）。
        // 被跳过的片段记录日志，便于真机排查。
        val validHeaders = mutableListOf<Pair<String, WavIo.WavFormat>>()
        var totalDataBytes = 0L
        paths.forEach { path ->
            val header = try {
                WavIo.readHeader(path)
            } catch (throwable: Throwable) {
                // 片段头非法（占位头未回填 / 文件损坏）——跳过该片段
                logSkipped("跳过损坏片段: $path", throwable)
                return@forEach
            }
            val reference = validHeaders.firstOrNull()?.second
            if (reference != null &&
                (header.sampleRate != reference.sampleRate || header.channels != reference.channels)
            ) {
                // 采样率/声道不一致：PCM 直拼会产生变速音频——跳过该片段
                logSkipped(
                    "跳过采样率不一致片段: $path (${header.sampleRate}Hz/${header.channels}ch) " +
                        "≠ ${reference.sampleRate}Hz/${reference.channels}ch",
                )
                return@forEach
            }
            totalDataBytes += header.dataSize
            validHeaders += path to header
        }

        if (validHeaders.isEmpty()) {
            throw IOException("没有可合并的有效片段（共 ${paths.size} 个，全部损坏或格式不一致）")
        }
        // WAV（RIFF）长度字段为无符号 32 位，上限约 4GB：超限时无法产出合法文件，
        // 抛 [MergeOversizeException] 让调用方降级（保留分片，跳过合并），避免产出损坏文件。
        if (totalDataBytes > WavIo.MAX_RIFF_BYTES) {
            throw MergeOversizeException(
                "合并结果超出 WAV 4GB 上限（$totalDataBytes > ${WavIo.MAX_RIFF_BYTES}）",
            )
        }
        val validPaths = validHeaders.map { it.first }
        val targetFormat = validHeaders.first().second
        val outFile = File(outPath)
        outFile.parentFile?.mkdirs()
        var copiedBytes = 0L
        RandomAccessFile(outFile, "rw").use { out ->
            out.setLength(0)
            WavIo.writePlaceholderHeader(out)
            validPaths.forEachIndexed { index, path ->
                WavIo.openPcmStream(path).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copiedBytes += read.toLong()
                    }
                }
                onProgress(((index + 1) * 100) / validPaths.size)
            }
            WavIo.patchHeader(
                out,
                copiedBytes,
                targetFormat.sampleRate,
                targetFormat.channels,
                targetFormat.bitsPerSample,
            )
        }
        if (copiedBytes == 0L) {
            outFile.delete()
            throw IOException("合并结果为空：所有片段均无音频数据")
        }
        return outPath
    }

    /**
     * 记录被跳过的片段（诊断用）。
     *
     * 用 [runCatching] 包裹：JVM 单元测试中 `android.util.Log` 未 mock，
     * 直接调用会抛 `RuntimeException` 而掩盖真正的业务异常。
     */
    private fun logSkipped(message: String, throwable: Throwable? = null) {
        if (throwable != null) EchoLog.w(TAG, message, throwable) else EchoLog.w(TAG, message)
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val TAG = "AudioMerger"
    }
}
