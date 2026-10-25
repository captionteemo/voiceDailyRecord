package com.echolog.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.echolog.app.data.local.entity.MarkerEntity
import kotlinx.coroutines.flow.Flow

/**
 * 打点标记 DAO（架构 3.2 类图）。
 */
@Dao
interface MarkerDao {

    /** 当天全部标记，按打点时刻升序。 */
    @Query("SELECT * FROM marker WHERE day_id = :dayId ORDER BY at_ms ASC")
    fun observeMarkersForDay(dayId: String): Flow<List<MarkerEntity>>

    /** 一次性读取当天标记。 */
    @Query("SELECT * FROM marker WHERE day_id = :dayId ORDER BY at_ms ASC")
    suspend fun getMarkersForDay(dayId: String): List<MarkerEntity>

    /** 插入标记，返回自增 markerId。 */
    @Insert
    suspend fun insert(marker: MarkerEntity): Long

    /** 删除单个标记。 */
    @Query("DELETE FROM marker WHERE marker_id = :markerId")
    suspend fun delete(markerId: Long)

    /** 删除当天全部标记（「删除文字」场景，架构 3.1 deleteDay）。 */
    @Query("DELETE FROM marker WHERE day_id = :dayId")
    suspend fun deleteForDay(dayId: String)
}
