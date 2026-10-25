package com.echolog.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.echolog.app.data.local.entity.TodoItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * 待办/决策/约定条目 DAO（架构 3.2 类图）。
 */
@Dao
interface TodoItemDao {

    /** 当天全部条目，按时间来源升序。 */
    @Query("SELECT * FROM todo_item WHERE day_id = :dayId ORDER BY source_ms ASC, todo_id ASC")
    fun observeTodosForDay(dayId: String): Flow<List<TodoItemEntity>>

    /** 一次性读取当天条目。 */
    @Query("SELECT * FROM todo_item WHERE day_id = :dayId ORDER BY source_ms ASC, todo_id ASC")
    suspend fun getTodosForDay(dayId: String): List<TodoItemEntity>

    /** 批量插入条目（保存总结时整体替换）。 */
    @Insert
    suspend fun insertAll(items: List<TodoItemEntity>)

    /** 勾选/取消勾选。 */
    @Query("UPDATE todo_item SET done = :done WHERE todo_id = :todoId")
    suspend fun setDone(todoId: Long, done: Boolean)

    /** 删除当天全部条目（保存总结前清空 / 「删除文字」场景）。 */
    @Query("DELETE FROM todo_item WHERE day_id = :dayId")
    suspend fun deleteForDay(dayId: String)
}
