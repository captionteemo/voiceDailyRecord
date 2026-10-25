package com.echolog.app.domain.repository

import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.RecordingSettings
import com.echolog.app.domain.model.SensitivityLevel
import kotlinx.coroutines.flow.Flow

/**
 * 设置仓库（架构 3.1/3.2）。
 *
 * 实现见 `data/repository/SettingsRepositoryImpl.kt`（DataStore）。
 */
interface SettingsRepository {

    /** 完整设置视图，任一键变更即重新发射。 */
    fun observeSettings(): Flow<RecordingSettings>

    /** 一次性读取当前设置（服务/Worker 启动前快照）。 */
    suspend fun getSettings(): RecordingSettings

    /** VAD 灵敏度档位（FR-204）。 */
    suspend fun setSensitivity(level: SensitivityLevel)

    /** 静音断段时长（FR-003，3/5/10s）。 */
    suspend fun setSilenceDurationMs(durationMs: Long)

    /** 隐私模式（FR-102：个人自用 / 会议记录）。 */
    suspend fun setPrivacyMode(mode: PrivacyMode)

    /** 云端增强开关（FR-101，默认关）。 */
    suspend fun setCloudEnhance(enabled: Boolean)

    /** 本地加密开关（FR-103，默认关）。 */
    suspend fun setEncryption(enabled: Boolean)

    /** 端侧模型就绪标记（FR-012）。 */
    suspend fun setModelsReady(ready: Boolean)

    /** 监听活跃标记（健康检查 / 开机恢复依据）。 */
    suspend fun setMonitorActive(active: Boolean)

    /** 写服务心跳（epoch millis，架构决策 3：服务每 5s 写一次）。 */
    suspend fun writeHeartbeat(epochMs: Long)

    /** 隐私告知确认标记（FR-014）。 */
    suspend fun setPrivacyAck(acknowledged: Boolean)

    /** 录音采样率（用户需求 3；默认 16000）。 */
    suspend fun setSampleRate(sampleRate: Int)

    /** 自选录音存储目录（SAF tree URI；null = 恢复应用内部目录）。 */
    suspend fun setStorageTreeUri(treeUri: String?)

    /** 录音模式（用户需求 4：全天/自动/模板/手动）。 */
    suspend fun setRecordingMode(mode: RecordingMode)
}
