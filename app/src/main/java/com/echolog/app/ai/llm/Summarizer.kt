package com.echolog.app.ai.llm

import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.TimelineItem
import com.echolog.app.domain.model.TodoType

/**
 * 总结引擎接口（架构 3.3 类图 / 2.4）。
 *
 * 实现：[QwenSummarizer]（llama.cpp JNI，端侧 LLM）与 [RuleBasedSummarizer]（规则兜底）；
 * 选择逻辑在 `SummarizeDayUseCase`：LLM 就绪则用 LLM，失败/未就绪降级规则兜底（E-4001/E-4002）。
 */
interface Summarizer {

    /** 引擎是否就绪（模型文件存在且加载成功）。 */
    fun isReady(): Boolean

    /**
     * 生成当天总结草稿。
     *
     * @param transcriptText 全天转写文本（含 `[HH:mm]` 时间前缀，由用例构建）。
     * @param markers 当天打点标记（FR-005：用户标定的重点线索，LLM Prompt 注入；
     *                规则兜底实现可忽略）。
     * @return 摘要 + 关键时间线 + 待办/决策/约定草稿。
     */
    suspend fun summarize(
        transcriptText: String,
        markers: List<Marker> = emptyList(),
    ): SummaryDraft

    /** 释放 native 资源。 */
    fun release() = Unit
}

/**
 * 总结草稿（LLM/规则输出的中间结构，落库前由用例转换为实体）。
 */
data class SummaryDraft(
    val summaryText: String,
    val timeline: List<TimelineItem>,
    val todos: List<TodoDraft>,
)

/**
 * 待办条目草稿。
 *
 * @param atMs 时间来源（epoch millis，全天绝对时间）；规则兜底可能无法定位时为 null，
 *             用例回落为 0 或最近转写段时间。
 */
data class TodoDraft(
    val type: TodoType,
    val content: String,
    val atMs: Long? = null,
)
