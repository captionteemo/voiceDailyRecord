package com.echolog.app.core.util

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.TranscriptSegment

/**
 * 全天转写 Markdown 文档构建器（用户需求 3：总结后内容导出）。
 *
 * 与片段级 [TranscriptMarkdown] 的区别：
 * - 片段级：一个片段一个 `.md`（`2026-10-25_第3次.md`），用于「音频与文字成对」；
 * - 全天级：一天一个 `.md`（`2026-10-25_全天转写.md`），把当天所有片段的转写
 *   按时间轴聚合成一份可读文档，并附带打点标记与当天总结。
 *
 * 纯函数（无 IO/Android 依赖），落盘由 `data/media` 层负责。
 */
object DayTranscriptMarkdown {

    /**
     * 构建全天转写 Markdown。
     *
     * @param dayId 录音日（`yyyy-MM-dd`）。
     * @param segments 当天片段（按起始时间升序）。
     * @param transcriptsBySegment 片段 ID → 该片段的转写行（按时间升序）。
     * @param markers 当天打点（按打点时刻升序）。
     * @param summary 当天总结（可空：尚未生成时省略该节）。
     * @param totalRecordMs 当天累计录音时长（epoch millis）。
     */
    fun build(
        dayId: String,
        segments: List<AudioSegment>,
        transcriptsBySegment: Map<Long, List<TranscriptSegment>>,
        markers: List<Marker>,
        summary: DaySummary?,
        totalRecordMs: Long,
    ): String {
        val totalText = transcriptsBySegment.values.sumOf { it.size }
        return buildString {
            appendLine("# $dayId 全天转写")
            appendLine()
            appendLine("- 日期：$dayId")
            appendLine("- 录音时长：${TimeUtils.formatDurationClock(totalRecordMs)}")
            appendLine("- 片段数：${segments.size}")
            appendLine("- 转写行数：$totalText")
            appendLine()

            // ── 当天总结（若有） ──
            val summaryText = summary?.summaryText?.trim().orEmpty()
            if (summaryText.isNotEmpty()) {
                appendLine("## 当天总结")
                appendLine()
                appendLine(summaryText)
                appendLine()
            }

            // ── 打点标记 ──
            if (markers.isNotEmpty()) {
                appendLine("## 打点标记")
                appendLine()
                markers.forEach { marker ->
                    val note = marker.note.trim()
                    appendLine(
                        if (note.isEmpty()) {
                            "- [${TimeUtils.formatTime(marker.atMs)}] （标记）"
                        } else {
                            "- [${TimeUtils.formatTime(marker.atMs)}] $note"
                        },
                    )
                }
                appendLine()
            }

            // ── 全天转写正文（按片段时间轴） ──
            appendLine("## 转写内容")
            appendLine()
            if (segments.isEmpty() || totalText == 0) {
                appendLine("（当天没有转写内容）")
            } else {
                segments.forEach { segment ->
                    val lines = transcriptsBySegment[segment.segmentId].orEmpty()
                    if (lines.isEmpty()) return@forEach
                    val ordinal = RecordingNamer.parseOrdinal(
                        FileUtils.fileNameOf(segment.filePath),
                    ) ?: 0
                    val header = if (ordinal > 0) {
                        "### 第${ordinal}次录音（${TimeUtils.formatTime(segment.startMs)} - " +
                            "${TimeUtils.formatTime(segment.endMs)}）"
                    } else {
                        "### ${TimeUtils.formatTime(segment.startMs)} - " +
                            "${TimeUtils.formatTime(segment.endMs)}"
                    }
                    appendLine(header)
                    appendLine()
                    lines.forEach { line ->
                        // 转写行时间为全天绝对时间，直接展示本地时刻
                        appendLine("- [${TimeUtils.formatTime(line.startMs)}] ${line.text}")
                    }
                    appendLine()
                }
            }
        }
    }

    /** 全天转写文件名：`2026-10-25_全天转写.md`。 */
    fun fileName(dayId: String): String = "${dayId}_全天转写.md"
}
