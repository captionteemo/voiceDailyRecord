package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 开启监听用例（架构 2.3/4.1，FR-001）。
 *
 * 流程：建档/恢复当天录音日（MONITORING）→ 置监听活跃标记 → 启动前台服务。
 * 隐私确认（FR-014）由 UI 层在调用本用例前完成（`privacy_ack` 标记）。
 */
class StartMonitoringUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val settingsRepository: SettingsRepository,
    private val serviceController: ServiceController,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 开启监听。
     *
     * @return 成功时携带 dayId；失败（如音频占用）携带 E-1001。
     */
    suspend operator fun invoke(): EchoResult<String> = withContext(ioDispatcher) {
        try {
            val dayId = TimeUtils.todayDayId()
            // 用户需求 4：当天采用的录音模式随建档落库（已有记录保留原模式）
            val mode = settingsRepository.getSettings().recordingMode
            val day = recordingRepository.createOrResumeDay(dayId, mode)
            settingsRepository.setMonitorActive(true)
            serviceController.startMonitoring(day.dayId)
            EchoResult.success(day.dayId)
        } catch (throwable: Throwable) {
            EchoResult.failure(
                code = Constants.ERROR_AUDIO_INIT,
                message = "开启监听失败：${throwable.message ?: "未知错误"}",
                cause = throwable,
            )
        }
    }
}
