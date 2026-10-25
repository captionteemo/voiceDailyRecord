package com.echolog.app.domain.model

/**
 * 总结任务的执行状态（架构 3.1，落库于 `day_summary.status`）。
 */
enum class SummaryRunStatus {
    /** 未开始。 */
    NONE,

    /** 已入队。 */
    PENDING,

    /** 执行中。 */
    RUNNING,

    /** 完成（可能来自 LLM 或规则兜底，`model_name` 记录来源）。 */
    DONE,

    /** 失败（`error` 携带原因）。 */
    FAILED,
}
