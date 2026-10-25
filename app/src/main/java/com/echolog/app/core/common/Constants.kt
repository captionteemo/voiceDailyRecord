package com.echolog.app.core.common

/**
 * 全局常量（架构 2.1/7.4/7.5）。
 *
 * 集中管理：音频规格、服务心跳、通知渠道与 ID、Intent Action/Extra、Worker 唯一工作名、
 * 错误码表。跨层通信一律使用本对象的显式常量，禁止散落字符串字面量。
 */
object Constants {

    // ── 音频规格（架构 1.6） ──
    const val SAMPLE_RATE = 16000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    /** VAD 帧长 25ms @16kHz = 400 样本，无重叠。 */
    const val FRAME_SAMPLES = 400
    const val FRAME_INTERVAL_MS = 25L

    /**
     * 采样率可选项（设置页，用户需求 3）。
     * 16000 = ASR 原生输入（推荐）；8000 = 最省空间；44100/48000 = 高保真。
     * 变更只对之后的**新录音日**生效（当天已录片段保持原采样率，合并时校验一致性）。
     */
    val SAMPLE_RATE_OPTIONS = intArrayOf(8000, 16000, 44100, 48000)

    /** 默认采样率（与 ASR 原生一致，免重采样）。 */
    const val DEFAULT_SAMPLE_RATE = 16000

    /** ASR（SenseVoice）要求的输入采样率；录音采样率不同时先重采样再推理。 */
    const val ASR_SAMPLE_RATE = 16000

    /**
     * 指定采样率下的 VAD 帧长（样本数，25ms 名义帧长）。
     * 16000→400 / 8000→200 / 44100→1102 / 48000→1200。
     */
    fun frameSamples(sampleRate: Int): Int =
        (sampleRate * FRAME_INTERVAL_MS / 1000L).toInt().coerceAtLeast(1)
    /** 预录环形缓冲 300ms，保证突变起点不被吞。 */
    const val PRE_BUFFER_MS = 300L
    /** 连续 100ms 超阈值才开录，防键盘声/咳嗽误触发。 */
    const val TRIGGER_VOICE_MS = 100L
    /** 默认断段静音时长 5s（设置可配 3/5/10s）。 */
    const val DEFAULT_SILENCE_MS = 5000L
    val SILENCE_DURATION_OPTIONS_MS = longArrayOf(3000L, 5000L, 10000L)

    /**
     * 单录音片段时长上限（用户需求 4：全天/模板录音）。
     *
     * 超过此时长自动滚动切段，原因有二：
     * 1. WAV（RIFF）单文件上限 4GB——全天 16kHz 单声道约 2.76GB/天，
     *    高采样率（44.1k/48k）下会直接超出格式上限；
     * 2. 下游 ASR 需把整段读入内存（`WavIo.readFloatSamples`），
     *    片段过长会显著抬高内存峰值（5 分钟 @16kHz ≈ 19MB FloatArray）。
     */
    const val MAX_SEGMENT_DURATION_MS = 5 * 60 * 1000L

    /**
     * ASR 单次读取的最大样本数（内存护栏）。
     *
     * ≈ 5 分钟 @16kHz 单声道（4.8M 样本 → FloatArray 约 19MB）。
     * `WavIo.readFloatSamples` 为整段读入内存，超大文件会直接 OOM，
     * 故在读取前拦截并抛 `IOException` 由调用方标记片段失败。
     */
    const val MAX_SAMPLES_PER_READ: Long = 8_000_000L

    /**
     * 按采样率计算单片段时长上限（用户需求 3 + 4）。
     *
     * [MAX_SEGMENT_DURATION_MS] 是按 16kHz 定的；采样率越高，同样时长内的
     * 样本数越多——48kHz 下 5 分钟 = 14.4M 样本，会直接超过 ASR 的内存护栏
     * （[MAX_SAMPLES_PER_READ] = 8M 样本 ≈ 5 分钟 @16kHz）。
     * 故高采样率下按样本数上限反推时长，保证「片段一定能被 ASR 整段读入」。
     */
    fun maxSegmentDurationMs(sampleRate: Int): Long {
        val rate = sampleRate.coerceAtLeast(1)
        val bySampleLimit = MAX_SAMPLES_PER_READ * 1000L / rate
        return bySampleLimit.coerceAtMost(MAX_SEGMENT_DURATION_MS).coerceAtLeast(30_000L)
    }

    // ── 服务与保活（架构决策 3 / 7.5） ──
    const val HEARTBEAT_INTERVAL_MS = 5000L
    const val HEARTBEAT_TIMEOUT_MS = 60_000L
    /** 充电兜底转写的电量门槛（FR-010：建议 >= 50%）。 */
    const val LOW_BATTERY_THRESHOLD = 50

    // ── 通知渠道（架构 7.5） ──
    const val CHANNEL_MONITOR = "channel_monitor"
    const val CHANNEL_PROGRESS = "channel_progress"
    const val CHANNEL_ALERT = "channel_alert"
    const val NOTIFICATION_ID_MONITOR = 1001
    const val NOTIFICATION_ID_PROGRESS = 1002
    const val NOTIFICATION_ID_ALERT = 1003

