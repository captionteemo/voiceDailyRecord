package com.echolog.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.service.notify.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 服务健康检查 Worker（架构 2.5/4.3/决策 3，NFR-003 第二层防线）。
 *
 * 每 15min（WorkManager 周期下限）执行一次：
 * 1. `monitorActive` 为 false → 无需检查，直接返回；
 * 2. 心跳超时（[Constants.HEARTBEAT_TIMEOUT_MS] = 60s；服务每 5s 写心跳）：
 *    - 存在 MONITORING 天 → 经 [ServiceController.resumeMonitoring] 尝试拉起前台服务；
 *    - 拉起失败（Android 12+ 后台启动限制 `ForegroundServiceStartNotAllowedException`
 *      等）→ 降级第三层：发送「记录已中断，点击恢复」高优先级通知
 *      （用户点击 = 用户主动触发，可合法启动前台服务）；
 * 3. 无 MONITORING 天但标记残留 → 清理陈旧 `monitorActive` 标记，避免后续误判。
 */
@HiltWorker
class HealthCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val recordingRepository: RecordingRepository,
    private val serviceController: ServiceController,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.getSettings()
        if (!settings.monitorActive) return Result.success()

        val heartbeat = settings.serviceHeartbeat
        val now = TimeUtils.currentEpochMs()
        val stale = heartbeat <= 0L || now - heartbeat > Constants.HEARTBEAT_TIMEOUT_MS
        if (!stale) return Result.success()

        val monitoringDay = recordingRepository.observeDays().first()
            .firstOrNull { it.state == DayState.MONITORING }
        if (monitoringDay == null) {
            // 无监听中的天但标记未清：清理陈旧标记（服务已被正常结束的竞态场景）
            settingsRepository.setMonitorActive(false)
            return Result.success()
        }

        // 用户需求 4：手动模式下服务被系统杀死后无法自动恢复「正在录」状态
        // （那需要用户主动点按），故此处不自动拉起——避免服务在后台常驻录音。
        // 全天/自动/模板模式可安全自动恢复。
        if (monitoringDay.mode == RecordingMode.MANUAL) {
            settingsRepository.setMonitorActive(false)
            return Result.success()
        }

        return try {
            serviceController.resumeMonitoring(monitoringDay.dayId)
            Result.success()
        } catch (throwable: Throwable) {
            // 后台启动受限：降级为「点击恢复」通知（NFR-003 第三层防线）
            notificationHelper.notify(
                Constants.NOTIFICATION_ID_ALERT,
                notificationHelper.buildRecoveryNotification(monitoringDay.dayId),
            )
            Result.success()
        }
    }
}
