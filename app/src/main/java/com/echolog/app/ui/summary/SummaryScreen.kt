package com.echolog.app.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.TodoType
import com.echolog.app.ui.components.EmptyState
import com.echolog.app.ui.components.SummarySectionCard
import com.echolog.app.ui.components.TodoRow
import com.echolog.app.ui.summary.displayLabel

/**
 * 总结详情页（架构 2.5/4.2，PRD 4.3，FR-011）。
 *
 * 顶部日期 + 总时长 + 待办徽标；三张分区卡片（摘要 / 关键时间线 /
 * 待办·决策·约定）；底部操作栏（播放全天合并录音 / 删除当天）。
 *
 * @param dayId 目标录音日（导航参数）。
 * @param onBack 返回（删除当天后由状态归空自动触发）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    dayId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(dayId) { viewModel.load(dayId) }

    // 一次性提示
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 删除成功后数据流归空 → 自动退出页面
    LaunchedEffect(state.loaded, state.summary) {
        if (state.loaded && state.summary == null && state.dayState == DayState.IDLE) {
            onBack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.summary_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.model_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 顶部：日期 + 总时长 + 待办徽标 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = TimeUtils.formatDayLabel(state.dayId),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(
                        R.string.summary_total_duration,
                        TimeUtils.formatDurationCompact(state.totalRecordMs),
                    ) + " · " + stringResource(R.string.summary_section_todos) +
                        " ${state.openTodoCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                // 结算进行中：进度条
                state.isSettling -> {
                    Text(
                        text = stringResource(
                            R.string.summary_settling_desc,
                            state.settleProgress,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { state.settleProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 空态
                !state.hasSummary -> EmptyState(
                    title = stringResource(R.string.summary_empty),
                    description = stringResource(R.string.summary_empty_desc),
                    actionLabel = null,
                )

                // 内容态：三卡片
                else -> {
                    val summary = state.summary
                    if (summary != null && summary.status == SummaryRunStatus.DONE) {
                        // 摘要卡片
                        SummarySectionCard(
                            title = stringResource(R.string.summary_section_summary),
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(summary.summaryText))
                            },
                        ) {
                            Text(
                                text = summary.summaryText.ifBlank {
                                    stringResource(R.string.summary_empty)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.summary_model_badge,
                                    summary.modelName.ifBlank { "—" },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 关键时间线卡片
                        if (state.timeline.isNotEmpty()) {
                            SummarySectionCard(
                                title = stringResource(R.string.summary_section_timeline),
                                onCopy = {
                                    val text = state.timeline.joinToString("\n") { it.displayLabel() }
                                    clipboardManager.setText(AnnotatedString(text))
                                },
                            ) {
                                state.timeline.forEach { item ->
                                    Text(
                                        text = item.displayLabel(),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }

                        // 待办 / 决策 / 约定三卡片
                        SummarySectionCard(
                            title = stringResource(R.string.summary_section_todos),
                        ) {
                            val todos = state.todosOfType(TodoType.TODO)
                            if (todos.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.summary_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            todos.forEach { todo ->
                                TodoRow(
                                    item = todo,
                                    onToggleDone = { done ->
                                        viewModel.setTodoDone(todo.todoId, done)
                                    },
                                    onCopy = {
                                        clipboardManager.setText(AnnotatedString(todo.content))
                                    },
                                )
                            }
                        }
                        SummarySectionCard(
                            title = stringResource(R.string.summary_section_decisions),
                        ) {
                            val decisions = state.todosOfType(TodoType.DECISION)
                            if (decisions.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.summary_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            decisions.forEach { todo ->
                                TodoRow(
                                    item = todo,
                                    onToggleDone = {},
                                    onCopy = {
                                        clipboardManager.setText(AnnotatedString(todo.content))
                                    },
                                )
                            }
                        }
                        SummarySectionCard(
                            title = stringResource(R.string.summary_section_agreements),
                        ) {
                            val agreements = state.todosOfType(TodoType.AGREEMENT)
                            if (agreements.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.summary_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            agreements.forEach { todo ->
                                TodoRow(
                                    item = todo,
                                    onToggleDone = {},
                                    onCopy = {
                                        clipboardManager.setText(AnnotatedString(todo.content))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── 底部操作栏（PRD 4.3） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = viewModel::togglePlayAll,
                    enabled = !state.mergedAudioPath.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (state.playing) R.string.summary_pause_all
                            else R.string.summary_play_all,
                        ),
                    )
                }
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.summary_delete_day),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ── 导出全天转写（用户需求 3） ──
            OutlinedButton(
                onClick = viewModel::exportDayTranscript,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.export_day_transcript))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.history_delete_confirm)) },
            text = { Text(stringResource(R.string.history_delete_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteDay()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.history_delete_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
}
