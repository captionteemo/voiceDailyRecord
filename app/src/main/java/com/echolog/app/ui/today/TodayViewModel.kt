package com.echolog.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.AddMarkerUseCase
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.domain.usecase.SettleScheduler
import com.echolog.app.domain.usecase.StartMonitoringUseCase
import com.echolog.app.domain.usecase.StopMonitoringAndSettleUseCase
import com.echolog.app.service.RecordingServiceState
import com.echolog.app.service.RecordingStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 今天页 ViewModel（架构 2.5/4.1）：首页三态 + 当天结算态聚合。
 *
 * 数据来源（架构 7.5：UI 只读，不直接调 Worker/服务）：
 * - 运行态：[RecordingStateHolder.state]（服务唯一运行态来源）；
 * - 日级状态：[RecordingRepository.observeDay]（SETTLING 进度/总结状态）；
 * - 设置：[SettingsRepository.observeSettings]（隐私确认/模型就绪标记）。
 *
 * 时钟平滑：服务心跳 5s 一次，[TICK_INTERVAL_MS] 本地秒级递增量在每次
 * 服务状态刷新时归零，展示值 = 服务值 + 本地增量，兼顾平滑与准确。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val startMonitoringUseCase: StartMonitoringUseCase,
    private val stopMonitoringAndSettleUseCase: StopMonitoringAndSettleUseCase,
    private val addMarkerUseCase: AddMarkerUseCase,
    private val settleScheduler: SettleScheduler,
    private val serviceController: ServiceController,
    private val recordingRepository: RecordingRepository,
    private val settingsRepository: SettingsRepository,
    private val recordingStateHolder: RecordingStateHolder,
) : ViewModel() {

    /** 「今天」日期流：每秒刷新一次，保证应用跨零点后自动跟上。 */
    private val todayFlow: Flow<String> = flow {
        while (currentCoroutineContext().isActive) {
            emit(TimeUtils.todayDayId())
            delay(TICK_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    /**
     * 目标录音日（用户需求 4 第一种：全天录音跨零点换天后 UI 需跟随新的一天）。
     *
     * - 服务运行中 → 取服务当前录音日（[RecordingStateHolder.state] 的 `dayId`）；
     * - 服务未运行 → 取「今天」（每秒刷新，应用长时间挂起跨零点也能跟上）。
     */
    private val targetDayId: Flow<String> = combine(
        recordingStateHolder.state
            .map { it.dayId }
            .distinctUntilChanged(),
        todayFlow,
    ) { serviceDayId, today -> serviceDayId ?: today }
        .distinctUntilChanged()

    private val _uiState = MutableStateFlow(TodayUiState(dayId = TimeUtils.todayDayId()))
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    /** 本地秒级递增量：服务状态每次刷新时归零，避免与服务值重复累计。 */
    private val _tickMs = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            combine(
                recordingStateHolder.state,
                targetDayId.flatMapLatest { dayId ->
                    recordingRepository.observeDay(dayId).map { day -> dayId to day }
                },
                settingsRepository.observeSettings(),
                _tickMs,
            ) { serviceState, dayPair, settings, tickMs ->
                val (dayId, day) = dayPair
                val active = serviceState.serviceState == RecordingServiceState.MONITORING ||
                    serviceState.serviceState == RecordingServiceState.RECORDING
                TodayUiState(
                    dayId = dayId,
                    privacyAck = settings.privacyAck,
                    modelsReady = settings.modelsReady,
                    serviceState = serviceState.serviceState,
                    dayState = day?.state ?: DayState.IDLE,
                    monitoredMs = serviceState.monitoredMs + if (active) tickMs else 0L,
                    segmentCount = serviceState.segmentCount,
                    markerCount = serviceState.markerCount,
                    currentSegmentMs = if (
                        serviceState.serviceState == RecordingServiceState.RECORDING
                    ) {
                        serviceState.currentSegmentMs + tickMs
                    } else {
                        0L
                    },
                    settleProgress = day?.settleProgress ?: 0,
                    settleError = day?.settleError,
                    summaryStatus = day?.summaryStatus ?: SummaryStatus.NONE,
                    errorCode = serviceState.errorCode,
                    errorMessage = serviceState.errorMessage,
                    recordingMode = serviceState.recordingMode,
                    manualRecordingOn = serviceState.manualRecordingOn,
                    starting = _uiState.value.starting,
                    stopping = _uiState.value.stopping,
                )
            }.collect { state ->
                _uiState.value = state
                // 服务状态刷新 → 本地增量归零（下次心跳前由 ticker 重新累积）
                _tickMs.value = 0L
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val state = _uiState.value
                val active = state.serviceState == RecordingServiceState.MONITORING ||
                    state.serviceState == RecordingServiceState.RECORDING
                if (active) {
                    _tickMs.value += TICK_INTERVAL_MS
                }
            }
        }
    }

    /** 点击「开始记录」（隐私确认已在入口层完成，FR-014）。 */
    fun startMonitoring() {
        if (_uiState.value.starting) return
        _uiState.update { it.copy(starting = true, message = null) }
        viewModelScope.launch {
            when (val result = startMonitoringUseCase()) {
                is EchoResult.Success ->
                    _uiState.update { it.copy(starting = false) }

                is EchoResult.Failure ->
                    _uiState.update {
                        it.copy(starting = false, message = result.message)
                    }
            }
        }
    }

    /** 点击「结束（结算当天）」：停服务 → SETTLING → 入队 DaySettleWorker。 */
    fun stopMonitoringAndSettle() {
        if (_uiState.value.stopping) return
        _uiState.update { it.copy(stopping = true, message = null) }
        viewModelScope.launch {
            when (val result = stopMonitoringAndSettleUseCase(currentDayId())) {
                is EchoResult.Success ->
                    _uiState.update { it.copy(stopping = false) }

                is EchoResult.Failure ->
                    _uiState.update {
                        it.copy(stopping = false, message = result.message)
                    }
            }
        }
    }

    /** 一键打点（备注可空，应用内与通知栏共用同一用例，FR-005）。 */
    fun addMarker(note: String) {
        viewModelScope.launch {
            when (val result = addMarkerUseCase(currentDayId(), note.trim())) {
                is EchoResult.Success ->
                    _uiState.update { it.copy(message = MARKER_SAVED) }

                is EchoResult.Failure ->
                    _uiState.update { it.copy(message = result.message) }
            }
        }
    }

    /** 结算失败重试：重新入队当天结算（唯一工作名 REPLACE，架构 7.5）。 */
    fun retrySettle() {
        settleScheduler.scheduleDaySettle(currentDayId())
        _uiState.update { it.copy(message = RETRY_QUEUED) }
    }

    /**
     * 暂停监听（用户需求 2：应用内暂停；通知栏暂停走同一 ServiceController）。
     * 暂停后服务保持前台，DB 日状态仍为 MONITORING。
     */
    fun pauseMonitoring() {
        serviceController.pauseMonitoring(currentDayId())
        _uiState.update { it.copy(message = PAUSED) }
    }

    /**
     * 手动恢复监听（DB 为 MONITORING 但服务已死的应用内路径，NFR-003；
     * 亦用于暂停后「继续」——同一 ACTION_RESUME 意图）。
     *
     * HealthCheckWorker 的 15min 周期之外，用户重进应用可立即恢复。
     */
    fun resumeMonitoring() {
        serviceController.resumeMonitoring(currentDayId())
        _uiState.update { it.copy(message = RESUMING) }
    }

    /**
     * 手动录音开停（用户需求 4 第四种：App 内录音按键）。
     *
     * 与通知栏按钮走同一 [ServiceController] 通路；
     * 手动模式下 App 按钮与状态栏按钮共享同一「正在录」标志。
     */
    fun toggleManualRecording() {
        serviceController.toggleManualRecording(currentDayId(), null)
        _uiState.update { it.copy(message = MANUAL_TOGGLED) }
    }

    /** 当前目标录音日（优先用 UI 态里的 dayId，回落到「今天」）。 */
    private fun currentDayId(): String =
        _uiState.value.dayId.takeIf { it.isNotBlank() } ?: TimeUtils.todayDayId()

    /** 消费一次性提示（Snackbar 展示后调用）。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        /** 本地时钟递增值（服务心跳 5s 一次的平滑补偿）。 */
        const val TICK_INTERVAL_MS = 1000L
        const val MARKER_SAVED = "已打点"
        const val RETRY_QUEUED = "已重新加入结算队列"
        const val RESUMING = "正在恢复监听…"
        const val PAUSED = "已暂停，恢复后继续监听"
        const val MANUAL_TOGGLED = "已切换录音状态"
    }
}

