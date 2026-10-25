package com.echolog.app.ai.asr

import com.echolog.app.ai.model.ModelCatalog
import com.echolog.app.ai.model.ModelKind
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.core.util.AudioResampler
import com.echolog.app.core.util.WavIo
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SenseVoice 端侧 ASR 引擎（架构 1.4 决策 2 / 2.4 / 3.3，FR-009）。
 *
 * 实现要点：
 * 1. 模型经 [ModelManager] 定位（`{filesDir}/models/asr/`：`model.int8.onnx` + `tokens.txt`）；
 * 2. sherpa-onnx AAR 依赖隔离在本文件内（唯一 import `com.k2fsa.sherpa.onnx.*` 的类），
 *    AAR 缺失/损坏仅影响本类，不影响其余模块编译与「仅录音」核心链路；
 * 3. 推理为 native 阻塞调用：调用方须置于 `Dispatchers.Default`
 *    （`TranscribeDayUseCase` 已保证，架构 7.3）；本类内部另加串行锁，
 *    防多 Worker 并发推理导致内存峰值与 native 竞争；
 * 4. 初始化失败按「模型指纹」记忆（路径+最后修改时间）：同一损坏模型不反复重试，
 *    模型重新下载/替换后自动允许再次初始化；
 * 5. 时间戳解析：token 与时间戳对齐时按标点/时长聚句为带时间戳文字行；
 *    不对齐（部分版本 SenseVoice 不产出 token 级时间戳）降级为单段兜底，
 *    保证转写链路永远有产出（E-3002 由调用方重试）。
 */
