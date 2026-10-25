package com.echolog.app.ui.schedule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.domain.model.TimeRange

/**
 * 课表模板页（用户需求 4：模板录音）。
 *
 * 支持两条路径：
 * - 导入模板文件（SAF 选 `.txt`，内容格式 `HH:mm-HH:mm [备注]`）；
 * - 手工添加时间段（起止时间选择器 + 备注）。
 *
 * 模板仅在「模板录音」模式下生效；开关状态实时落库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTemplateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScheduleTemplateViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var editingRange by remember { mutableStateOf<TimeRange?>(null) }
    var addingRange by remember { mutableStateOf(false) }

    // 导入模板文件（SAF）
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFromFile(context, uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedule_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.schedule_cancel))
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
            Text(
                text = stringResource(R.string.schedule_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 启用开关 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.schedule_enable),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = state.template.enabled,
                        onCheckedChange = viewModel::setEnabled,
                    )
                }
            }

            // ── 导入模板 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.schedule_import),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.schedule_import_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            importLauncher.launch(arrayOf("text/plain", "text/*"))
                        },
                    ) {
                        Text(stringResource(R.string.schedule_import))
                    }
                }
            }

            // ── 时间段列表 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.schedule_summary, state.ranges.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { addingRange = true }) {
                    Text(stringResource(R.string.schedule_add_range))
                }
            }

            if (state.ranges.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.ranges.forEachIndexed { index, range ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = range.display(),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (range.label.isNotBlank()) {
                                    Text(
                                        text = range.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "${range.durationMinutes} 分钟",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editingRange = range }) {
                                Text(stringResource(R.string.schedule_edit_title))
                            }
                            TextButton(onClick = { viewModel.removeRange(index) }) {
                                Text(
                                    text = stringResource(R.string.schedule_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 新增时间段对话框 ──
    if (addingRange) {
        TimeRangeDialog(
            initial = null,
            onConfirm = { start, end, label ->
                viewModel.addRange(start, end, label)
                addingRange = false
            },
            onDismiss = { addingRange = false },
        )
    }

    // ── 编辑时间段对话框 ──
    editingRange?.let { range ->
        TimeRangeDialog(
            initial = range,
            onConfirm = { start, end, label ->
                viewModel.removeRange(state.ranges.indexOf(range))
                viewModel.addRange(start, end, label)
                editingRange = null
            },
            onDismiss = { editingRange = null },
        )
    }
}

/**
 * 时间段编辑对话框：起止时间选择 + 备注输入。
 *
 * @param initial 初始区间（新增时传 null）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangeDialog(
    initial: TimeRange?,
    onConfirm: (startMinute: Int, endMinute: Int, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val startState = rememberTimePickerState(
        initialHour = (initial?.startMinute ?: 8 * 60) / 60,
        initialMinute = (initial?.startMinute ?: 8 * 60) % 60,
        is24Hour = true,
    )
    val endState = rememberTimePickerState(
        initialHour = (initial?.endMinute ?: 9 * 60) / 60,
        initialMinute = (initial?.endMinute ?: 9 * 60) % 60,
        is24Hour = true,
    )
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.schedule_range_start),
                    style = MaterialTheme.typography.labelLarge,
                )
                TimePicker(state = startState)
                HorizontalDividerLine()
                Text(
                    text = stringResource(R.string.schedule_range_end),
                    style = MaterialTheme.typography.labelLarge,
                )
                TimePicker(state = endState)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.schedule_range_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        startState.hour * 60 + startState.minute,
                        endState.hour * 60 + endState.minute,
                        label,
                    )
                },
            ) {
                Text(stringResource(R.string.schedule_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.schedule_cancel))
            }
        },
    )
}

/** 对话框内分隔线（避免额外依赖 Material3 Divider 的实验性标注差异）。 */
@Composable
private fun HorizontalDividerLine() {
    androidx.compose.material3.HorizontalDivider()
}
