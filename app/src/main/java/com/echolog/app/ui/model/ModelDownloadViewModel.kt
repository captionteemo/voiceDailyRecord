package com.echolog.app.ui.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.ai.model.ModelDownloader
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
 *
 * ## 重试提示的双通道
 *
 * 大文件下载会因网络抖动自动重试（见 [ModelDownloader]），重试期间**状态保持
 * [ModelState.DOWNLOADING]**（重试是同一次下载内部的事，不能闪回「未在下载」）。
 * 用户不该对着不动的进度条干等，故有两条通道把「正在重试」透到界面：
 *
 * | 通道 | 来源 | 用途 | 覆盖场景 |
 * |---|---|---|---|
 * | [ModelManager.retryEvents] | SharedFlow（replay=0） | Snackbar 即时提示 | 页面已打开时 |
 * | [ModelManager.retryStates] | StateFlow（持久） | 行内常驻文字 | 页面**后**打开时 |
 *
 * 事件流只在订阅期间收得到，而下载跑在应用级作用域里（用户可能十分钟后才打开
 * 页面），所以行内文字走 StateFlow 才不会漏；两者互补，缺一不可。
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
            // 下载失败的**即时**提示：原因文本是动态的（真实原因，如「HTTP 403」），
            // 不能用 string 资源承载，故直接携带文本。
            modelManager.errorEvents.collect { reason ->
                _uiState.update { it.copy(errorMessage = reason) }
            }
        }
        viewModelScope.launch {
            // 重试的**即时**提示（Snackbar）：只在页面已打开时收得到，
            // 因此它只是「尽力而为」的补充；持久可见的行内文字走 retryStates。
            modelManager.retryEvents.collect { (kind, info) ->
                _uiState.update { it.copy(retryInfo = info.toUiModel(kind)) }
            }
        }
        viewModelScope.launch {
            combine(
                modelManager.modelStates,
                downloadProgressMap,
                modelManager.lastErrors,
                modelManager.retryStates,
            ) { states, progress, errors, retries ->
                ModelUiState(
                    asr = states.toRowState(
                        ModelKind.ASR,
                        progress[ModelKind.ASR],
                        errors[ModelKind.ASR],
                        retries[ModelKind.ASR],
                    ),
                    llm = states.toRowState(
                        ModelKind.LLM,
                        progress[ModelKind.LLM],
                        errors[ModelKind.LLM],
                        retries[ModelKind.LLM],
                    ),
                    allReady = states.isNotEmpty() &&
                        states.values.all { it == ModelState.READY },
                    // 重试期间状态恒为 DOWNLOADING ⇒ downloading 保持 true，
                    // 按钮保持置灰，不会闪回「未在下载」
                    downloading = states.values.any { it == ModelState.DOWNLOADING },
                )
            }.collect { state ->
                // 一次性提示字段**不能**由 combine 的产出决定：它们由另外两条
                // 通道（计量网络入队、下载失败事件、重试事件）写入，若这里覆盖就会把刚写进去的
                // 提示抹掉。用 update 在写入瞬间读取当前值，避免竞态。
                _uiState.update { current ->
                    state.copy(
                        messageRes = current.messageRes,
                        errorMessage = current.errorMessage,
                        retryInfo = current.retryInfo,
                    )
                }
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

    /** 消费一次性失败原因提示（Snackbar 展示后清空）。 */
    fun consumeErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** 消费一次性重试提示（Snackbar 展示后清空）。 */
    fun consumeRetryInfo() {
        _uiState.update { it.copy(retryInfo = null) }
    }

    /** 当前网络是否为非计量（Wi-Fi/以太网）；取不到按非计量放行。 */
    private fun isOnUnmeteredNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** 模型状态 → 展示行（体积提示取内置清单期望体积；失败原因透出真实原因文本）。 */
    private fun Map<ModelKind, ModelState>.toRowState(
        kind: ModelKind,
        progress: Int?,
        error: String?,
        retry: ModelDownloader.DownloadRetryInfo?,
    ): ModelRowState {
        val state = this[kind] ?: ModelState.MISSING
        val entry = ModelCatalog.builtInEntries.firstOrNull { it.kind == kind }
        return ModelRowState(
            kind = kind,
            state = state,
            progress = progress ?: 0,
            sizeHint = entry?.let { FileUtils.formatBytes(it.sizeBytes) } ?: "",
            error = error,
            // 重试提示只在「正在下载」时展示：下载结束后它就没有意义了
            // （成功已就绪、失败已有失败原因），留着会变成过期信息误导用户。
            retry = retry?.takeIf { state == ModelState.DOWNLOADING }?.toUiModel(kind),
        )
    }

    /** 下载器层的重试快照 → UI 层模型（隔离 ai.model 的内部类型，避免 UI 直接依赖）。 */
    private fun ModelDownloader.DownloadRetryInfo.toUiModel(kind: ModelKind): ModelRetryInfo =
        ModelRetryInfo(
            kind = kind,
            attempt = attempt,
            maxAttempts = maxAttempts,
            reason = reason,
        )
}

/**
 * 模型页 UI 状态。
 *
 * @param downloading 任一模型下载中（按钮置灰防重复触发）。
 * @param messageRes 一次性提示文案资源（Snackbar 消费后置空）。
 * @param errorMessage 一次性失败原因**动态文本**（Snackbar 消费后置空）。
 *   与 [messageRes] 分开：失败原因是真实原因（如「下载失败：Connection reset」），
 *   无法预先做成 string 资源。
 * @param retryInfo 一次性重试提示（Snackbar 消费后置空）。行内常驻的重试文字见
 *   [ModelRowState.retry]（来自持久 StateFlow，页面后开也可见）。
 */
data class ModelUiState(
    val asr: ModelRowState = ModelRowState(ModelKind.ASR),
    val llm: ModelRowState = ModelRowState(ModelKind.LLM),
    val allReady: Boolean = false,
    val downloading: Boolean = false,
    val messageRes: Int? = null,
    val errorMessage: String? = null,
    val retryInfo: ModelRetryInfo? = null,
)

/**
 * 单个模型行的展示状态。
 *
 * @param error 该模型最近一次下载失败的原因（真实原因文本）；成功或未失败时为 null。
 * @param retry 该模型当前的重试提示（仅在下载中非 null）；用于行内常驻显示
 *   「网络不稳定，正在重试（第 N/M 次）」，避免用户对着不动的进度条干等。
 */
data class ModelRowState(
    val kind: ModelKind,
    val state: ModelState = ModelState.MISSING,
    val progress: Int = 0,
    val sizeHint: String = "",
    val error: String? = null,
    val retry: ModelRetryInfo? = null,
)

/**
 * 重试提示（UI 层模型）。
 *
 * @param attempt 已连续失败的次数（1 = 第一次尝试失败，即将进行第 2 次尝试）。
 * @param maxAttempts 总尝试次数上限（含首次）。
 * @param reason 本次失败的**真实原因**（动态文本，如「Software caused connection abort」）。
 */
data class ModelRetryInfo(
    val kind: ModelKind,
    val attempt: Int,
    val maxAttempts: Int,
    val reason: String,
)
