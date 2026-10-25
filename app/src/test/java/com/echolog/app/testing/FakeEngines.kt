package com.echolog.app.testing

import com.echolog.app.ai.asr.AsrEngine
import com.echolog.app.ai.asr.TimedText
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.usecase.AudioPathProvider
import com.echolog.app.domain.usecase.SegmentTranscriptExporter
import java.io.File

/**
 * 测试替身：内存版 [AsrEngine]。
 *
 * 支持按路径返回结果、按路径抛错、就绪开关，用于验证转写用例的
 * 成功/失败/降级路径，无需真实 sherpa-onnx 模型。
 */
class FakeAsrEngine(
    private var ready: Boolean = true,
) : AsrEngine {

    private val results = mutableMapOf<String, List<TimedText>>()

    /** 这些路径转写时抛错（模拟 E-3002 推理失败）。 */
    val throwPaths = mutableSetOf<String>()

    /** 全部转写调用按顺序记录（断言重试次数用）。 */
    val transcribeCalls = mutableListOf<String>()

    fun resultFor(path: String, lines: List<TimedText>) {
        results[path] = lines
    }

    fun setReady(ready: Boolean) {
        this.ready = ready
    }

    override fun isReady(): Boolean = ready

    override fun transcribe(wavPath: String): List<TimedText> {
        transcribeCalls += wavPath
        if (wavPath in throwPaths) throw RuntimeException("模拟 ASR 推理失败: $wavPath")
        return results[wavPath] ?: emptyList()
    }

    override fun release() = Unit

    fun callsFor(path: String): Int = transcribeCalls.count { it == path }
}

/**
 * 测试替身：[SegmentTranscriptExporter]（用户需求 1：与录音同名的 .md 导出）。
 *
 * 记录每次导出调用（dayId + 片段路径），并可选地按路径抛错以验证
 * 导出失败不阻断转写主链路（调用方用 runCatching 吞掉返回值）。
 */
class FakeSegmentTranscriptExporter : SegmentTranscriptExporter {

    /** 成功导出的 (dayId, segment.filePath) 序列。 */
    val exportedPaths = mutableListOf<Pair<String, String>>()

    /** 这些片段路径的导出会抛错（模拟 SAF 授权失效 / 存储满）。 */
    val throwPaths = mutableSetOf<String>()

    override suspend fun exportSegment(
        dayId: String,
        segment: AudioSegment,
        transcripts: List<TranscriptSegment>,
    ): Boolean {
        if (segment.filePath in throwPaths) throw RuntimeException("模拟导出失败: ${segment.filePath}")
        exportedPaths += dayId to segment.filePath
        return true
    }
}

/**
 * 测试替身：[AudioPathProvider]，落地到临时目录。
 */
class FakeAudioPathProvider(private val dir: File) : AudioPathProvider {

    override fun mergedDayPath(dayId: String): String = File(dir, "day_$dayId.wav").absolutePath

    override fun segmentPath(dayId: String, ordinal: Int): String =
        File(dir, "seg_${dayId}_$ordinal.wav").absolutePath
}