/**
 * 今天页 UI 状态（架构 7.5：服务运行态 + DB 日级态的聚合快照）。
 *
 * @param dayState 当天 DB 状态（IDLE=无记录或待机；SETTLING/DONE 驱动结算卡片）。
 * @param settleError 结算失败原因（[DayState.SETTLING] 时由 DaySettleWorker 写入）。
 */
data class TodayUiState(
    val dayId: String = "",
    val privacyAck: Boolean = false,
    val modelsReady: Boolean = false,
    val serviceState: RecordingServiceState = RecordingServiceState.IDLE,
    val dayState: DayState = DayState.IDLE,
    val monitoredMs: Long = 0L,
    val segmentCount: Int = 0,
    val markerCount: Int = 0,
    val currentSegmentMs: Long = 0L,
    val settleProgress: Int = 0,
    val settleError: String? = null,
    val summaryStatus: SummaryStatus = SummaryStatus.NONE,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val message: String? = null,
    /** 当前录音模式（用户需求 4；决定 UI 展示哪种操作）。 */
    val recordingMode: RecordingMode = RecordingMode.AUTO_VAD,
    /** 手动模式下的「正在录」标志（用户需求 4 第四种）。 */
    val manualRecordingOn: Boolean = false,
) {
    /** 是否处于监听/录音运行态（服务活着）。 */
    val isServiceActive: Boolean
        get() = serviceState == RecordingServiceState.MONITORING ||
            serviceState == RecordingServiceState.RECORDING

    /** 是否处于暂停态（用户需求 2：服务前台但音频循环停止）。 */
    val isPaused: Boolean
        get() = serviceState == RecordingServiceState.PAUSED

    /** 是否应展示结算进行中（DB 态 SETTLING 且服务已停）。 */
    val isSettling: Boolean
        get() = dayState == DayState.SETTLING && !isServiceActive

    /** 结算是否失败（SETTLING 停滞且携带错误）。 */
    val isSettleFailed: Boolean
        get() = isSettling && !settleError.isNullOrBlank() && !isSettleNote

    /**
     * 结算备注是否为「非致命提示」（BUG-11 修复）。
     *
     * 合并失败但转写成功时，DB 里会留一条带 [Constants.SETTLE_NOTE_PREFIX]
     * 前缀的备注——结算**已成功归档**（进度 100），只是「全天合并音频」这个
     * 产物缺失。这类备注不该被当作错误展示「重试结算」按钮
     * （重试对确定性的合并失败无效，重试只会再次失败）。
     */
    val isSettleNote: Boolean
        get() = settleError?.startsWith(Constants.SETTLE_NOTE_PREFIX) == true

    /** 当天是否已归档完成。 */
    val isDayDone: Boolean
        get() = dayState == DayState.DONE

    /**
     * 是否需要在「已归档」状态下展示结算备注（BUG-11 修复）。
     *
     * 合并降级场景下当天是 DONE/100，若不在此处展示备注，用户完全看不到
     * 「合并产物缺失」这一事实（而他们随后去导出/下载合并音频时会扑空）。
     */
    val showSettleNoteWhenDone: Boolean
        get() = isDayDone && isSettleNote && !settleError.isNullOrBlank()

    /** DB 为 MONITORING 但服务未运行（进程被杀/服务死亡）：展示恢复入口。 */
    val needsRecovery: Boolean
        get() = dayState == DayState.MONITORING && !isServiceActive && !isPaused
}
