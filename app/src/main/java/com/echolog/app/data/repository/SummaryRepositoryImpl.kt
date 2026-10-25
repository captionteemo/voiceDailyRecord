package com.echolog.app.data.repository

import androidx.room.withTransaction
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.data.local.EchoDatabase
import com.echolog.app.data.local.dao.DaySummaryDao
import com.echolog.app.data.local.dao.RecordingDayDao
import com.echolog.app.data.local.dao.TodoItemDao
import com.echolog.app.data.mapper.toDomain
import com.echolog.app.data.mapper.toEntity
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.repository.SummaryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SummaryRepository] 实现（架构 3.2）。
 *
 * [saveSummary] 以同事务「覆盖总结 + 整体替换待办」保证数据一致，
 * 避免重跑总结时残留上一轮条目。
 */
@Singleton
class SummaryRepositoryImpl @Inject constructor(
    private val dayDao: RecordingDayDao,
    private val summaryDao: DaySummaryDao,
    private val todoDao: TodoItemDao,
    private val database: EchoDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SummaryRepository {

    override fun observeSummary(dayId: String): Flow<DaySummary?> =
        summaryDao.observeSummary(dayId).map { it?.toDomain() }

    override suspend fun getSummary(dayId: String): DaySummary? = withContext(ioDispatcher) {
        summaryDao.getSummary(dayId)?.toDomain()
    }

    override fun observeTodosForDay(dayId: String): Flow<List<TodoItem>> =
        todoDao.observeTodosForDay(dayId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveSummary(summary: DaySummary, todos: List<TodoItem>) {
        database.withTransaction {
            summaryDao.upsert(summary.toEntity())
            todoDao.deleteForDay(summary.dayId)
            todoDao.insertAll(todos.map { it.toEntity() })
        }
    }

    override suspend fun setTodoDone(todoId: Long, done: Boolean) = withContext(ioDispatcher) {
        todoDao.setDone(todoId, done)
    }

    override suspend fun deleteSummary(dayId: String) {
        database.withTransaction {
            summaryDao.deleteByDayId(dayId)
            todoDao.deleteForDay(dayId)
        }
    }

    /**
     * 一键清除所有天的总结内容（用户需求 2：清理缓存）。
     *
     * 逐日删除总结与待办，并把日级总结状态重置为 NONE（UI 不再显示「已生成」）。
     * 录音原文件与转写文字保留。
     */
    override suspend fun deleteAllSummaries(): Int = withContext(ioDispatcher) {
        val days = dayDao.getAllDays()
        database.withTransaction {
            days.forEach { day ->
                summaryDao.deleteByDayId(day.dayId)
                todoDao.deleteForDay(day.dayId)
                dayDao.updateSummaryStatus(day.dayId, SummaryStatus.NONE)
            }
        }
        days.size
    }
}
