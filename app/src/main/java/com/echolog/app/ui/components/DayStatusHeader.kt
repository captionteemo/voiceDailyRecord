package com.echolog.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.service.RecordingServiceState

/**
 * 监听态头部（架构 2.5，PRD 4.1）：已监听时长 + 今日片段数 + 打点数。
 *
 * 仅监听/录音态展示；待机态由 TodayScreen 展示日期与提示语。
 *
 * @param serviceState 服务运行态（决定状态文案）。
 * @param monitoredMs 已监听时长（epoch 时长语义）。
 * @param segmentCount 今日片段数。
 * @param markerCount 今日打点数。
 */
@Composable
fun DayStatusHeader(
    serviceState: RecordingServiceState,
    monitoredMs: Long,
    segmentCount: Int,
    markerCount: Int,
    modifier: Modifier = Modifier,
) {
    val recording = serviceState == RecordingServiceState.RECORDING
    val paused = serviceState == RecordingServiceState.PAUSED
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = when {
                recording -> MaterialTheme.colorScheme.secondaryContainer
                paused -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        recording -> R.string.today_status_recording
                        paused -> R.string.today_status_paused
                        else -> R.string.today_status_listening
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = TimeUtils.formatDurationClock(monitoredMs),
                style = MaterialTheme.typography.headlineMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem(
                    label = stringResource(R.string.today_segments_stat),
                    value = segmentCount.toString(),
                )
                StatItem(
                    label = stringResource(R.string.today_markers_stat),
                    value = markerCount.toString(),
                )
            }
        }
    }
}

/** 单个统计项（数值 + 标签，架构 2.5 DayStatusHeader 内部）。 */
@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
