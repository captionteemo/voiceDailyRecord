package com.echolog.app.ui.transcript

import com.echolog.app.core.util.TimeUtils

/**
 * 转写全文页的复制文本构建器（**纯函数**，无 Android 依赖，可直接单测）。
 *
 * 格式与项目内既有转写文本口径一致（见
 * [com.echolog.app.domain.usecase.SummarizeDayUseCase] 的 `buildTranscriptText`
 * 与 [com.echolog.app.ui.summary.TimelineItem.displayLabel]）：
 *
 * ```
 * [HH:mm] 第一行文字
 * [HH:mm] 第二行文字
 * ```
 *
 * 全文复制与所选复制使用**同一格式**，只是行数不同。
 */
object TranscriptCopyFormatter {

    /**
     * 复制全部：当天全部转写（按传入顺序，即时间升序）。
     *
     * @param lines 转写行。
     * @return 复制文本；无内容时返回 `null`（调用方应提示「没有可复制的文字」）。
     */
    fun formatAll(lines: List<TranscriptLineUi>): String? {
        if (lines.isEmpty()) return null
        return lines.joinToString(separator = "\n") { formatLine(it) }
    }

    /**
     * 复制所选：仅勾选的行，**仍按时间升序**输出（与页面展示顺序一致）。
     *
     * @param lines 全部转写行。
     * @param selectedTsIds 已勾选的转写主键。
     * @return 复制文本；未勾选任何行时返回 `null`。
     */
    fun formatSelected(
        lines: List<TranscriptLineUi>,
        selectedTsIds: Set<Long>,
    ): String? {
        if (selectedTsIds.isEmpty()) return null
        val picked = lines.filter { it.tsId in selectedTsIds }
        if (picked.isEmpty()) return null
        return picked.joinToString(separator = "\n") { formatLine(it) }
    }

    /** 单行格式：`[HH:mm] 文字`。 */
    fun formatLine(line: TranscriptLineUi): String =
        "[${TimeUtils.formatTime(line.startMs)}] ${line.text}"
}
