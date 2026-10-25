package com.echolog.app.domain.usecase

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment

/**
 * 片段转写导出接缝（用户需求 1：与录音同名的 .md 文字稿）。
 *
 * domain 层不直接做文件 IO；实现位于 data 层（`TranscriptExporter`），
 * 单测可替换为 fake。导出失败（存储满/授权失效）不得影响转写主链路——
 * 调用方忽略返回值即可（内部文件仍是数据源）。
 */
interface SegmentTranscriptExporter {

    /**
     * 导出单个片段的转写 Markdown（与录音 WAV 同名，`.wav` → `.md`）。
     *
     * @param dayId 录音日。
     * @param segment 片段（filePath 为内部 WAV 路径，文件名即命名规范来源）。
     * @param transcripts 段内转写行（已叠加为全天绝对时间）。
     * @return true = 已写入目标目录（或无需写入）；false = 写入失败。
     */
    suspend fun exportSegment(
        dayId: String,
        segment: AudioSegment,
        transcripts: List<TranscriptSegment>,
    ): Boolean
}
