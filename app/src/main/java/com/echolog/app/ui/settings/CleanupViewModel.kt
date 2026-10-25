package com.echolog.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.core.common.EchoResult
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.usecase.CleanupCacheUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 清理缓存 ViewModel（用户需求 2）。
 *
 * 三个一键操作，各自独立：
 * 1. 删除所有录音原文件（`.wav`）；
 * 2. 清除某天「转化后」的产物（合并 `.wav` + 同名 `.md` + DB 转写分段）；
 * 3. 清除所有天的总结内容。
 *
 * 二次确认在 UI 层（[CleanupUiState.pendingAction] 非空即弹确认框）。
 */
@HiltViewModel
class CleanupViewModel @Inject constructor(
    private val cleanupCacheUseCase: CleanupCacheUseCase,
    private val recordingRepository: RecordingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CleanupUiState())
    val uiState: StateFlow<CleanupUiState> = _uiState.asStateFlow()

    init {
        // 供「选择日期」对话框使用的录音日列表
        viewModelScope.launch {
            recordingRepository.observeDays().collect { days ->
                _uiState.update { it.copy(days = days) }
            }
        }
    }

    /** 请求执行「删除所有录音原文件」（弹确认框）。 */
    fun requestDeleteAllAudio() {
        _uiState.update { it.copy(pendingAction = CleanupAction.DELETE_ALL_AUDIO) }
    }

    /** 请求执行「清除所有总结内容」（弹确认框）。 */
    fun requestDeleteAllSummaries() {
        _uiState.update { it.copy(pendingAction = CleanupAction.DELETE_ALL_SUMMARY) }
    }

    /** 请求执行「清除某天转写文字」：先选日期，再弹确认框。 */
    fun requestClearDayTranscript(dayId: String) {
        _uiState.update {
            it.copy(pendingAction = CleanupAction.CLEAR_DAY_TRANSCRIPT, targetDayId = dayId)
        }
    }

    /** 取消待执行操作。 */
    fun cancelAction() {
        _uiState.update { it.copy(pendingAction = null, targetDayId = null) }
    }

    /** 确认执行待操作。 */
    fun confirmAction() {
        val action = _uiState.value.pendingAction ?: return
        val dayId = _uiState.value.targetDayId
        _uiState.update { it.copy(pendingAction = null, targetDayId = null, working = true) }
        viewModelScope.launch {
            val result = when (action) {
                CleanupAction.DELETE_ALL_AUDIO -> cleanupCacheUseCase()
                CleanupAction.CLEAR_DAY_TRANSCRIPT -> dayId?.let {
                    cleanupCacheUseCase.clearDayConverted(it)
                }

                CleanupAction.DELETE_ALL_SUMMARY -> cleanupCacheUseCase.clearAllSummaries()
            }
            val message = when (result) {
                is EchoResult.Success -> when (action) {
                    CleanupAction.DELETE_ALL_AUDIO -> "已删除 ${result.data} 个录音文件"
                    CleanupAction.CLEAR_DAY_TRANSCRIPT -> "已清除 $dayId 的转写内容"
                    CleanupAction.DELETE_ALL_SUMMARY -> "已清除 ${result.data} 天的总结"
                }

                is EchoResult.Failure -> result.message
                null -> null
            }
            _uiState.update { it.copy(working = false, message = message) }
        }
    }

    /** 消费一次性提示。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

/** 清理操作类型。 */
enum class CleanupAction {
    /** 删除所有录音原文件。 */
    DELETE_ALL_AUDIO,

    /** 清除某天「转化后」的产物。 */
    CLEAR_DAY_TRANSCRIPT,

    /** 清除所有天的总结内容。 */
    DELETE_ALL_SUMMARY,
}

/**
 * 清理页 UI 状态。
 *
 * @param days 全部录音日（供选择日期）。
 * @param pendingAction 待确认的操作（null = 无）。
 * @param targetDayId 待确认操作的目标日期。
 */
data class CleanupUiState(
    val days: List<RecordingDay> = emptyList(),
    val pendingAction: CleanupAction? = null,
    val targetDayId: String? = null,
    val working: Boolean = false,
    val message: String? = null,
)
