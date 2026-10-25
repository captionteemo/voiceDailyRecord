package com.echolog.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.ui.components.BreathingRecordButton
import com.echolog.app.ui.components.DayStatusHeader
import com.echolog.app.ui.components.MarkerNoteDialog

/**
 * 今天页（架构 2.5/4.1，PRD 4.1）：待机 / 监听·录音 / 结算中 / 已归档四态。
 *
 * 运行态唯一来源 [com.echolog.app.service.RecordingStateHolder]（经
 * [TodayViewModel] 聚合）；隐私确认在入口层（MainActivity）完成后才进入本页。
 *
 * @param onOpenTimeline 跳转当天时间线。
 * @param onOpenSummary 跳转当天总结。
 * @param onOpenTranscript 跳转当天转写全文。
 * @param onOpenModelManager 跳转模型管理（模型未就绪提示条）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenTimeline: (String) -> Unit,
    onOpenSummary: (String) -> Unit,
    onOpenTranscript: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMarkerDialog by rememberSaveable { mutableStateOf(false) }

    // 一次性提示（打点成功/失败/恢复等）
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.today_today_date,
                            TimeUtils.formatDayLabel(state.dayId),
                        ),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            when {
                // ── 结算中：进度卡（架构 7.5：UI 只读 settleProgress） ──
                state.isSettling -> SettlingCard(
                    progress = state.settleProgress,
                    error = state.settleError,
                    onRetry = viewModel::retrySettle,
                )

                // ── 暂停态（用户需求 2）：状态头 + 继续/结束 ──
                state.isPaused -> {
                    DayStatusHeader(
                        serviceState = state.serviceState,
                        monitoredMs = state.monitoredMs,
                        segmentCount = state.segmentCount,
                        markerCount = state.markerCount,
                    )
                    Text(
                        text = stringResource(R.string.today_paused_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = viewModel::resumeMonitoring,
                        enabled = !state.starting,
                    ) {
                        Text(stringResource(R.string.today_resume))
                    }
                    OutlinedButton(
                        onClick = viewModel::stopMonitoringAndSettle,
                        enabled = !state.stopping,
                    ) {
                        Text(stringResource(R.string.today_stop_settle))
                    }
                }

                // ── 运行态：状态头 + 呼吸按钮 + 打点 ──
                state.isServiceActive -> {
                    DayStatusHeader(
                        serviceState = state.serviceState,
                        monitoredMs = state.monitoredMs,
                        segmentCount = state.segmentCount,
                        markerCount = state.markerCount,
                    )
                    ModeHintText(state.recordingMode)
                    if (state.currentSegmentMs > 0L) {
                        Text(
                            text = "${stringResource(R.string.today_status_recording)} " +
                                TimeUtils.formatDurationClock(state.currentSegmentMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    BreathingRecordButton(
                        serviceState = state.serviceState,
                        settleProgress = 0,
                        enabled = !state.stopping,
                        onClick = viewModel::stopMonitoringAndSettle,
                    )
                    // 用户需求 4 第四种：手动模式提供 App 内录音开关
                    if (state.recordingMode == RecordingMode.MANUAL) {
                        FilledTonalButton(
                            onClick = viewModel::toggleManualRecording,
                            enabled = !state.stopping,
                        ) {
                            Text(
                                stringResource(
                                    if (state.manualRecordingOn) {
                                        R.string.today_stop_record_now
                                    } else {
                                        R.string.today_start_record_now
                                    },
                                ),
                            )
                        }
                    }
                    FilledTonalButton(onClick = { showMarkerDialog = true }) {
                        Text(stringResource(R.string.today_add_marker))
                    }
                    OutlinedButton(
                        onClick = viewModel::pauseMonitoring,
                        enabled = !state.stopping,
                    ) {
                        Text(stringResource(R.string.today_pause))
                    }
                }

                // ── 已归档：总结/时间线入口 ──
                state.isDayDone -> {
                    if (state.summaryStatus == SummaryStatus.DONE) {
                        Text(
                            text = stringResource(R.string.today_summary_ready),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.today_no_summary),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // BUG-11 修复：合并降级场景当天是 DONE/100，但 DB 留有
                    // 「合并音频失败，转写已完成」的备注——此处必须展示，
                    // 否则用户随后去导出/下载合并音频时会扑空却毫无预兆。
                    if (state.showSettleNoteWhenDone) {
                        SettleNoteCard(note = state.settleError.orEmpty())
                    }
                    BreathingRecordButton(
                        serviceState = state.serviceState,
                        settleProgress = 0,
                        enabled = !state.starting,
                        onClick = viewModel::startMonitoring,
                    )
                    OutlinedButton(onClick = { onOpenTimeline(state.dayId) }) {
                        Text(stringResource(R.string.today_view_timeline))
                    }
                    OutlinedButton(onClick = { onOpenTranscript(state.dayId) }) {
                        Text(stringResource(R.string.today_view_transcript))
                    }
                    Button(onClick = { onOpenSummary(state.dayId) }) {
                        Text(stringResource(R.string.today_view_summary))
                    }
                }

                // ── 待机：日期 + 提示 + 开始按钮 ──
                else -> {
                    Text(
                        text = stringResource(
                            R.string.today_today_date,
                            TimeUtils.formatDayLabel(state.dayId),
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    ModeHintText(state.recordingMode)
                    BreathingRecordButton(
                        serviceState = state.serviceState,
                        settleProgress = 0,
                        enabled = !state.starting,
                        onClick = viewModel::startMonitoring,
                    )
                }
            }

            // ── 服务中断恢复（DB MONITORING 但服务未运行，NFR-003 应用内路径） ──
            if (state.needsRecovery) {
                RecoveryCard(onResume = viewModel::resumeMonitoring)
            }

            // ── 模型未就绪提示（FR-012） ──
            if (!state.modelsReady) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.today_model_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenModelManager) {
                            Text(stringResource(R.string.today_model_action))
                        }
                    }
                }
            }
        }
    }

    if (showMarkerDialog) {
        MarkerNoteDialog(
            onConfirm = { note ->
                viewModel.addMarker(note)
                showMarkerDialog = false
            },
            onDismiss = { showMarkerDialog = false },
        )
    }
}

/**
 * 模式提示文案（用户需求 4）。
 *
 * 待机态展示「当前模式如何工作」，运行态展示模式说明；
 * 自动模式沿用通用文案，其余模式给出针对性提示。
 */