    // ── Intent Action（架构 7.1：com.echolog.app.action.*） ──
    const val ACTION_START_MONITORING = "com.echolog.app.action.START_MONITORING"
    const val ACTION_RESUME = "com.echolog.app.action.RESUME"
    const val ACTION_PAUSE = "com.echolog.app.action.PAUSE"
    const val ACTION_STOP_SETTLE = "com.echolog.app.action.STOP_SETTLE"
    const val ACTION_ADD_MARKER = "com.echolog.app.action.ADD_MARKER"

    /**
     * 手动录音开停（用户需求 4：手动模式）。
     *
     * 通知栏按钮与 App 内按钮均投递该动作到前台服务；
     * 携带 [EXTRA_RECORDING_ON] 指定目标态（true=开始录 / false=停止录），
     * 缺省时按当前态取反。
     */
    const val ACTION_MANUAL_TOGGLE = "com.echolog.app.action.MANUAL_TOGGLE"

    // ── Intent Extra（架构 7.1：com.echolog.app.extra.*） ──
    const val EXTRA_DAY_ID = "com.echolog.app.extra.DAY_ID"
    const val EXTRA_MARKER_NOTE = "com.echolog.app.extra.MARKER_NOTE"

    /** 手动录音目标态（Boolean；缺省表示取反）。 */
    const val EXTRA_RECORDING_ON = "com.echolog.app.extra.RECORDING_ON"

    // ── Worker 唯一工作名（架构 7.5） ──
    const val WORK_SETTLE_PREFIX = "settle-"
    const val WORK_TRANSCRIBE_PENDING = "transcribe-pending"
    const val WORK_HEALTH_CHECK = "health-check"
    const val WORK_MODEL_DOWNLOAD = "model-download"
    const val WORK_DAY_ROLLOVER = "day-rollover"

    // ── 错误码表（架构 7.4） ──
    const val ERROR_AUDIO_INIT = "E-1001"
    const val ERROR_DISK_WRITE = "E-1002"
    /** 清理缓存失败（用户需求 2）。 */
    const val ERROR_CLEANUP = "E-1003"
    /** 导出失败（用户需求 3）。 */
    const val ERROR_EXPORT = "E-1004"
    const val ERROR_VAD = "E-2001"
    const val ERROR_ASR_MODEL_MISSING = "E-3001"
    const val ERROR_ASR_FAILED = "E-3002"
    const val ERROR_SUMMARY_MODEL_MISSING = "E-4001"
    const val ERROR_SUMMARY_FAILED = "E-4002"

    /**
     * 当天无转写内容，无法生成总结（BUG-11 修复）。
     *
     * 与 [ERROR_SUMMARY_FAILED] 区分：这是**确定性**失败——
     * 片段已全部转写完成（状态 DONE），只是没有产出任何文字
     * （静音、纯环境噪声、或 ASR 对该批音频未识别到语音）。
     * 重试无法改变结果（片段已 DONE，兜底扫描不会重取），
     * 因此调用方必须按「终态软失败」处理：归档为 DONE 并给出提示，
     * **不得** [androidx.work.ListenableWorker.Result.retry] 无限重试
     * （WorkManager 的 retry 无次数上限 → 真机上该天永久停在 95%）。
     */
    const val ERROR_SUMMARY_NO_CONTENT = "E-4003"
    const val ERROR_MERGE_FAILED = "E-5001"
    /**
     * 合并结果超出 WAV（RIFF）4GB 格式上限（用户需求 4：全天录音 + 高采样率）。
     *
     * 与 [ERROR_MERGE_FAILED] 区分：这是**非重试**的软失败——
     * 片段本身完好，转写与总结可照常进行，仅不产出「全天合并」单文件。
     */
    const val ERROR_MERGE_OVERSIZE = "E-5002"
    const val ERROR_MODEL_DOWNLOAD = "E-6001"
    const val ERROR_MODEL_CHECKSUM = "E-6002"
    const val ERROR_SERVICE_KILLED = "E-7001"

    // ── 其他 ──
    const val DAY_ID_PATTERN = "yyyy-MM-dd"
    const val DATABASE_NAME = "echolog.db"
    const val SETTINGS_DATASTORE_NAME = "echolog_settings"

    /** 录音时间模板存储（用户需求 4：模板录音；独立 DataStore 文件）。 */
    const val SCHEDULE_DATASTORE_NAME = "echolog_schedule"

    // ── 结算提示前缀（BUG-11 修复：区分「提示」与「错误」） ──

    /**
     * 非致命提示的统一起始标记。
     *
     * 背景：结算备注（如「合并音频失败，转写已完成」）与真正的失败原因
     * 共用 `settle_error` 字段（零数据库迁移方案）。UI 需要区分二者：
     * - 以本前缀开头 → **提示**：结算已成功归档，只是某个产物缺失，不该展示
     *   「重试结算」按钮（重试对确定性失败无效）；
     * - 不以本前缀开头 → **错误**：结算停滞，可重试。
     *
     * 统一常量避免散落字面量被改坏。
     */
    const val SETTLE_NOTE_PREFIX = "提示："
}
