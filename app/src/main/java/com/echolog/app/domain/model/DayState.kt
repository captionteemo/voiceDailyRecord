package com.echolog.app.domain.model

/**
 * 录音日状态机（架构 1.5）。
 *
 * ```
 * IDLE ──开始记录──▶ MONITORING ──声音突变──▶ RECORDING（服务内态，不落库）
 *                       │  │                      │ 静音≥5s 断段
 *                       │  │◀─────────────────────┘
 *       点击结束/跨天    ▼
 *                   SETTLING ──合并→转写→总结完成──▶ DONE
 * ```
 * 说明：RECORDING 是 [com.echolog.app.service.RecordingStateHolder] 的服务内运行态，
 * 本枚举仅描述数据库持久化的日级状态。
 */
enum class DayState {
    /** 待机（当天行已建但未开始监听，或从未建档）。 */
    IDLE,

    /** 监听中（VAD 待命，安静时不录音）。 */
    MONITORING,

    /** 结算中（合并/转写/总结流水线进行中）。 */
    SETTLING,

    /** 已归档（当天结算完成）。 */
    DONE,
}
