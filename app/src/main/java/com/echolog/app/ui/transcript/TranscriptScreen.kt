package com.echolog.app.ui.transcript

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.echolog.app.data.media.AudioPlayer
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.ui.components.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 转写全文页（用户需求：转写全文）。
 *
 * 结构：顶栏（标题 + 返回）+ 工具区（复制全部 / 重新生成总结 + 总结状态）
 * + 正文（当天全部转写，按时间顺序连续阅读）。
 *
 * 交互：
 * - **点击整行** → 跳到该句对应的声音位置并播放（当前行高亮 + 播放图标）；
 * - **行尾编辑图标** → 就地编辑（多行输入 + 保存/取消），保存后**直接覆盖**原文；
 * - **长按整行** → 进入选择模式，可勾选多条，底部出现「已选 N 条 / 复制所选 / 退出选择」；
 * - **重新生成总结成功** → Snackbar 带「查看」按钮，点击跳转当天总结页。
 *
 * 反馈文案兜底：总结失败文案经 [TranscriptFeedbackText] 三级回落，
 * 保证任何情况下不给用户半截句子（静默失败红线）；复制成功走
 * [TranscriptMessage.CopySucceeded] 展示「已复制 N 条」。
 *
 * 播放器生命周期：页面持有 [AudioPlayer]，离开页面时经 [DisposableEffect] 释放。
 *
 * @param dayId 目标录音日（导航参数）。
 * @param onBack 返回上一页。
 * @param onOpenSummary 跳转当天总结页（重新生成总结成功后「查看」入口）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TranscriptScreen(
    dayId: String,
    onBack: () -> Unit,
    onOpenSummary: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TranscriptViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // 播放器：非线程安全，只在主线程调用（页面回调均为主线程）
    val audioPlayer = remember { AudioPlayer() }

    // 离开页面释放播放器
    DisposableEffect(Unit) {
        onDispose { audioPlayer.release() }
    }

    // 进入页面加载目标天
    LaunchedEffect(dayId) { viewModel.load(dayId) }

    // 文案在组合期取出（stringResource 只能在组合期调用）
    val nothingToCopyText = stringResource(R.string.transcript_nothing_to_copy)
    val savedText = stringResource(R.string.transcript_saved)
    val saveFailedText = stringResource(R.string.transcript_save_failed)
    val editEmptyText = stringResource(R.string.transcript_edit_empty)
    val summaryRegeneratedText = stringResource(R.string.transcript_regenerate_success)
    val viewSummaryText = stringResource(R.string.transcript_view_summary)
    val playbackUnavailableText = stringResource(R.string.transcript_play_unavailable)
    val summaryFailedTemplate = stringResource(R.string.transcript_regenerate_failed)
    val summaryFailedNoReasonText = stringResource(R.string.transcript_regenerate_failed_no_reason)
    val copiedCountTemplate = stringResource(R.string.transcript_copied_count)

    // 一次性提示（保存结果 / 复制结果 / 重新生成总结结果 / 播放失败）
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        when (message) {
            // 重新生成总结成功：带「查看」action，点击跳转当天总结页
            // （用户重新生成的预期就是立刻看到新总结，不给入口等于没反馈）
            TranscriptMessage.SummaryRegenerated -> {
                val result = snackbarHostState.showSnackbar(
                    message = summaryRegeneratedText,
                    actionLabel = viewSummaryText,
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    onOpenSummary(dayId)
                }
            }

            else -> {
                val text = when (message) {
                    TranscriptMessage.Saved -> savedText
                    TranscriptMessage.SaveFailed -> saveFailedText
                    TranscriptMessage.EditEmpty -> editEmptyText
                    // 三级回落兜底：不得给用户「重新生成总结失败：」这样的半截文案
                    is TranscriptMessage.SummaryRegenerateFailed ->
                        TranscriptFeedbackText.summaryFailedText(
                            template = summaryFailedTemplate,
                            noReasonText = summaryFailedNoReasonText,
                            reason = message.reason,
                        )

                    is TranscriptMessage.CopySucceeded ->
                        String.format(copiedCountTemplate, message.count)

                    TranscriptMessage.PlaybackUnavailable -> playbackUnavailableText
                    // 上面已分流，不会到达；保留分支保证 when 完备
                    TranscriptMessage.SummaryRegenerated -> summaryRegeneratedText
                }
                snackbarHostState.showSnackbar(text)
            }
        }
        viewModel.consumeMessage()
    }

    // 播放自然结束时清除高亮
    LaunchedEffect(state.playingTsId) {
        if (state.playingTsId == null) return@LaunchedEffect
        while (audioPlayer.isPlaying) {
            delay(PLAYBACK_POLL_INTERVAL_MS)
        }
        viewModel.onPlaybackStopped()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transcript_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.model_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.selectionMode) {
                TranscriptSelectionBar(
                    selectedCount = state.selectedCount,
                    onCopySelected = {
                        val text = viewModel.copySelected()
                        if (text == null) {
                            scope.launch { snackbarHostState.showSnackbar(nothingToCopyText) }
                        } else {
                            clipboardManager.setText(AnnotatedString(text))
                            // 成功反馈走 VM 的 CopySucceeded 消息（见上方 LaunchedEffect）
                        }
                    },
                    onExit = viewModel::exitSelectionMode,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // ── 顶部：日期 + 总结状态 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = TimeUtils.formatDayLabel(state.dayId),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(
                        R.string.transcript_summary_status_label,
                        state.summaryStatus.displayText(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 工具区：复制全部 / 重新生成总结 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val text = viewModel.copyAll()
                        if (text == null) {
                            scope.launch { snackbarHostState.showSnackbar(nothingToCopyText) }
                        } else {
                            clipboardManager.setText(AnnotatedString(text))
                            // 成功反馈走 VM 的 CopySucceeded 消息（见上方 LaunchedEffect）
                        }
                    },
                    enabled = state.hasTranscript,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.transcript_copy_all),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Button(
                    onClick = viewModel::regenerateSummary,
                    enabled = !state.regeneratingSummary,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.regeneratingSummary) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = stringResource(R.string.transcript_regenerating),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.transcript_regenerate_summary),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            // ── 正文 ──
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                !state.hasTranscript -> EmptyState(
                    title = stringResource(R.string.transcript_empty),
                    description = stringResource(R.string.transcript_empty_desc),
                    actionLabel = null,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = state.lines,
                        key = { it.tsId },
                    ) { line ->
                        TranscriptLineRow(
                            line = line,
                            state = state,
                            onClick = {
                                if (state.selectionMode) {
                                    viewModel.toggleSelection(line.tsId)
                                } else if (state.isPlaying(line.tsId) && audioPlayer.isPlaying) {
                                    audioPlayer.pause()
                                    viewModel.onPlaybackStopped()
                                } else {
                                    val target = viewModel.onTranscriptClicked(line.tsId)
                                    if (target != null) {
                                        runCatching {
                                            audioPlayer.play(target.path, target.offsetMs)
                                        }.onFailure { viewModel.onPlaybackFailed() }
                                    }
                                }
                            },
                            onLongClick = { viewModel.onTranscriptLongPressed(line.tsId) },
                            onStartEdit = { viewModel.startEditing(line.tsId) },
                            onEditingTextChange = viewModel::updateEditingText,
                            onSaveEdit = viewModel::saveEdit,
                            onCancelEdit = viewModel::cancelEditing,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 转写单行。
 *
 * - 非编辑态：时间戳 `[HH:mm]` + 文字（+ 行尾编辑图标）；
 * - 编辑态：多行输入框 + 保存/取消；
 * - 选择模式：左侧勾选框替代编辑图标。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptLineRow(
    line: TranscriptLineUi,
    state: TranscriptUiState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStartEdit: () -> Unit,
    onEditingTextChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val playing = state.isPlaying(line.tsId)
    val editing = state.isEditing(line.tsId)

    Surface(
        color = if (playing) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (state.selectionMode) {
                Checkbox(
                    checked = state.isSelected(line.tsId),
                    onCheckedChange = { onClick() },
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = state.editingText,
                        onValueChange = onEditingTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text(stringResource(R.string.transcript_edit_label)) },
                    )
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = onSaveEdit,
                            enabled = !state.saving,
                        ) {
                            Text(stringResource(R.string.transcript_edit_save))
                        }
                        TextButton(onClick = onCancelEdit) {
                            Text(stringResource(R.string.transcript_edit_cancel))
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[${TimeUtils.formatTime(line.startMs)}]",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (playing) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.transcript_playing),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(16.dp),
                            )
                        }
                    }
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (!editing && !state.selectionMode) {
                IconButton(onClick = onStartEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.transcript_edit),
                    )
                }
            }
        }
    }
}

/**
 * 选择模式底部栏：「已选 N 条 / 复制所选 / 退出选择」。
 */
@Composable
private fun TranscriptSelectionBar(
    selectedCount: Int,
    onCopySelected: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.transcript_selected_count, selectedCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopySelected) {
                    Text(stringResource(R.string.transcript_copy_selected))
                }
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.transcript_exit_selection))
                }
            }
        }
    }
}

/** 总结状态 → 展示文案。 */
@Composable
private fun SummaryStatus.displayText(): String = when (this) {
    SummaryStatus.NONE -> stringResource(R.string.transcript_summary_none)
    SummaryStatus.PENDING -> stringResource(R.string.transcript_summary_pending)
    SummaryStatus.RUNNING -> stringResource(R.string.transcript_summary_running)
    SummaryStatus.DONE -> stringResource(R.string.transcript_summary_done)
    SummaryStatus.FAILED -> stringResource(R.string.transcript_summary_failed)
}

/** 播放结束轮询间隔（毫秒）。 */
private const val PLAYBACK_POLL_INTERVAL_MS = 300L
