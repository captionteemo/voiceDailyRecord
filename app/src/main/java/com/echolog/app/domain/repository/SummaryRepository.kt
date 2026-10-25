package com.echolog.app.domain.repository

import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.TodoItem
import kotlinx.coroutines.flow.Flow

/**
 * 总结仓库（架构 3.1/3.2）：总结与待办条目。
 *
 * 实现见 `data/repository/SummaryRepositoryImpl.kt`。
 */
interface SummaryRepository {

    /** 观察当天总结；未生成时发射 null。 */
    fun observeSummary(dayId: String): Flow<DaySummary?>

    /** 一次性读取当天总结。 */
    suspend fun getSummary(dayId: String): DaySummary?

    /** 当天的待办/决策/约定条目流，按时间来源升序。 */
    fun observeTodosForDay(dayId: String): Flow<List<TodoItem>>

    /**
     * 保存总结并整体替换当天待办条目（同事务，避免半份数据）。
     *
     * @param todos 新条目列表（`todoId` 忽略，由自增主键生成）。
     */
    suspend fun saveSummary(summary: DaySummary, todos: List<TodoItem>)

    /** 勾选/取消勾选待办。 */
    suspend fun setTodoDone(todoId: Long, done: Boolean)

    /** 删除当天总结与全部待办条目。 */
    suspend fun deleteSummary(dayId: String)

    /**
     * 一键清除所有天的总结内容（用户需求 2：清理缓存）。
     *
     * 删除全部录音日的总结、待办与关键时间线，并把日级总结状态重置为 NONE；
     * 录音原文件与转写文字保留。
     *
     * @return 被清理的录音日数量。
     */
    suspend fun deleteAllSummaries(): Int
}
