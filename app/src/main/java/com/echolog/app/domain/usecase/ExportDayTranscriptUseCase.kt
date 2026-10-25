package com.echolog.app.domain.usecase

import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.DayTranscriptMarkdown
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SummaryRepository
import com.echolog.app.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 按日期导出全天转写用例（用户需求 3：总结后内容导出）。
 *
 * 流程：
 * 1. 读取当天片段（按时间升序）、转写分段、打点标记与当天总结；
 * 2. 按 `segmentId` 把转写行还原为片段结构；
 * 3. 经 [DayTranscriptMarkdown] 生成带日期的 Markdown 正文；
 * 4. 交 [DayTranscriptExporter] 落盘（内部目录 + 用户自选目录）。
 *
 * 当天无转写内容时返回失败（E-1004），避免生成空文档。
 */
class ExportDayTranscriptUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val summaryRepository: SummaryRepository,
    private val dayTranscriptExporter: DayTranscriptExporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 导出指定日期的全天转写文档。
     *
     * @param dayId 目标录音日（`yyyy-MM-dd`）。
     * @return 成功时携带导出文件路径；当天无转写内容 / 写盘失败时携带错误码。
     */
    suspend operator fun invoke(dayId: String): EchoResult<String> = withContext(ioDispatcher) {
        try {
            val day = recordingRepository.getDay(dayId)
                ?: return@withContext EchoResult.failure(
                    code = ERROR_EXPORT,
                    message = "找不到 $dayId 的记录",
                )

            val segments = recordingRepository.getSegmentsForDay(dayId)
            val transcripts = transcriptionRepository.getTranscriptsForDay(dayId)
            if (transcripts.isEmpty()) {
                return@withContext EchoResult.failure(
                    code = ERROR_EXPORT,
                    message = "当天没有可导出的转写内容",
                )
            }

            val transcriptsBySegment = transcripts.groupBy { it.segmentId }
            val markers = recordingRepository.getMarkersForDay(dayId)
            val summary = summaryRepository.getSummary(dayId)

            val content = DayTranscriptMarkdown.build(
                dayId = dayId,
                segments = segments,
                transcriptsBySegment = transcriptsBySegment,
                markers = markers,
                summary = summary,
                totalRecordMs = day.totalRecordMs,
            )

            dayTranscriptExporter.export(dayId, content).fold(
                onSuccess = { path -> EchoResult.success(path) },
                onFailure = { throwable ->
                    EchoResult.failure(
                        code = ERROR_EXPORT,
                        message = "导出失败：${throwable.message ?: "未知错误"}",
                        cause = throwable,
                    )
                },
            )
        } catch (throwable: Throwable) {
            EchoResult.failure(
                code = ERROR_EXPORT,
                message = "导出失败：${throwable.message ?: "未知错误"}",
                cause = throwable,
            )
        }
    }

    private companion object {
        /** 导出类错误码。 */
        const val ERROR_EXPORT = "E-1004"
    }
}