@Singleton
class SenseVoiceAsrEngine @Inject constructor(
    private val modelManager: ModelManager,
) : AsrEngine {

    private val initLock = Any()

    /** native 识别器；仅在模型就绪且初始化成功后非空。 */
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** 上次初始化失败时的模型指纹（`绝对路径:最后修改时间`）；相同指纹跳过重复初始化。 */
    @Volatile
    private var failedModelStamp: String? = null

    /** 推理串行锁：ASR 为 CPU 密集型，串行执行同时避免 native 竞争。 */
    private val decodeLock = Any()

    override fun isReady(): Boolean {
        ensureInitialized()
        return recognizer != null
    }

    override fun transcribe(wavPath: String): List<TimedText> {
        ensureInitialized()
        // 识别器未就绪（模型缺失/初始化失败）：向上抛，由调用方前置 E-3001 拦截或
        // 标记 FAILED——不再静默返回空列表造成「假成功」的数据丢失（BUG-9）
        val activeRecognizer = recognizer
            ?: throw IllegalStateException("ASR 识别器未就绪（模型缺失或初始化失败）")

        // 文件缺失/头部损坏：向上抛 IOException，调用方标记 FAILED 供兜底重试（E-3002）。
        // 返回空列表会让调用方误判为「无语音内容」而标记 DONE，造成静默数据丢失。
        val format = WavIo.readHeader(wavPath)
        val rawSamples = WavIo.readFloatSamples(wavPath)
        if (rawSamples.isEmpty()) return emptyList()
        // 用户需求 3：录音采样率可配（8k/44.1k/48k），ASR 固定 16kHz——
        // 非 16k 片段在推理前线性重采样；时长口径按源采样率计算（重采样不改时长）。
        val samples = AudioResampler.resample(rawSamples, format.sampleRate, SAMPLE_RATE)
        val durationMs = rawSamples.size.toLong() * MILLIS_PER_SECOND / format.sampleRate

        return try {
            synchronized(decodeLock) {
                val stream: OfflineStream = activeRecognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    activeRecognizer.decode(stream)
                    val result = activeRecognizer.getResult(stream)
                    buildTimedLines(result.text, result.tokens, result.timestamps, durationMs)
                } finally {
                    // release() 内部即 finalize() 语义（delete native ptr 后置 0，重复调用安全）
                    runCatching { stream.release() }
                }
            }
        } catch (throwable: Throwable) {
            // native 推理异常（OOM/段错误捕获）：单片段降级为空，不拖垮全天转写
            emptyList()
        }
    }

    override fun release() {
        synchronized(initLock) {
            recognizer?.let { active ->
                runCatching { active.release() }
            }
            recognizer = null
            failedModelStamp = null
        }
    }

    /**
     * 惰性初始化识别器（双检锁 + 模型指纹防重试）。
     *
     * 模型未就绪时不标记失败（用户可能稍后下载）；模型就绪但加载失败时
     * 记录指纹，文件变化前不再重试。
     */
    private fun ensureInitialized() {
        if (recognizer != null) return
        synchronized(initLock) {
            if (recognizer != null) return
            val files = locateModelFiles() ?: return
            val stamp = "${files.first.absolutePath}:${files.first.lastModified()}"
            if (failedModelStamp == stamp) return
            runCatching {
                val instance = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        // featConfig 默认 16kHz/80dim，与本管线录音参数一致，无需显式设置
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = files.first.absolutePath,
                            ),
                            tokens = files.second.absolutePath,
                            numThreads = NUM_THREADS,
                        ),
                        decodingMethod = DECODING_METHOD,
                    ),
                )
                recognizer = instance
                failedModelStamp = null
            }.onFailure {
                failedModelStamp = stamp
            }
        }
    }

    /**
     * 定位 ASR 模型文件（主模型 + tokens）。
     *
     * 条目来源与 [ModelManager] 保持一致：内置清单（远端 manifest 暂不覆盖文件名，
     * 避免下载完成路径与识别路径不一致）。
     */
    private fun locateModelFiles(): Pair<File, File>? {
        val entry = ModelCatalog.builtInEntries.firstOrNull { it.kind == ModelKind.ASR } ?: return null
        val dir = modelManager.asrModelDir()
        val modelFile = File(dir, entry.fileName)
        val tokensFileName = entry.additionalFiles
            .firstOrNull { name -> name.endsWith(TOKENS_FILE_SUFFIX) }
            ?: DEFAULT_TOKENS_FILE_NAME
        val tokensFile = File(dir, tokensFileName)
        val modelValid = modelFile.isFile && modelFile.length() >= entry.minSizeBytes
        val tokensValid = tokensFile.isFile && tokensFile.length() > 0L
        return if (modelValid && tokensValid) modelFile to tokensFile else null
    }

    /**
     * 将识别结果转为带时间戳文字行。
     *
     * @param text 整段文本（ITN 后，已剥离 SenseVoice 标签）。
     * @param tokens token 序列（含 `<|zh|>` 等标签与 SentencePiece 元符号 `▁`）。
     * @param timestamps 与 tokens 一一对应的起始时间（秒）。
     * @param durationMs 片段总时长（相对起点，epoch millis）。
     */
    private fun buildTimedLines(
        text: String,
        tokens: Array<String>,
        timestamps: FloatArray,
        durationMs: Long,
    ): List<TimedText> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return emptyList()

        // 时间戳缺失或与 token 不对齐：单段兜底（时间线退化为整段，转写文本不丢失）
        if (tokens.isEmpty() || timestamps.isEmpty() || tokens.size != timestamps.size) {
            return listOf(TimedText(startMs = 0L, endMs = durationMs, text = cleanText))
        }

        val lines = mutableListOf<TimedText>()
        val buffer = StringBuilder()
        var lineStartMs = 0L
        for (index in tokens.indices) {
            if (lines.size >= MAX_TIMED_LINES) break
            val token = tokens[index]
            if (isMetaToken(token)) continue
            val tokenText = token.replace(META_SYMBOL, " ").trim()
            if (tokenText.isEmpty()) continue

            val tokenStartMs = (timestamps[index] * MILLIS_PER_SECOND)
                .toLong()
                .coerceIn(0L, durationMs)
            if (buffer.isEmpty()) lineStartMs = tokenStartMs
            buffer.append(tokenText)

            // 行末时间取下一 token 起点（本 token 无独立终点信息）
            val lineEndMs = if (index + 1 < timestamps.size) {
                (timestamps[index + 1] * MILLIS_PER_SECOND)
                    .toLong()
                    .coerceIn(tokenStartMs, durationMs)
            } else {
                durationMs
            }

            val endsSentence = tokenText.last().let { last -> PUNCTUATION.any { it == last } }
            val tooLong = buffer.length >= MAX_LINE_CHARS ||
                (lineEndMs - lineStartMs) >= MAX_LINE_MS
            if (endsSentence || tooLong) {
                flushLine(lines, buffer, lineStartMs, lineEndMs)
            }
        }
        if (buffer.isNotEmpty() && lines.size < MAX_TIMED_LINES) {
            flushLine(lines, buffer, lineStartMs, durationMs)
        }

        // tokens 全部为标签等元信息时，回退整段文本
        if (lines.isEmpty()) {
            return listOf(TimedText(startMs = 0L, endMs = durationMs, text = cleanText))
        }
        return lines
    }

    /** 提交缓冲行；空行（纯空白）丢弃。 */
    private fun flushLine(
        lines: MutableList<TimedText>,
        buffer: StringBuilder,
        startMs: Long,
        endMs: Long,
    ) {
        val lineText = buffer.toString().trim()
        buffer.clear()
        if (lineText.isEmpty()) return
        val safeStart = startMs.coerceIn(0L, endMs)
        lines.add(TimedText(startMs = safeStart, endMs = endMs, text = lineText))
    }

    /** SenseVoice 标签/特殊 token（语种、情感、事件、句首句末）。 */
    private fun isMetaToken(token: String): Boolean {
        if (token.startsWith(TAG_PREFIX) && token.endsWith(TAG_SUFFIX)) return true
        return token in SPECIAL_TOKENS
    }

    private companion object {
        /** 管线固定采样率（架构 1.4：16kHz mono PCM16）。 */
        const val SAMPLE_RATE = 16000

        const val MILLIS_PER_SECOND = 1000L

        /** 中端机保守线程数（架构 7.3：ASR 跑 Default 调度器）。 */
        const val NUM_THREADS = 2

        /** 离线识别贪心解码（SenseVoice 无 LM 场景唯一实用选项）。 */
        const val DECODING_METHOD = "greedy_search"

        /** 单行最大字符数（超出即使无标点也断行）。 */
        const val MAX_LINE_CHARS = 40

        /** 单行最大时长（ms，防超长无标点语音撑出巨型行）。 */
        const val MAX_LINE_MS = 10_000L

        /** 单片段最大文字行数（防异常输出撑爆内存）。 */
        const val MAX_TIMED_LINES = 200

        /** SentencePiece 元符号（词首标记）。 */
        const val META_SYMBOL = "▁"

        const val TAG_PREFIX = "<|"
        const val TAG_SUFFIX = "|>"

        val SPECIAL_TOKENS = setOf("<s>", "</s>", "<blank>", "<unk>", "<pad>")

        /** 中文/英文句读（行断开判据）。 */
        val PUNCTUATION = charArrayOf(
            '，', '。', '！', '？', '；', '、', '…',
            '.', ',', '!', '?', ';',
        )

        const val TOKENS_FILE_SUFFIX = "tokens.txt"
        const val DEFAULT_TOKENS_FILE_NAME = "tokens.txt"
    }
}
