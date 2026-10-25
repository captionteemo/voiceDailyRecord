package com.echolog.app.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.core.common.EchoResult
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.TranscriptionRepository
import com.echolog.app.domain.usecase.RegenerateSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 转写全文页 ViewModel（用户需求：转写全文页 UI 层）。
 *
 * 聚合当天三类数据：
 * - 转写文字（[TranscriptionRepository.observeTranscriptsForDay]，**全天绝对时间升序**）；
 * - 音频片段（[RecordingRepository.observeSegmentsForDay]，播放定位需要）；
 * - 日级状态（[RecordingRepository.observeDay]：合并音频路径 + 总结状态）。
 *
 * 播放定位由 [TranscriptPlaybackLocator] 完成（纯函数，可单测）：
 * 优先当天合并音频（偏移 = 前缀片段时长之和 + 片段内偏移），
 * 回退片段 WAV（偏移 = 片段内偏移）。
 *
 * 本类**不持有播放器**：`AudioPlayer` 依赖 `android.media.MediaPlayer`，
 * 由 UI 层（[TranscriptScreen]）持有并在页面离开时释放；
 * ViewModel 只负责计算播放目标（路径 + 文件内偏移）与高亮状态，
 * 因此可以在 JVM 单元测试中完整驱动。
 */
@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val transcriptionRepository: TranscriptionRepository,
    private val recordingRepository: RecordingRepository,
    private val regenerateSummaryUseCase: RegenerateSummaryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranscriptUiState())
    val uiState: StateFlow<TranscriptUiState> = _uiState.asStateFlow()

    /** 当天片段（播放定位用；不进入 UI 状态，避免把内部结构暴露给 UI）。 */
    private var segments: List<AudioSegment> = emptyList()

    /** 数据收集 Job（重入 load 时取消旧收集器，防并发写乱）。 */
    private var observeJob: Job? = null

    /** 加载目标天（导航参数注入）。 */
    fun load(dayId: String) {
        if (_uiState.value.dayId == dayId && _uiState.value.loaded) return
        observeJob?.cancel()
        _uiState.update { it.copy(dayId = dayId, loading = true) }
        observeJob = viewModelScope.launch {
            combine(
                transcriptionRepository.observeTranscriptsForDay(dayId),
                recordingRepository.observeSegmentsForDay(dayId),
                recordingRepository.observeDay(dayId),
            ) { transcripts, daySegments, day ->
                Triple(transcripts, daySegments, day)
            }.collect { (transcripts, daySegments, day) ->
                segments = daySegments
                _uiState.update { current ->
                    current.copy(
                        dayId = dayId,
                        loading = false,
                        loaded = true,
                        // 按全天绝对时间升序（连续阅读）
                        lines = transcripts
                            .sortedBy { it.startMs }
                            .map { TranscriptLineUi.from(it) },
                        mergedAudioPath = day?.mergedAudioPath,
                        summaryStatus = day?.summaryStatus ?: SummaryStatus.NONE,
                    )
                }
            }
        }
    }

    /**
     * 点击某条转写：计算播放目标并置为高亮。
     *
     * @param tsId 转写分段主键。
     * @return 播放目标（路径 + 文件内偏移）；无法定位时返回 `null`
     *   （此时已写入可见错误提示，见 [TranscriptMessage.PlaybackUnavailable]）。
     */
    fun onTranscriptClicked(tsId: Long): TranscriptPlaybackTarget? {
        val line = _uiState.value.lines.firstOrNull { it.tsId == tsId }
        if (line == null) {
            _uiState.update { it.copy(playingTsId = null, message = TranscriptMessage.PlaybackUnavailable) }
            return null
        }
        val target = TranscriptPlaybackLocator.resolveTarget(
            transcript = line.toTranscriptSegment(),
            segments = segments,
            mergedAudioPath = _uiState.value.mergedAudioPath,
            mergedAudioExists = TranscriptPlaybackLocator.mergedAudioFileExists(
                _uiState.value.mergedAudioPath,
            ),
        )
        if (target == null) {
            _uiState.update { it.copy(playingTsId = null, message = TranscriptMessage.PlaybackUnavailable) }
            return null
        }
        _uiState.update { it.copy(playingTsId = tsId, message = null) }
        return target
    }

    /** 播放停止（暂停 / 播完 / 离开页面）：清除高亮。 */
    fun onPlaybackStopped() {
        _uiState.update { it.copy(playingTsId = null) }
    }

    /** 播放失败（音频文件不可读等）：清除高亮并给出可见错误提示。 */
    fun onPlaybackFailed() {
        _uiState.update {
            it.copy(playingTsId = null, message = TranscriptMessage.PlaybackUnavailable)
        }
    }

    // ── 选择模式（长按进入） ──

    /** 长按某条：进入选择模式并勾选该条。 */
    fun onTranscriptLongPressed(tsId: Long) {
        _uiState.update {
            it.copy(
                selectionMode = true,
                selectedTsIds = it.selectedTsIds + tsId,
            )
        }
    }

    /** 勾选/取消勾选某条。 */
    fun toggleSelection(tsId: Long) {
        _uiState.update {
            val selected = it.selectedTsIds
            it.copy(
                selectedTsIds = if (tsId in selected) selected - tsId else selected + tsId,
            )
        }
    }

    /** 退出选择模式（清空勾选）。 */
    fun exitSelectionMode() {
        _uiState.update { it.copy(selectionMode = false, selectedTsIds = emptySet()) }
    }

    // ── 就地编辑（编辑即覆盖原文，不保留副本） ──

    /** 进入编辑态。 */
    fun startEditing(tsId: Long) {
        val line = _uiState.value.lines.firstOrNull { it.tsId == tsId } ?: return
        _uiState.update {
            it.copy(
                editingTsId = tsId,
                editingText = line.text,
                message = null,
            )
        }
    }

    /** 编辑中的文本变化。 */
    fun updateEditingText(text: String) {
        _uiState.update { it.copy(editingText = text) }
    }

    /** 取消编辑（丢弃改动）。 */
    fun cancelEditing() {
        _uiState.update { it.copy(editingTsId = null, editingText = "") }
    }

    /**
     * 保存编辑：**直接覆盖**原文（`transcript_segment.text`）。
     *
     * 失败（仓库返回 0 行，或抛异常）时保留编辑态并给出可见错误提示。
     */
    fun saveEdit() {
        val state = _uiState.value
        val tsId = state.editingTsId ?: return
        val text = state.editingText
        if (text.isBlank()) {
            _uiState.update { it.copy(message = TranscriptMessage.EditEmpty) }
            return
        }
        if (state.saving) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val affected = runCatching { transcriptionRepository.updateTranscriptText(tsId, text) }
                .getOrElse { 0 }
            if (affected > 0) {
                _uiState.update {
                    it.copy(
                        saving = false,
                        editingTsId = null,
                        editingText = "",
                        message = TranscriptMessage.Saved,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(saving = false, message = TranscriptMessage.SaveFailed)
                }
            }
        }
    }

    // ── 复制 ──

    /**
     * 「复制全部」：返回待复制文本，并写入 [TranscriptMessage.CopySucceeded] 提示。
     *
     * 复制成功必须有可见反馈（静默失败红线）：用户点了按钮不知道成没成功
     * 会重复点，因此复用一次性消息机制（UI 层展示「已复制 N 条」）。
     *
     * @return 复制文本；当天无转写内容时返回 `null`（**不**产生提示，
     *   由 UI 层提示「没有可复制的文字」）。
     */
    fun copyAll(): String? {
        val lines = _uiState.value.lines
        val text = TranscriptCopyFormatter.formatAll(lines) ?: return null
        _uiState.update { it.copy(message = TranscriptMessage.CopySucceeded(lines.size)) }
        return text
    }

    /**
     * 「复制所选」：返回待复制文本，并写入 [TranscriptMessage.CopySucceeded] 提示。
     *
     * 提示里的条数是**实际复制的条数**（勾选项中确实存在展示行的数量），
     * 不是勾选集合的大小——防止勾选了已不存在的行时数字对不上。
     *
     * @return 复制文本；未勾选任何有效条时返回 `null`（不产生提示）。
     */
    fun copySelected(): String? {
        val state = _uiState.value
        val copiedCount = state.lines.count { it.tsId in state.selectedTsIds }
        if (copiedCount == 0) return null
        val text = TranscriptCopyFormatter.formatSelected(state.lines, state.selectedTsIds)
            ?: return null
        _uiState.update { it.copy(message = TranscriptMessage.CopySucceeded(copiedCount)) }
        return text
    }

    // ── 重新生成总结 ──

    /**
     * 重新生成当天总结。
     *
     * 防重复点击：`regeneratingSummary` 为 true 时直接返回。
     */
    fun regenerateSummary() {
        val state = _uiState.value
        if (state.regeneratingSummary) return
        if (state.dayId.isBlank()) return
        _uiState.update { it.copy(regeneratingSummary = true, message = null) }
        viewModelScope.launch {
            when (val result = regenerateSummaryUseCase(state.dayId)) {
                is EchoResult.Success -> _uiState.update {
                    it.copy(regeneratingSummary = false, message = TranscriptMessage.SummaryRegenerated)
                }

                is EchoResult.Failure -> _uiState.update {
                    it.copy(
                        regeneratingSummary = false,
                        message = TranscriptMessage.SummaryRegenerateFailed(result.message),
                    )
                }
            }
        }
    }

    /** 消费一次性提示。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

/**
 * 转写全文页的一次性提示（由 UI 层映射为 `stringResource` 文案）。
 *
 * 用**枚举 + 可选原因**而不是最终文案，是为了让 ViewModel 保持无 Android 依赖，
 * 同时让文案统一走 `strings.xml`（硬性规范）。
 */
