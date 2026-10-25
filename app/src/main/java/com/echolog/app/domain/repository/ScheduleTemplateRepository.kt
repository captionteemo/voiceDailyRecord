package com.echolog.app.domain.repository

import com.echolog.app.domain.model.ScheduleTemplate
import kotlinx.coroutines.flow.Flow

/**
 * 录音时间模板仓库（用户需求 4：模板录音）。
 *
 * 实现见 `data/repository/ScheduleTemplateRepositoryImpl.kt`（DataStore）。
 */
interface ScheduleTemplateRepository {

    /** 模板流；任一变更即重新发射。 */
    fun observeTemplate(): Flow<ScheduleTemplate>

    /** 一次性读取当前模板。 */
    suspend fun getTemplate(): ScheduleTemplate

    /** 保存模板（正文与区间一并落库）。 */
    suspend fun saveTemplate(template: ScheduleTemplate)

    /** 覆盖模板正文（导入文件后调用），保留当前启用状态。 */
    suspend fun saveTemplateBody(body: String)

    /** 启用/停用模板。 */
    suspend fun setTemplateEnabled(enabled: Boolean)
}
