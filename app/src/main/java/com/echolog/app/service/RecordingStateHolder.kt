package com.echolog.app.service

import com.echolog.app.domain.model.RecordingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务运行态持有者（架构 1.2/3.3/7.5）：服务 → UI 的单向数据桥。
 *
 * `@Singleton` 生命周期跟随 Application，UI（T05 TodayViewModel）只观察 [state]，
 * 服务（生产者）通过 [update]/[mutate] 推送；不依赖任何一方生命周期，天然解耦。
 */
@Singleton
class RecordingStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(RecordingUiState())

    /** 当前运行态快照流。 */
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    /** 整体替换状态。 */
    fun update(newState: RecordingUiState) {
        _state.value = newState
    }

    /**
     * 基于当前状态局部更新（高频场景，避免整构）。
     *
     * BUG-15 修复：经 [MutableStateFlow.update]（CAS 循环）保证读-改-写原子性——
     * 音频线程与 IO 线程并发 mutate 不再丢失更新。
     */
    fun mutate(transform: (RecordingUiState) -> RecordingUiState) {
        _state.update(transform)
    }
}

/** 服务运行态（UI 三态 + 结算态，架构 1.5）。 */
enum class RecordingServiceState {
    /** 待机：服务未运行。 */
    IDLE,

    /** 监听中：VAD 待命，安静不录音。 */
    MONITORING,

    /** 录音中：VAD 已触发，正在写片段文件。 */
    RECORDING,

    /** 已暂停（用户需求 2）：服务保持前台，音频循环停止，可随时继续。 */
    PAUSED,

    /** 结算中：已手动结束，Worker 流水线进行中（DB 状态同步透出）。 */
    SETTLING,
}

/**
 * UI 观察的服务状态快照（架构 7.5：UI 唯一运行态来源）。
 *
 * 时间字段一律 epoch millis 语义的时长（非时间戳）。
 */
data class RecordingUiState(
    val dayId: String? = null,
    val serviceState: RecordingServiceState = RecordingServiceState.IDLE,
    /** 本次已监听时长（ms）。 */
    val monitoredMs: Long = 0L,
    /** 今日已完成片段数。 */
    val segmentCount: Int = 0,
    /** 今日打点数。 */
    val markerCount: Int = 0,
    /** 当前片段已录时长（ms；未录音为 0）。 */
    val currentSegmentMs: Long = 0L,
    /** 最近一次错误码（[com.echolog.app.core.common.Constants] 错误码表）。 */
    val errorCode: String? = null,
    /** 最近一次错误可读信息。 */
    val errorMessage: String? = null,
    /** 当前录音模式（用户需求 4；UI 据此切换按钮与提示）。 */
    val recordingMode: RecordingMode = RecordingMode.AUTO_VAD,
    /** 手动模式下的「正在录」标志（用户需求 4 第四种）。 */
    val manualRecordingOn: Boolean = false,
)
