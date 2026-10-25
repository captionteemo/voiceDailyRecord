package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 手动结束并结算用例（架构 2.3/4.2，FR-006）。
 *
 * 流程：停服务音频循环（收尾当前片段）→ 日状态置 SETTLING（进度 5）→
 * 清监听标记与心跳 → 入队结算任务（[SettleScheduler]）。
 *
 * 结算进度此后以 Room `settleProgress` 为唯一真相源（架构 7.5），
 * 由 T05 的 DaySettleWorker 驱动（合并 15 → 转写 80 → 总结 95 → DONE 100）。
 */
class StopMonitoringAndSettleUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val settingsRepository: SettingsRepository,
    private val serviceController: ServiceController,
    private val settleScheduler: SettleScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 结束当天监听并进入结算。
     *
     * @param dayId 目标录音日（`yyyy-MM-dd`）。
     */
    suspend operator fun invoke(dayId: String): EchoResult<Unit> = withContext(ioDispatcher) {
        try {
            // BUG-5 修复：服务投递失败（后台启动限制/服务未运行）不阻断 DB 迁移与
            // 结算入队——片段收尾缺失由结算侧兜底（MergeDayAudioUseCase 过滤
            // durationMs<=0 的未收尾片段）
            runCatching { serviceController.stopSettle(dayId) }
            recordingRepository.updateDayState(dayId, DayState.SETTLING)
            recordingRepository.updateSettleProgress(dayId, SETTLE_START_PROGRESS, null)
            settingsRepository.setMonitorActive(false)
            settingsRepository.writeHeartbeat(0L)
            settleScheduler.scheduleDaySettle(dayId)
            EchoResult.success(Unit)
        } catch (throwable: Throwable) {
            EchoResult.failure(
                code = Constants.ERROR_SERVICE_KILLED,
                message = "结束结算失败：${throwable.message ?: "未知错误"}",
                cause = throwable,
            )
        }
    }

    companion object {
        /** 进入 SETTLING 的初始进度（架构 4.2）。 */
        const val SETTLE_START_PROGRESS = 5
    }
}
