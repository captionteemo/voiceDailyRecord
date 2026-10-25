package com.echolog.app.ai.model

import android.content.Context
import com.echolog.app.core.common.ApplicationScope
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.FileUtils
import com.echolog.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型状态机（架构 2.4/3.3 类图，FR-012）。
 *
 * 职责：
 * 1. 维护 [ModelKind] → [ModelState] 映射（UI 模型管理页直接观察 [modelStates]）；
 * 2. [isAsrReady]/[isLlmReady] 同步就绪判定（文件存在 + 体积下限）；
 * 3. [ensureModels] 对缺失模型发起下载（T04 直接经 [ModelDownloader]；
 *    T05 的 ModelDownloadWorker 可复用同一下载器并叠加 Wi-Fi 约束）。
 *
 * **权重来源：远端下载（不再内置进 APK）**。
 *
 * 早期版本把 1.35GB 权重打进 `assets/` 离线安装，但这让 APK 体积达到 1.6GB，
 * 无法分发到代码托管平台（GitHub 单文件 100MB 限制），故改为**用户自行下载**：
 * 权重从 ModelScope 拉取，解压到应用私有目录 `{filesDir}/models/`。
 *
 * 下载产物的完整性由两道闸门保证（BUG-11 修复成果，不可回退）：
 * 1. [ModelDownloader] 先写 `.part` 临时文件，**校验体积达标后原子 rename**
 *    到正式路径 —— 正式路径上的文件要么完整，要么不存在；
 * 2. [ModelEntry.minSizeBytes]（真实字节数的 90%）作为就绪门槛 —— 任何截断
 *    的半成品都无法通过判定，不会被误认为「模型已就绪」而让推理引擎加载失败
 *    后永不重试。
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: ModelDownloader,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val _modelStates = MutableStateFlow<Map<ModelKind, ModelState>>(emptyMap())

    /** 模型状态流（MISSING/DOWNLOADING/READY/ERROR）。 */
    val modelStates: StateFlow<Map<ModelKind, ModelState>> = _modelStates.asStateFlow()

    init {
        refreshStates()
    }

    /** 同步读取某类模型当前状态（不触发下载）。 */
    fun currentState(kind: ModelKind): ModelState =
        _modelStates.value[kind] ?: computeState(kind)

    /** ASR 模型是否就绪（主模型 + tokens.txt 均在且非空）。 */
    fun isAsrReady(): Boolean = entryOf(ModelKind.ASR)?.let { entry ->
        filesReady(FileUtils.asrModelDir(context), entry)
    } ?: false

    /** LLM 模型是否就绪（GGUF 文件在且非空）。 */
    fun isLlmReady(): Boolean = entryOf(ModelKind.LLM)?.let { entry ->
        filesReady(FileUtils.llmModelDir(context), entry)
    } ?: false

    /** ASR 模型目录。 */
    fun asrModelDir(): File = FileUtils.asrModelDir(context)

    /** LLM 模型目录。 */
    fun llmModelDir(): File = FileUtils.llmModelDir(context)

    /** 重算并发布全部模型状态（应用启动 / 下载完成后调用）。 */
    fun refreshStates() {
        scope.launch(ioDispatcher) {
            publishStates()
        }
    }

    /**
     * 确保模型就绪：对 MISSING/ERROR 模型发起下载。
     *
     * **幂等**：已就绪（[filesReady] 通过体积门槛）的模型直接跳过，不重复下载
     * 1GB+ 权重；缺失/损坏的条目走 [ModelDownloader.download]（`.part` 断点续传 +
     * 原子 rename + 体积校验）。
     *
     * **异步执行**（[scope] 为应用级作用域）：本方法立即返回，下载在后台继续。
     * 调用方不应 await 下载结果——1.1GB 下载可能耗时十几分钟，远超
     * WorkManager 单次执行的 10 分钟上限（超时会被系统 kill）。
     *
     * 下载失败不抛异常，状态落 ERROR 由 UI 提示重试（未就绪时核心录音功能仍可用）。
     */
    fun ensureModels() {
        scope.launch(ioDispatcher) {
            val entries = ModelCatalog.builtInEntries
            var allReady = true
            for (entry in entries) {
                val targetDir = dirOf(entry.kind)
                // 1) 已就绪则跳过（体积门槛达标），避免重复下载 1GB+ 权重
                if (filesReady(targetDir, entry)) {
                    publishState(entry.kind, ModelState.READY)
                    continue
                }
                // 2) 缺失/损坏 → 从 ModelScope 下载（伴随文件如 tokens.txt 同步下载）
                publishState(entry.kind, ModelState.DOWNLOADING)
                val result = downloader.download(entry, targetDir) { progress ->
                    // 下载进度经状态流之外的通道透出（T05 ModelDownloadScreen 可扩展
                    // 为带进度的状态；当前进度事件保留在下载器回调内）
                    _downloadProgress.tryEmit(entry.kind to progress)
                }
                if (result.isSuccess) {
                    publishState(entry.kind, ModelState.READY)
                } else {
                    allReady = false
                    publishState(entry.kind, ModelState.ERROR)
                }
            }
            settingsRepository.setModelsReady(allReady)
            publishStates()
        }
    }

    /** 下载进度事件流（kind → 0~100）。 */
    private val _downloadProgress = kotlinx.coroutines.flow.MutableSharedFlow<Pair<ModelKind, Int>>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val downloadProgress: kotlinx.coroutines.flow.SharedFlow<Pair<ModelKind, Int>> = _downloadProgress

    private suspend fun publishStates() {
        val states = ModelKind.entries.associateWith { kind -> computeState(kind) }
        _modelStates.value = states
        settingsRepository.setModelsReady(states.values.all { it == ModelState.READY })
    }

    private suspend fun publishState(kind: ModelKind, state: ModelState) {
        _modelStates.value = _modelStates.value + (kind to state)
    }

    private fun computeState(kind: ModelKind): ModelState {
        val entry = entryOf(kind) ?: return ModelState.ERROR
        return if (filesReady(dirOf(kind), entry)) ModelState.READY else ModelState.MISSING
    }

    /**
     * 就绪判定：主文件体积达到 [ModelEntry.minSizeBytes] 门槛，且伴随文件
     * （如 ASR 的 tokens.txt）存在且非空。
     *
     * 体积门槛是 BUG-11 的核心防线：截断的半成品文件长度不足 → 判定未就绪 →
     * 重新下载，而不是被误判为「已就绪」后推理引擎加载失败且永不重试。
     */
    private fun filesReady(dir: File, entry: ModelEntry): Boolean {
        val main = File(dir, entry.fileName)
        if (!main.isFile || main.length() < entry.minSizeBytes) return false
        return entry.additionalFiles.all { extra ->
            File(dir, extra).let { it.isFile && it.length() > 0L }
        }
    }

    private fun entryOf(kind: ModelKind): ModelEntry? =
        ModelCatalog.builtInEntries.firstOrNull { it.kind == kind }

    private fun dirOf(kind: ModelKind): File = when (kind) {
        ModelKind.ASR -> FileUtils.asrModelDir(context)
        ModelKind.LLM -> FileUtils.llmModelDir(context)
    }
}

/** 模型类别。 */
enum class ModelKind { ASR, LLM }

/** 模型状态（架构 3.3 类图）。 */
enum class ModelState {
    /** 文件缺失。 */
    MISSING,

    /** 下载中。 */
    DOWNLOADING,

    /** 就绪（文件校验通过）。 */
    READY,

    /** 下载/校验失败，可重试。 */
    ERROR,
}