@Composable
private fun ModeHintText(mode: RecordingMode) {
    val text = when (mode) {
        RecordingMode.ALL_DAY -> stringResource(R.string.today_mode_hint_all_day)
        RecordingMode.SCHEDULE_TEMPLATE -> stringResource(R.string.today_mode_hint_schedule)
        RecordingMode.MANUAL -> stringResource(R.string.today_mode_hint_manual)
        RecordingMode.AUTO_VAD -> stringResource(R.string.today_hint_idle)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** 结算进行中卡片：进度条 + 失败时的重试入口。 */
@Composable
private fun SettlingCard(
    progress: Int,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.today_settling),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.today_settle_progress, progress),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!error.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.today_settle_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.today_retry_settle))
                }
            }
        }
    }
}

/**
 * 结算备注卡片（BUG-11 修复）。
 *
 * 用于「已归档但有非致命备注」的场景（如合并音频失败、转写已完成）：
 * 语气是提示而非错误，**不提供重试按钮**——合并失败是确定性的，
 * 重试只会再次失败。
 */
@Composable
private fun SettleNoteCard(
    note: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 服务中断恢复卡片（NFR-003：HealthCheckWorker 周期之外的手动恢复入口）。 */
@Composable
private fun RecoveryCard(
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.today_recover_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.today_recover_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(modifier = Modifier.padding(start = 8.dp)) {
                Button(onClick = onResume) {
                    Text(stringResource(R.string.today_recover_action))
                }
            }
        }
    }
}
