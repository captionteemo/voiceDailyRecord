package com.echolog.app.domain.usecase

/**
 * 全天转写导出接缝（用户需求 3：按日期导出）。
 *
 * domain 层不依赖 Android SDK，落盘（内部目录 + 用户自选目录）由 data 层实现
 * （`data/media/DayTranscriptExporter`）。
 */
interface DayTranscriptExporter {

    /**
     * 将指定日期的全天转写导出为一个带日期的 Markdown 文档。
     *
     * @param dayId 录音日（`yyyy-MM-dd`）。
     * @param content 已构建好的 Markdown 正文。
     * @return 成功时携带导出文件路径（内部目录）；失败携带原因。
     */
    suspend fun export(dayId: String, content: String): Result<String>
}
