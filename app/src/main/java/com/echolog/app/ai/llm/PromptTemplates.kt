package com.echolog.app.ai.llm

import com.echolog.app.domain.model.Marker

/**
 * 中文总结 Prompt 模板（架构 2.4，决策 2）。
 *
 * 设计要点：
 * 1. 输入转写文本带 `[HH:mm]` 时间前缀，时间线条目回引同一格式，便于 [SummaryJsonParser] 解析；
 * 2. 强约束**只输出 JSON**（无解释、无 Markdown 围栏），降低解析失败率；
 * 3. 打点标记作为重点线索注入（FR-005 的备注是用户标定的关键点）；
 * 4. 无转写内容时明确要求产出空结构而非编造（约束幻觉）。
 */
object PromptTemplates {

    /** 全天总结 Prompt。 */
    fun fullDay(transcriptText: String, markers: List<Marker> = emptyList()): String = buildString {
        appendLine(SYSTEM_ROLE)
        appendLine()
        appendLine("【任务】根据下面的全天转写记录，完成四件事：")
        appendLine("1. summary：全天摘要，120字以内，客观陈述发生了什么、定了什么；")
        appendLine("2. timeline：关键时间线，3~8条，按时间顺序，每条附时间与一句话描述；")
        appendLine("3. todos：待办事项（type=TODO）；")
        appendLine("4. decisions：关键决策（type=DECISION）与约定（type=AGREEMENT）。")
        appendLine()
        appendLine("【输出要求】只输出一个 JSON 对象，不要任何解释文字或代码围栏，格式：")
        appendLine(
            """
            {"summary":"...","timeline":[{"time":"HH:mm","text":"..."}],
            "items":[{"type":"TODO|DECISION|AGREEMENT","time":"HH:mm","content":"..."}]}
            """.trimIndent(),
        )
        appendLine("时间一律用转写中出现的 HH:mm；无明确时间时填该内容所在时间段的中点。")
        appendLine("没有对应内容时输出空数组，禁止编造。")
        appendLine()
        if (markers.isNotEmpty()) {
            appendLine("【用户打点标记（重点线索）】")
            markers.forEach { marker ->
                val note = marker.note.ifBlank { "（无备注）" }
                appendLine("- [${marker.atMs.timeLabel()}] $note")
            }
            appendLine()
        }
        appendLine("【全天转写记录】")
        append(transcriptText)
    }

    private val SYSTEM_ROLE = "你是一名严谨的中文会议纪要助手，只依据给定转写内容输出结构化纪要。"

    /** epoch millis → `HH:mm` 标签（与转写文本前缀一致）。 */
    private fun Long.timeLabel(): String = com.echolog.app.core.util.TimeUtils.formatTime(this)
}
