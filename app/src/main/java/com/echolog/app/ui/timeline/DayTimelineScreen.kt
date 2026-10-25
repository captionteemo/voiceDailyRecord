package com.echolog.app.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.ui.components.EmptyState
import com.echolog.app.ui.components.SegmentCard
import com.echolog.app.ui.components.TimeRangeBlock
import com.echolog.app.ui.components.TimelineCanvas

/**
 * 当天时间线页（架构 2.5/4.2，PRD 4.2，FR-017/104）。
 *
 * 顶部全天时间轴（0:00–24:00 色块 + 旗标）+ 分段列表
 * （按片段 / 按半小时双视图）+ 结算完成后的「生成总结」入口。
 *
 * @param dayId 目标录音日（导航参数）。
 * @param onBack 返回（关闭页面）。
 * @param onOpenSummary 跳转当天总结。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayTimelineScreen(
    dayId: String,
    onBack: () -> Unit,
    onOpenSummary: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DayTimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 进入页面加载目标天
    LaunchedEffect(dayId) { viewModel.load(dayId) }

    // 点击时间轴/标记后的高亮定位区间（会话内保持，切天重置）
    var highlightStart by remember(dayId) { mutableStateOf<Long?>(null) }
    var highlightEnd by remember(dayId) { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.timeline_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.model_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // ── 全天时间轴（PRD 4.2） ──
            if (state.segments.isNotEmpty()) {
                TimelineCanvas(
                    dayId = state.dayId,
                    blocks = state.segments.map {
                        TimeRangeBlock(it.segment.startMs, it.segment.endMs)
                    },
                    markers = state.markers,
                    highlightStartMs = highlightStart,
                    highlightEndMs = highlightEnd,
                    onSeekTo = { atMs ->
                        // 点击空白区：定位到最近片段并播放（FR-104 简化）
                        val target = state.segments
                            .minByOrNull { item ->
                                kotlin.math.abs(item.segment.startMs - atMs)
                            }
                        if (target != null) {
                            highlightStart = target.segment.startMs
                            highlightEnd = target.segment.endMs
                            viewModel.playSegment(target.segment, atMs)
                        }
                    },
                )
                HorizontalDivider()
            }

            // ── 视图切换 + 总结入口 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.groupByHalfHour,
                        onClick = { if (state.groupByHalfHour) viewModel.toggleGrouping() },
                        label = { Text(stringResource(R.string.timeline_mode_segment)) },
                    )
                    FilterChip(
                        selected = state.groupByHalfHour,
                        onClick = { if (!state.groupByHalfHour) viewModel.toggleGrouping() },
                        label = { Text(stringResource(R.string.timeline_mode_halfhour)) },
                    )
                }
                when {
                    state.isSummaryReady ->
                        FilledTonalButton(onClick = { onOpenSummary(state.dayId) }) {
                            Text(stringResource(R.string.timeline_summary_done))
                        }

                    state.canGenerateSummary ->
                        FilledTonalButton(onClick = { onOpenSummary(state.dayId) }) {
                            Text(stringResource(R.string.timeline_generate_summary))
                        }
                }
            }

            // ── 分段列表 ──
            if (state.segments.isEmpty() && !state.loading) {
                EmptyState(
                    title = stringResource(R.string.timeline_empty),
                    description = stringResource(R.string.today_hint_idle),
                    actionLabel = null,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.groupByHalfHour) {
                        // 按半小时分组（PRD 4.2）
                        val groups = state.segments.groupBy { item ->
                            val minutes = TimeUtils.startOfDayEpochMs(state.dayId)
                            (item.segment.startMs - minutes) / HALF_HOUR_MS
                        }
                        groups.forEach { (slot, items) ->
                            item(key = "header-$slot") {
                                Text(
                                    text = formatSlotLabel(slot),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            items(
                                items = items,
                                key = { it.segment.segmentId },
                            ) { item ->
                                SegmentCard(
                                    item = item,
                                    playing = state.playingSegmentId == item.segment.segmentId,
                                    onPlayClick = { viewModel.togglePlayback(item.segment) },
                                    onMarkerClick = { marker ->
                                        highlightStart = marker.atMs
                                        highlightEnd = item.segment.endMs
                                        viewModel.playSegment(item.segment, marker.atMs)
                                    },
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.segments,
                            key = { it.segment.segmentId },
                        ) { item ->
                            SegmentCard(
                                item = item,
                                playing = state.playingSegmentId == item.segment.segmentId,
                                onPlayClick = { viewModel.togglePlayback(item.segment) },
                                onMarkerClick = { marker ->
                                    highlightStart = marker.atMs
                                    highlightEnd = item.segment.endMs
                                    viewModel.playSegment(item.segment, marker.atMs)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 半小时分组标签：`HH:mm - HH:mm`。 */
private fun formatSlotLabel(slot: Long): String {
    val startMinutes = slot * 30L
    val endMinutes = startMinutes + 30L
    return "%02d:%02d - %02d:%02d".format(
        startMinutes / 60L,
        startMinutes % 60L,
        endMinutes / 60L,
        endMinutes % 60L,
    )
}

/** 半小时分组粒度（ms）。 */
private const val HALF_HOUR_MS = 30 * 60 * 1000L
