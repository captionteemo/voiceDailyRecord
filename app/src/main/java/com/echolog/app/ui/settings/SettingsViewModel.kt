package com.echolog.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echolog.app.ai.model.ModelKind
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.ai.model.ModelState
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.EchoLog
import com.echolog.app.core.util.FileUtils
import com.echolog.app.data.media.StorageLocationManager
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.RecordingSettings
import com.echolog.app.domain.model.SensitivityLevel
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.ServiceController
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
 * 设置页 ViewModel（架构 2.5，PRD 4.5）。
 *
 * 读写全部经 [SettingsRepository]（DataStore，架构 2.2）；
 * 存储统计经 [FileUtils] 只读文件系统（不碰 DAO）；
 * 模型状态透出 [ModelManager.modelStates]（设置页展示就绪徽标）。
 *
 * 用户需求 3：录音采样率、自选存储目录（SAF 持久授权）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val modelManager: ModelManager,
    private val storageLocationManager: StorageLocationManager,
    private val serviceController: ServiceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.observeSettings(),
                modelManager.modelStates,
            ) { settings, modelStates ->
                SettingsUiState(
                    settings = settings,
                    modelStates = modelStates,
                    audioSizeBytes = _uiState.value.audioSizeBytes,
                    availableBytes = _uiState.value.availableBytes,
                    storageLoaded = _uiState.value.storageLoaded,
                    storageLocationName = storageLocationManager.displayNameOf(
                        settings.storageTreeUri,
                    ),
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
        refreshStorage()
    }

    /** VAD 灵敏度（FR-204：低=需更响声音 / 中 / 高=轻微人声即触发）。 */
    fun setSensitivity(level: SensitivityLevel) {
        viewModelScope.launch { settingsRepository.setSensitivity(level) }
    }

    /** 静音断段时长（FR-003：3/5/10s）。 */
    fun setSilenceDurationMs(durationMs: Long) {
        viewModelScope.launch { settingsRepository.setSilenceDurationMs(durationMs) }
    }

    /** 录音采样率（用户需求 3；仅允许 [Constants.SAMPLE_RATE_OPTIONS] 内的值）。 */
    fun setSampleRate(sampleRate: Int) {
        viewModelScope.launch { settingsRepository.setSampleRate(sampleRate) }
    }

    /**
     * 设置自选存储目录（用户需求 3）：持久化 SAF 授权并落库。
     * 授权失败（Uri 失效）时忽略，保持原设置。
     */
    fun setStorageTreeUri(treeUri: Uri?) {
        viewModelScope.launch {
            if (treeUri == null) return@launch
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }.isSuccess
            if (persisted) {
                settingsRepository.setStorageTreeUri(treeUri.toString())
            }
        }
    }

    /** 恢复默认存储位置（应用内部目录）：释放持久化授权并清库。 */
    fun clearStorageLocation() {
        viewModelScope.launch {
            val current = settingsRepository.getSettings().storageTreeUri
            if (!current.isNullOrBlank()) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(current),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            settingsRepository.setStorageTreeUri(null)
        }
    }

    /** 隐私模式（FR-102：个人 / 会议）。 */
    fun setPrivacyMode(mode: PrivacyMode) {
        viewModelScope.launch { settingsRepository.setPrivacyMode(mode) }
    }

    /** 录音模式（用户需求 4：全天 / 自动 / 模板 / 手动）。 */
    fun setRecordingMode(mode: RecordingMode) {
        viewModelScope.launch { settingsRepository.setRecordingMode(mode) }
    }

    /** 云端增强开关（P1 FR-101 占位：UI 置灰，仅持久化意图）。 */
    fun setCloudEnhance(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCloudEnhance(enabled) }
    }

    /** 本地加密开关（P1 FR-103 占位：UI 置灰，仅持久化意图）。 */
    fun setEncryption(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEncryption(enabled) }
    }

    /**
     * 待命态快捷录音入口（用户需求：锁屏/状态栏一键开始录音；默认开启）。
     *
     * 打开时若入口尚未建立（例如还没结束过一次录音、服务未在待命），
     * 立即把常驻入口挂出来——否则「开关打开了却看不到入口」等同于设置静默失效。
     * 关闭时由服务自行退出（服务在待命态监听本设置），无需此处投递。
     *
     * **顺序不可颠倒**：必须先 `settingsRepository.set...` 再 `enterStandby()`。
     * 服务侧的待命监听是 `observeSettings().first { !it.quickRecordNotificationEnabled }`
     * （`RecordingService.startQuickRecordSettingWatch`），若先拉服务再落盘，
     * 服务会读到旧值 false 并立刻 `stopSelfSafely()`——用户刚打开开关入口就没了。
     * 两句在同一协程内顺序执行，且 DataStore 的 `edit {}` 在返回前已 commit，
     * 故这不是时序侥幸而是顺序保证。
     *
     * **失败不静默**（项目红线）：`enterStandby()` 失败时实现方已代发 alert 渠道
     * 可见通知（用户会看到「快捷录音入口未建立」），此处补日志留存排查线索。
     */
    fun setQuickRecordNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setQuickRecordNotificationEnabled(enabled)
            if (enabled) {
                serviceController.enterStandby().onFailure { failure ->
                    // 不带 cause：Controller 内部已用完整堆栈打过一次，此处只留业务上下文
                    EchoLog.w(TAG, "快捷入口建立失败：${failure.code} ${failure.message}")
                }
            }
        }
    }

    /** 刷新存储统计（音频目录体积 + 可用空间）。 */
    fun refreshStorage() {
        viewModelScope.launch {
            val audioBytes = FileUtils.directorySizeBytes(FileUtils.audioDir(context))
            val available = FileUtils.availableStorageBytes(context)
            _uiState.update {
                it.copy(
                    audioSizeBytes = audioBytes,
                    availableBytes = available,
                    storageLoaded = true,
                )
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}

/**
 * 设置页 UI 状态。
 *
 * @param storageLoaded 存储统计是否已计算（首帧展示占位）。
 * @param storageLocationName 自选目录展示名（null = 使用默认内部目录）。
 */
data class SettingsUiState(
    val settings: RecordingSettings = RecordingSettings(),
    val modelStates: Map<ModelKind, ModelState> = emptyMap(),
    val audioSizeBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val storageLoaded: Boolean = false,
    val storageLocationName: String? = null,
) {
    /** 端侧模型是否全部就绪（设置页「模型管理」徽标）。 */
    val allModelsReady: Boolean
        get() = modelStates.isNotEmpty() && modelStates.values.all { it == ModelState.READY }
}
