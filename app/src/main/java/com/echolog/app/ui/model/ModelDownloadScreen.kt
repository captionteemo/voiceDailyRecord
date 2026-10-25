package com.echolog.app.ui.model

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.R
import com.echolog.app.ai.model.ModelState

/**
 * 模型管理 / 下载引导页（架构 2.5，FR-012）。
 *
 * 两种入口共用：
 * - 设置页进入（[onBack] 非空）：查看状态、按需下载；
 * - 首次启动引导（[onSkip] 非空）：TopAppBar 无返回，底部提供「跳过」。
 *
 * 状态唯一来源 [ModelManager][com.echolog.app.ai.model.ModelManager]（经
 * [ModelDownloadViewModel] 聚合）；下载中状态由 [ModelManager.downloadProgress]
 * 事件驱动，失败可重试。
 *
 * @param onBack 返回（设置入口；引导态传 null 隐藏返回）。
 * @param onSkip 跳过引导（引导态；设置入口传 null）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    onBack: (() -> Unit)?,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: ModelDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 一次性提示（计量网络入队等）
    LaunchedEffect(state.messageRes) {
        val messageRes = state.messageRes ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(messageRes))
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) {
                            Text(stringResource(R.string.model_back))
                        }
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
                text = stringResource(R.string.model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ModelRowCard(
                name = stringResource(R.string.model_asr_name),
                row = state.asr,
                onRetry = viewModel::retry,
            )
            ModelRowCard(
                name = stringResource(R.string.model_llm_name),
                row = state.llm,
                onRetry = viewModel::retry,
            )

            // ── 底部操作：一键下载 / 跳过 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = viewModel::downloadAll,
                    enabled = !state.downloading && !state.allReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.model_download_all),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (onSkip != null) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.model_skip))
                    }
                }
            }
        }
    }
}

/** 单个模型行：名称 + 状态徽标 + 体积提示 + 下载进度 / 重试。 */
@Composable
private fun ModelRowCard(
    name: String,
    row: ModelRowState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = row.state == ModelState.ERROR, onClick = onRetry),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge)
                StateBadge(row)
            }
            if (row.sizeHint.isNotBlank()) {
                Text(
                    text = stringResource(R.string.model_size_hint, row.sizeHint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.state == ModelState.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { row.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 模型状态徽标（图标 + 文案；ERROR 态整行可点击重试）。 */
@Composable
private fun StateBadge(
    row: ModelRowState,
    modifier: Modifier = Modifier,
) {
    val (icon, label, tint) = when (row.state) {
        ModelState.MISSING -> Triple(
            Icons.Outlined.CloudDownload,
            stringResource(R.string.model_state_missing),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModelState.DOWNLOADING -> Triple(
            Icons.Outlined.Downloading,
            stringResource(R.string.model_state_downloading, row.progress),
            MaterialTheme.colorScheme.primary,
        )

        ModelState.READY -> Triple(
            Icons.Outlined.CheckCircle,
            stringResource(R.string.model_state_ready),
            MaterialTheme.colorScheme.primary,
        )

        ModelState.ERROR -> Triple(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.model_state_error),
            MaterialTheme.colorScheme.error,
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}
