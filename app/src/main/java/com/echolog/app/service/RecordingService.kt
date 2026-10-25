package com.echolog.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.HandlerThread
import android.os.Process
import androidx.core.app.ServiceCompat
import com.echolog.app.core.common.ApplicationScope
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.IoDispatcher
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
import com.echolog.app.domain.usecase.StopMonitoringAndSettleUseCase
import com.echolog.app.service.notify.NotificationHelper
import com.echolog.app.service.vad.EnergyVadEngine
import com.echolog.app.service.vad.VadEngine
import com.echolog.app.service.vad.VadEvent
import dagger.hilt.android.AndroidEntryPoint
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
    private var vadErrorStreak: Int = 0

    /** 模板区间最近一次判定时刻（用户需求 4：模板录音）。 */
    private var lastScheduleCheckAt: Long = 0L

    /** 跨零点检测最近一次时刻（用户需求 4 第一种：全天录音跨天换天）。 */
    @Volatile
    private var lastRolloverCheckAt: Long = 0L

    /** 暂停期间的心跳保活 Job（音频循环停止后由它维持健康检查依据）。 */
    private var pauseHeartbeatJob: Job? = null

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
            else -> if (!running) stopSelfSafely()
        }
        // 被系统杀死后带空 Intent 重启时尽量恢复（START_STICKY 语义）
        return START_STICKY
    }

    // ── Action 处理 ──

    /**
     * 开启/恢复监听。
     *
     * 先立即进入前台（避免 ANSS 5s 超时崩溃，通知先以监听态呈现），
     * 再异步确认目标录音日并启动音频循环。
     */
    private fun handleStart(intent: Intent) {
        startForegroundCompat(buildMonitorNotification(RecordingServiceState.MONITORING, 0L, 0L, 0, 0))
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
                // 停的是别的天：不动当前音频；服务本就未运行时无需存活
                if (!running) stopSelfSafely()
                return
            }
            if (!running) {
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
        refreshMonitorNotification()
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
        notificationHelper.notify(
            Constants.NOTIFICATION_ID_MONITOR,
            notificationHelper.buildMonitorNotification(
                dayId = snapshot.dayId.orEmpty(),
                serviceState = snapshot.serviceState,
                monitoredMs = snapshot.monitoredMs,
                currentSegmentMs = snapshot.currentSegmentMs,
                segmentCount = snapshot.segmentCount,
                markerCount = snapshot.markerCount,
                privacyMode = privacyMode,
                recordingMode = recordingMode,
                manualRecordingOn = manualRecordingOn,
            ),
        )
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
    }

    private fun stopSelfSafely() {
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(serviceLock) {
            running = false
            paused = false
        }
        cancelPauseHeartbeat()
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
        private const val CALIBRATION_FRAMES = 20
        private const val STATE_PUSH_INTERVAL_MS = 500L
        private const val MAX_VAD_ERROR_STREAK = 3

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
