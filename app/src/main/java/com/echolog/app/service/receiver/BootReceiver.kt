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
        if (!settings.monitorActive) return
        val monitoringDay = recordingRepository.observeDays().first()
            .firstOrNull { it.state == DayState.MONITORING }
            ?: return
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

    companion object {
        private const val TAG = "BootReceiver"
    }
}
