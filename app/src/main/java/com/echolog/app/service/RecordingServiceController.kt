package com.echolog.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.echolog.app.core.common.Constants
import com.echolog.app.domain.usecase.ServiceController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ServiceController] 实现（T03 接缝落地）：显式 Intent 驱动 [RecordingService]。
 *
 * - 开启/恢复：`startForegroundService`（Android 8+ 后台启动前台服务规范）；
 * - 结束：`startService`（服务已在前台，仅需投递 Action）。
 */
@Singleton
class RecordingServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServiceController {

    override fun startMonitoring(dayId: String) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_START_MONITORING
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun resumeMonitoring(dayId: String) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_RESUME
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun pauseMonitoring(dayId: String) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_PAUSE
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        // 暂停时服务正在前台运行，startService 合法；竞态（服务恰好在退出）时
        // 吞掉异常——此时无需暂停，DB 状态迁移由 StopMonitoringAndSettleUseCase 负责
        runCatching { context.startService(intent) }
    }

    override fun stopSettle(dayId: String) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_STOP_SETTLE
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        // BUG-5 修复：服务未运行且 App 在后台时，Android 8+ 后台启动限制使
        // startService 抛 IllegalStateException。此时服务本就不需要停止，
        // 吞掉异常让 DB 状态迁移（用例内）与结算入队继续执行——
        // 这正是跨天 Worker 在服务被杀后归档陈旧天的最关键场景。
        runCatching { context.startService(intent) }
    }

    /**
     * 手动录音开停（用户需求 4 第四种）。
     *
     * 服务需在前台才能开停录音：未运行时用 `startForegroundService` 拉起
     * （服务内部收到该动作后按当前模式决定是否响应）。
     */
    override fun toggleManualRecording(dayId: String, recordingOn: Boolean?) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_MANUAL_TOGGLE
            putExtra(Constants.EXTRA_DAY_ID, dayId)
            if (recordingOn != null) {
                putExtra(Constants.EXTRA_RECORDING_ON, recordingOn)
            }
        }
        runCatching { context.startService(intent) }
    }
}
