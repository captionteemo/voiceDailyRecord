package com.echolog.app.service.vad

/**
 * VAD 引擎接口（架构 3.3 类图）。
 *
 * 帧进事件出：由 [com.echolog.app.service.RecordingService] 的音频线程循环驱动，
 * 实现必须是纯计算、无阻塞、无堆分配抖动（NFR-001 功耗约束）。
 */
interface VadEngine {

    /**
     * 初始化/重置门限基准。
     *
     * @param noiseFloorDb 启动时校准的环境噪声底（dBFS）。
     */
    fun init(noiseFloorDb: Float)

    /**
     * 处理一帧音频（25ms/400 样本 @16kHz）。
     *
     * @param frame PCM16 样本。
     * @return 本帧的语音事件。
     */
    fun processFrame(frame: ShortArray): VadEvent

    /** 异常恢复：清空内部状态（保留噪声底）。 */
    fun reset()
}

/**
 * VAD 事件（架构 1.6）。
 *
 * - [SILENT]：静音帧；
 * - [VOICE_ONSET]：连续 100ms 超阈，开录（含 300ms 预录回吐）；
 * - [VOICE]：持续人声；
 * - [VOICE_OFFSET]：连续静音达到断段时长，收尾当前片段。
 */
enum class VadEvent {
    SILENT,
    VOICE_ONSET,
    VOICE,
    VOICE_OFFSET,
}