sealed interface TranscriptMessage {

    /** 保存成功。 */
    data object Saved : TranscriptMessage

    /** 保存失败（覆盖的行数为 0 或仓库抛异常）。 */
    data object SaveFailed : TranscriptMessage

    /** 编辑内容为空，拒绝保存。 */
    data object EditEmpty : TranscriptMessage

    /** 总结重新生成成功。 */
    data object SummaryRegenerated : TranscriptMessage

    /** 总结重新生成失败（携带领域层原因；文案前缀由 UI 层拼，见兜底逻辑）。 */
    data class SummaryRegenerateFailed(val reason: String?) : TranscriptMessage

    /**
     * 复制成功（携带**实际复制的条数**，UI 层展示「已复制 N 条」）。
     *
     * 复制成功也必须有可见反馈：否则用户不知道成没成功，会重复点。
     */
    data class CopySucceeded(val count: Int) : TranscriptMessage

    /** 找不到可播放的音频（片段缺失 / 合并音频与片段均不可用）。 */
    data object PlaybackUnavailable : TranscriptMessage
}

/**
 * 转写全文页 UI 状态。
 *
 * @param lines 当天全部转写（按全天绝对时间升序）。
 * @param playingTsId 当前正在播放的转写（用于高亮）。
 * @param editingTsId 正在编辑的转写（null = 非编辑态）。
 * @param selectionMode 是否处于选择模式。
 * @param selectedTsIds 已勾选的转写主键。
 */
