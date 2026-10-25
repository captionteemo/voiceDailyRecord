package com.echolog.app.ai.llm

import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.TimelineItem
import com.echolog.app.domain.model.TodoType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * LLM 总结输出 JSON 解析器（架构 2.4，容错设计）。
 *
 * 容错层级（E-4002 的处理关键）：
 * 1. 剥离 Markdown 代码围栏（```json ... ```）；
 * 2. 截取首个 `{` 到末个 `}` 之间的内容（容忍前后杂质）；
 * 3. JSON 解析失败时尝试「截断修复」：回退到最后一个完整元素并闭合括号
 *    （LLM 达到 max_tokens 被截断是高频场景）；
 * 4. 单条时间线/条目解析失败时跳过该条，不拖垮整体；
 * 5. 彻底失败抛 [SummaryParseException]，由用例降级 [RuleBasedSummarizer]。
 */
object SummaryJsonParser {

    private const val MAX_SUMMARY_LENGTH = 2000

    /**
     * 解析 LLM 原始输出为 [SummaryDraft]。
     *
     * @throws SummaryParseException 无法解析出任何有效结构时。
     */
    @Throws(SummaryParseException::class)
    fun parse(raw: String): SummaryDraft {
        val jsonText = extractJsonObject(raw)
            ?: throw SummaryParseException("未找到 JSON 对象：${raw.take(80)}")
        val json = try {
            JSONObject(jsonText)
        } catch (throwable: Throwable) {
            parseWithRepair(jsonText)
                ?: throw SummaryParseException("JSON 解析失败：${throwable.message}")
        }
        val summary = json.optString("summary", "").trim().take(MAX_SUMMARY_LENGTH)
        val timeline = parseTimeline(json.optJSONArray("timeline"))
        val todos = parseItems(json.optJSONArray("items")) + parseItems(json.optJSONArray("todos"))
        if (summary.isEmpty() && timeline.isEmpty() && todos.isEmpty()) {
            throw SummaryParseException("JSON 结构有效但内容为空")
        }
        return SummaryDraft(summaryText = summary, timeline = timeline, todos = todos)
    }

    /** 剥离围栏 + 截取 `{...}`。 */
    private fun extractJsonObject(raw: String): String? {
        val withoutFence = raw
            .replace(FENCE_START_REGEX, "")
            .replace(FENCE_END, "")
            .trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return withoutFence.substring(start, end + 1)
    }

    /** 截断修复：逐层回退到可解析的最长前缀。 */
    private fun parseWithRepair(jsonText: String): JSONObject? {
        var candidate = jsonText
        while (candidate.length > 2) {
            // 依次尝试补齐数组/对象闭合
            for (suffix in REPAIR_SUFFIXES) {
                try {
                    return JSONObject(candidate + suffix)
                } catch (_: Throwable) {
                    // 继续尝试其他后缀或更短的前缀
                }
            }
            candidate = dropLastPartialElement(candidate)
        }
        return null
    }

    /** 回退到最后一个完整元素边界（逗号或括号处截断）。 */
    private fun dropLastPartialElement(json: String): String {
        val lastComma = json.lastIndexOf(',')
        val lastClose = json.lastIndexOf('}')
        val lastArrayClose = json.lastIndexOf(']')
        val cutIndex = maxOf(lastComma, lastClose, lastArrayClose)
        return if (cutIndex > 1) json.substring(0, cutIndex) else json.dropLast(1)
    }

    private fun parseTimeline(array: JSONArray?): List<TimelineItem> {
        if (array == null) return emptyList()
        val result = mutableListOf<TimelineItem>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isEmpty()) continue
            result.add(TimelineItem(atMs = parseTimeLabel(item.optString("time")), text = text))
        }
        return result
    }

    private fun parseItems(array: JSONArray?): List<TodoDraft> {
        if (array == null) return emptyList()
        val result = mutableListOf<TodoDraft>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val content = item.optString("content").trim()
            if (content.isEmpty()) continue
            val type = when (item.optString("type").uppercase()) {
                "DECISION" -> TodoType.DECISION
                "AGREEMENT" -> TodoType.AGREEMENT
                else -> TodoType.TODO
            }
            result.add(TodoDraft(type = type, content = content, atMs = parseTimeLabel(item.optString("time"))))
        }
        return result
    }

    /** `HH:mm` → 当天本地时间的 epoch millis；无法解析回落 0。 */
    private fun parseTimeLabel(label: String?): Long {
        if (label.isNullOrBlank()) return 0L
        val parsed = runCatching { LocalTime.parse(label.trim()) }.getOrNull() ?: return 0L
        return LocalDate.now().atTime(parsed).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 解析落库的时间线 JSON（[com.echolog.app.domain.model.DaySummary.timelineJson]）。
     *
     * 与 [SummarizeDayUseCase.encodeTimeline] 的序列化格式互为逆操作：
     * `[{"atMs":<epoch millis>,"text":"..."}]`，按 atMs 升序返回。
     * 任何格式问题都静默回落空列表（展示层容错，不抛异常）。
     */
    fun parseTimelineJson(json: String?): List<TimelineItem> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val text = item.optString("text").trim()
                if (text.isEmpty()) return@mapNotNull null
                TimelineItem(atMs = item.optLong("atMs"), text = text)
            }.sortedBy { it.atMs }
        }.getOrDefault(emptyList())
    }

    private val FENCE_START_REGEX = Regex("(?s)```[a-zA-Z]*\\s*")
    private const val FENCE_END = "```"

    private val REPAIR_SUFFIXES = listOf(
        "\"]}",
        "\"]}}",
        "]}",
        "}}",
        "}",
        "",
    )
}

/** 总结 JSON 解析失败（调用方据此降级规则兜底，E-4002）。 */
class SummaryParseException(message: String) : Exception(message)
