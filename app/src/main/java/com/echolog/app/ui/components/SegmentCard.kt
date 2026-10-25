package com.echolog.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.model.TranscriptStatus
import com.echolog.app.ui.timeline.SegmentUi

/**
 * 分段卡片（架构 2.5，PRD 4.2）：起止时间 + 转写摘要 + 段内标记 + 播放。
 *
 * @param item 片段 + 段内转写 + 段内标记（[com.echolog.app.ui.timeline.DayTimelineViewModel] 聚合）。
 * @param playing 是否正在播放本段（按钮图标切换播放/暂停）。
 * @param onPlayClick 播放/暂停回调。
 * @param onMarkerClick 段内标记点击（定位到标记时刻，FR-104）。
 */
@Composable
fun SegmentCard(
    item: SegmentUi,
    playing: Boolean,
    onPlayClick: () -> Unit,
    onMarkerClick: (Marker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segment = item.segment
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.timeline_pause else R.string.timeline_play,
                    ),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 起止时间（本地时区 HH:mm，架构 7.2）
                Text(
                    text = "${TimeUtils.formatTime(segment.startMs)} - " +
                        TimeUtils.formatTime(segment.endMs) +
                        "（${TimeUtils.formatDurationCompact(segment.durationMs)}）",
                    style = MaterialTheme.typography.labelLarge,
                )
                // 转写摘要（未转写/失败时展示状态）
                val transcriptText = item.transcripts
                    .sortedBy { it.startMs }
                    .joinToString(separator = "") { it.text }
                    .trim()
                Text(
                    text = when {
                        transcriptText.isNotEmpty() -> transcriptText
                        segment.transcriptStatus == TranscriptStatus.FAILED ->
                            stringResource(R.string.timeline_transcript_failed)

                        else -> stringResource(R.string.timeline_transcript_pending)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // 段内标记（时间 + 备注）
                item.markers.forEach { marker ->
                    MarkerChip(
                        marker = marker,
                        onClick = { onMarkerClick(marker) },
                    )
                }
            }
        }
    }
}

/** 段内标记行：`⚑ HH:mm 备注`（备注为空时仅展示时间）。 */
@Composable
private fun MarkerChip(
    marker: Marker,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = marker.note.ifBlank { null }
    val label = if (note != null) {
        "${TimeUtils.formatTime(marker.atMs)} $note"
    } else {
        TimeUtils.formatTime(marker.atMs)
    }
    Text(
        text = "⚑ $label",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
            .padding(top = 2.dp)
            .clickable(onClick = onClick),
    )
}
