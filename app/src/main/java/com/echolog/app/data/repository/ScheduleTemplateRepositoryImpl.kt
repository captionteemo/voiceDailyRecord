package com.echolog.app.data.repository

import com.echolog.app.data.local.prefs.ScheduleTemplateStore
import com.echolog.app.domain.model.ScheduleTemplate
import com.echolog.app.domain.repository.ScheduleTemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ScheduleTemplateRepository] 实现（用户需求 4：模板录音）。
 *
 * DataStore 自身主安全且挂起，直接委托。
 */
@Singleton
class ScheduleTemplateRepositoryImpl @Inject constructor(
    private val scheduleTemplateStore: ScheduleTemplateStore,
) : ScheduleTemplateRepository {

    override fun observeTemplate(): Flow<ScheduleTemplate> = scheduleTemplateStore.template

    override suspend fun getTemplate(): ScheduleTemplate = scheduleTemplateStore.template.first()

    override suspend fun saveTemplate(template: ScheduleTemplate) =
        scheduleTemplateStore.save(template)

    override suspend fun saveTemplateBody(body: String) =
        scheduleTemplateStore.saveBody(body)

    override suspend fun setTemplateEnabled(enabled: Boolean) =
        scheduleTemplateStore.setEnabled(enabled)
}
