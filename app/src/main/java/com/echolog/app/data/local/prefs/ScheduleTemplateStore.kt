package com.echolog.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.ScheduleTemplateCodec
import com.echolog.app.domain.model.ScheduleTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.scheduleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = Constants.SCHEDULE_DATASTORE_NAME,
)

/**
 * 录音时间模板存储（用户需求 4：模板录音）。
 *
 * 采用独立 DataStore 文件（与设置项分离，避免模板高频写入污染设置流）。
 * 模板正文以 [ScheduleTemplateCodec] 的文本格式存储——**与导入文件格式一致**，
 * 用户可直接编辑文本文件后导入。
 *
 * 键位：
 * - `schedule_template_body`：模板正文（时间区间文本）
 * - `schedule_template_name`：模板名（默认「我的课表」）
 * - `schedule_template_enabled`：模板总开关
 */
class ScheduleTemplateStore(private val context: Context) {

    private val dataStore: DataStore<Preferences>
        get() = context.scheduleDataStore

    /** 模板流；任一键变更即重新发射。 */
    val template: Flow<ScheduleTemplate> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            val body = prefs[Keys.BODY].orEmpty()
            val ranges = ScheduleTemplateCodec.parseRanges(body)
            ScheduleTemplate(
                name = prefs[Keys.NAME] ?: DEFAULT_NAME,
                ranges = ranges,
                enabled = prefs[Keys.ENABLED] ?: false,
            )
        }

    /** 保存模板（正文以文本格式落库）。 */
    suspend fun save(template: ScheduleTemplate) {
        dataStore.edit { prefs ->
            prefs[Keys.BODY] = ScheduleTemplateCodec.encode(template)
            prefs[Keys.NAME] = template.name.ifBlank { DEFAULT_NAME }
            prefs[Keys.ENABLED] = template.enabled
        }
    }

    /** 覆盖模板正文（导入文件后调用），保持当前开关状态。 */
    suspend fun saveBody(body: String) {
        dataStore.edit { prefs ->
            prefs[Keys.BODY] = body
            if (prefs[Keys.NAME] == null) prefs[Keys.NAME] = DEFAULT_NAME
        }
    }

    /** 启用/停用模板。 */
    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.ENABLED] = enabled }
    }

    private object Keys {
        val BODY = stringPreferencesKey("schedule_template_body")
        val NAME = stringPreferencesKey("schedule_template_name")
        val ENABLED = booleanPreferencesKey("schedule_template_enabled")
    }

    companion object {
        const val DEFAULT_NAME = "我的课表"
    }
}
