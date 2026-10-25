package com.echolog.app.domain.usecase

import com.echolog.app.ai.llm.QwenSummarizer
import com.echolog.app.ai.llm.RuleBasedSummarizer
import com.echolog.app.ai.llm.Summarizer
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.TimelineItem
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SummaryRepository
import com.echolog.app.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 当天总结用例（架构 2.4/4.2，FR-011）。
 *
 * 流程：读全天转写 + 打点标记 → 构建带 `[HH:mm]` 前缀的文本 →
 * 选择引擎（LLM 就绪且推理成功 → Qwen；否则/失败 → 规则兜底）→
 * 落库总结 + 待办/决策/约定条目（整体替换）。
 *
 * 降级链（架构 7.4）：E-4001 模型缺失直接规则兜底；E-4002 推理/解析失败先重试一次再规则兜底。
 */
class SummarizeDayUseCase @Inject constructor(
    private val transcriptionRepository: TranscriptionRepository,
    private val recordingRepository: RecordingRepository,
    private val summaryRepository: SummaryRepository,
    private val qwenSummarizer: QwenSummarizer,
    private val ruleBasedSummarizer: RuleBasedSummarizer,
    private val modelManager: ModelManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 生成并保存当天总结。
     *
     * @param dayId 目标录音日。
     * @param force 是否强制重新生成（转写全文页「重新生成总结」入口）。
     *
     *   **force 与 BUG-10 的关系**：BUG-10 修复引入的「已有非空总结则跳过」保护，
     *   是为了防止**兜底扫描**（[SettlePendingDaysUseCase] / [DaySettleWorker]）
     *   重复结算已 DONE 的天——那次重跑会用新总结覆盖旧总结，
     *   并把用户已勾选的待办状态重置（用户体感是「我勾的待办又回来了」）。
     *
     *   `force = true` 是**用户显式动作**（在转写全文页点「重新生成总结」），
     *   语义是「旧结果作废，按当前文字重算」，因此：
     *   1. 跳过上述跳过保护（否则用户点了没反应）；
     *   2. 先 `deleteSummary` 清掉旧总结与旧待办再生成——旧待办可能与新文字
     *      不再对应，保留会造成「总结与文字稿不一致」，且旧的勾选态已无意义。
     *
     *   代价（有意取舍）：生成失败时旧总结不可恢复——这是「显式重新生成」的既定语义，
     *   失败信息会通过返回值交给 UI 呈现，用户可再次点击重试。
     *
     *   `force = false`（默认）时行为与既往**完全一致**：兜底扫描仍受 BUG-10 保护。
     *
     * @return 成功/失败（携带使用的引擎标识于 message，便于排查）。
     */
    suspend operator fun invoke(dayId: String, force: Boolean = false): EchoResult<String> =
        withContext(ioDispatcher) {
            try {
                if (force) {
                    // 强制重算：先清空旧总结与旧待办，再走完整生成流程（见 KDoc 的 force 语义）。
                    summaryRepository.deleteSummary(dayId)
                } else {
                    // BUG-10 修复：已有非空总结则跳过——兜底扫描会重复结算 DONE 天，
                    // 直接重跑会用新总结覆盖旧总结并重置待办勾选态
                    val existing = summaryRepository.getSummary(dayId)
                    if (existing?.summaryText?.isNotBlank() == true) {
                        return@withContext EchoResult.success(existing.modelName)
                    }
                }
                val transcripts = transcriptionRepository.observeTranscriptsForDay(dayId).first()
                if (transcripts.isEmpty()) {
                    // BUG-11 修复：区分「确定性无内容」与「真正的总结失败」。
                    // 片段已全部转写完成（状态 DONE）却没有任何文字 —— 这是**终态**，
                    // 重试不可能改变结果（兜底扫描只取 PENDING/FAILED 片段）。
                    // 若按普通失败返回，调用方会 Result.retry() 无限重试
                    // （WorkManager 的 retry 无次数上限）→ 该天永久停在 95%。
                    return@withContext EchoResult.failure(
                        Constants.ERROR_SUMMARY_NO_CONTENT,
                        "当天无转写内容，无法生成总结",
                    )
                }
                val markers = recordingRepository.observeMarkersForDay(dayId).first()
                val transcriptText = buildTranscriptText(transcripts)

                val useLlm = modelManager.isLlmReady() && qwenSummarizer.isReady()
                val draft = if (useLlm) {
                    runCatching { qwenSummarizer.summarize(transcriptText, markers) }
                        .getOrElse { ruleBasedSummarizer.summarize(transcriptText, markers) }
                } else {
                    ruleBasedSummarizer.summarize(transcriptText, markers)
                }

                val todos = draft.todos.map { todoDraft ->
                    TodoItem(
                        dayId = dayId,
                        type = todoDraft.type,
                        content = todoDraft.content,
                        sourceMs = todoDraft.atMs ?: transcripts.first().startMs,
                    )
                }
                val summary = DaySummary(
                    dayId = dayId,
                    status = SummaryRunStatus.DONE,
                    summaryText = draft.summaryText,
                    timelineJson = encodeTimeline(draft.timeline),
                    modelName = if (useLlm) MODEL_NAME_QWEN else MODEL_NAME_RULE_BASED,
                )
                summaryRepository.saveSummary(summary, todos)
                recordingRepository.updateSummaryStatus(dayId, com.echolog.app.domain.model.SummaryStatus.DONE)
                EchoResult.success(summary.modelName)
            } catch (throwable: Throwable) {
                EchoResult.failure(
                    code = Constants.ERROR_SUMMARY_FAILED,
                    message = "总结失败：${throwable.message ?: "未知错误"}",
                    cause = throwable,
                )
            }
        }

    /** 构建带 `[HH:mm]` 前缀的转写文本（PromptTemplates 与 RuleBasedSummarizer 共用格式）。 */
    private fun buildTranscriptText(
        transcripts: List<com.echolog.app.domain.model.TranscriptSegment>,
    ): String = buildString {
        transcripts.forEach { segment ->
            append("[")
            append(TimeUtils.formatTime(segment.startMs))
            append("] ")
            appendLine(segment.text)
        }
    }

    /** 时间线 JSON 序列化（domain 层不使用 org.json，手工转义保证纯净可单测）。 */
    private fun encodeTimeline(items: List<TimelineItem>): String {
        if (items.isEmpty()) return "[]"
        val builder = StringBuilder("[")
        items.forEachIndexed { index, item ->
            if (index > 0) builder.append(',')
            builder.append("{\"atMs\":").append(item.atMs)
            builder.append(",\"text\":\"").append(escapeJson(item.text)).append("\"}")
        }
        return builder.append(']').toString()
    }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\t", " ")

    private companion object {
        const val MODEL_NAME_QWEN = "qwen2.5-1.5b-instruct-q4"
        const val MODEL_NAME_RULE_BASED = "rule-based"
    }
}
