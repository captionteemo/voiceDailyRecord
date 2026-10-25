package com.echolog.app.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.data.media.AudioPlayer
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.TranscriptionRepository
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
 * 当天时间线 ViewModel（架构 2.5/4.2，PRD 4.2，FR-017/104）。
 *
 * 聚合三类流：片段（[RecordingRepository.observeSegmentsForDay]）、
 * 转写（[TranscriptionRepository.observeTranscriptsForDay]）、
 * 标记（[RecordingRepository.observeMarkersForDay]），外加日级状态驱动
 * 「生成总结」入口的出现时机（架构 7.5：UI 只读 settleProgress/summaryStatus）。
 *
 * 播放定位（FR-104）：点击标记/片段经 [AudioPlayer] 播放片段文件，
 * 起始位置 = 目标时刻 - 片段起点（片段内偏移）。
 */
@HiltViewModel
class DayTimelineViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val transcriptionRepository: TranscriptionRepository,
) : ViewModel() {

    private val audioPlayer = AudioPlayer()

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    /** 目标天数据收集 Job（BUG-14：重入 load 时取消旧收集器，防并发写错乱）。 */
    private var observeJob: Job? = null

    /** 加载目标天（导航参数注入，架构 2.5：VM 按 dayId 取数）。 */
    fun load(dayId: String) {
        if (_uiState.value.dayId == dayId && _uiState.value.loaded) return
        // BUG-14 修复：先取消旧收集器再起新的，避免两个 combine 并发写 _uiState
        observeJob?.cancel()
        _uiState.update { it.copy(dayId = dayId, loading = true) }
        observeJob = viewModelScope.launch {
            combine(
                recordingRepository.observeSegmentsForDay(dayId),
                transcriptionRepository.observeTranscriptsForDay(dayId),
                recordingRepository.observeMarkersForDay(dayId),
                recordingRepository.observeDay(dayId),
            ) { segments, transcripts, markers, day ->
                val transcriptsBySegment = transcripts.groupBy { it.segmentId }
                val markersBySegment = markers.groupBy { marker ->
                    segments.firstOrNull { segment ->
                        marker.atMs >= segment.startMs && marker.atMs <= segment.endMs
                    }?.segmentId
                }
                TimelineUiState(
                    dayId = dayId,
                    loading = false,
                    loaded = true,
                    dayState = day?.state ?: DayState.IDLE,
                    settleProgress = day?.settleProgress ?: 0,
                    summaryStatus = day?.summaryStatus ?: SummaryStatus.NONE,
                    totalRecordMs = day?.totalRecordMs ?: 0L,
                    segments = segments.map { segment ->
                        SegmentUi(
                            segment = segment,
                            transcripts = transcriptsBySegment[segment.segmentId].orEmpty(),
                            markers = markersBySegment[segment.segmentId].orEmpty(),
                        )
                    },
                    markers = markers,
                    playingSegmentId = _uiState.value.playingSegmentId,
                    groupByHalfHour = _uiState.value.groupByHalfHour,
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    /** 切换「按片段 / 按半小时」组织方式（PRD 4.2）。 */
    fun toggleGrouping() {
        _uiState.update { it.copy(groupByHalfHour = !it.groupByHalfHour) }
    }

    /**
     * 播放片段；[fromMs] 为全天绝对时刻（默认片段起点），
     * 内部换算为片段内偏移（FR-104 打点跳转）。
     */
    fun playSegment(segment: AudioSegment, fromMs: Long = segment.startMs) {
        val offset = (fromMs - segment.startMs).coerceAtLeast(0L)
        runCatching { audioPlayer.play(segment.filePath, offset) }
        _uiState.update { it.copy(playingSegmentId = segment.segmentId) }
    }

    /** 播放/暂停切换（同一片段再点即暂停）。 */
    fun togglePlayback(segment: AudioSegment) {
        if (_uiState.value.playingSegmentId == segment.segmentId && audioPlayer.isPlaying) {
            audioPlayer.pause()
            _uiState.update { it.copy(playingSegmentId = null) }
        } else {
            playSegment(segment)
        }
    }

    /** 暂停播放（页面不可见时由 UI 层调用）。 */
    fun pausePlayback() {
        audioPlayer.pause()
        _uiState.update { it.copy(playingSegmentId = null) }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}

/**
 * 时间线页 UI 状态。
 *
 * @param segments 按时间升序的片段（含段内转写与标记）。
 * @param groupByHalfHour true=按半小时分组展示（PRD 4.2 双视图）。
 */
data class TimelineUiState(
    val dayId: String = "",
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val dayState: DayState = DayState.IDLE,
    val settleProgress: Int = 0,
    val summaryStatus: SummaryStatus = SummaryStatus.NONE,
    val totalRecordMs: Long = 0L,
    val segments: List<SegmentUi> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val playingSegmentId: Long? = null,
    val groupByHalfHour: Boolean = false,
) {
    /** 是否展示「生成总结」入口（结算完成但总结未出，或总结进行中）。 */
    val canGenerateSummary: Boolean
        get() = dayState == DayState.DONE && summaryStatus != SummaryStatus.DONE

    /** 总结是否已生成（入口变为「查看总结」）。 */
    val isSummaryReady: Boolean
        get() = summaryStatus == SummaryStatus.DONE
}

/** 片段 + 段内转写 + 段内标记（分段卡片的一行数据，架构 2.5 SegmentCard）。 */
data class SegmentUi(
    val segment: AudioSegment,
    val transcripts: List<TranscriptSegment>,
    val markers: List<Marker>,
)