data class TranscriptUiState(
    val dayId: String = "",
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val lines: List<TranscriptLineUi> = emptyList(),
    val mergedAudioPath: String? = null,
    val summaryStatus: SummaryStatus = SummaryStatus.NONE,
    val playingTsId: Long? = null,
    val editingTsId: Long? = null,
    val editingText: String = "",
    val saving: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedTsIds: Set<Long> = emptySet(),
    val regeneratingSummary: Boolean = false,
    val message: TranscriptMessage? = null,
) {
    /** 当天是否有转写内容（空态判定）。 */
    val hasTranscript: Boolean
        get() = lines.isNotEmpty()

    /** 已勾选条数（底部「已选 N 条」）。 */
    val selectedCount: Int
        get() = selectedTsIds.size

    /** 某条是否正在编辑。 */
    fun isEditing(tsId: Long): Boolean = editingTsId == tsId

    /** 某条是否已勾选。 */
    fun isSelected(tsId: Long): Boolean = tsId in selectedTsIds

    /** 某条是否正在播放（高亮）。 */
    fun isPlaying(tsId: Long): Boolean = playingTsId == tsId
}

/**
 * 转写全文页的一行（UI 展示单元）。
 *
 * @param tsId 转写分段主键（编辑时回写用）。
 * @param segmentId 所属音频片段主键（播放定位用）。
 */
data class TranscriptLineUi(
    val tsId: Long,
    val segmentId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    companion object {
        /** 由领域模型构造 UI 行。 */
        fun from(segment: TranscriptSegment): TranscriptLineUi = TranscriptLineUi(
            tsId = segment.tsId,
            segmentId = segment.segmentId,
            startMs = segment.startMs,
            endMs = segment.endMs,
            text = segment.text,
        )
    }

    /** 回转为领域模型（播放定位需要 `TranscriptSegment`）。 */
    fun toTranscriptSegment(): TranscriptSegment = TranscriptSegment(
        tsId = tsId,
        segmentId = segmentId,
        startMs = startMs,
        endMs = endMs,
        text = text,
    )
}
