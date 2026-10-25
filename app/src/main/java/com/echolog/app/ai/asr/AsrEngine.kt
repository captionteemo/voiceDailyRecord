package com.echolog.app.ai.asr

/**
 * ASR 引擎接口（架构 3.3 类图 / 2.4）。
 *
 * 实现：[SenseVoiceAsrEngine]（sherpa-onnx + SenseVoiceSmall，端侧离线）。
 * 推理为 native 阻塞调用，调用方须置于 [kotlinx.coroutines.Dispatchers.Default]（架构 7.3）。
 */
interface AsrEngine {

    /** 模型是否已加载就绪（文件存在且初始化成功）。 */
    fun isReady(): Boolean

    /**
     * 转写单个片段 WAV（16kHz/mono/PCM16）。
     *
     * @param wavPath 片段文件绝对路径。
     * @return 带相对时间戳（相对片段起点，epoch millis）的文字行列表；失败返回空列表。
     */
    fun transcribe(wavPath: String): List<TimedText>

    /** 释放 native 资源（切模型/退出时调用）。 */
    fun release() = Unit
}

/**
 * 带时间戳的文字行（架构 2.4）。
 *
 * 时间戳为**相对片段起点**的偏移（epoch millis）；
 * 落库前由 `TranscribeDayUseCase` 叠加片段 `startMs` 转为全天绝对时间（架构 4.2）。
 */
data class TimedText(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
