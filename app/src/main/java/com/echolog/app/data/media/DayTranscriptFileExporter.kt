package com.echolog.app.data.media

import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.DayTranscriptMarkdown
import com.echolog.app.core.util.FileUtils
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.DayTranscriptExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DayTranscriptExporter] 实现（用户需求 3：按日期导出全天转写）。
 *
 * 落盘策略与片段级 [TranscriptExporter] 一致：
 * - 未选自选目录 → 写在音频目录同级（`{filesDir}/audio/{dayId}_全天转写.md`）；
 * - 已选自选目录 → 额外写一份到自选目录（SAF），内部目录始终保留一份。
 *
 * 自选目录写入失败不影响内部落盘（内部文件是数据源）。
 */
@Singleton
class DayTranscriptFileExporter @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val storageLocationManager: StorageLocationManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DayTranscriptExporter {

    override suspend fun export(dayId: String, content: String): Result<String> =
        withContext(ioDispatcher) {
            val fileName = DayTranscriptMarkdown.fileName(dayId)
            val target = File(FileUtils.audioDir(context), fileName)
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(content)
                // 自选目录副本（失败不影响内部落盘）
                val settings = settingsRepository.getSettings()
                storageLocationManager.writeTextToUserDir(
                    fileName = fileName,
                    content = content,
                    treeUri = settings.storageTreeUri,
                    mimeType = StorageLocationManager.MIME_MARKDOWN,
                )
                target.absolutePath
            }
        }
}
