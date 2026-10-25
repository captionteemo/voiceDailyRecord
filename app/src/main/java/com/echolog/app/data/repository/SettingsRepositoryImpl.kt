package com.echolog.app.data.repository

import com.echolog.app.data.local.prefs.SettingsStore
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.RecordingSettings
import com.echolog.app.domain.model.SensitivityLevel
import com.echolog.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsRepository] 实现（架构 3.2）。
 *
 * DataStore 自身主安全且挂起，直接委托，无需额外调度。
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsStore: SettingsStore,
) : SettingsRepository {

    override fun observeSettings(): Flow<RecordingSettings> = settingsStore.settings

    override suspend fun getSettings(): RecordingSettings = settingsStore.settings.first()

    override suspend fun setSensitivity(level: SensitivityLevel) =
        settingsStore.setSensitivity(level)

    override suspend fun setSilenceDurationMs(durationMs: Long) =
        settingsStore.setSilenceDurationMs(durationMs)

    override suspend fun setPrivacyMode(mode: PrivacyMode) =
        settingsStore.setPrivacyMode(mode)

    override suspend fun setCloudEnhance(enabled: Boolean) =
        settingsStore.setCloudEnhance(enabled)

    override suspend fun setEncryption(enabled: Boolean) =
        settingsStore.setEncryption(enabled)

    override suspend fun setModelsReady(ready: Boolean) =
        settingsStore.setModelsReady(ready)

    override suspend fun setMonitorActive(active: Boolean) =
        settingsStore.setMonitorActive(active)

    override suspend fun writeHeartbeat(epochMs: Long) =
        settingsStore.writeHeartbeat(epochMs)

    override suspend fun setPrivacyAck(acknowledged: Boolean) =
        settingsStore.setPrivacyAck(acknowledged)

    override suspend fun setSampleRate(sampleRate: Int) =
        settingsStore.setSampleRate(sampleRate)

    override suspend fun setStorageTreeUri(treeUri: String?) =
        settingsStore.setStorageTreeUri(treeUri)

    override suspend fun setRecordingMode(mode: RecordingMode) =
        settingsStore.setRecordingMode(mode)
}
