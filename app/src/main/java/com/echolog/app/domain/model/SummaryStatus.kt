package com.echolog.app.domain.model

/**
 * 录音日维度的总结状态（架构 3.1，落库于 `recording_day.summary_status`）。
 *
 * 与 [SummaryRunStatus] 的区别：本状态是「日」级别的汇总视图（UI 直接读），
 * [SummaryRunStatus] 是 `day_summary` 表内总结任务自身的执行状态。
 */
enum class SummaryStatus {
    /** 未生成（默认）。 */
    NONE,

    /** 已入队等待生成。 */
    PENDING,

    /** 生成中。 */
    RUNNING,

    /** 生成完成。 */
    DONE,

    /** 生成失败（已降级规则兜底或可重试）。 */
    FAILED,
}
