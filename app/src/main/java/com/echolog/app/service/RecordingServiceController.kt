package com.echolog.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.echolog.app.R
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.util.EchoLog
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.service.notify.NotificationHelper
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
    private val notificationHelper: NotificationHelper,
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

    /**
     * 以待命态启动前台服务（仅挂出快捷录音入口，不启动音频循环）。
     *
     * **闸 0：录音权限（P0 崩溃修复，必读）**
     * 服务声明的是 `foregroundServiceType="microphone"`，系统对该类型前台服务的要求是
     * 「持有 `FOREGROUND_SERVICE_MICROPHONE` **且** 持有 `RECORD_AUDIO` 之类**且**处于
     * eligible state」，而校验发生在 [android.app.Service.startForeground] **的瞬间**——
     * **与待命态是否真的在录音无关**。缺 `RECORD_AUDIO` 时 `startForeground` 直接抛
     * `SecurityException`，而它跑在服务进程里 ⇒ **App 启动即崩**（装机实测：
     * 默认开关是开 → 首启建入口 → 崩）。
     *
     * 更关键的是：这个异常**发生在本方法返回之后**（`startForegroundService` 本身
     * 是异步的、不会抛），调用方**拿不到**——所以必须在**这里**就拦住。
     * 缺权限时快捷入口本就无意义（点了也不能录），直接返回失败 + 可见提示，不起服务。
     *
     * **失败不静默**（项目红线）：失败一律发 alert 渠道可见通知。否则用户只会看到
     * 「设置里开关是开的、锁屏却没有入口」而得不到任何解释。
     *
     * 通知由**本实现**代发而非交给调用方：三个调用方（首启 / 设置页 / 开机）
     * 都经由此处，写在实现里可保证**任何新增调用方都不可能因「忘了处理返回值」
     * 而静默失败**，也让这道权限闸只需写一次。调用方仍须处理返回值（至少补日志）。
     *
     * 说明：本方法非 suspend，`startForegroundService` 为同步调用，不存在协程取消
     * 场景，故无需像 suspend 函数那样显式放行 `CancellationException`；
     * 此处 `runCatching` 捕获的是启动被拒的真实异常，不是吞异常。
     */
    override fun enterStandby(): EchoResult<Unit> {
        if (!hasRecordAudioPermission()) {
            val message = context.getString(R.string.notify_quick_entry_reason_no_record_audio)
            EchoLog.w(TAG, "缺少录音权限，不建立快捷录音入口（入口点了也不能录）")
            notifyQuickEntryFailed(message)
            return EchoResult.failure(
                code = Constants.ERROR_QUICK_ENTRY_FAILED,
                message = message,
            )
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_STANDBY
        }
        return runCatching<EchoResult<Unit>> {
            ContextCompat.startForegroundService(context, intent)
            EchoResult.success(Unit)
        }.getOrElse { throwable ->
            val message = context.getString(
                R.string.notify_quick_entry_reason_start,
                throwable.message ?: throwable::class.java.simpleName,
            )
            EchoLog.w(TAG, "待命服务启动被拒绝，快捷录音入口未建立", throwable)
            notifyQuickEntryFailed(message)
            EchoResult.failure(
                code = Constants.ERROR_QUICK_ENTRY_FAILED,
                message = message,
                cause = throwable,
            )
        }
    }

    /** 快捷入口建立失败的可见提示（不可滑掉，点击回应用内）。 */
    private fun notifyQuickEntryFailed(message: String) {
        notificationHelper.notify(
            Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED,
            notificationHelper.buildQuickEntryFailedNotification(message),
        )
    }

    /**
     * 是否持有录音权限。
     *
     * 这道闸的作用是**避免以 microphone 型前台服务进入前台**——
     * 缺少该权限时 `startForeground` 会抛 `SecurityException` 崩掉进程。
     */
    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RecordingServiceController"
    }
}
