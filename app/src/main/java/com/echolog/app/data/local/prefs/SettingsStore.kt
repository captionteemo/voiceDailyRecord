package com.echolog.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.echolog.app.core.common.Constants
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.RecordingSettings
import com.echolog.app.domain.model.SensitivityLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = Constants.SETTINGS_DATASTORE_NAME,
)

/**
 * 设置存储（架构 2.2，DataStore Preferences）。
 *
 * 键位（架构 7.2/2.2）：
 * - `sensitivity`：VAD 灵敏度档位名称（LOW/MEDIUM/HIGH）
 * - `silence_duration_ms`：静音断段时长（默认 5000）
 * - `privacy_mode`：隐私模式（PERSONAL/MEETING）
 * - `cloud_enhance` / `encryption`：P1 开关（默认关）
 * - `models_ready`：端侧模型就绪标记（FR-012）
 * - `monitor_active`：监听活跃标记（健康检查依据）
 * - `service_heartbeat`：服务心跳时间戳（epoch millis，0 表示从未心跳）
 * - `privacy_ack`：隐私告知确认标记（FR-014）
 * - `sample_rate`：录音采样率（用户需求 3；默认 16000）
 * - `storage_tree_uri`：自选录音目录 SAF tree URI（空 = 应用内部目录）
 *
 * 读损坏时回退默认偏好（IOException 视为空文件，其余异常上抛）。
 */
class SettingsStore(private val context: Context) {

    private val dataStore: DataStore<Preferences>
        get() = context.settingsDataStore

    /** 完整设置视图，任一键变更即重新发射。 */
    val settings: Flow<RecordingSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs.toRecordingSettings() }

    suspend fun setSensitivity(level: SensitivityLevel) {
        dataStore.edit { prefs -> prefs[Keys.SENSITIVITY] = level.name }
    }

    suspend fun setSilenceDurationMs(durationMs: Long) {
        dataStore.edit { prefs -> prefs[Keys.SILENCE_DURATION_MS] = durationMs.coerceAtLeast(0L) }
    }

    suspend fun setPrivacyMode(mode: PrivacyMode) {
        dataStore.edit { prefs -> prefs[Keys.PRIVACY_MODE] = mode.name }
    }

    suspend fun setCloudEnhance(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.CLOUD_ENHANCE] = enabled }
    }

    suspend fun setEncryption(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.ENCRYPTION] = enabled }
    }

    suspend fun setModelsReady(ready: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.MODELS_READY] = ready }
    }

    suspend fun setMonitorActive(active: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.MONITOR_ACTIVE] = active }
    }

    suspend fun writeHeartbeat(epochMs: Long) {
        dataStore.edit { prefs -> prefs[Keys.SERVICE_HEARTBEAT] = epochMs }
    }

    suspend fun setPrivacyAck(acknowledged: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.PRIVACY_ACK] = acknowledged }
    }

    /** 录音采样率（用户需求 3；仅允许 [Constants.SAMPLE_RATE_OPTIONS] 内的值）。 */
    suspend fun setSampleRate(sampleRate: Int) {
        val safe = if (sampleRate in Constants.SAMPLE_RATE_OPTIONS) {
            sampleRate
        } else {
            Constants.DEFAULT_SAMPLE_RATE
        }
        dataStore.edit { prefs -> prefs[Keys.SAMPLE_RATE] = safe }
    }

    /** 自选录音存储目录（SAF tree URI 字符串；null = 恢复应用内部目录）。 */
    suspend fun setStorageTreeUri(treeUri: String?) {
        dataStore.edit { prefs ->
            if (treeUri.isNullOrBlank()) {
                prefs.remove(Keys.STORAGE_TREE_URI)
            } else {
                prefs[Keys.STORAGE_TREE_URI] = treeUri
            }
        }
    }

    /** 录音模式（用户需求 4）。 */
    suspend fun setRecordingMode(mode: RecordingMode) {
        dataStore.edit { prefs -> prefs[Keys.RECORDING_MODE] = mode.name }
    }

    private fun Preferences.toRecordingSettings(): RecordingSettings = RecordingSettings(
        sensitivity = SensitivityLevel.fromNameOrDefault(this[Keys.SENSITIVITY]),
        silenceDurationMs = this[Keys.SILENCE_DURATION_MS] ?: Constants.DEFAULT_SILENCE_MS,
        privacyMode = PrivacyMode.fromNameOrDefault(this[Keys.PRIVACY_MODE]),
        cloudEnhance = this[Keys.CLOUD_ENHANCE] ?: false,
        encryption = this[Keys.ENCRYPTION] ?: false,
        modelsReady = this[Keys.MODELS_READY] ?: false,
        monitorActive = this[Keys.MONITOR_ACTIVE] ?: false,
        serviceHeartbeat = this[Keys.SERVICE_HEARTBEAT] ?: 0L,
        privacyAck = this[Keys.PRIVACY_ACK] ?: false,
        sampleRate = this[Keys.SAMPLE_RATE] ?: Constants.DEFAULT_SAMPLE_RATE,
        storageTreeUri = this[Keys.STORAGE_TREE_URI],
        recordingMode = RecordingMode.fromNameOrDefault(this[Keys.RECORDING_MODE]),
    )

    private object Keys {
        val SENSITIVITY = stringPreferencesKey("sensitivity")
        val SILENCE_DURATION_MS = longPreferencesKey("silence_duration_ms")
        val PRIVACY_MODE = stringPreferencesKey("privacy_mode")
        val CLOUD_ENHANCE = booleanPreferencesKey("cloud_enhance")
        val ENCRYPTION = booleanPreferencesKey("encryption")
        val MODELS_READY = booleanPreferencesKey("models_ready")
        val MONITOR_ACTIVE = booleanPreferencesKey("monitor_active")
        val SERVICE_HEARTBEAT = longPreferencesKey("service_heartbeat")
        val PRIVACY_ACK = booleanPreferencesKey("privacy_ack")
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val STORAGE_TREE_URI = stringPreferencesKey("storage_tree_uri")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
    }
}
