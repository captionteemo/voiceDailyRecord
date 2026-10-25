package com.echolog.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echolog.app.core.util.EchoLog
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.usecase.StopMonitoringAndSettleUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 跨天归档 Worker（架构 2.5/4.3，FR-008）。
 *
 * 每日一次扫描录音日列表，处理「非今天但仍未归档」的天：
 * - `MONITORING` 且 dayId ≠ 今天：服务可能已随跨天死亡或仍在为过期日运行，
 *   经 [StopMonitoringAndSettleUseCase] 走「停服务 → SETTLING → 入队结算」完整链路；
 * - `SETTLING` 且 dayId ≠ 今天：结算未完成（如上次模型缺失失败），重新入队结算。
 *
 * 「新建当天」不在本 Worker 内执行：无运行服务时创建 MONITORING 行会污染状态机
 * （monitorActive 为 false 但 state 为 MONITORING）；当天行由用户下次开启监听时
 * 经 `StartMonitoringUseCase.createOrResumeDay` 自然建立。
 */
@HiltWorker
class DayRolloverWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val stopMonitoringAndSettleUseCase: StopMonitoringAndSettleUseCase,
    private val workScheduler: WorkScheduler,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val today = TimeUtils.todayDayId()
        val staleDays = recordingRepository.observeDays().first()
            .filter { it.dayId != today }
            .filter { it.state == DayState.MONITORING || it.state == DayState.SETTLING }
            .sortedBy { it.dayId }

        for (day in staleDays) {
            if (day.state == DayState.MONITORING) {
                // BUG-5 修复：检查返回值——DB 迁移失败时记日志，下个周期重试，
                // 不再静默忽略导致陈旧天永远无法归档
                val result = stopMonitoringAndSettleUseCase(day.dayId)
                if (result.isFailure) {
                    EchoLog.w(
                        TAG,
                        "跨天归档失败（DB 迁移未完成，下周期重试）: ${day.dayId} - " +
                            "${result.failureOrNull()?.message}",
                    )
                }
            } else {
                // SETTLING 未完成：重新入队结算（唯一工作名防重）
                workScheduler.scheduleDaySettle(day.dayId)
            }
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "DayRolloverWorker"
    }
}
