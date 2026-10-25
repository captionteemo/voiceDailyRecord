package com.echolog.app.ui.schedule

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.core.util.ScheduleTemplateCodec
import com.echolog.app.domain.model.ScheduleTemplate
import com.echolog.app.domain.model.TimeRange
import com.echolog.app.domain.repository.ScheduleTemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 课表模板页 ViewModel（用户需求 4：模板录音）。
 *
 * 模板正文以文本存储（与导入文件同格式），因此「导入文件」与「手工编辑」
 * 共用同一份数据模型——见 [ScheduleTemplateCodec]。
 */
@HiltViewModel
class ScheduleTemplateViewModel @Inject constructor(
    private val scheduleTemplateRepository: ScheduleTemplateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleTemplateUiState())
    val uiState: StateFlow<ScheduleTemplateUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            scheduleTemplateRepository.observeTemplate().collect { template ->
                _uiState.update { it.copy(template = template, loaded = true) }
            }
        }
    }

    /** 启用/停用模板。 */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { scheduleTemplateRepository.setTemplateEnabled(enabled) }
    }

    /** 重命名模板。 */
    fun setName(name: String) {
        val current = _uiState.value.template
        viewModelScope.launch {
            scheduleTemplateRepository.saveTemplate(current.copy(name = name.trim()))
        }
    }

    /** 新增一个时间段。 */
    fun addRange(startMinute: Int, endMinute: Int, label: String) {
        val current = _uiState.value.template
        val range = TimeRange(
            startMinute = startMinute.coerceIn(0, TimeRange.MINUTES_PER_DAY),
            endMinute = endMinute.coerceIn(0, TimeRange.MINUTES_PER_DAY),
            label = label.trim(),
        )
        val updated = current.ranges + range
        viewModelScope.launch {
            scheduleTemplateRepository.saveTemplate(current.copy(ranges = updated))
        }
    }

    /** 移除指定时间段（按当前列表下标）。 */
    fun removeRange(index: Int) {
        val current = _uiState.value.template
        if (index !in current.ranges.indices) return
        val updated = current.ranges.toMutableList().also { it.removeAt(index) }
        viewModelScope.launch {
            scheduleTemplateRepository.saveTemplate(current.copy(ranges = updated))
        }
    }

    /**
     * 导入模板文件（用户需求 4：导入一整天的时间模板）。
     *
     * 读取文件正文 → 解析为时间段 → 覆盖模板正文。
     * 解析不出任何区间时保留原模板并回报失败。
     */
    fun importFromFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            val body = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (body == null) {
                _uiState.update { it.copy(message = "读取文件失败") }
                return@launch
            }
            val ranges = ScheduleTemplateCodec.parseRanges(body)
            if (ranges.isEmpty()) {
                _uiState.update { it.copy(message = "文件中没有可解析的时间段") }
                return@launch
            }
            scheduleTemplateRepository.saveTemplateBody(body)
            _uiState.update { it.copy(message = "已导入 ${ranges.size} 个时间段") }
        }
    }

    /** 消费一次性提示。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

/**
 * 课表模板页 UI 状态。
 *
 * @param template 当前模板（区间 + 名称 + 开关）。
 * @param loaded 是否已从存储加载完成。
 * @param message 一次性提示（Snackbar）。
 */
data class ScheduleTemplateUiState(
    val template: ScheduleTemplate = ScheduleTemplate(),
    val loaded: Boolean = false,
    val message: String? = null,
) {
    /** 已解析的时间段（按开始时间升序）。 */
    val ranges: List<TimeRange>
        get() = template.ranges.sortedBy { it.startMinute }
}
