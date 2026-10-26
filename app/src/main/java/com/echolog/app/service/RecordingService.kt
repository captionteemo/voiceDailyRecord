package com.echolog.app.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.HandlerThread
import android.os.Process
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.echolog.app.core.common.ApplicationScope
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.R
import com.echolog.app.core.util.EchoLog
import com.echolog.app.core.util.FileUtils
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.data.media.AudioRecorder
import com.echolog.app.data.media.StorageLocationManager
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.ScheduleTemplate
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.ScheduleTemplateRepository
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.AddMarkerUseCase
import com.echolog.app.domain.usecase.SettleScheduler
import com.echolog.app.domain.usecase.StartMonitoringUseCase
import com.echolog.app.domain.usecase.StopMonitoringAndSettleUseCase
import com.echolog.app.service.notify.NotificationHelper
import com.echolog.app.service.vad.EnergyVadEngine
import com.echolog.app.service.vad.VadEngine
import com.echolog.app.service.vad.VadEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

/**
 * 前台录音服务（架构 1.5/2.3）：录音状态机宿主。
 *
 * 职责（架构 4.1 时序）：
 * 1. `ACTION_START_MONITORING` / `ACTION_RESUME`：进入前台（microphone 类型常驻通知）→
 *    确认目标录音日 → 校准噪声底 → 启动音频线程循环；
 * 2. 音频循环（`EchoAudioThread`，单线程，架构 7.3）：AudioRecord 读帧 → 预录缓冲/VAD →
 *    开录（insertSegment + beginSegmentFile）/持续写帧/断段（endSegmentFile + finishSegment）；
 * 3. 每 5s 写服务心跳（保活第二层 HealthCheckWorker 的依据）；
 * 4. `ACTION_ADD_MARKER`：通知栏/应用内一键打点（含 RemoteInput 备注）；
 * 5. `ACTION_STOP_SETTLE`：收尾当前片段并停止（DB 状态置 SETTLING 由
 *    [com.echolog.app.domain.usecase.StopMonitoringAndSettleUseCase] 完成）。
 *
 * 音频线程禁止做 DB/网络：所有仓储写操作切到 [IoDispatcher]。
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject
    lateinit var stateHolder: RecordingStateHolder

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var recordingRepository: RecordingRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var scheduleTemplateRepository: ScheduleTemplateRepository

    @Inject
    lateinit var settleScheduler: SettleScheduler

    @Inject
    lateinit var addMarkerUseCase: AddMarkerUseCase

    /** 快捷入口「开始录音」复用应用内同一建档链路（用户需求：锁屏/状态栏一键开始）。 */
    @Inject
    lateinit var startMonitoringUseCase: StartMonitoringUseCase

    @Inject
    lateinit var storageLocationManager: StorageLocationManager

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    private val recorder = AudioRecorder()
    private val vad: VadEngine = EnergyVadEngine()

    private lateinit var audioThread: HandlerThread
    private lateinit var audioDispatcher: CoroutineDispatcher
    private var loopJob: Job? = null
    private val serviceLock = Any()

    @Volatile
    private var running: Boolean = false

    /** 暂停标记（用户需求 2：通知栏暂停/继续）。暂停时服务保持前台但音频循环停止。 */
    @Volatile
    private var paused: Boolean = false

    /**
     * 待命标记（用户需求：锁屏/状态栏快捷录音入口）。
     *
     * 待命 = 服务保持前台、音频循环未运行、常驻通知为快捷录音入口。
     * 与 [paused] 的区别：暂停是用户主动暂停（可继续当天监听，DB 仍是 MONITORING，
     * 心跳由 [startPauseHeartbeat] 维持）；待命是当天已结束（DB 已转 SETTLING），
     * 服务仅为快捷入口常驻，且**不写心跳**（`monitorActive` 已为 false，
     * HealthCheckWorker 不会误判服务已死而拉起）。
     */
    @Volatile
    private var standby: Boolean = false

    @Volatile
    private var dayId: String? = null

    @Volatile
    private var segmentOpen: Boolean = false

    @Volatile
    private var currentSegmentId: Long = 0L

    @Volatile
    private var currentSegmentPath: String? = null

    @Volatile
    private var currentSegmentStartMs: Long = 0L

    @Volatile
    private var segmentCount: Int = 0

    @Volatile
    private var markerCount: Int = 0

    @Volatile
    private var privacyMode: PrivacyMode = PrivacyMode.PERSONAL

    /** 当前采样率（用户需求 3，设置可配；帧缓冲随其缩放）。 */
    @Volatile
    private var sampleRate: Int = Constants.DEFAULT_SAMPLE_RATE

    /** 当前录音模式（用户需求 4：全天/自动/模板/手动），启动时从设置读取。 */
    @Volatile
    private var recordingMode: RecordingMode = RecordingMode.AUTO_VAD

    /**
     * 手动模式的「正在录」标志（用户需求 4 第四种）。
     *
     * 由通知栏/App 的录音按键经 [Constants.ACTION_MANUAL_TOGGLE] 切换；
     * 音频循环每帧检查此标志决定是否写文件。
     */
    @Volatile
    private var manualRecordingOn: Boolean = false

    /** 模板录音的时间模板（用户需求 4 第三种；每次判定前刷新）。 */
    @Volatile
    private var scheduleTemplate: ScheduleTemplate = ScheduleTemplate()

    /** 模板模式下当前所处区间的下标（-1 = 不在任何区间）。 */
    @Volatile
    private var activeScheduleRangeIndex: Int = -1

    /** VAD 帧缓冲（大小随采样率缩放：25ms 名义帧长）。 */
    @Volatile
    private var frameBuffer: ShortArray = ShortArray(Constants.FRAME_SAMPLES)

    private var monitorStartedAt: Long = 0L
    private var lastHeartbeatAt: Long = 0L
    private var lastStatePushAt: Long = 0L

    /**
     * 最近一次**常驻通知**刷新时刻（epoch millis）。
     *
     * 与 [lastStatePushAt]（内部状态推送，500ms）刻意分开：状态推送只改内存里的
     * StateFlow（很便宜），而通知刷新要过 [startForegroundCompat]（binder 到 AMS +
     * 通知服务 + FGS 类型校验），两者成本差一个数量级。
     */
    @Volatile
    private var lastNotificationRefreshAt: Long = 0L

    /**
     * 监听通知刷新失败是否已经提示过（限流）。
     *
     * 失败通常是持续性的（权限被撤销不会自愈），若每次刷新都发一条可见提示，
     * 用户会被刷屏。故只在**一段连续失败**里提示一次，成功后复位。
     */
    @Volatile
    private var monitorNotificationFailureNotified: Boolean = false

    /**
     * 当前是否持有**前台服务身份**（由 [startForegroundCompat] 建立）。
     *
     * 用途：区分「刷新前台通知失败」与「从未进入过前台」——
     * 前者**绝不能**停服务（停了 = 全天录音被静默中断），后者必须退出
     * （否则 ANSS 的 5s 超时会抛 ForegroundServiceDidNotStartInTimeException 崩溃）。
     */
    @Volatile
    private var foregroundEstablished: Boolean = false

    /**
     * 服务是否已被销毁（onDestroy 已回调）。
     *
     * 用途：异步任务（如待命态的持久化状态复核）在回调时若服务已销毁，
     * 不应再触碰通知/前台身份。
     */
    @Volatile
    private var destroyed: Boolean = false

    private var vadErrorStreak: Int = 0

    /** 模板区间最近一次判定时刻（用户需求 4：模板录音）。 */
    private var lastScheduleCheckAt: Long = 0L

    /** 跨零点检测最近一次时刻（用户需求 4 第一种：全天录音跨天换天）。 */
    @Volatile
    private var lastRolloverCheckAt: Long = 0L

    /** 暂停期间的心跳保活 Job（音频循环停止后由它维持健康检查依据）。 */
    private var pauseHeartbeatJob: Job? = null

    /** 待命期间的开关监听 Job（用户在设置里关闭快捷入口后让服务自行退出）。 */
    private var quickRecordWatchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        audioThread = HandlerThread("EchoAudioThread", Process.THREAD_PRIORITY_AUDIO)
        audioThread.start()
        audioDispatcher = android.os.Handler(audioThread.looper).asCoroutineDispatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_START_MONITORING, Constants.ACTION_RESUME -> handleStart(intent)
            Constants.ACTION_PAUSE -> handlePause()
            Constants.ACTION_ADD_MARKER -> handleMarker(intent)
            Constants.ACTION_STOP_SETTLE -> handleStop(intent)
            Constants.ACTION_MANUAL_TOGGLE -> handleManualToggle(intent)
            Constants.ACTION_QUICK_START -> handleQuickStart()
            Constants.ACTION_STANDBY -> handleEnterStandby()
            // P0 修复：系统按 START_STICKY 语义重启服务时，**Intent 为 null**
            // （或 action 为 null），此时不能走「未知动作 → 自杀」分支：
            // 那会让服务刚被系统拉起就立刻 stopSelfSafely()，锁屏快捷录音入口
            // **永远不会被重建**（用户体感：锁屏的 bar 莫名其妙消失了，只能
            // 冷启动 App 才能回来）。
            //
            // 处理：按「系统重启 → 重建待命入口」处理。重建失败（缺权限、
            // 系统拒绝 microphone 型 FGS）由 handleEnterStandby 内部兜住并
            // 转失败出口，不会崩。
            null -> handleRestartWithoutIntent()
            else -> if (!running && !standby) stopSelfSafely()
        }
        // 被系统杀死后带空 Intent 重启时尽量恢复（START_STICKY 语义）
        return START_STICKY
    }

    /**
     * 系统按 START_STICKY 语义重启服务（Intent 为 null）时的恢复逻辑。
     *
     * 三种可能状态：
     * 1. 原本在监听/暂停 → 音频循环已被系统一并回收，需要重新拉起监听；
     * 2. 原本在待命（快捷入口） → 重建待命入口，锁屏 bar 回来；
     * 3. 原本就没在工作 → 无需恢复，让服务自行退出。
     *
     * 判据用「当天是否存在监听中的录音日」而非内存态——内存态在进程被杀后已丢失。
     */
    private fun handleRestartWithoutIntent() {
        EchoLog.i(TAG, "系统重启服务（空 Intent），尝试恢复状态")
        scope.launch(ioDispatcher) {
            val hasMonitoringDay = recordingRepository.observeDays().first()
                .any { it.state == DayState.MONITORING }
            when {
                hasMonitoringDay -> {
                    // 监听日仍在：重新拉起监听链路（handleStart 内部有权限校验，
                    // 缺权限时走失败出口而非崩溃）
                    EchoLog.i(TAG, "存在监听中的录音日 → 恢复监听")
                    val resumeIntent = Intent()
                    resumeIntent.action = Constants.ACTION_RESUME
                    handleStart(resumeIntent)
                }
                settingsRepository.getSettings().quickRecordNotificationEnabled -> {
                    // 无监听日但快捷入口开着 → 重建待命入口（锁屏 bar 恢复）
                    EchoLog.i(TAG, "无监听中的录音日且快捷入口已开启 → 重建待命入口")
                    handleEnterStandby()
                }
                else -> {
                    EchoLog.i(TAG, "无监听中的录音日且快捷入口已关闭 → 服务无需存在")
                    stopSelfSafely()
                }
            }
        }
    }

    // ── Action 处理 ──

    /**
     * 开启/恢复监听。
     *
     * 先立即进入前台（避免 ANSS 5s 超时崩溃，通知先以监听态呈现），
     * 再异步确认目标录音日并启动音频循环。
     */
    private fun handleStart(intent: Intent) {
        // 竞态守卫：handleStart 可能由**异步回调**调用（[handleEnterStandby] 第 3 步、
        // [handleRestartWithoutIntent]），而这两个回调跑在 @ApplicationScope 上——
        // **不随服务销毁而取消**。若服务在回调到达前已 onDestroy，继续往下走会
        // 对已 release 的 recorder 调 open()（native 崩溃风险），并给用户发一条
        // 莫名其妙的「无法开始录音」。故先确认服务还活着。
        if (destroyed) {
            EchoLog.w(TAG, "服务已销毁，忽略启动监听请求")
            return
        }
        // P0 崩溃防线（与 handleEnterStandby 同源）：本服务是 microphone 型前台服务，
        // `startForeground` 在缺 `RECORD_AUDIO` 时抛 `SecurityException` ⇒ 进程崩溃。
        // 到达此处的路径不止一条（应用内开启监听、通知栏「继续」、健康检查自动恢复、
        // 快捷入口 QUICK_START），其中**健康检查恢复**完全自动，权限可能在监听期间
        // 被用户在系统设置里撤销——因此必须在这里兜住，不能依赖上游校验。
        if (!hasRecordAudioPermission()) {
            EchoLog.w(TAG, "缺少录音权限，无法进入前台监听（不进入前台，避免崩溃）")
            notifyQuickStartFailed(getString(R.string.notify_quick_start_reason_permission))
            // 用 stopSelfSafely（= 撤前台 + stopSelf）：本分支可能「从未进入前台」
            // （冷启动开启监听），也可能「已在待命前台」（通知栏继续），
            // 而 stopForeground 对未处于前台的服务是 no-op，故统一走既有收尾路径。
            stopSelfSafely()
            return
        }
        // 离开待命态（快捷入口 → 监听中）：停止开关监听，常驻通知由 startForeground 换入
        standby = false
        cancelQuickRecordWatch()
        // 铁律 12：换通知必须走 startForeground（见 updateForegroundNotification 的机制说明），
        // 且必须 runCatching 兜住 SecurityException——权限可能在上面的检查与本次调用之间
        // 被撤销，未捕获 ⇒ 从 onStartCommand 冒泡 ⇒ 进程崩溃。
        val foregroundError = updateForegroundNotification(
            buildMonitorNotification(RecordingServiceState.MONITORING, 0L, 0L, 0, 0),
        )
        if (foregroundError != null) {
            if (!foregroundEstablished) {
                // 从未成功进入过前台：本次是 `startForegroundService` 拉起的冷启动路径，
                // 若不在 ANSS 的 5s 时限内成功进前台，系统会抛
                // ForegroundServiceDidNotStartInTimeException ⇒ 进程崩溃。
                // 故必须主动退出（stopSelfSafely = 撤前台 + stopSelf）。
                EchoLog.w(TAG, "进入前台监听失败（系统拒绝），服务退出以规避 ANSS 超时崩溃")
                // 这条提示的断言是成立的：服务即将退出 ⇒ 确实不会开始录音。
                notifyQuickStartFailed(
                    getString(R.string.notify_foreground_rejected_reason, foregroundError.message ?: ""),
                )
                stopSelfSafely()
                return
            }
            // 已经持有前台身份（由待命态的 startForeground 建立）：startForeground 失败
            // **不会**撤销既有前台身份，旧通知仍然挂着。此时停服务 = 中断正在进行的
            // 录音（用户侧表现为「全天录音被静默中断」），代价远大于一次通知没换成功。
            // 故保留服务继续往下走，交给后续的周期性通知刷新重试。
            EchoLog.w(TAG, "换入监听通知失败（仍持有前台身份），保留服务并继续监听")
            // 这里**不能**发「无法开始录音」——服务会继续跑、录音会照常开始，
            // 断言「无法开始」是说过头的话（与「记录已中断」同一类错误）。
            // 只陈述能确定的事实：系统拒绝了这次通知更新。
            notifyMonitorRefreshFailed()
        }
        scope.launch(ioDispatcher) {
            val targetDayId = intent.getStringExtra(Constants.EXTRA_DAY_ID)
            var day = targetDayId?.let { recordingRepository.getDay(it) }
                ?: recordingRepository.observeDays().first()
                    .firstOrNull { it.state == DayState.MONITORING }
            if (day == null) {
                // 无监听中的录音日：服务无意义，退出
                stateHolder.update(RecordingUiState())
                stopSelfSafely()
                return@launch
            }
            val settings = settingsRepository.getSettings()
            privacyMode = settings.privacyMode
            (vad as EnergyVadEngine).setSensitivity(settings.sensitivity)
            vad.setSilenceDurationMs(settings.silenceDurationMs)
            // 用户需求 4：录音模式（当天以建档时落库的 mode 为准，缺失时回落到当前设置）
            recordingMode = day.mode
            // 模板模式：预取模板（区间为空时视为无模板，行为退化为「不录音」）
            if (recordingMode == RecordingMode.SCHEDULE_TEMPLATE) {
                scheduleTemplate = scheduleTemplateRepository.getTemplate()
            }
            // 用户需求 3：按设置的采样率采集（帧缓冲随之缩放；非 16k 时 ASR 前重采样）
            sampleRate = settings.sampleRate
            frameBuffer = ShortArray(Constants.frameSamples(sampleRate))

            // 全天录音跨零点续录（用户需求 4 第一种）：服务在零点后被系统杀死并重启时，
            // 拾取到的仍是「昨天」的 MONITORING 行——此处先把昨天入队结算，再切到今天继续录。
            val today = TimeUtils.todayDayId()
            if (recordingMode == RecordingMode.ALL_DAY && day.dayId != today) {
                day = rolloverStaleDayToToday(day, today)
            }

            synchronized(serviceLock) {
                if (running) return@launch
                if (!recorder.open(sampleRate = sampleRate)) {
                    // E-1001 音频采集初始化失败
                    stateHolder.update(
                        RecordingUiState(
                            dayId = day.dayId,
                            serviceState = RecordingServiceState.IDLE,
                            errorCode = Constants.ERROR_AUDIO_INIT,
                            errorMessage = "麦克风初始化失败，请重试",
                        ),
                    )
                    stopSelfSafely()
                    return@launch
                }
                running = true
                paused = false
                cancelPauseHeartbeat()
                dayId = day.dayId
                monitorStartedAt =
                    if (day.monitorStartedAt > 0L) day.monitorStartedAt else TimeUtils.currentEpochMs()
                segmentCount = 0
                markerCount = 0
                vadErrorStreak = 0
                // 用户需求 4：手动模式恢复时不自动开录（等待用户点按录音键）
                manualRecordingOn = false
                activeScheduleRangeIndex = -1
            }
            stateHolder.update(
                RecordingUiState(
                    dayId = day.dayId,
                    serviceState = RecordingServiceState.MONITORING,
                    recordingMode = recordingMode,
                    manualRecordingOn = false,
                ),
            )
            settingsRepository.setMonitorActive(true)
            launchAudioLoop()
        }
    }

    /**
     * 全天录音跨零点续录辅助（用户需求 4 第一种）。
     *
     * 把陈旧的（昨天的）MONITORING 天置 `SETTLING` 并入队结算，
     * 再为今天建档并返回新的一天。**不**触碰 `monitorActive` 与心跳标记——
     * 服务本身仍在录音，保活链路不能断。
     */
    private suspend fun rolloverStaleDayToToday(
        staleDay: RecordingDay,
        today: String,
    ): RecordingDay {
        recordingRepository.updateDayState(staleDay.dayId, DayState.SETTLING)
        recordingRepository.updateSettleProgress(
            staleDay.dayId,
            StopMonitoringAndSettleUseCase.SETTLE_START_PROGRESS,
            null,
        )
        settleScheduler.scheduleDaySettle(staleDay.dayId)
        return recordingRepository.createOrResumeDay(today, recordingMode)
    }

    /**
     * 暂停监听（用户需求 2：通知栏/应用内暂停）。
     *
     * 语义：收尾当前片段（不丢失已录内容）→ 停止音频循环 → 服务保持前台，
     * 常驻通知切为「已暂停」。暂停期间 DB 日状态仍为 MONITORING（可随时继续），
     * 心跳由 [startPauseHeartbeat] 维持，健康检查不会误恢复。
     */
    private fun handlePause() {
        synchronized(serviceLock) {
            if (!running) return
            running = false
            paused = true
        }
        // 音频循环自行退出（≤1 帧，25ms）并在 finally 中收尾片段；
        // onAudioLoopExited 依据 paused 仅刷新通知，不停前台服务
    }

    /** 一键打点（通知栏 RemoteInput 或应用内）。 */
    private fun handleMarker(intent: Intent) {
        val targetDayId = intent.getStringExtra(Constants.EXTRA_DAY_ID) ?: dayId ?: return
        val note = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Constants.EXTRA_MARKER_NOTE)
            ?.toString()
            ?.trim()
            .orEmpty()
        scope.launch(ioDispatcher) {
            addMarkerUseCase(targetDayId, note)
            markerCount++
            refreshMonitorNotification()
            stateHolder.mutate { it.copy(markerCount = markerCount) }
        }
    }

    /**
     * 停止并准备结算：仅服务侧音频收尾，DB 状态迁移由用例负责。
     *
     * 按 [Constants.EXTRA_DAY_ID] 只停目标天（BUG-4 修复）：跨天 Worker 停陈旧天时，
     * 若服务正在录今天（dayId 不一致），不影响当前音频循环，DB 迁移由
     * [StopMonitoringAndSettleUseCase] 独立完成。
     */
    private fun handleStop(intent: Intent?) {
        val targetDayId = intent?.getStringExtra(Constants.EXTRA_DAY_ID)
        synchronized(serviceLock) {
            if (targetDayId != null && targetDayId != dayId) {
                // 停的是别的天：不动当前音频。**待命态必须保留**——跨天 Worker
                // 归档昨天时会投递该动作，若顺手 stopSelf 会把快捷入口一起撤掉
                // （用户体感：没动过它，锁屏入口却莫名没了）。
                if (!running && !standby) stopSelfSafely()
                return
            }
            if (!running) {
                // 待命态（当天已结束）：没有音频循环可收尾，保留快捷入口常驻
                if (standby) return
                stopSelfSafely()
                return
            }
            running = false
            paused = false
        }
        cancelPauseHeartbeat()
        // 音频循环自行退出（≤1 帧，25ms）并在 finally 中收尾片段/停止前台
    }

    /**
     * 快捷入口「开始录音」（用户需求：锁屏/状态栏一键开始录音）。
     *
     * 复用 [StartMonitoringUseCase]——与应用内「开始记录」按钮**完全同一链路**
     * （建档/续档当天 → 置监听活跃标记 → 拉起前台服务），避免两条路径行为漂移；
     * 服务随后收到 [Constants.ACTION_START_MONITORING]，由 [handleStart] 把常驻
     * 通知从快捷入口换回监听通知（两者共用同一通知 id，天然互斥）。
     *
     * 失败**不静默**（项目红线）：缺麦克风权限或建档失败时发一条高优先级通知，
     * 点击回到应用内处理。
     */
    private fun handleQuickStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyQuickStartFailed(getString(R.string.notify_quick_start_reason_permission))
            return
        }
        scope.launch(ioDispatcher) {
            when (val result = startMonitoringUseCase()) {
                is EchoResult.Success -> Unit // 监听通知由 handleStart 换入
                is EchoResult.Failure -> notifyQuickStartFailed(result.message)
            }
        }
    }

    /**
     * 直接进入待命态（快捷录音入口）：开机自恢复 / 设置开关打开时调用
     * （[Constants.ACTION_STANDBY]，经 `ServiceController.enterStandby`）。
     *
     * 与 [enterStandbyMode] 的区别：本方法**尚未处于前台**，需先
     * `startForegroundCompat` 挂出快捷通知；[enterStandbyMode] 是「音频循环刚退出、
     * 服务本就在前台」时只替换通知内容的路径。
     *
     * 已在监听/暂停/待命时**无操作**——不得打断进行中的录音。
     *
     * ## 铁律 13：最终态一致性（缺陷 B 修复）
     *
     * 本方法是「冷启动快捷入口」路径。它与 [handleRestartWithoutIntent] 走的是
     * **同一条** [Constants.ACTION_STANDBY] 通道，但两者进入时的内存态不同：
     * - [handleRestartWithoutIntent]：进程被系统杀掉后重启，内存态全丢；
     * - 本方法：可能是**同一次冷启动**里先走了待命分支。
     *
     * 缺陷现象：冷启动时若 DB 里**仍有监听中的录音日**，但先走了待命分支，
     * 最终态就是「待命（快捷入口）」——与「当天在监听」这一持久化事实矛盾：
     * 用户看到的是快捷入口，而实际上当天仍在监听 ⇒ 两边打架
     * （通知显示待命、DB 显示监听、录音循环也没跑）。
     *
     * 修复采用**四步结构**，顺序不可换：
     * 1. **同步**建立前台身份（`startForegroundCompat`）——满足 ANSS 的 5s 时限，
     *    这一步**绝不能**挪进协程，否则 `startForegroundService` 后 5s 内未进前台 ⇒
     *    `ForegroundServiceDidNotStartInTimeException` ⇒ 崩溃；
     * 2. **异步**查持久化状态（DB 里是否还有 `DayState.MONITORING` 的录音日）；
     * 3. 若**有**监听日 → 把常驻通知换回监听态（交回 [handleStart] 走
     *    `ACTION_RESUME` 链路，它内部会重新 `startForegroundCompat` 换入监听通知，
     *    幂等安全）；
     * 4. 若**没有**监听日 → 保持待命（当前行为）。
     *
     * 查不到持久化状态（读 DB 异常）时，按「有监听日」**保守处理**（走第 3 步）：
     * 宁可多显示一次监听态通知，也不能漏掉「当天还在监听」这个事实。
     *
     * ## 遗留不一致（后续待办，本轮不修）
     *
     * [com.echolog.app.service.receiver.BootReceiver.resumeMonitoringIfNeeded] 的
     * 判据顺序是「先看 `settings.monitorActive`，再看有没有监听中的录音日」，
     * 而 [handleRestartWithoutIntent] 只看「有没有监听中的录音日」。
     * 两者不一致 ⇒ 开机自恢复时可能走进与实际状态不符的分支。
     * 本轮**不扩大改动范围**统一它（会牵动 BootReceiver 与其单测），
     * 仅在此登记为待办。
     *
     * ## 为什么第 2 步（查持久化状态）放在 `startForeground` **之后**
     *
     * 硬约束是 ANSS（Android 10+ App Startup）：`startForegroundService` 之后
     * 5 秒内必须进前台，否则系统抛 `ForegroundServiceDidNotStartInTimeException`
     * ⇒ 进程崩溃。而查「DB 里有没有监听中的录音日」是一次 IO 读取，
     * **耗时不可控**（磁盘繁忙时可达数百毫秒，极端情况更久）。
     * 若把查库提到建前台之前，一旦这次 IO 慢到超过 5 秒时限 ⇒ 直接崩溃。
     *
     * 所以顺序必须是：**先同步 `startForeground`（满足时限）→ 再异步查库 →
     * 若库里的事实要求换一种通知，再换回去**。
     * 「先查库再决定发什么通知」在语义上更自然，但在这里是**拿崩溃换语义**，
     * 不可接受。故宁可让通知短暂显示「待命」，也不冒崩溃风险。
     */
    private fun handleEnterStandby() {
        if (running || paused || standby) return
        // 入口已成功建立：撤掉可能残存的「入口未建立」提醒（该提醒是 ongoing，
        // 用户滑不掉，只能由应用主动 cancel）。放在成功路径可覆盖全部调用方。
        // 铁律 5：撤通知属旁支动作，失败不得中断主链路（`onStartCommand` 无兜底）。
        runCatching { notificationHelper.cancel(Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED) }
            .onFailure { throwable ->
                EchoLog.w(TAG, "撤下「入口未建立」提醒失败（不影响入口建立）", throwable)
            }
        // P0 崩溃兜底：`startForeground` 在缺 `RECORD_AUDIO`（或厂商 ROM 收紧）时抛
        // `SecurityException`。该异常从 `onStartCommand` 冒泡 ⇒ **进程直接崩**
        // （装机实测：启动即闪退）。调用方虽已校验权限，但权限状态可能在
        // 「startForegroundService → 本方法」之间被撤销，故此处仍必须兜住。
        val foregroundError = updateForegroundNotification(
            notificationHelper.buildQuickRecordNotification(TimeUtils.todayDayId()),
        )
        if (foregroundError != null) {
            EchoLog.w(TAG, "建立快捷入口失败：系统拒绝 microphone 型前台服务", foregroundError)
            notifyQuickEntryFailed(
                getString(
                    R.string.notify_quick_entry_reason_start,
                    foregroundError.message ?: foregroundError::class.java.simpleName,
                ),
            )
            // 入口没建起来，服务没有存在意义。用 stopSelfSafely（= 撤前台 + stopSelf）：
            // 本分支通常「从未成功进入前台」，而 stopForeground 对未处于前台的服务
            // 是 no-op，故统一走既有收尾路径即可。
            stopSelfSafely()
            return
        }
        standby = true
        // 第 2 步：异步核对持久化状态（铁律 13）。**必须在 startForeground 之后**——
        // ANSS 的 5s 时限不容许把 DB 查询放在建前台之前。
        scope.launch(ioDispatcher) {
            val hasMonitoringDay = try {
                recordingRepository.observeDays().first().any { it.state == DayState.MONITORING }
            } catch (cancellation: CancellationException) {
                // 协程取消不是「系统拒绝」，必须原样抛出（runCatching 会把它一起吞掉）。
                throw cancellation
            } catch (throwable: Throwable) {
                // 读不到持久化状态 ⇒ 按「有监听日」保守处理（见 KDoc）。
                EchoLog.w(TAG, "查询持久化监听状态失败，按「有监听日」保守处理", throwable)
                true
            }
            if (!hasMonitoringDay) {
                EchoLog.i(TAG, "无监听中的录音日，保持待命（快捷入口）")
                // **确定保持待命之后**才挂开关监听（见 KDoc 的竞态分析）。
                startQuickRecordSettingWatch()
                return@launch
            }
            // 第 3 步：有监听日 → 交回监听链路。handleStart 内部会
            // `startForegroundCompat` 换入监听通知（与待命通知共用同一个 id，
            // 幂等替换）并启动音频循环。
            if (destroyed || running || paused) return@launch
            EchoLog.i(TAG, "存在监听中的录音日，待命入口交回监听链路")
            handleStart(Intent().apply { action = Constants.ACTION_RESUME })
        }
    }

    /** 快捷入口**建立失败**的可见提示（不可滑掉，点击回应用内）。 */
    private fun notifyQuickEntryFailed(reason: String) {
        notificationHelper.notify(
            Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED,
            notificationHelper.buildQuickEntryFailedNotification(reason),
        )
    }

    /**
     * 是否持有录音权限。
     *
     * 服务声明 `foregroundServiceType="microphone"`，系统在
     * [startForegroundCompat] 的**瞬间**就要求 `RECORD_AUDIO`
     * （与当时是否真的在录音无关）。缺权限时必须**不进入前台**并给可见提示，
     * 否则抛 `SecurityException` 崩掉进程。
     */
    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 快捷入口启动失败的可见提示（[Constants.CHANNEL_ALERT]，点击回应用内）。 */
    private fun notifyQuickStartFailed(reason: String) {
        notificationHelper.notify(
            Constants.NOTIFICATION_ID_ALERT,
            notificationHelper.buildQuickStartFailedNotification(reason),
        )
    }

    /**
     * 「常驻通知暂时无法更新」的可见提示（[Constants.NOTIFICATION_ID_ALERT]）。
     *
     * ## 触发场景（与 [handleMonitorNotificationFailure] 是两条不同的路径）
     *
     * - 本方法：[handleStart] 里「换入监听通知失败，但服务已持有前台身份」——
     *   此时**录音会照常开始**，只是这一次通知没能换成监听态；
     * - [handleMonitorNotificationFailure]：监听态下**周期刷新**通知失败。
     *
     * 两条路径的失败事实相同（`startForeground` 被系统拒绝，最常见原因是
     * `RECORD_AUDIO` 在监听期间被撤销），故**共用同一文案**
     * （[NotificationHelper.buildMonitorRefreshFailedNotification]），
     * 避免两条路径给用户两种说法。
     *
     * ## 为什么不能复用「记录已中断，点击恢复后台录音监听」
     *
     * 那条文案（[NotificationHelper.buildRecoveryNotification]）**断言**录音已中断。
     * 而本通知的触发事实只是「`startForeground` 被系统拒绝」——此刻
     * **音频循环可能仍在跑、录音可能仍在继续**。断言「已中断」是说过头的话
     * （项目红线：通知文案不得断言用户看不到的事实，BUG-13 教训）。
     *
     * 故只陈述能确定的事：权限可能已失效 ⇒ 通知无法更新，
     * 录音状态以应用内显示为准。
     *
     * ## 为什么本方法是**唯一的发送出口**（但两个入口各自保留限流语义）
     *
     * 两条失败路径（[handleStart] 的「换入监听通知失败」与
     * [handleMonitorNotificationFailure] 的「周期刷新失败」）**共用本方法**发送，
     * 而不是各自写一遍 `notificationHelper.notify(...)`：
     * 分叉的发送逻辑意味着「改文案必漏一处」——而这正是 BUG-13 的成因
     * （同一个失败原因在不同分支给出了两套说法）。
     *
     * 但**限流不合并**，因为两者的事件性质不同：
     * - [handleMonitorNotificationFailure] 走「一段连续失败只提示一次」的限流
     *   （[monitorNotificationFailureNotified]，成功一次后复位）——
     *   周期刷新每秒失败一次，不限流会把用户刷屏；
     * - [handleStart] 的调用点是**一次性的状态转换事件**（冷启动换入监听通知失败），
     *   它不经过周期刷新的限流标志 ⇒ 每次发生都该让用户看到。
     *
     * 即：**发送逻辑一份（避免文案分叉），限流逻辑各按事件性质（避免漏报或刷屏）**。
     *
     * ## 走独立 id [Constants.NOTIFICATION_ID_ALERT]（与常驻通知 1001 不同）
     *
     * 用 `notify()` 覆盖前台服务通知会剥掉 FOREGROUND_SERVICE flag（铁律 12）——
     * 那正是本轮修复的缺陷本身，绝不能在自己的修复里再犯一次。
     */
    private fun notifyMonitorRefreshFailed() {
        // 提示发不出去也不能影响主链路（铁律 5）：此处位于 [handleStart] 的
        // 同步路径上，未捕获的异常会从 onStartCommand 冒泡 ⇒ 进程崩溃。
        runCatching {
            notificationHelper.notify(
                Constants.NOTIFICATION_ID_ALERT,
                notificationHelper.buildMonitorRefreshFailedNotification(dayId.orEmpty()),
            )
        }.onFailure { throwable ->
            EchoLog.w(TAG, "发送「通知无法更新」提示失败（不影响主链路）", throwable)
        }
    }

    /**
     * 手动录音开停（用户需求 4 第四种：通知栏/App 按钮点按区间录音）。
     *
     * 仅在 [RecordingMode.MANUAL] 下生效：翻转 [manualRecordingOn] 标志，
     * 由音频循环在下一帧（≤25ms）开/关片段文件。非手动模式忽略此动作。
     *
     * 携带 [Constants.EXTRA_RECORDING_ON] 时为「设定目标态」（幂等重入），
     * 缺省时按当前态取反。
     */
    private fun handleManualToggle(intent: Intent) {
        if (recordingMode != RecordingMode.MANUAL) return
        val target = if (intent.hasExtra(Constants.EXTRA_RECORDING_ON)) {
            intent.getBooleanExtra(Constants.EXTRA_RECORDING_ON, false)
        } else {
            !manualRecordingOn
        }
        if (target == manualRecordingOn) return
        manualRecordingOn = target
        refreshMonitorNotification()
        stateHolder.mutate { it.copy(manualRecordingOn = target) }
    }

    // ── 音频线程循环（架构 7.3：唯一接触音频硬件的线程） ──

    private fun launchAudioLoop() {
        loopJob?.cancel()
        loopJob = scope.launch(audioDispatcher) {
            try {
                audioLoop()
            } finally {
                // BUG-3 修复：取消（onDestroy/重入）后仍需完成片段收尾与 DB 落账。
                // NonCancellable 保证 finally 中的挂起写不被 CancellationException 截断，
                // 且 onDestroy 可经 join() 同步等待本块完成后安全 release recorder。
                withContext(NonCancellable) { onAudioLoopExited() }
            }
        }
    }

    private suspend fun audioLoop() {
        // 启动校准：读取 500ms 环境噪声底（仅自动模式需要 VAD 门限）
        val noiseFloorDb = calibrateNoiseFloor()
        vad.init(noiseFloorDb)

        // 全天录音：立即开录一个贯穿全天的片段（不依赖 VAD 断段）
        if (recordingMode == RecordingMode.ALL_DAY) {
            onSegmentStart()
        }

        while (coroutineContext.isActive && running) {
            val read = recorder.readFrame(frameBuffer)
            if (read < 0) {
                handleAudioReadError(read)
                return
            }
            val frame = if (read == frameBuffer.size) frameBuffer else frameBuffer.copyOf(read)

            // 全天录音跨零点换天（必须先于切段：换天会收尾当前片段并切换 dayId）
            if (recordingMode == RecordingMode.ALL_DAY) {
                rolloverToNewDayIfNeeded()
            }
            // 通用滚动切段：单片段时长超上限时收尾并立即开新片段
            // （防止 WAV 超 4GB 上限 / ASR 整段读入内存导致 OOM）
            rotateSegmentIfTooLong()

            when (recordingMode) {
                // 模式一：全天录音 —— 持续写帧，不做 VAD 判定；
                // 超过单片段时长上限则滚动切段（避免单文件超过 WAV 4GB 上限、
                // 且保证下游 ASR 单次推理的音频长度可控），跨零点则切换到新的一天。
                RecordingMode.ALL_DAY -> {
                    if (!segmentOpen) onSegmentStart()
                    recorder.writeFrame(frame)
                }

                // 模式二：自动录音 —— VAD 判定静音并断段
                RecordingMode.AUTO_VAD -> {
                    if (segmentOpen) {
                        recorder.writeFrame(frame)
                    } else {
                        recorder.pushPreBuffer(frame)
                    }
                    val event = try {
                        vad.processFrame(frame)
                    } catch (throwable: Throwable) {
                        // BUG-2 修复：VAD 异常不退出音频循环，跳过该帧让 handleVadError 的
                        // streak 逻辑（连续 MAX_VAD_ERROR_STREAK 次才停）真正生效；
                        // streak 满时 handleVadError 置 running=false，while 条件自然收敛退出。
                        handleVadError(throwable)
                        continue
                    }
                    when (event) {
                        VadEvent.VOICE_ONSET -> onSegmentStart()
                        VadEvent.VOICE_OFFSET -> onSegmentEnd()
                        else -> Unit
                    }
                }

                // 模式三：模板录音 —— 按课表区间启停；区间外只进预录缓冲不落盘
                RecordingMode.SCHEDULE_TEMPLATE -> {
                    val inRange = evaluateScheduleRange()
                    if (inRange) {
                        if (!segmentOpen) onSegmentStart()
                        recorder.writeFrame(frame)
                    } else {
                        if (segmentOpen) onSegmentEnd()
                        recorder.pushPreBuffer(frame)
                    }
                }

                // 模式四：手动录音 —— 由用户点按录音键开停
                RecordingMode.MANUAL -> {
                    if (manualRecordingOn) {
                        if (!segmentOpen) onSegmentStart()
                        recorder.writeFrame(frame)
                    } else {
                        if (segmentOpen) onSegmentEnd()
                        recorder.pushPreBuffer(frame)
                    }
                }
            }
            pushStateThrottled()
            writeHeartbeatThrottled()
        }
        // 正常退出（handleStop）：收尾未关闭的片段
        finishCurrentSegmentIfOpen()
    }

    /**
     * 模板录音区间判定（用户需求 4 第三种）。
     *
     * 每 [SCHEDULE_CHECK_INTERVAL_MS] 毫秒重取一次模板（用户可能在应用内改了课表），
     * 判定当前时刻是否落在任一区间内。跨区间时结束旧片段、由下一次判定开新片段。
     */
    private suspend fun evaluateScheduleRange(): Boolean {
        val now = TimeUtils.currentEpochMs()
        if (now - lastScheduleCheckAt >= SCHEDULE_CHECK_INTERVAL_MS) {
            lastScheduleCheckAt = now
            scheduleTemplate = scheduleTemplateRepository.getTemplate()
        }
        val minuteOfDay = TimeUtils.minuteOfDay(now)
        val matchedIndex = scheduleTemplate.ranges.indexOfFirst { it.contains(minuteOfDay) }
        if (matchedIndex != activeScheduleRangeIndex) {
            activeScheduleRangeIndex = matchedIndex
        }
        return matchedIndex >= 0
    }

    /**
     * 全天录音跨零点换天（用户需求 4 第一种）。
     *
     * 检测到本地日期已变更时依次完成：
     * 1. 收尾旧天最后一个片段（关文件 + `finishSegment` 落库）；
     * 2. 旧天置 `SETTLING` 并入队结算（合并 → 转写 → 总结，当天即出结果）；
     * 3. 建立新的一天（`MONITORING`，模式沿用当前）并切换 [dayId] 继续录音。
     *
     * 刻意**不复用** [StopMonitoringAndSettleUseCase]：它会 `setMonitorActive(false)` +
     * `writeHeartbeat(0)`，会打断新一天的保活链路（[com.echolog.app.work.HealthCheckWorker]
     * 将据此误判服务已死并尝试拉起）。
     */
    private suspend fun rolloverToNewDayIfNeeded() {
        val currentDayId = dayId ?: return
        val now = TimeUtils.currentEpochMs()
        // 节流：每 10s 检查一次即可（跨零点后最多延迟 10s 换天），避免每帧（25ms）解析日期
        if (now - lastRolloverCheckAt < ROLLOVER_CHECK_INTERVAL_MS) return
        lastRolloverCheckAt = now
        val today = TimeUtils.todayDayId()
        if (today == currentDayId) return

        // 1) 收尾旧天的最后一个片段（DB 落账完成后才允许结算，避免片段尾部丢失）
        finishCurrentSegmentIfOpen()

        // 2) 旧天转 SETTLING 并入队结算（服务本身不停，监听链路保持）
        withContext(ioDispatcher) {
            recordingRepository.updateDayState(currentDayId, DayState.SETTLING)
            recordingRepository.updateSettleProgress(
                currentDayId,
                StopMonitoringAndSettleUseCase.SETTLE_START_PROGRESS,
                null,
            )
        }
        settleScheduler.scheduleDaySettle(currentDayId)

        // 3) 建立新的一天并切换（模式沿用当前，跨天后仍是全天录音）
        val newDay = withContext(ioDispatcher) {
            recordingRepository.createOrResumeDay(today, recordingMode)
        }
        dayId = newDay.dayId
        monitorStartedAt = newDay.monitorStartedAt
        segmentCount = 0
        markerCount = 0
        stateHolder.mutate {
            it.copy(
                dayId = newDay.dayId,
                segmentCount = 0,
                markerCount = 0,
                currentSegmentMs = 0L,
            )
        }
        refreshMonitorNotification()
    }

    /**
     * 滚动切段（用户需求 4 第一/二/三/四种通用护栏）。
     *
     * 当前片段时长达到按采样率折算的上限（[Constants.maxSegmentDurationMs]）时
     * 收尾并立即开新片段：
     * 1. 规避 WAV（RIFF）4GB 单文件上限（16kHz 全天约 2.76GB，高采样率必然超限）；
     * 2. 控制下游 ASR 单次读入内存的音频长度（`WavIo.readFloatSamples` 整段入内存）。
     *
     * 切段后按模式立即续开，避免「正在录」出现空档：
     * 自动录音下 VAD 仍处 `voiceActive` 态、不会再发 `VOICE_ONSET`，
     * 若不续开则该段语音的后半程会整体丢失。
     */
    private suspend fun rotateSegmentIfTooLong() {
        if (!segmentOpen) return
        val elapsed = TimeUtils.currentEpochMs() - currentSegmentStartMs
        if (elapsed < Constants.maxSegmentDurationMs(sampleRate)) return
        finishCurrentSegmentIfOpen()
        when (recordingMode) {
            RecordingMode.ALL_DAY -> onSegmentStart()
            RecordingMode.AUTO_VAD -> onSegmentStart()
            RecordingMode.SCHEDULE_TEMPLATE -> if (evaluateScheduleRange()) onSegmentStart()
            RecordingMode.MANUAL -> if (manualRecordingOn) onSegmentStart()
        }
    }

    /** 读 20 帧（500ms）估算噪声底（取中位数附近的均值，抗突发声）。 */
    private fun calibrateNoiseFloor(): Float {
        val calibrationBuffer = ShortArray(frameBuffer.size)
        val energies = ArrayList<Float>(CALIBRATION_FRAMES)
        repeat(CALIBRATION_FRAMES) {
            val read = recorder.readFrame(calibrationBuffer)
            if (read <= 0) return@repeat
            energies.add(frameEnergyDb(calibrationBuffer, read))
        }
        if (energies.isEmpty()) return EnergyVadEngine.DEFAULT_NOISE_FLOOR_DB
        energies.sort()
        // 取 60% 分位，略保守（避免把语音算进噪声底）
        val index = (energies.size * 0.6f).toInt().coerceIn(0, energies.lastIndex)
        return energies[index]
    }

    private fun frameEnergyDb(frame: ShortArray, count: Int): Float {
        var sumSquares = 0.0
        for (index in 0 until count) {
            val normalized = frame[index] / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumSquares / count)
        return if (rms <= 1e-10) EnergyVadEngine.MIN_ENERGY_DB
        else (20.0 * kotlin.math.log10(rms)).toFloat()
    }

    /**
     * 开录一个片段（模式无关的统一入口）。
     *
     * 顺序（架构 4.1）：先建档（DB），再开文件（回吐预录缓冲）。
     */
    private suspend fun onSegmentStart() {
        val id = dayId ?: return
        val startMs = TimeUtils.currentEpochMs()
        // 用户需求 1：当天第几次录音（DB 内已有片段数 + 1；跨重启连续）
        val ordinal = withContext(ioDispatcher) {
            recordingRepository.getSegmentsForDay(id).size + 1
        }
        val path = FileUtils.segmentPath(this, id, ordinal)
        val segmentId = withContext(ioDispatcher) {
            recordingRepository.insertSegment(id, startMs, path)
        }
        if (!recorder.beginSegmentFile(path)) {
            withContext(ioDispatcher) { recordingRepository.finishSegment(segmentId, startMs, 0L) }
            return
        }
        segmentOpen = true
        currentSegmentId = segmentId
        currentSegmentPath = path
        currentSegmentStartMs = startMs
        segmentCount++
        stateHolder.mutate {
            it.copy(
                serviceState = RecordingServiceState.RECORDING,
                segmentCount = segmentCount,
                currentSegmentMs = 0L,
            )
        }
    }

    /** 结束当前片段（模式无关的统一入口）。 */
    private suspend fun onSegmentEnd() {
        finishCurrentSegmentIfOpen()
    }

    /** 收尾当前片段：关文件 → finishSegment（DB）→ 复制到自选目录 → 回到监听态。 */
    private suspend fun finishCurrentSegmentIfOpen() {
        if (!segmentOpen) return
        val segmentId = currentSegmentId
        val path = currentSegmentPath
        val endMs = TimeUtils.currentEpochMs()
        val dataBytes = recorder.endSegmentFile()
        segmentOpen = false
        currentSegmentId = 0L
        currentSegmentPath = null
        if (segmentId > 0L) {
            withContext(ioDispatcher) {
                recordingRepository.finishSegment(segmentId, endMs, dataBytes)
                // 用户需求 3：选自定目录时复制一份（内部路径仍是数据源；失败不影响链路）
                if (path != null && dataBytes > 0L) {
                    val settings = settingsRepository.getSettings()
                    storageLocationManager.copyToUserDir(
                        source = File(path),
                        treeUri = settings.storageTreeUri,
                        mimeType = StorageLocationManager.MIME_AUDIO_WAV,
                    )
                }
            }
        }
        stateHolder.mutate {
            it.copy(
                serviceState = RecordingServiceState.MONITORING,
                currentSegmentMs = 0L,
            )
        }
    }

    private suspend fun onAudioLoopExited() {
        finishCurrentSegmentIfOpen()
        if (paused) {
            // 用户需求 2：暂停态——保持前台服务与常驻通知，仅切暂停态；
            // 心跳由 startPauseHeartbeat 维持，健康检查不会误恢复
            stateHolder.mutate { it.copy(serviceState = RecordingServiceState.PAUSED) }
            refreshMonitorNotification()
            startPauseHeartbeat()
            return
        }
        // 用户需求：快捷录音入口——待命态**保持前台**并把常驻通知换成快捷入口，
        // 而不是 stopForeground(REMOVE)（现状行为由设置项开关决定）
        if (settingsRepository.getSettings().quickRecordNotificationEnabled) {
            enterStandbyMode()
            return
        }
        stopForegroundCompat()
        stopSelf()
        // BUG-6 修复：不清除 error 字段——音频错误态（E-1001/E-2001）必须保留到
        // 用户可见，否则循环退出即把错误提示抹掉
        stateHolder.mutate {
            RecordingUiState(
                errorCode = it.errorCode,
                errorMessage = it.errorMessage,
            )
        }
    }

    // ── 暂停心跳（用户需求 2：暂停期间维持 HealthCheckWorker 的新鲜心跳） ──

    private fun startPauseHeartbeat() {
        pauseHeartbeatJob?.cancel()
        pauseHeartbeatJob = scope.launch(ioDispatcher) {
            while (coroutineContext.isActive) {
                settingsRepository.writeHeartbeat(TimeUtils.currentEpochMs())
                delay(Constants.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun cancelPauseHeartbeat() {
        pauseHeartbeatJob?.cancel()
        pauseHeartbeatJob = null
    }

    // ── 待命态：快捷录音入口（用户需求：锁屏/状态栏一键开始录音） ──

    /**
     * 进入待命态：服务**保持前台**，常驻通知切换为快捷录音入口。
     *
     * 与监听通知共用 [Constants.NOTIFICATION_ID_MONITOR]，故两者互斥——
     * 任一时刻状态栏/锁屏上只有一条，不需要额外的取消逻辑。
     *
     * 铁律 12：换通知必须走 [updateForegroundNotification]（`startForeground`），
     * **不能**用 `notify()` 覆盖——否则前台服务身份（FOREGROUND_SERVICE flag /
     * `ServiceRecord.isForeground` / FGS 类型）会被剥掉。
     *
     * ## `standby` 状态位：先置位、失败回滚（不是「成功后置位」）
     *
     * `standby` 的语义是「服务处于（或正在进入）待命态」，读取方据此决定
     * 「要不要因为结束/停止动作而撤掉整个服务」：
     * - [handleStop] 的「停的是别的天」分支：`if (!running && !standby) stopSelfSafely()`
     *   —— 跨天 Worker 归档昨天时会投递 `ACTION_STOP_SETTLE`，**待命态必须保留**
     *   （BUG-5 的原教训：顺手 stopSelf 会把用户没动过的快捷入口一起撤掉）；
     * - [handleStop] 的「没在跑」分支：`if (standby) return`；
     * - [handleEnterStandby] 入口：`if (running || paused || standby) return`；
     * - [onStartCommand] 未知动作分支：`if (!running && !standby) stopSelfSafely()`。
     *
     * 因此**不能**简单地把 `standby = true` 挪到换通知成功之后：本方法跑在
     * **音频线程**（[onAudioLoopExited] → finally），而上述读取方跑在**主线程**
     * （`onStartCommand`）—— 两者并发。换通知（一次 binder 调用）期间存在毫秒级
     * 窗口，此时 `standby` 若为 false，跨天 Worker 恰好投递 `ACTION_STOP_SETTLE`
     * 就会把**正在建立**的快捷入口连同服务一起撤掉（用户体感：锁屏入口莫名消失）。
     *
     * 故本方法的处置是：**先置位**（把转场窗口纳入「待命态」保护），
     * **失败时立即回滚为 false**（服务即将退出 ⇒ 事实上不是待命态，
     * 状态位不能停留在谎言上）。方法返回后的**最终状态**与「成功后置位」
     * 完全一致，差别只在于转场窗口内不再有「状态位说谎」的风险。
     *
     * 失败后果与 [handleMonitorNotificationFailure] **刻意不同**：本方法运行在
     * 音频循环刚退出之后（本来就没在录音），此时停掉服务**没有损失**，
     * 反而能避免留下一条内容过时的旧通知。故失败时发可见提示 + 退出服务。
     */
    private fun enterStandbyMode() {
        // 服务已销毁（onDestroy 已跑完）时不再重建前台身份：
        // onDestroy 的收尾（runBlocking { loopJob?.cancelAndJoin() }）会等音频循环的
        // finally 走完，而 finally 里可能调用本方法——此时 startForeground 是在
        // 给一个正在销毁的服务重新挂前台通知。
        if (destroyed) {
            EchoLog.w(TAG, "服务已销毁，跳过进入待命态")
            return
        }
        // 先置位：把「正在建立快捷入口」的转场窗口纳入待命态保护（见 KDoc 并发分析）。
        standby = true
        val error = updateForegroundNotification(
            notificationHelper.buildQuickRecordNotification(TimeUtils.todayDayId()),
        )
        if (error != null) {
            // 回滚：入口没建立出来 ⇒ 事实上不是待命态（状态位必须反映事实）。
            standby = false
            EchoLog.w(TAG, "切换到快捷入口通知失败，服务退出", error)
            notifyQuickEntryFailed(
                getString(
                    R.string.notify_quick_entry_reason_start,
                    error.message ?: error::class.java.simpleName,
                ),
            )
            stopSelfSafely()
            return
        }
        startQuickRecordSettingWatch()
    }

    /**
     * 待命期间监听快捷入口开关：用户在设置里关闭后服务自行退出，
     * 恢复「结束即撤掉通知」的现状行为。
     *
     * 否则关闭开关后常驻通知依旧存在（且要等下一次录音结束才消失）——
     * 属于「设置项静默失效」，与项目红线冲突。
     *
     * ## 为什么不用 `runCatching`（项目约定）
     *
     * `runCatching` 会把**两种**东西一起吞掉：
     * 1. [CancellationException] —— 取消信号被吞 ⇒ 破坏结构化并发，
     *    协程会在「已取消」的状态下继续往下走（项目既有约定，见
     *    `RegenerateSummaryUseCase` / `ModelDownloader`）；
     * 2. **真实的读取失败**（DataStore IO 异常）—— 本协程就此静默死掉，
     *    「用户关闭开关 ⇒ 服务自行退出」这条行为**静默失效**
     *    （设置项静默失效 = 项目红线）。
     *
     * 故手写 try/catch：取消原样抛出，真实失败**记日志**（让排障看得见）。
     */
    private fun startQuickRecordSettingWatch() {
        quickRecordWatchJob?.cancel()
        quickRecordWatchJob = scope.launch(ioDispatcher) {
            try {
                settingsRepository.observeSettings()
                    .first { !it.quickRecordNotificationEnabled }
            } catch (cancellation: CancellationException) {
                // 取消不是「开关被关闭」，必须原样抛出（见上方 KDoc）。
                throw cancellation
            } catch (throwable: Throwable) {
                // 读不到设置 ⇒ 不知道开关状态 ⇒ 保持待命（不擅自退出服务），
                // 但必须留日志，否则「开关关了服务却不退」将无从排查。
                EchoLog.w(TAG, "监听快捷入口开关失败，保持待命态", throwable)
                return@launch
            }
            // 开关已关闭 ⇒ 待命入口已无意义，服务自行退出。
            standby = false
            try {
                stopSelfSafely()
            } catch (throwable: Throwable) {
                // 收尾动作失败不得中断主链路（铁律 5）：撤前台/停服务失败时
                // 也不能让协程把异常抛到作用域外（那会变成进程崩溃）。
                EchoLog.w(TAG, "快捷入口开关已关闭，但退出服务失败", throwable)
            }
        }
    }

    private fun cancelQuickRecordWatch() {
        quickRecordWatchJob?.cancel()
        quickRecordWatchJob = null
    }

    // ── 节流推送 ──

    private suspend fun pushStateThrottled() {
        val now = TimeUtils.currentEpochMs()
        if (now - lastStatePushAt < STATE_PUSH_INTERVAL_MS) return
        lastStatePushAt = now
        val monitored = now - monitorStartedAt
        val currentSegment =
            if (segmentOpen) now - currentSegmentStartMs else 0L
        stateHolder.mutate {
            it.copy(
                monitoredMs = monitored.coerceAtLeast(0L),
                currentSegmentMs = currentSegment.coerceAtLeast(0L),
            )
        }
        // 通知刷新**单独节流**（NOTIFICATION_REFRESH_INTERVAL_MS），不跟状态推送
        // 同频——通知刷新要过 startForeground（binder 到 AMS + 通知服务 + FGS 类型校验），
        // 成本比改内存 StateFlow 高一个数量级。
        refreshMonitorNotificationThrottled()
    }

    /** 服务每 5s 写心跳（架构决策 3 第二层；HealthCheckWorker 比对）。 */
    private suspend fun writeHeartbeatThrottled() {
        val now = TimeUtils.currentEpochMs()
        if (now - lastHeartbeatAt < Constants.HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatAt = now
        withContext(ioDispatcher) { settingsRepository.writeHeartbeat(now) }
    }

    // ── 错误处理（架构 7.4） ──

    private suspend fun handleAudioReadError(errorCode: Int) {
        synchronized(serviceLock) { running = false }
        stateHolder.mutate {
            it.copy(
                serviceState = RecordingServiceState.IDLE,
                errorCode = Constants.ERROR_AUDIO_INIT,
                errorMessage = "音频采集异常（code=$errorCode），监听已停止",
            )
        }
    }

    private suspend fun handleVadError(throwable: Throwable) {
        vadErrorStreak++
        if (vadErrorStreak >= MAX_VAD_ERROR_STREAK) {
            // E-2001：连续 3 次异常则停止监听
            synchronized(serviceLock) { running = false }
            stateHolder.mutate {
                it.copy(
                    serviceState = RecordingServiceState.IDLE,
                    errorCode = Constants.ERROR_VAD,
                    errorMessage = "VAD 引擎连续异常，监听已停止",
                )
            }
        } else {
            vad.reset()
        }
    }

    // ── 通知 ──

    private fun buildMonitorNotification(
        serviceState: RecordingServiceState,
        monitoredMs: Long,
        currentSegmentMs: Long,
        segments: Int,
        markers: Int,
    ): android.app.Notification = notificationHelper.buildMonitorNotification(
        dayId = dayId.orEmpty(),
        serviceState = serviceState,
        monitoredMs = monitoredMs,
        currentSegmentMs = currentSegmentMs,
        segmentCount = segments,
        markerCount = markers,
        privacyMode = privacyMode,
        recordingMode = recordingMode,
        manualRecordingOn = manualRecordingOn,
    )

    private fun refreshMonitorNotification() {
        val snapshot = stateHolder.state.value
        val notification = notificationHelper.buildMonitorNotification(
            dayId = snapshot.dayId.orEmpty(),
            serviceState = snapshot.serviceState,
            monitoredMs = snapshot.monitoredMs,
            currentSegmentMs = snapshot.currentSegmentMs,
            segmentCount = snapshot.segmentCount,
            markerCount = snapshot.markerCount,
            privacyMode = privacyMode,
            recordingMode = recordingMode,
            manualRecordingOn = manualRecordingOn,
        )
        val error = updateForegroundNotification(notification)
        if (error == null) {
            // 成功一次就复位限流标记：后续再失败时可以重新提示用户。
            monitorNotificationFailureNotified = false
            return
        }
        handleMonitorNotificationFailure(error)
    }

    /**
     * 周期性地刷新常驻通知（监听态计时文本）。
     *
     * 频率由 [NOTIFICATION_REFRESH_INTERVAL_MS] 单独控制，**刻意与**
     * [STATE_PUSH_INTERVAL_MS]（内部状态推送）解耦——理由见该常量的 KDoc。
     */
    private fun refreshMonitorNotificationThrottled() {
        val now = TimeUtils.currentEpochMs()
        if (now - lastNotificationRefreshAt < NOTIFICATION_REFRESH_INTERVAL_MS) return
        refreshMonitorNotification()
    }

    /**
     * 监听态下常驻通知刷新失败的处置：**绝不能停服务**。
     *
     * 为什么不能像 [enterStandbyMode] 那样 `stopSelfSafely()`：
     * 监听态意味着**正在录音**（全天录音场景下一录就是几小时），
     * 因为一次通知刷新失败就把服务停掉 = 全天录音被静默中断，
     * 而用户侧只看到「录音莫名停了」——损失远大于一次通知没刷新的代价。
     *
     * 正确做法：
     * 1. 发一条**可见**通知告知用户（限流：一段连续失败只提示一次，避免刷屏）；
     * 2. 让音频循环自己因 `recorder` 失败退出（[handleAudioReadError] 已有该路径），
     *    由既有的错误收尾流程（状态置错误 + 停止前台）接管。
     *
     * 可见通知选「麦克风权限可能已失效，常驻通知无法更新」
     * （[NotificationHelper.buildMonitorRefreshFailedNotification]）：
     * - **不误判实际状态**：本场景只是 `startForeground` 被系统拒绝（通常是
     *   `RECORD_AUDIO` 被撤销），**音频循环可能仍在跑、录音可能仍在继续**。
     *   所以**不能**复用 [NotificationHelper.buildRecoveryNotification] 的
     *   「记录已中断」——那是在断言一个用户看不到的事实，正是 BUG-13 的
     *   假文案教训（当时的假原因是「可能为错误页」，实际是权限问题）。
     *   新文案只陈述**真正发生的事**：权限可能失效 ⇒ 通知无法更新，
     *   并提示「录音状态以应用内显示为准」——不对录音是否中断下结论；
     * - 点击**只打开应用**（不挂 `ACTION_RESUME`）：若挂上，用户点一下提示
     *   可能经 `handleStart` 走到 `stopSelfSafely()` 把服务杀掉
     *   （麦克风权限已撤销时 `hasRecordAudioPermission()` 返回 false），
     *   与本轮红线「监听态失败 ⇒ 绝不能停服务」冲突。详见
     *   [NotificationHelper.buildMonitorRefreshFailedNotification] 的 KDoc；
     * - 走 [Constants.NOTIFICATION_ID_ALERT]（独立 id），**不能**复用
     *   [Constants.NOTIFICATION_ID_MONITOR]——用 `notify()` 覆盖前台服务通知会剥掉
     *   FOREGROUND_SERVICE flag（这正是本轮修复的缺陷本身）。
     */
    private fun handleMonitorNotificationFailure(error: Throwable) {
        EchoLog.w(TAG, "刷新监听常驻通知失败（保持服务运行，不中断录音）", error)
        if (monitorNotificationFailureNotified) return
        monitorNotificationFailureNotified = true
        // 复用统一出口，避免同一条通知出现两处发送逻辑（分叉 = 后续改文案必漏一处）。
        notifyMonitorRefreshFailed()
    }

    /**
     * 更新前台服务的常驻通知（**唯一允许改前台通知的入口**）。
     *
     * ## 为什么必须走 [startForegroundCompat] 而不是 `NotificationManager.notify()`
     *
     * 系统给通知打上 `FOREGROUND_SERVICE` flag、把 `ServiceRecord.isForeground` 置为
     * true、把前台服务类型记进 `ServiceRecord.types`——这三件事**全部发生在
     * `Service.startForeground()` 这一次调用里**。用 `notify()` 覆盖同 id 的通知
     * = 用一条普通通知替换掉前台服务通知 ⇒ flag 丢失 + `isForeground=false` +
     * `types=0` ⇒ **服务失去前台身份**（随时可被系统回收，用户侧表现为
     * 「锁屏 bar 还在但点了没反应」的僵尸入口）。
     *
     * 真机证据（targetSdk 35 / Android 15，同一台机、同一 APK，只改一个变量）：
     * - 走 `notify()` 覆盖后：`flags=ONGOING_EVENT|ONLY_ALERT_ONCE`（**无**
     *   `FOREGROUND_SERVICE`），`isForeground=false foregroundId=1001 types=0x00000000`；
     * - 走 `startForeground()` 后：`flags=...|FOREGROUND_SERVICE`，
     *   `isForeground=true types=0x00000080`（microphone）。
     *
     * 注：`NotificationCompat.setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`
     * 只决定通知是否抢占横幅，**不是** flag 的来源，别拿它当证据。
     *
     * ## 为什么必须 `runCatching` 兜住 `SecurityException`
     *
     * 本服务声明 `foregroundServiceType="microphone"`，系统在 `startForeground()` 的
     * **瞬间**就要求 `RECORD_AUDIO`（与当时是否真的在录音无关）。权限在监听期间被
     * 用户在系统设置里撤销 ⇒ 抛 `SecurityException`。本方法可能在**协程**里被调用
     * （[refreshMonitorNotification] ← 音频循环），未捕获的异常会取消协程并把异常
     * 抛到协程作用域之外 ⇒ **进程崩溃**（P0）。
     *
     * 官方文档明确支持「再次调用 `startForeground()` 以更新通知 / 增加 service type」，
     * 重复调用是合法的（幂等更新），因此这里只是把「更新」动作包起来，不改变语义。
     *
     * @param notification 要展示的通知（**必须**已按前台服务语义构建）。
     * @return `null` 表示成功；非 `null` 为失败原因（已记日志，由调用方决定后果）。
     */
    private fun updateForegroundNotification(notification: android.app.Notification): Throwable? {
        // 记录尝试时刻（成功与否都记）：失败时也要限速重试，不能每 500ms 打一次 AMS。
        lastNotificationRefreshAt = TimeUtils.currentEpochMs()
        return try {
            startForegroundCompat(notification)
            foregroundEstablished = true
            null
        } catch (cancellation: CancellationException) {
            // 协程取消不是「系统拒绝」，必须原样抛出，否则会破坏结构化并发。
            throw cancellation
        } catch (throwable: Throwable) {
            EchoLog.w(TAG, "startForeground 被系统拒绝（microphone 型前台服务）", throwable)
            throwable
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            Constants.NOTIFICATION_ID_MONITOR,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundEstablished = false
    }

    private fun stopSelfSafely() {
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        synchronized(serviceLock) {
            running = false
            paused = false
        }
        cancelPauseHeartbeat()
        cancelQuickRecordWatch()
        // BUG-3 修复：同步等待音频循环退出（含 finally 的片段收尾与 DB 落账），
        // 再释放 recorder——避免与音频线程的 endSegmentFile/finishSegment 竞态
        // （native crash + 收尾数据丢失）
        runBlocking { loopJob?.cancelAndJoin() }
        recorder.release()
        audioThread.quitSafely()
        // 暂停态销毁时循环 finally 不会走停止分支，这里显式移除常驻通知
        stopForegroundCompat()
        // BUG-6 修复：保留错误态（同上），仅重置运行态字段
        stateHolder.mutate {
            RecordingUiState(
                errorCode = it.errorCode,
                errorMessage = it.errorMessage,
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val TAG = "RecordingService"
        private const val CALIBRATION_FRAMES = 20
        private const val STATE_PUSH_INTERVAL_MS = 500L
        private const val MAX_VAD_ERROR_STREAK = 3

        /**
         * **常驻通知**刷新间隔（毫秒）——与 [STATE_PUSH_INTERVAL_MS]（内部状态推送）**刻意分开**。
         *
         * ## 为什么必须解耦
         *
         * - 内部状态推送（[pushStateThrottled] → `stateHolder.mutate`）只改内存里的
         *   StateFlow：纯进程内操作，几乎零成本，可以跑得很密（500ms）。
         * - 通知刷新要过 [androidx.core.app.ServiceCompat.startForeground]：
         *   binder 到 AMS + 通知服务重建通知 + FGS 类型（microphone）校验，
         *   成本比前者高一个数量级。
         *
         * 两者同频 ⇒ 通知侧的 binder 开销被白白放大 N 倍，而用户看不出任何区别
         * （状态栏上显示的精度由通知文本本身决定，不由刷新频率决定）。
         *
         * ## 取 1 秒的理由（工程判断）
         *
         * 监听通知的正文渲染的是 `HH:MM:SS`（见
         * `TimeUtils.formatDurationClock`），即**秒级精度**：
         * - 若取 5s：秒位每 5 秒才跳一次，状态栏上会出现「00:12:35 → 00:12:40」
         *   的跳变，用户会以为计时卡住 ⇒ 可见的体验退化，不接受；
         * - 若沿用 500ms：文本每秒才变一次，多出来的一半刷新**纯属浪费**
         *   （每次都是一次 AMS binder 往返 + 通知重建）；
         * - 取 1s 恰好「一次可见变化 = 一次刷新」，且比现状（500ms×2/秒）**减半**
         *   了 AMS 往返次数。
         *
         * 官方明确支持「再次调用 `startForeground()` 以更新通知 / 增加 service type」，
         * 重复调用合法（幂等更新），因此 1s 的频率不会带来语义问题；真正的开销约束
         * 由上面的「1s 足够」承担。
         *
         * 注意：**不要**用改 [STATE_PUSH_INTERVAL_MS] 的方式来降通知频率——
         * 那会把内部状态推送一起变慢（UI 侧状态更新会变迟钝）。两者必须独立。
         */
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 1_000L

        /**
         * 模板区间判定间隔（用户需求 4：模板录音）。
         *
         * 1s 足够精确（课表粒度为分钟），同时避免每帧（25ms）读 DataStore。
         */
        private const val SCHEDULE_CHECK_INTERVAL_MS = 1000L

        /**
         * 跨零点检测间隔（用户需求 4 第一种：全天录音跨天换天）。
         *
         * 10s 足够（换天延迟上限 10s），同时避免每帧（25ms）解析日期。
         */
        private const val ROLLOVER_CHECK_INTERVAL_MS = 10_000L
    }
}
