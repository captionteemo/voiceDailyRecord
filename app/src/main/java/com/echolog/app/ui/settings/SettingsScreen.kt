package com.echolog.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.FileUtils
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SensitivityLevel
import com.echolog.app.ui.util.FolderLauncher
import android.net.Uri

/**
 * 设置页（架构 2.5，PRD 4.5）：AI 与转写 / 隐私与通知 / 录音行为 / 存储 / 关于。
 *
 * 全部读写经 [SettingsViewModel]（DataStore，架构 2.2）；
 * P1 开关（云端增强/本地加密）置灰展示，仅持久化用户意图。
 *
 * @param onOpenModelManager 跳转模型管理页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenModelManager: () -> Unit,
    onOpenScheduleTemplate: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 用户需求 3：SAF 目录选择器（授予持久化读写授权）
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.setStorageTreeUri(uri)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── AI 与转写 ──
            SettingsSection(title = stringResource(R.string.settings_section_ai)) {
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_models),
                    subtitle = stringResource(R.string.settings_models_desc),
                    badge = if (state.allModelsReady) {
                        stringResource(R.string.model_state_ready)
                    } else {
                        stringResource(R.string.model_state_missing)
                    },
                    onClick = onOpenModelManager,
                )
            }

            // ── 隐私与通知 ──
            SettingsSection(title = stringResource(R.string.settings_section_privacy)) {
                Text(
                    text = stringResource(R.string.settings_privacy_mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_privacy_mode_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = state.settings.privacyMode == mode,
                            onClick = { viewModel.setPrivacyMode(mode) },
                        )
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                HorizontalDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_cloud_enhance),
                    subtitle = stringResource(R.string.settings_cloud_enhance_desc),
                    checked = state.settings.cloudEnhance,
                    enabled = false,
                    onCheckedChange = { viewModel.setCloudEnhance(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_encryption),
                    subtitle = stringResource(R.string.settings_encryption_desc),
                    checked = state.settings.encryption,
                    enabled = false,
                    onCheckedChange = { viewModel.setEncryption(it) },
                )
            }

            // ── 录音行为 ──
            SettingsSection(title = stringResource(R.string.settings_section_recording)) {
                Text(
                    text = stringResource(R.string.settings_recording_mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_recording_mode_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecordingMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = state.settings.recordingMode == mode,
                            onClick = { viewModel.setRecordingMode(mode) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = mode.desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // 模板录音：进入课表模板编辑页
                if (state.settings.recordingMode == RecordingMode.SCHEDULE_TEMPLATE) {
                    TextButton(onClick = onOpenScheduleTemplate) {
                        Text(stringResource(R.string.settings_schedule_template_edit))
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_silence_duration),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_silence_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val silenceOptions = listOf(
                    3000L to R.string.settings_silence_3s,
                    5000L to R.string.settings_silence_5s,
                    10000L to R.string.settings_silence_10s,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    silenceOptions.forEachIndexed { index, (durationMs, labelRes) ->
                        SegmentedButton(
                            selected = state.settings.silenceDurationMs == durationMs,
                            onClick = { viewModel.setSilenceDurationMs(durationMs) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = silenceOptions.size,
                            ),
                        ) {
                            Text(stringResource(labelRes))
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_sample_rate),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_sample_rate_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rateOptions = listOf(
                    8000 to R.string.settings_sample_rate_8k,
                    16000 to R.string.settings_sample_rate_16k,
                    44100 to R.string.settings_sample_rate_44k,
                    48000 to R.string.settings_sample_rate_48k,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    rateOptions.forEachIndexed { index, (rate, labelRes) ->
                        SegmentedButton(
                            selected = state.settings.sampleRate == rate,
                            onClick = { viewModel.setSampleRate(rate) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = rateOptions.size,
                            ),
                        ) {
                            Text(stringResource(labelRes))
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_sensitivity),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_sensitivity_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SensitivityLevel.entries.forEach { level ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = state.settings.sensitivity == level,
                            onClick = { viewModel.setSensitivity(level) },
                        )
                        Text(
                            text = level.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ── 存储 ──
            SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                Text(
                    text = stringResource(R.string.settings_storage_location),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (state.settings.storageTreeUri != null && state.storageLocationName != null) {
                        stringResource(
                            R.string.settings_storage_location_custom,
                            state.storageLocationName.orEmpty(),
                        )
                    } else {
                        stringResource(R.string.settings_storage_location_default)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_storage_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            folderPickerLauncher.launch(
                                state.settings.storageTreeUri?.let { Uri.parse(it) },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.settings_storage_pick))
                    }
                    if (state.settings.storageTreeUri != null) {
                        TextButton(onClick = viewModel::clearStorageLocation) {
                            Text(stringResource(R.string.settings_storage_reset))
                        }
                    }
                }
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                FolderLauncher.audioFolderIntent(
                                    context,
                                    state.settings.storageTreeUri,
                                ),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(FolderLauncher.fallbackDocumentTreeIntent())
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_storage_open))
                }
                HorizontalDivider()
                Text(
                    text = if (state.storageLoaded) {
                        stringResource(
                            R.string.settings_storage_used,
                            FileUtils.formatBytes(state.audioSizeBytes),
                            FileUtils.formatBytes(state.availableBytes),
                        )
                    } else {
                        stringResource(R.string.settings_storage_loading)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = viewModel::refreshStorage) {
                    Text(stringResource(R.string.settings_storage_refresh))
                }
            }

            // ── 清理缓存（用户需求 2） ──
            CleanupSection()

            // ── 关于 ──
            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                Text(
                    text = stringResource(R.string.settings_version, APP_VERSION),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_about_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 设置分区容器（标题 + 内容）。 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            content()
        }
    }
}

/** 开关行（P1 置灰：展示但不响应）。 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** 导航行（模型管理入口 + 就绪徽标）。 */
@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick) { Text(badge) }
    }
}

/** 版本号（MVP 硬编码；发布流水线可替换为 BuildConfig 注入）。 */
private const val APP_VERSION = "1.0.0"
