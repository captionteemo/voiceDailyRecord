package com.echolog.app.work

import android.content.Context
import android.os.BatteryManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.domain.usecase.SettlePendingDaysUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 充电兜底转写 Worker（架构 2.5/4.3，FR-010）。
 *
 * 约束与校验：
 * - 入队约束：充电中（见 [WorkScheduler.scheduleTranscribePending]，WorkManager 层面）；
 * - Worker 内自定义校验：电量 ≥ [Constants.LOW_BATTERY_THRESHOLD]（50%），
 *   未达门槛返回 [Result.retry]（等电量恢复，不空转烧 CPU）；
 * - 执行体：[SettlePendingDaysUseCase] 扫描全部待结算天，逐天合并→转写→总结。
 *
 * 说明：电量读取优先用 [BatteryManager.BATTERY_PROPERTY_CAPACITY]；
 * 取不到（返回 ≤0）时按未知电量放行（充电约束已提供基本保障）。
 */
@HiltWorker
class TranscribeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settlePendingDaysUseCase: SettlePendingDaysUseCase,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val batteryLevel = readBatteryLevel()
        if (batteryLevel in 1 until Constants.LOW_BATTERY_THRESHOLD) {
            return Result.retry()
        }
        val result: EchoResult<Int> = settlePendingDaysUseCase()
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    /** 当前电量百分比；未知返回 100（放行）。 */
    private fun readBatteryLevel(): Int {
        val manager = applicationContext.getSystemService(BatteryManager::class.java)
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (level <= 0) UNKNOWN_BATTERY_LEVEL else level
    }

    private companion object {
        /** 电量未知时的放行值（充电约束已提供基本保障）。 */
        const val UNKNOWN_BATTERY_LEVEL = 100
    }
}
