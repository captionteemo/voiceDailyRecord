package com.echolog.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils

/**
 * 清理缓存区块（用户需求 2）。
 *
 * 三个一键操作，各带二次确认：
 * 1. 删除所有录音原文件（`.wav`）——保留转写文字与总结；
 * 2. 清除某天「转化后」的产物——先选日期，再确认；
 * 3. 清除所有天的总结内容——保留录音与转写文字。
 */
@Composable
fun CleanupSection(
    modifier: Modifier = Modifier,
    viewModel: CleanupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDayPicker by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(R.string.cleanup_title),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.cleanup_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── 录音原文件 ──
        Text(
            text = stringResource(R.string.cleanup_section_audio),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.cleanup_delete_all_audio_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = viewModel::requestDeleteAllAudio,
            enabled = !state.working,
        ) {
            Text(
                text = stringResource(R.string.cleanup_delete_all_audio),
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ── 转写文字 ──
        Text(
            text = stringResource(R.string.cleanup_section_transcript),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.cleanup_delete_day_transcript_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { showDayPicker = true },
            enabled = !state.working && state.days.isNotEmpty(),
        ) {
            Text(stringResource(R.string.cleanup_delete_day_transcript))
        }

        // ── 总结内容 ──
        Text(
            text = stringResource(R.string.cleanup_section_summary),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.cleanup_delete_all_summary_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = viewModel::requestDeleteAllSummaries,
            enabled = !state.working,
        ) {
            Text(
                text = stringResource(R.string.cleanup_delete_all_summary),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.working) {
            Text(
                text = stringResource(R.string.cleanup_working),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ── 二次确认 ──
    state.pendingAction?.let { action ->
        val descRes = when (action) {
            CleanupAction.DELETE_ALL_AUDIO -> R.string.cleanup_confirm_desc_all_audio
            CleanupAction.CLEAR_DAY_TRANSCRIPT -> R.string.cleanup_confirm_desc_day_transcript
            CleanupAction.DELETE_ALL_SUMMARY -> R.string.cleanup_confirm_desc_all_summary
        }
        AlertDialog(
            onDismissRequest = viewModel::cancelAction,
            title = { Text(stringResource(R.string.cleanup_confirm_title)) },
            text = {
                Text(
                    text = if (action == CleanupAction.CLEAR_DAY_TRANSCRIPT &&
                        state.targetDayId != null
                    ) {
                        stringResource(
                            R.string.cleanup_confirm_desc_day_transcript,
                        ) + "（${state.targetDayId}）"
                    } else {
                        stringResource(descRes)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAction) {
                    Text(
                        text = stringResource(R.string.schedule_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelAction) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
        )
    }

    // ── 选择日期 ──
    if (showDayPicker) {
        AlertDialog(
            onDismissRequest = { showDayPicker = false },
            title = { Text(stringResource(R.string.cleanup_pick_day)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.days.isEmpty()) {
                        Text(stringResource(R.string.cleanup_no_days))
                    } else {
                        state.days.forEach { day ->
                            TextButton(
                                onClick = {
                                    showDayPicker = false
                                    viewModel.requestClearDayTranscript(day.dayId)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = TimeUtils.formatDayLabel(day.dayId),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDayPicker = false }) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
        )
    }
}
