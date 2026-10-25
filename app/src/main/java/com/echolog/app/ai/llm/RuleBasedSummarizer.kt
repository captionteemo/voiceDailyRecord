package com.echolog.app.ai.llm

import com.echolog.app.core.common.DefaultDispatcher
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.TimelineItem
import com.echolog.app.domain.model.TodoType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则兜底总结器（架构 1.4 决策 2/2.4）。
 *
 * 保证 FR-011「永不空白」：LLM 缺失（E-4001）或推理/解析失败（E-4002）时，
 * 基于关键词规则从转写文本提取摘要/时间线/待办，产出非空结果。
 *
 * 规则（架构决策 2）：
 * - 待办关键词：`待办|记得|安排|需要|提醒|别忘|下周|之前`；
 * - 决策关键词：`决定|同意|确定|敲定|通过`；
 * - 约定关键词：`约定|说好|约好|商量|对齐`；
 * - 时间来源：优先取每行 `[HH:mm]` 前缀（与 [PromptTemplates] 输出格式一致）。
 */
@Singleton
class RuleBasedSummarizer @Inject constructor(
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : Summarizer {

    override fun isReady(): Boolean = true

    /**
     * 规则兜底总结（[markers] 不参与规则匹配，仅为对齐 [Summarizer] 接口签名）。
     *
     * 规则提取完全基于转写文本的关键词与结构，打点标记对规则引擎无增量信息。
     */
    override suspend fun summarize(
        transcriptText: String,
        markers: List<Marker>,
    ): SummaryDraft =
        withContext(defaultDispatcher) {
            val lines = transcriptText.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.isEmpty()) {
                return@withContext SummaryDraft(
                    summaryText = "当天无转写内容。",
                    timeline = emptyList(),
                    todos = emptyList(),
                )
            }
            val entries = lines.mapNotNull(::parseLine)
            val todos = entries.mapNotNull { entry -> classify(entry) }
            val summary = buildSummary(entries)
            val timeline = buildTimeline(entries)
            SummaryDraft(summaryText = summary, timeline = timeline, todos = todos)
        }

    /** 一行转写：`[HH:mm] 文字` → 时间 + 文字；无前缀则时间为 null。 */
    private data class Entry(val atMs: Long?, val text: String)

    private fun parseLine(line: String): Entry? {
        val match = TIME_PREFIX_REGEX.find(line)
        return if (match != null) {
            val atMs = runCatching {
                val localTime = java.time.LocalTime.parse(match.groupValues[1])
                java.time.LocalDate.now().atTime(localTime)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
            Entry(atMs = atMs, text = line.substring(match.value.length).trim())
        } else {
            Entry(atMs = null, text = line)
        }
    }

    private fun classify(entry: Entry): TodoDraft? {
        if (entry.text.isEmpty()) return null
        val type = when {
            DECISION_KEYWORDS.any { entry.text.contains(it) } -> TodoType.DECISION
            AGREEMENT_KEYWORDS.any { entry.text.contains(it) } -> TodoType.AGREEMENT
            TODO_KEYWORDS.any { entry.text.contains(it) } -> TodoType.TODO
            else -> return null
        }
        return TodoDraft(type = type, content = entry.text, atMs = entry.atMs)
    }

    /** 摘要：取前两句实质内容 + 当日规模统计。 */
    private fun buildSummary(entries: List<Entry>): String {
        val sentences = entries.asSequence()
            .flatMap { it.text.splitToSequence(SENTENCE_DELIMITER_REGEX) }
            .map { it.trim() }
            .filter { it.length >= MIN_SENTENCE_LENGTH }
            .take(2)
            .toList()
        val head = if (sentences.isEmpty()) {
            "当天记录了 ${entries.size} 段对话。"
        } else {
            sentences.joinToString("。") + "。"
        }
        return "$head（全天共 ${entries.size} 段录音文字，规则摘要）"
    }

    /** 时间线：取含关键词/较长的代表性句子，最多 5 条，按时间排序。 */
    private fun buildTimeline(entries: List<Entry>): List<TimelineItem> {
        val keySentences = entries.asSequence()
            .flatMap { entry ->
                entry.text.splitToSequence(SENTENCE_DELIMITER_REGEX)
                    .map { it.trim() }
                    .filter { it.length >= MIN_SENTENCE_LENGTH }
                    .map { Entry(entry.atMs, it) }
            }
            .filter { entry -> ALL_KEYWORDS.any { entry.text.contains(it) } || entry.text.length >= 20 }
            .take(MAX_TIMELINE_ITEMS)
            .toList()
        return keySentences.map { entry ->
            TimelineItem(
                atMs = entry.atMs ?: 0L,
                text = entry.text.take(MAX_TIMELINE_TEXT_LENGTH),
            )
        }.sortedBy { it.atMs }
    }

    companion object {
        private val TIME_PREFIX_REGEX = Regex("^\\[(\\d{1,2}:\\d{2})]\\s*")
        private val SENTENCE_DELIMITER_REGEX = Regex("[。！？!?]")
        private const val MIN_SENTENCE_LENGTH = 6
        private const val MAX_TIMELINE_ITEMS = 5
        private const val MAX_TIMELINE_TEXT_LENGTH = 60

        private val TODO_KEYWORDS = listOf("待办", "记得", "安排", "需要", "提醒", "别忘", "下周", "之前")
        private val DECISION_KEYWORDS = listOf("决定", "同意", "确定", "敲定", "通过")
        private val AGREEMENT_KEYWORDS = listOf("约定", "说好", "约好", "商量", "对齐")
        private val ALL_KEYWORDS = TODO_KEYWORDS + DECISION_KEYWORDS + AGREEMENT_KEYWORDS
    }
}
