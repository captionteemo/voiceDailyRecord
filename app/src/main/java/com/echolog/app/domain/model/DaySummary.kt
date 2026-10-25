package com.echolog.app.domain.model

/**
 * 领域模型：当天总结（架构 3.1，FR-011）。
 *
 * 与 [TodoItem]（待办/决策/约定条目）、[TimelineItem]（关键时间线条目）
 * 共同构成总结详情页的数据来源（PRD 4.3）；
 * `timelineJson` 为 [TimelineItem] 列表的 JSON 序列化，解析容错由 T04 SummaryJsonParser 负责。
 */
data class DaySummary(
    val dayId: String,
    val status: SummaryRunStatus = SummaryRunStatus.NONE,
    val summaryText: String = "",
    val timelineJson: String = "[]",
    val modelName: String = "",
    val error: String? = null,
)

/**
 * 关键时间线条目：某绝对时刻的关键事件描述。
 */
data class TimelineItem(
    val atMs: Long,
    val text: String,
)

/**
 * 待办/决策/约定条目（架构 3.1）。
 *
 * `sourceMs` 为该条目在录音中的时间来源（epoch millis）；
 * `done` 仅对 [TodoType.TODO] 有意义。
 */
data class TodoItem(
    val todoId: Long = 0L,
    val dayId: String,
    val type: TodoType,
    val content: String,
    val sourceMs: Long,
    val done: Boolean = false,
)
