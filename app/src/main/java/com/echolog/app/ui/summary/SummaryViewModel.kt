package com.echolog.app.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.ai.llm.SummaryJsonParser
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.util.FileUtils
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.data.media.AudioPlayer
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.TimelineItem
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.model.TodoType
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SummaryRepository
import com.echolog.app.domain.usecase.ExportDayTranscriptUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 总结详情 ViewModel（架构 2.5/4.2，PRD 4.3，FR-011）。
 *
 * 聚合：当天总结（[SummaryRepository.observeSummary]）、待办条目
 * （[SummaryRepository.observeTodosForDay]）、日级状态（进度/合并音频路径）。
 *
 * 时间线 JSON 经 [SummaryJsonParser.parseTimelineJson] 解析
 * （与 `SummarizeDayUseCase.encodeTimeline` 互为逆操作）。
 */
@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val summaryRepository: SummaryRepository,
    private val recordingRepository: RecordingRepository,
    private val exportDayTranscriptUseCase: ExportDayTranscriptUseCase,
) : ViewModel() {

    private val audioPlayer = AudioPlayer()

    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    /** 加载目标天（导航参数注入）。 */
    fun load(dayId: String) {
        if (_uiState.value.dayId == dayId && _uiState.value.loaded) return
        _uiState.update { it.copy(dayId = dayId, loading = true) }
        viewModelScope.launch {
            combine(
                summaryRepository.observeSummary(dayId),
                summaryRepository.observeTodosForDay(dayId),
                recordingRepository.observeDay(dayId),
            ) { summary, todos, day ->
                SummaryUiState(
                    dayId = dayId,
                    loading = false,
                    loaded = true,
                    dayState = day?.state ?: DayState.IDLE,
                    settleProgress = day?.settleProgress ?: 0,
                    totalRecordMs = day?.totalRecordMs ?: 0L,
                    mergedAudioPath = day?.mergedAudioPath,
                    summary = summary,
                    timeline = summary?.timelineJson
                        ?.let { SummaryJsonParser.parseTimelineJson(it) }
                        ?: emptyList(),
                    todos = todos,
                    playing = _uiState.value.playing,
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    /** 待办勾选/取消（FR-105 简化版：仅本地持久化勾选态）。 */
    fun setTodoDone(todoId: Long, done: Boolean) {
        viewModelScope.launch {
            summaryRepository.setTodoDone(todoId, done)
        }
    }

    /** 播放/暂停全天合并录音（PRD 4.3 底部操作栏）。 */
    fun togglePlayAll() {
        val path = _uiState.value.mergedAudioPath
        if (_uiState.value.playing) {
            audioPlayer.pause()
            _uiState.update { it.copy(playing = false) }
            return
        }
        if (path.isNullOrBlank()) {
            _uiState.update { it.copy(message = NO_MERGED_AUDIO) }
            return
        }
        runCatching { audioPlayer.play(path, 0L) }
        _uiState.update { it.copy(playing = audioPlayer.isPlaying) }
    }

    /** 删除当天全部数据（音频 + 文字，FR-106；删除后页面由 UI 层自动退出）。 */
    fun deleteDay() {
        val dayId = _uiState.value.dayId
        if (dayId.isBlank()) return
        viewModelScope.launch {
            runCatching { recordingRepository.deleteDay(dayId, true, true) }
                .onSuccess { _uiState.update { it.copy(message = DAY_DELETED) } }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(message = "删除失败：${throwable.message ?: "未知错误"}")
                    }
                }
        }
    }

    /** 消费一次性提示。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * 导出当天全天转写为带日期的 Markdown 文档（用户需求 3）。
     *
     * 成功时提示导出文件路径；失败时展示可读原因（当天无转写内容等）。
     */
    fun exportDayTranscript() {
        val dayId = _uiState.value.dayId
        if (dayId.isBlank()) return
        viewModelScope.launch {
            when (val result = exportDayTranscriptUseCase(dayId)) {
                is EchoResult.Success -> _uiState.update {
                    it.copy(message = "已导出：${FileUtils.fileNameOf(result.data)}")
                }

                is EchoResult.Failure -> _uiState.update {
                    it.copy(message = result.message)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }

    private companion object {
        const val NO_MERGED_AUDIO = "当天暂无合并音频"
        const val DAY_DELETED = "已删除当天全部记录"
    }
}

/**
 * 总结页 UI 状态。
 *
 * @param timeline 解析后的关键时间线（按 atMs 升序）。
 * @param mergedAudioPath 全天合并音频路径（[DaySummary] 不持有，取自录音日）。
 */
data class SummaryUiState(
    val dayId: String = "",
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val dayState: DayState = DayState.IDLE,
    val settleProgress: Int = 0,
    val totalRecordMs: Long = 0L,
    val mergedAudioPath: String? = null,
    val summary: DaySummary? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val playing: Boolean = false,
    val message: String? = null,
) {
    /** 总结是否已生成（决定空态/内容态）。 */
    val hasSummary: Boolean
        get() = summary != null && summary.status == SummaryRunStatus.DONE

    /** 结算是否进行中（展示进度而非空态）。 */
    val isSettling: Boolean
        get() = dayState == DayState.SETTLING

    /** 待办未完成数（顶部徽标）。 */
    val openTodoCount: Int
        get() = todos.count { !it.done && it.type == TodoType.TODO }

    /** 按类型分组的条目（PRD 4.3 三卡片）。 */
    fun todosOfType(type: TodoType): List<TodoItem> =
        todos.filter { it.type == type }
}

/** 时间线条目展示标签：`HH:mm 文字`（与转写文本前缀格式一致）。 */
fun TimelineItem.displayLabel(): String =
    "[${TimeUtils.formatTime(atMs)}] $text"
