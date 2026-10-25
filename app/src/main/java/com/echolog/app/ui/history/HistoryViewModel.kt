package com.echolog.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SummaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 历史列表 ViewModel（架构 2.5，PRD 4.4，FR-016）。
 *
 * 数据：全部录音日按月份分组倒序；每天附带摘要首行与未完成待办数
 * （经 [SummaryRepository] 一次性读取，Flow 变更时自动重算）。
 *
 * 删除（FR-106）：二次确认在 UI 层；VM 提供三种粒度
 * （全删 / 仅音频 / 仅文字），落 [RecordingRepository.deleteDay]。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val summaryRepository: SummaryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recordingRepository.observeDays()
                .map { days ->
                    days.sortedByDescending { it.dayId }
                        .map { day -> day.toHistoryItem() }
                        .groupBy { item -> TimeUtils.formatMonthLabel(item.day.dayId) }
                        .map { (month, items) -> MonthGroup(month, items) }
                }
                .collect { groups ->
                    _uiState.update {
                        it.copy(loading = false, monthGroups = groups)
                    }
                }
        }
    }

    /** 打开某天（导航到当天时间线）。 */
    fun openDay(dayId: String) {
        _uiState.update { it.copy(navigateToDayId = dayId) }
    }

    /** 消费导航事件。 */
    fun consumeNavigation() {
        _uiState.update { it.copy(navigateToDayId = null) }
    }

    /** 请求删除（打开二次确认）。 */
    fun requestDelete(day: RecordingDay) {
        _uiState.update { it.copy(pendingDelete = day) }
    }

    /** 取消删除。 */
    fun cancelDelete() {
        _uiState.update { it.copy(pendingDelete = null) }
    }

    /**
     * 确认删除（FR-106：音频/文字可分别删）。
     *
     * @param deleteAudio 删除片段与合并音频文件。
     * @param deleteText 删除文字类数据与录音日行。
     */
    fun confirmDelete(deleteAudio: Boolean, deleteText: Boolean) {
        val day = _uiState.value.pendingDelete ?: return
        _uiState.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            runCatching {
                recordingRepository.deleteDay(day.dayId, deleteAudio, deleteText)
            }.onSuccess {
                _uiState.update { it.copy(message = DELETED) }
            }.onFailure { throwable ->
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

    /** 领域模型 → 列表项（摘要首行 + 未完成待办数）。 */
    private suspend fun RecordingDay.toHistoryItem(): HistoryDay {
        val summary = runCatching { summaryRepository.getSummary(dayId) }.getOrNull()
        val todos = runCatching { summaryRepository.observeTodosForDay(dayId).first() }
            .getOrDefault(emptyList())
        return HistoryDay(
            day = this,
            summaryPreview = summary?.summaryText
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim(),
            openTodoCount = todos.count { !it.done },
        )
    }

    private companion object {
        const val DELETED = "已删除"
    }
}

/**
 * 历史页 UI 状态。
 *
 * @param monthGroups 按月分组（组内按日期倒序）。
 * @param pendingDelete 待二次确认的删除目标（非空即弹确认框）。
 * @param navigateToDayId 一次性导航事件（打开某天时间线）。
 */
data class HistoryUiState(
    val loading: Boolean = true,
    val monthGroups: List<MonthGroup> = emptyList(),
    val pendingDelete: RecordingDay? = null,
    val navigateToDayId: String? = null,
    val message: String? = null,
) {
    val isEmpty: Boolean
        get() = !loading && monthGroups.isEmpty()
}

/** 月份分组（PRD 4.4：按月份分组）。 */
data class MonthGroup(
    val monthLabel: String,
    val days: List<HistoryDay>,
)

/** 历史列表的一天（PRD 4.4：日期 + 总时长 + 摘要首句 + 待办徽标）。 */
data class HistoryDay(
    val day: RecordingDay,
    val summaryPreview: String?,
    val openTodoCount: Int,
) {
    val isMonitoring: Boolean get() = day.state == DayState.MONITORING
    val isSettling: Boolean get() = day.state == DayState.SETTLING
    val isDone: Boolean get() = day.state == DayState.DONE
}
