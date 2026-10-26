package com.echolog.app.domain.model

import com.echolog.app.core.common.Constants
import com.echolog.app.domain.model.RecordingMode

/**
 * VAD 灵敏度档位（FR-204 / 架构 1.6，用户需求 3 语义修正）。
 *
 * 自适应噪声门 = 噪声底 + 灵敏度余量。余量越大 → 门限越高 → 需要更响的
 * 声音才能开录，即灵敏度越低：
 * - 低（14dB）：需要相当响的声音才触发录音（嘈杂环境防误触发）；
 * - 中（10dB）：默认档，办公室/街道均衡；
 * - 高（6dB）：轻微人声即触发（安静环境捕捉低语）。
 */
enum class SensitivityLevel(
    val marginDb: Float,
    val label: String,
) {
    LOW(14f, "低"),
    MEDIUM(10f, "中"),
    HIGH(6f, "高"),
    ;

    companion object {
        /** 按名称解析，非法值回落 MEDIUM（与 DataStore 默认一致）。 */
        fun fromNameOrDefault(name: String?): SensitivityLevel =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/**
 * 隐私模式（FR-102）：决定常驻通知的文案策略。
 *
 * - PERSONAL：个人自用；
 * - MEETING：会议记录（通知文案更正式，规避合规提示歧义）。
 */
enum class PrivacyMode(
    val label: String,
) {
    PERSONAL("个人自用"),
    MEETING("会议记录"),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): PrivacyMode =
            entries.firstOrNull { it.name == name } ?: PERSONAL
    }
}

/**
 * 领域模型：设置快照（架构 3.1）。
 *
 * [SettingsRepository.observeSettings] 发射的完整设置视图；
 * 字段与 DataStore 键一一对应（架构 2.2）。
 *
 * @param sampleRate 录音采样率（用户需求 3；默认 16000，与 ASR 原生一致）。
 * @param storageTreeUri 用户自选录音存储目录的 SAF tree URI（null = 应用内部目录）。
 */
data class RecordingSettings(
    val sensitivity: SensitivityLevel = SensitivityLevel.MEDIUM,
    val silenceDurationMs: Long = Constants.DEFAULT_SILENCE_MS,
    val privacyMode: PrivacyMode = PrivacyMode.PERSONAL,
    val cloudEnhance: Boolean = false,
    val encryption: Boolean = false,
    val modelsReady: Boolean = false,
    val monitorActive: Boolean = false,
    val serviceHeartbeat: Long = 0L,
    val privacyAck: Boolean = false,
    val sampleRate: Int = Constants.DEFAULT_SAMPLE_RATE,
    val storageTreeUri: String? = null,
    /** 录音模式（用户需求 4，默认自动录音）。 */
    val recordingMode: RecordingMode = RecordingMode.AUTO_VAD,

    /**
     * 未录音时常驻「开始录音」快捷入口（用户需求：锁屏/状态栏一键开始录音）。
     *
     * 开启（默认）：结束录音后服务保持前台待命，通知切换为快捷入口；
     * 关闭：恢复现状行为（结束即撤掉通知）。
     */
    val quickRecordNotificationEnabled: Boolean = true,
)
