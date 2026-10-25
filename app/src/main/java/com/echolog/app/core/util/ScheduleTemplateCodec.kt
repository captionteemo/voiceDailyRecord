package com.echolog.app.core.util

import com.echolog.app.domain.model.ScheduleTemplate
import com.echolog.app.domain.model.TimeRange

/**
 * 录音时间模板编解码器（用户需求 4：模板录音）。
 *
 * 文本格式（每行一个时间区间，`#` 开头为注释）——**存储格式与导入文件格式一致**，
 * 用户可直接编辑文本文件后导入：
 * ```
 * # 课表模板
 * 08:00-09:30 高等数学
 * 10:00-11:30 大学英语
 * 14:00-15:30 计算机导论
 * ```
 *
 * 行内格式：`HH:mm-HH:mm[ 备注]`；分隔符兼容 `-` / `~` / `至` 与全角 `－`。
 * 解析失败的行被跳过（不因一行错误丢弃整个模板）。
 */
object ScheduleTemplateCodec {

    /** 编码为文本（按开始时间升序）。 */
    fun encode(template: ScheduleTemplate): String = buildString {
        appendLine("# 回声日志 录音时间模板")
        appendLine("# 格式：HH:mm-HH:mm [备注]，每行一个区间；# 开头为注释")
        template.ranges
            .sortedBy { it.startMinute }
            .forEach { range ->
                val label = range.label.trim()
                appendLine(
                    if (label.isEmpty()) {
                        range.display()
                    } else {
                        "${range.display()} $label"
                    },
                )
            }
    }

    /** 从文本解析模板；解析不出任何区间时返回空模板。 */
    fun decode(text: String): ScheduleTemplate {
        val ranges = parseRanges(text)
        return ScheduleTemplate(name = "", ranges = ranges, enabled = ranges.isNotEmpty())
    }

    /** 从文本解析时间区间列表（按开始时间升序，非法行跳过）。 */
    fun parseRanges(text: String): List<TimeRange> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { parseRange(it) }
        .sortedBy { it.startMinute }
        .toList()

    /** 解析单行区间；非法返回 null。 */
    fun parseRange(line: String): TimeRange? {
        val cleaned = line.trim()
        if (cleaned.isEmpty()) return null
        // 备注：第一个空白之后的内容
        val timePart: String
        val label: String
        val firstSpace = cleaned.indexOfFirst { it.isWhitespace() }
        if (firstSpace > 0) {
            timePart = cleaned.substring(0, firstSpace)
            label = cleaned.substring(firstSpace).trim()
        } else {
            timePart = cleaned
            label = ""
        }

        val separatorIndex = timePart.indexOfFirst { it in SEPARATORS }
        if (separatorIndex <= 0) return null
        val startText = timePart.substring(0, separatorIndex)
        val endText = timePart.substring(separatorIndex + 1)
        val start = TimeRange.parseClock(startText) ?: return null
        val end = TimeRange.parseClock(endText) ?: return null
        return TimeRange(startMinute = start, endMinute = end, label = label)
    }

    /** 时间区间分隔符（半角/全角均兼容）。 */
    private val SEPARATORS = charArrayOf('-', '~', '—', '–', '－', '至')
}
