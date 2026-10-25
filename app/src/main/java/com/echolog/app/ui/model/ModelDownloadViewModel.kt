package com.echolog.app.ui.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.ai.model.ModelKind
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.ai.model.ModelState
import com.echolog.app.ai.model.ModelCatalog
import com.echolog.app.core.util.FileUtils
import com.echolog.app.R
import com.echolog.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 模型下载引导 ViewModel（架构 2.5，FR-012）。
 *
 * 状态透出 [ModelManager.modelStates] + [ModelManager.downloadProgress]；
 * 下载动作分两路：
 * - Wi-Fi 下：直接 [ModelManager.ensureModels]（前台即时反馈进度）；
 * - 计量网络：经 [WorkScheduler.scheduleModelDownload] 入队
 *   （NetworkType.UNMETERED 约束 + 退避重试，避免消耗蜂窝流量）。
 */
@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    /** 下载进度累积（ModelManager.downloadProgress 为单模型事件流，需按 kind 汇总）。 */
    private val downloadProgressMap = MutableStateFlow<Map<ModelKind, Int>>(emptyMap())

    init {
        viewModelScope.launch {
            modelManager.downloadProgress.collect { (kind, percent) ->
                downloadProgressMap.update { it + (kind to percent) }
            }
        }
        viewModelScope.launch {
            combine(
                modelManager.modelStates,
                downloadProgressMap,
            ) { states, progress ->
                ModelUiState(
                    asr = states.toRowState(ModelKind.ASR, progress[ModelKind.ASR]),
                    llm = states.toRowState(ModelKind.LLM, progress[ModelKind.LLM]),
                    allReady = states.isNotEmpty() &&
                        states.values.all { it == ModelState.READY },
                    downloading = states.values.any { it == ModelState.DOWNLOADING },
                    messageRes = _uiState.value.messageRes,
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    /** 「一键下载全部」：Wi-Fi 直接下载，计量网络转后台队列。 */
    fun downloadAll() {
        if (_uiState.value.downloading) return
        if (isOnUnmeteredNetwork()) {
            modelManager.ensureModels()
        } else {
            workScheduler.scheduleModelDownload()
            _uiState.update {
                it.copy(messageRes = R.string.model_queued_message)
            }
        }
    }

    /** 重试（失败态点击）：直接触发（用户主动意图，允许任意网络）。 */
    fun retry() {
        modelManager.ensureModels()
    }

    /** 消费一次性提示。 */
    fun consumeMessage() {
        _uiState.update { it.copy(messageRes = null) }
    }

    /** 当前网络是否为非计量（Wi-Fi/以太网）；取不到按非计量放行。 */
    private fun isOnUnmeteredNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** 模型状态 → 展示行（体积提示取内置清单期望体积）。 */
    private fun Map<ModelKind, ModelState>.toRowState(
        kind: ModelKind,
        progress: Int?,
    ): ModelRowState {
        val state = this[kind] ?: ModelState.MISSING
        val entry = ModelCatalog.builtInEntries.firstOrNull { it.kind == kind }
        return ModelRowState(
            kind = kind,
            state = state,
            progress = progress ?: 0,
            sizeHint = entry?.let { FileUtils.formatBytes(it.sizeBytes) } ?: "",
        )
    }
}

/**
 * 模型页 UI 状态。
 *
 * @param downloading 任一模型下载中（按钮置灰防重复触发）。
 * @param messageRes 一次性提示文案资源（Snackbar 消费后置空）。
 */
data class ModelUiState(
    val asr: ModelRowState = ModelRowState(ModelKind.ASR),
    val llm: ModelRowState = ModelRowState(ModelKind.LLM),
    val allReady: Boolean = false,
    val downloading: Boolean = false,
    val messageRes: Int? = null,
)

/** 单个模型行的展示状态。 */
data class ModelRowState(
    val kind: ModelKind,
    val state: ModelState = ModelState.MISSING,
    val progress: Int = 0,
    val sizeHint: String = "",
)
