package com.echolog.app.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.echolog.app.core.common.ApplicationScope
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.EchoLog
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.service.RecordingService
import com.echolog.app.service.notify.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 开机 / 应用更新自恢复（架构决策 3 第三层，2.3）。
 *
 * 逻辑：读 `monitor_active` 标记 + 数据库 MONITORING 天 → 存在则
 * `startForegroundService(ACTION_RESUME)`；Android 12+ 后台启动受限时
 * （BOOT_COMPLETED 属豁免场景，但厂商 ROM 可能收紧），异常兜底为
 * 高优先级「记录已中断，点击恢复」通知（NFR-003 的"或"路径）。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var recordingRepository: RecordingRepository

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    @Inject
    lateinit var serviceController: ServiceController

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        scope.launch(ioDispatcher) {
            try {
                resumeMonitoringIfNeeded(context)
            } catch (throwable: Throwable) {
                EchoLog.w(TAG, "自恢复检查失败", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun resumeMonitoringIfNeeded(context: Context) {
        val settings = settingsRepository.getSettings()
        val monitoringDay = recordingRepository.observeDays().first()
            .firstOrNull { it.state == DayState.MONITORING }
        if (!settings.monitorActive) {
            // 未在监听：重建「快捷录音入口」（用户需求：锁屏/状态栏一键开始录音）。
            // 否则重启后入口消失，只能等用户下一次「录音结束」才重新出现——
            // 而入口的价值恰恰是「还没开始录的时候」能用。
            if (settings.quickRecordNotificationEnabled) {
                startStandbyService()
            }
            return
        }
        if (monitoringDay == null) return
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            this.action = Constants.ACTION_RESUME
            putExtra(Constants.EXTRA_DAY_ID, monitoringDay.dayId)
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (throwable: Throwable) {
            // Android 12+ 后台启动限制 / 厂商 ROM 收紧：降级为恢复通知
            EchoLog.w(TAG, "前台服务启动被拒绝，降级为恢复通知", throwable)
            withContext(ioDispatcher) {
                notificationHelper.notify(
                    Constants.NOTIFICATION_ID_ALERT,
                    notificationHelper.buildRecoveryNotification(monitoringDay.dayId),
                )
            }
        }
    }

    /**
     * 以待命态启动前台服务（仅挂出快捷录音入口，不启动音频循环）。
     *
     * 合规性：BOOT_COMPLETED 属 Android 12+ 后台启动前台服务的**豁免场景**
     * （见本类 KDoc），`startForegroundService` 合法。但厂商 ROM 仍可能收紧，
     * 故失败**不得静默**：`ServiceController.enterStandby()` 失败时已代发
     * alert 渠道可见通知，此处补日志留存排查线索。
     *
     * 与 `resumeMonitoringIfNeeded` 中的恢复分支共用同一套「失败可见」标准
     * （那条降级为 [NotificationHelper.buildRecoveryNotification]）。
     */
    private fun startStandbyService() {
        serviceController.enterStandby().onFailure { failure ->
            // 不带 cause：Controller 内部已用完整堆栈打过一次，此处只留业务上下文
            EchoLog.w(TAG, "快捷入口建立失败：${failure.code} ${failure.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
