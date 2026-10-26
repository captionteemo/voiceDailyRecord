package com.echolog.app.service.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.echolog.app.MainActivity
import com.echolog.app.R
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.service.RecordingService
import com.echolog.app.service.RecordingServiceState
import com.echolog.app.service.receiver.NotificationActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知构建与发送（架构 2.3/7.5）。
 *
 * 四类通知：
 * 1. [buildMonitorNotification] 常驻监听通知（channel_monitor，LOW，ongoing 不可滑掉，FR-013）：
 *    含「正在录音」文案 + 隐私提示 + 打点（RemoteInput 备注）/结束快捷操作；
 * 2. [buildQuickRecordNotification] 待命态快捷录音通知（channel_quick_record，LOW，
 *    ongoing + 锁屏可见）：未录音时的一键入口，只有「开始录音」一个操作；
 * 3. [buildProgressNotification] 结算/下载进度通知（channel_progress，LOW）；
 * 4. [buildRecoveryNotification] 中断恢复高优先级通知（channel_alert，HIGH，NFR-003 第三层防线）。
 *
 * 第 1、2 类共用 [Constants.NOTIFICATION_ID_MONITOR]，因此**互斥**：
 * 「监听中 ↔ 待命」只是替换同一条通知的内容，任一时刻只显示一条。
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val notificationManager: NotificationManager? =
        ContextCompat.getSystemService(context, NotificationManager::class.java)

    // ── 常驻监听通知（FR-013） ──

    fun buildMonitorNotification(
        dayId: String,
        serviceState: RecordingServiceState,
        monitoredMs: Long,
        currentSegmentMs: Long,
        segmentCount: Int,
        markerCount: Int,
        privacyMode: PrivacyMode,
        recordingMode: RecordingMode = RecordingMode.AUTO_VAD,
        manualRecordingOn: Boolean = false,
    ): Notification {
        val recording = serviceState == RecordingServiceState.RECORDING
        val paused = serviceState == RecordingServiceState.PAUSED
        val title = context.getString(
            when {
                recording -> R.string.notify_monitor_title_recording
                paused -> R.string.notify_monitor_title_paused
                else -> R.string.notify_monitor_title_listening
            },
        )
        val text = when {
            recording -> context.getString(
                R.string.notify_monitor_text_recording,
                TimeUtils.formatDurationClock(currentSegmentMs),
            )

            paused -> context.getString(
                R.string.notify_monitor_text_paused,
                segmentCount,
            )

            else -> context.getString(
                R.string.notify_monitor_text_listening,
                TimeUtils.formatDurationClock(monitoredMs),
                segmentCount,
                markerCount,
            )
        }
        val builder = NotificationCompat.Builder(context, Constants.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openMainActivityIntent(dayId))
            .addAction(buildMarkerAction(dayId))
        // 用户需求 4：手动模式用「开始/停止录音」替换「暂停/继续」
        if (recordingMode == RecordingMode.MANUAL) {
            builder.addAction(buildManualRecordAction(dayId, manualRecordingOn))
        } else {
            // 用户需求 2：监听/录音态显示「暂停」，暂停态显示「继续」
            builder.addAction(
                if (paused) buildResumeAction(dayId) else buildPauseAction(dayId),
            )
        }
        builder.addAction(buildStopAction(dayId))
        if (privacyMode == PrivacyMode.MEETING) {
            builder.setSubText(context.getString(R.string.notify_privacy_mode_meeting))
        }
        return builder.build()
    }

    // ── 进度通知（结算 / 下载，channel_progress） ──

    fun buildProgressNotification(dayId: String, stage: String, progressPercent: Int): Notification =
        NotificationCompat.Builder(context, Constants.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_monitor)
            .setContentTitle(stage)
            .setContentText(context.getString(R.string.notify_progress_text, progressPercent))
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openMainActivityIntent(dayId))
            .build()

    // ── 中断恢复通知（channel_alert，NFR-003 第三层） ──

    fun buildRecoveryNotification(dayId: String): Notification =
        NotificationCompat.Builder(context, Constants.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(context.getString(R.string.notify_recovery_title))
            .setContentText(context.getString(R.string.notify_recovery_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            // 用户点击通知 = 用户主动触发，可合法拉起前台服务（Android 12+ 兜底路径）
            .setContentIntent(resumeServiceIntent(dayId))
            .addAction(
                R.drawable.ic_notification_monitor,
                context.getString(R.string.notify_action_resume),
                resumeServiceIntent(dayId),
            )
            .build()

    // ── 快捷录音入口（用户需求：锁屏/状态栏一键开始录音） ──

    /**
     * 待命态快捷通知：低优先级、**ongoing（不可滑掉）**、**锁屏可见**。
     *
     * 与 [buildMonitorNotification] 共用 [Constants.NOTIFICATION_ID_MONITOR]，
     * 因此「监听中 / 待命」任一时刻只会有一条通知（互斥由通知 id 保证，
     * 不需要额外的取消逻辑）。
     *
     * 只提供「开始录音」一个操作：待命态没有进行中的录音，暂停/继续/打点/结束
     * 均不适用——既有操作的语义保持不变。
     */
    fun buildQuickRecordNotification(dayId: String): Notification =
        NotificationCompat.Builder(context, Constants.CHANNEL_QUICK_RECORD)
            .setSmallIcon(R.drawable.ic_notification_record)
            .setContentTitle(context.getString(R.string.notify_quick_record_title))
            .setContentText(context.getString(R.string.notify_quick_record_text))
            // 常驻：不可滑掉（与监听通知一致，误滑就会失去入口）
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // 锁屏可见：渠道已设为 PUBLIC，此处再显式声明
            // （系统取「渠道可见性」与「通知可见性」中**更严格**的一方，两者都要设）
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openMainActivityIntent(dayId))
            .addAction(buildQuickStartAction(dayId))
            .build()

    /**
     * 快捷入口启动失败提示（项目红线：未授权/失败不得静默失败，必须给可见提示）。
     *
     * 走 [Constants.CHANNEL_ALERT] 高优先级渠道，点击回到应用内处理
     * （缺麦克风权限时由应用内授权流兜底）。
     */
    fun buildQuickStartFailedNotification(reason: String): Notification =
        NotificationCompat.Builder(context, Constants.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(context.getString(R.string.notify_quick_start_failed_title))
            .setContentText(context.getString(R.string.notify_quick_start_failed_text, reason))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openMainActivityIntent(TimeUtils.todayDayId()))
            .build()

    /**
     * 快捷入口**建立失败**提示（项目红线：入口没建起来不得静默）。
     *
     * 与 [buildQuickStartFailedNotification] 的分工：
     * - 那条是用户点了入口、录音**没起来**；
     * - 本条是入口**本身没建起来**——设置里开关显示已开、锁屏却没有入口。
     *
     * 走 [Constants.CHANNEL_ALERT]（IMPORTANCE_HIGH，会横幅提醒），并占用独立 id
     * [Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED] 以免与「中断恢复」提示互相顶掉。
     *
     * **「不可滑掉」两个开关都要设**（口径勿混淆）：
     * - `setAutoCancel(false)` —— 只管「点击后不自动消失」；
     * - `setOngoing(true)` —— 才真正禁止左右滑动删除。
     * 一划就消失的提醒与被静默掉没有区别，故两条都设。
     *
     * **ongoing 的代价与兜底**：ongoing 通知用户滑不掉，只能由应用主动 cancel。
     * 为免它变成一条清不掉的常驻通知，入口**成功**建立时
     * （`RecordingService.handleEnterStandby`）会 cancel 本 id ——
     * 提醒的生命周期 = 「入口处于未建立状态」的持续时间，语义自洽。
     */
    fun buildQuickEntryFailedNotification(reason: String): Notification =
        NotificationCompat.Builder(context, Constants.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(context.getString(R.string.notify_quick_entry_failed_title))
            .setContentText(context.getString(R.string.notify_quick_entry_failed_text, reason))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openMainActivityIntent(TimeUtils.todayDayId()))
            .build()

    // ── 发送 / 取消 ──

    fun notify(id: Int, notification: Notification) {
        notificationManager?.notify(id, notification)
    }

    fun cancel(id: Int) {
        notificationManager?.cancel(id)
    }

    // ── 内部构建 ──

    private fun openMainActivityIntent(dayId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 点击后以 ACTION_RESUME 合法拉起 RecordingService（前台服务）。 */
    private fun resumeServiceIntent(dayId: String): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_RESUME
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        return PendingIntent.getForegroundService(
            context,
            REQUEST_CODE_RESUME,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 暂停操作（用户需求 2）：走 NotificationActionReceiver →
     * RecordingService.handlePause（收尾当前片段并切暂停态）。
     */
    private fun buildPauseAction(dayId: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = Constants.ACTION_PAUSE
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PAUSE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_pause,
            context.getString(R.string.notify_action_pause),
            pendingIntent,
        )
            .build()
    }

    /**
     * 继续操作（用户需求 2）：以 ACTION_RESUME 合法拉起 RecordingService
     * （服务已在前台，直接重开音频循环并恢复 MONITORING）。
     */
    private fun buildResumeAction(dayId: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            R.drawable.ic_notification_monitor,
            context.getString(R.string.notify_action_resume),
            resumeServiceIntent(dayId),
        )
            .build()

    /**
     * 「开始录音」操作（快捷入口）：以 [Constants.ACTION_QUICK_START] 投递
     * [RecordingService]，由服务复用 `StartMonitoringUseCase` 建档并重启音频循环。
     *
     * 用 [PendingIntent.getForegroundService]（与 [resumeServiceIntent] 同款）：
     * - 待命态下服务**本就在前台**，这里只是投递动作，不存在后台启动问题；
     * - 即便服务已被系统回收，通知点击属用户主动触发，可合法启动前台服务
     *   （Android 12+ 的兜底路径，见 [buildRecoveryNotification] 的同款注释）。
     */
    private fun buildQuickStartAction(dayId: String): NotificationCompat.Action {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_QUICK_START
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_CODE_QUICK_START,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_record,
            context.getString(R.string.notify_action_start_record),
            pendingIntent,
        )
            .build()
    }

    /** 打点操作：带 RemoteInput 备注框（可跳过，架构 4.1 PRD 4.1）。 */
    private fun buildMarkerAction(dayId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(Constants.EXTRA_MARKER_NOTE)
            .setLabel(context.getString(R.string.notify_marker_input_label))
            .build()
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = Constants.ACTION_ADD_MARKER
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        // RemoteInput 需要 MUTABLE PendingIntent 才能回填结果
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MARKER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_marker,
            context.getString(R.string.notify_action_marker),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    /**
     * 手动录音开停操作（用户需求 4 第四种：状态栏录音按键）。
     *
     * 走 NotificationActionReceiver → RecordingService.handleManualToggle；
     * 携带 [Constants.EXTRA_RECORDING_ON] 指定目标态（幂等：重复点击不会反复翻转）。
     */
    private fun buildManualRecordAction(
        dayId: String,
        recordingOn: Boolean,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = Constants.ACTION_MANUAL_TOGGLE
            putExtra(Constants.EXTRA_DAY_ID, dayId)
            putExtra(Constants.EXTRA_RECORDING_ON, !recordingOn)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MANUAL_RECORD,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            if (recordingOn) R.drawable.ic_notification_stop else R.drawable.ic_notification_record,
            context.getString(
                if (recordingOn) R.string.notify_action_stop_record else R.string.notify_action_start_record,
            ),
            pendingIntent,
        )
            .build()
    }

    /** 结束操作：走 NotificationActionReceiver → StopMonitoringAndSettleUseCase。 */
    private fun buildStopAction(dayId: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = Constants.ACTION_STOP_SETTLE
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_stop,
            context.getString(R.string.notify_action_stop),
            pendingIntent,
        )
            .build()
    }

    companion object {
        private const val REQUEST_CODE_OPEN_APP = 2001
        private const val REQUEST_CODE_RESUME = 2002
        private const val REQUEST_CODE_MARKER = 2003
        private const val REQUEST_CODE_STOP = 2004
        private const val REQUEST_CODE_PAUSE = 2005
        private const val REQUEST_CODE_MANUAL_RECORD = 2006
        private const val REQUEST_CODE_QUICK_START = 2007
    }
}
