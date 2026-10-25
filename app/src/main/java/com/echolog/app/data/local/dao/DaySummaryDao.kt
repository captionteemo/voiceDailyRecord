package com.echolog.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.echolog.app.data.local.entity.DaySummaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 当天总结 DAO（架构 3.2 类图）。
 *
 * 一天一行，[upsert] 覆盖写（总结重跑时更新同一行）。
 */
@Dao
interface DaySummaryDao {

    /** 观察当天总结；未生成时发射 null。 */
    @Query("SELECT * FROM day_summary WHERE day_id = :dayId LIMIT 1")
    fun observeSummary(dayId: String): Flow<DaySummaryEntity?>

    /** 一次性读取当天总结。 */
    @Query("SELECT * FROM day_summary WHERE day_id = :dayId LIMIT 1")
    suspend fun getSummary(dayId: String): DaySummaryEntity?

    /** 插入或更新总结。 */
    @Upsert
    suspend fun upsert(summary: DaySummaryEntity)

    /** 删除当天总结（「删除文字」场景；录音日删除时由外键级联完成）。 */
    @Query("DELETE FROM day_summary WHERE day_id = :dayId")
    suspend fun deleteByDayId(dayId: String)
}
