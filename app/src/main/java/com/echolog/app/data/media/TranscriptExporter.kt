package com.echolog.app.data.media

import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.RecordingNamer
import com.echolog.app.core.util.TranscriptMarkdown
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.SegmentTranscriptExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SegmentTranscriptExporter] 实现（用户需求 1）。
 *
 * 片段转写成功后，将文字稿写为与录音同名的 `.md`：
 * - 未选自定目录 → 写在 WAV 同级（`{filesDir}/audio/`）；
 * - 已选自定目录 → 写一份到自选目录（SAF），内部目录同样保留一份。
 *
 * 命名以 WAV 文件名为准（`2026-10-25_第3次.wav` → `2026-10-25_第3次.md`），
 * 保证「一键打开目录」时音频与文字稿严格成对。
 */
@Singleton
class TranscriptExporter @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val recordingRepository: RecordingRepository,
    private val settingsRepository: SettingsRepository,
    private val storageLocationManager: StorageLocationManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SegmentTranscriptExporter {

    override suspend fun exportSegment(
        dayId: String,
        segment: AudioSegment,
        transcripts: List<TranscriptSegment>,
    ): Boolean = withContext(ioDispatcher) {
        if (transcripts.isEmpty()) return@withContext true // 无文字不生成空稿

        val source = File(segment.filePath)
        val baseName = source.nameWithoutExtension
        val mdFileName = "$baseName.md"
        val ordinal = RecordingNamer.parseOrdinal(baseName) ?: 0
        val markers = recordingRepository.getMarkersForDay(dayId)
            .filter { it.atMs >= segment.startMs && it.atMs <= segment.endMs }
        val content = TranscriptMarkdown.build(
            dayId = dayId,
            ordinal = ordinal,
            startMs = segment.startMs,
            endMs = segment.endMs,
            audioFileName = source.name,
            transcripts = transcripts,
            markers = markers,
        )

        val settings = settingsRepository.getSettings()
        // 1) 自选目录（失败不影响内部落盘）
        storageLocationManager.writeTextToUserDir(
            fileName = mdFileName,
            content = content,
            treeUri = settings.storageTreeUri,
            mimeType = StorageLocationManager.MIME_MARKDOWN,
        )
        // 2) 内部目录（WAV 同级；数据源之一，始终尝试）
        val internalMd = File(source.parentFile, mdFileName)
        runCatching {
            internalMd.parentFile?.mkdirs()
            internalMd.writeText(content)
        }.isSuccess
    }
}
