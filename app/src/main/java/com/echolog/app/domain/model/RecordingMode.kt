package com.echolog.app.domain.model

/**
 * 录音模式（用户需求 4：四选一）。
 *
 * - [ALL_DAY] 全天录音：开启后一整天持续录音，直到手动结束，不依赖 VAD 断段；
 * - [AUTO_VAD] 自动录音：默认模式，按声音突变（VAD）自动开录、静音断段，
 *   静音判定可配置（灵敏度/静音时长，见 [RecordingSettings]）；
 * - [SCHEDULE_TEMPLATE] 模板录音：按课表式时间区间自动启停（见 `ScheduleTemplate`）；
 * - [MANUAL] 手动录音：仅用户点击状态栏/App 的录音按键时才录，松开/再点即停。
 *
 * 枚举以 `name` 字符串存储（DataStore/DB 通用约定）。
 */
enum class RecordingMode(
    val label: String,
    val desc: String,
) {
    ALL_DAY("全天录音", "开启后持续录音一整天，最后给出结果"),
    AUTO_VAD("自动录音", "根据声音自动判断，可配置静音灵敏度与断段时长"),
    SCHEDULE_TEMPLATE("模板录音", "按导入的课表时间区间自动启停"),
    MANUAL("手动录音", "由你手动点击状态栏或 App 的录音按键控制"),
    ;

    companion object {
        /** 按名称解析，非法值回落 AUTO_VAD（默认模式）。 */
        fun fromNameOrDefault(name: String?): RecordingMode =
            entries.firstOrNull { it.name == name } ?: AUTO_VAD
    }
}
