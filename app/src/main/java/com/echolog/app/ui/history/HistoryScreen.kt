package com.echolog.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.DayState
import com.echolog.app.ui.components.EmptyState

/**
 * 历史列表页（架构 2.5，PRD 4.4，FR-016）。
 *
 * 按自然月分组倒序；每天展示日期、总录音时长、摘要首句与未完成待办徽标；
 * 左滑/长按删除经二次确认（FR-106：音频/文字可分别删）。
 *
 * @param onOpenDay 打开某天（跳转当天时间线）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenDay: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性提示
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 导航事件
    LaunchedEffect(state.navigateToDayId) {
        val dayId = state.navigateToDayId ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onOpenDay(dayId)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.isEmpty) {
            EmptyState(
                title = stringResource(R.string.history_empty_title),
                description = stringResource(R.string.history_empty_desc),
                actionLabel = null,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.monthGroups.forEach { group ->
                    item(key = "month-${group.monthLabel}") {
                        Text(
                            text = group.monthLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(
                        count = group.days.size,
                        key = { index -> group.days[index].day.dayId },
                    ) { index ->
                        val item = group.days[index]
                        HistoryDayCard(
                            item = item,
                            onClick = { viewModel.openDay(item.day.dayId) },
                            onLongClick = { viewModel.requestDelete(item.day) },
                        )
                    }
                }
            }
        }
    }

    // 删除二次确认（FR-106：三种粒度）
    val pending = state.pendingDelete
    if (pending != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.history_delete_day, pending.dayId)) },
            text = { Text(stringResource(R.string.history_delete_confirm_desc)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        viewModel.confirmDelete(deleteAudio = true, deleteText = false)
                    }) {
                        Text(stringResource(R.string.history_delete_audio_only))
                    }
                    TextButton(onClick = {
                        viewModel.confirmDelete(deleteAudio = false, deleteText = true)
                    }) {
                        Text(stringResource(R.string.history_delete_text_only))
                    }
                    TextButton(onClick = {
                        viewModel.confirmDelete(deleteAudio = true, deleteText = true)
                    }) {
                        Text(
                            text = stringResource(R.string.history_delete_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
}

/** 历史列表的一天（PRD 4.4：日期 + 时长 + 摘要首句 + 待办徽标）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryDayCard(
    item: HistoryDay,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = TimeUtils.formatDayLabel(item.day.dayId),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StateBadge(item)
                    if (item.openTodoCount > 0) {
                        Text(
                            text = "· ${item.openTodoCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    R.string.summary_total_duration,
                    TimeUtils.formatDurationCompact(item.day.totalRecordMs),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.summaryPreview?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 日状态徽标（监听中/结算中/已完成）。 */
@Composable
private fun StateBadge(
    item: HistoryDay,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when {
        item.isMonitoring -> stringResource(R.string.history_state_monitoring) to
            MaterialTheme.colorScheme.primary

        item.isSettling -> stringResource(R.string.history_state_settling) to
            MaterialTheme.colorScheme.secondary

        item.isDone -> stringResource(R.string.history_state_done) to
            MaterialTheme.colorScheme.onSurfaceVariant

        else -> stringResource(R.string.history_state_idle) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}
