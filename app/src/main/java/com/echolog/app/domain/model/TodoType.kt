package com.echolog.app.domain.model

/**
 * 待办条目类型（架构 3.1，落库于 `todo_item.type`）。
 *
 * 对应总结详情页的三个分区卡片（PRD 4.3）。
 */
enum class TodoType {
    /** 待办事项。 */
    TODO,

    /** 关键决策。 */
    DECISION,

    /** 约定。 */
    AGREEMENT,
}
