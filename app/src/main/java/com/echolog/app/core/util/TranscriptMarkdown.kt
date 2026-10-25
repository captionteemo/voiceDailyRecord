package com.echolog.app.core.util

import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.TranscriptSegment

/**
 * 转写 Markdown 文档构建器（用户需求 1：与录音同名的 .md 文字稿）。
 *
 * 纯函数（无 IO/Android 依赖），由 `TranscriptExporter` 落盘：
 * - 标题与文件名对齐（`2026-10-25 第3次录音` ↔ `2026-10-25_第3次.wav/.md`）；
 * - 元信息头：时间段（本地时区 HH:mm:ss）、时长、对应音频文件名；
 * - 正文：带相对片段起点时间戳的转写行（绝对时刻见标题）；
 * - 段内打点标记（含备注）。
 */
object TranscriptMarkdown {

    /**
     * 构建单个片段的转写 Markdown。
     *
     * @param dayId 录音日（`yyyy-MM-dd`）。
     * @param ordinal 当天片段序号（1 起；<=0 时标题省略序号）。
     * @param startMs 片段起点（epoch millis）。
     * @param endMs 片段终点（epoch millis；0 表示未知）。
     * @param audioFileName 对应音频文件名（如 `2026-10-25_第3次.wav`）。
     * @param transcripts 段内转写行（相对片段起点的时间戳 + 文字，按时间升序）。
     * @param markers 段内打点（绝对时刻 + 备注，按时刻升序）。
     */
    fun build(
        dayId: String,
        ordinal: Int,
        startMs: Long,
        endMs: Long,
        audioFileName: String,
        transcripts: List<TranscriptSegment>,
        markers: List<Marker>,
    ): String {
        val title = if (ordinal > 0) "$dayId 第${ordinal}次录音" else "$dayId 录音"
        val durationMs = if (endMs > startMs) endMs - startMs else 0L
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("- 日期：$dayId")
            appendLine("- 时间段：${TimeUtils.formatTime(startMs)} - ${endMs.takeIf { it > 0L }?.let { TimeUtils.formatTime(it) } ?: "?"}")
            appendLine("- 时长：${TimeUtils.formatDurationClock(durationMs)}")
            appendLine("- 音频文件：$audioFileName")
            appendLine()
            appendLine("## 转写内容")
            appendLine()
            if (transcripts.isEmpty()) {
                appendLine("（无转写内容）")
            } else {
                transcripts.forEach { line ->
                    val relStart = (line.startMs - startMs).coerceAtLeast(0L)
                    appendLine("- [${formatOffset(relStart)}] ${line.text}")
                }
            }
            if (markers.isNotEmpty()) {
                appendLine()
                appendLine("## 打点标记")
                appendLine()
                markers.forEach { marker ->
                    val rel = (marker.atMs - startMs).coerceAtLeast(0L)
                    val note = marker.note.trim()
                    if (note.isEmpty()) {
                        appendLine("- [${formatOffset(rel)}] （标记）")
                    } else {
                        appendLine("- [${formatOffset(rel)}] $note")
                    }
                }
            }
        }
    }

    /** 片段内偏移格式化为 `MM:SS`（转写行时间戳口径）。 */
    private fun formatOffset(offsetMs: Long): String {
        val totalSeconds = offsetMs.coerceAtLeast(0L) / 1000L
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }
}