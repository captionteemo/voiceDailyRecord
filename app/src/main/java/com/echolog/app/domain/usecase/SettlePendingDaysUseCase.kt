package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.repository.RecordingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 兜底结算扫描用例（架构 2.4/4.3，FR-010 配套）。
 *
 * 由 T05 的 TranscribeWorker（充电 + 电量 ≥50% 约束）驱动：
 * 扫描「SETTLING 未完成」或「DONE 但有片段未转写/未总结」的天，
 * 依次执行合并 → 转写 → 总结，最终置 DONE（进度 100）。
 *
 * 失败策略（BUG-16 修复）：子用例失败经 [EchoResult] 返回值传递，
 * 必须逐段检查——任一环节失败即中止当天（保留 SETTLING 态与错误信息，
 * 供下次兜底重试），不得标记 DONE 造成「假归档」。
 *
 * 幂等保护（BUG-10 修复）：总结步骤经 [SummarizeDayUseCase] 内部的
 * 「已有非空总结则跳过」保护，避免重复结算覆盖用户已勾选的待办状态。
 *
 * 跳过当天仍在监听/待机的天（避免与正在进行的录音竞争）。
 */
class SettlePendingDaysUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val mergeDayAudioUseCase: MergeDayAudioUseCase,
    private val transcribeDayUseCase: TranscribeDayUseCase,
    private val summarizeDayUseCase: SummarizeDayUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 扫描并结算所有待处理的天。
     *
     * @return 成功时携带本次结算完成的天数（单天失败不影响其他天与整体返回）。
     */
    suspend operator fun invoke(): EchoResult<Int> = withContext(ioDispatcher) {
        try {
            var settledCount = 0
            val days = recordingRepository.observeDays().first()
                .filter { it.state == DayState.SETTLING || it.state == DayState.DONE }
                .sortedBy { it.dayId }
            for (day in days) {
                val settled = settleOneDay(day.dayId, day.mergedAudioPath)
                if (settled) settledCount++
            }
            EchoResult.success(settledCount)
        } catch (throwable: Throwable) {
            EchoResult.failure(
                code = Constants.ERROR_MERGE_FAILED,
                message = "兜底结算失败：${throwable.message ?: "未知错误"}",
                cause = throwable,
            )
        }
    }

    /**
     * 结算单一天：合并 → 转写 → 总结 → DONE。
     *
     * @return true = 已归档为 DONE；false = 失败保留 SETTLING（错误已落库）。
     */
    private suspend fun settleOneDay(dayId: String, mergedAudioPath: String?): Boolean {
        // ── 1. 合并（已合并过则跳过，保留原路径与时长） ──
        if (mergedAudioPath == null) {
            when (val mergeResult = mergeDayAudioUseCase(dayId)) {
                is EchoResult.Success -> Unit
                is EchoResult.Failure -> {
                    // 用户需求 4：全天 + 高采样率下合并超 WAV 4GB 上限——
                    // 非重试软失败：跳过合并，继续转写与总结（总时长已回写 DB）
                    if (mergeResult.code == Constants.ERROR_MERGE_OVERSIZE) {
                        recordingRepository.updateSettleProgress(
                            dayId,
                            PROGRESS_AFTER_MERGE,
                            mergeResult.message,
                        )
                        return@settleOneDay settleTranscribeSummarize(dayId)
                    }
                    // 无有效片段的空录音日（服务未开录即被结算等竞态）：直接归档，
                    // 与 DaySettleWorker 的空日语义保持一致，避免兜底无限重试
                    val hasValidSegments = recordingRepository.getSegmentsForDay(dayId)
                        .any { it.durationMs > 0L }
                    if (!hasValidSegments) {
                        finishEmptyDay(dayId)
                        return true
                    }
                    // BUG-11 修复：合并失败【不再】阻断转写。
                    // 转写逐片段进行，完全不依赖合并文件；合并文件只是给用户
                    // 下载/导出的产物。原先写回 5% 并 return false，会让兜底
                    // 扫描（TranscribeWorker）无限重复结算同一失败的天，
                    // 真机上表现为进度永久停在 5%。
                    // 现改为：跳过合并，继续转写与总结，并把失败原因作为提示写入。
                    recordingRepository.updateSettleProgress(
                        dayId,
                        PROGRESS_AFTER_MERGE,
                        mergeNote(mergeResult.message),
                    )
                    return@settleOneDay settleTranscribeSummarize(dayId, skipFinalErrorOverwrite = true)
                }
            }
        }
        recordingRepository.updateSettleProgress(dayId, PROGRESS_AFTER_MERGE, null)
        return settleTranscribeSummarize(dayId)
    }

    /**
     * 结算后段：转写 → 总结 → 归档（合并之后）。
     *
     * 独立成方法以便「合并超限降级」路径复用——该路径跳过合并但后续流程不变。
     *
     * @param skipFinalErrorOverwrite 合并已失败时为 true——归档写 DONE 时保留
     *   合并失败提示（用户需知道合并产物缺失），不被总结阶段的 null 覆盖。
     */
    private suspend fun settleTranscribeSummarize(
        dayId: String,
        skipFinalErrorOverwrite: Boolean = false,
    ): Boolean {
        // ── 2. 转写（E-3001 模型缺失等失败：保留 SETTLING 等下次兜底） ──
        when (val transcribeResult = transcribeDayUseCase(dayId)) {
            is EchoResult.Success -> Unit
            is EchoResult.Failure -> {
                recordingRepository.updateSettleProgress(
                    dayId,
                    PROGRESS_AFTER_MERGE,
                    transcribeResult.message,
                )
                return false
            }
        }
        recordingRepository.updateSettleProgress(dayId, PROGRESS_AFTER_TRANSCRIBE, null)

        // ── 3. 总结（SummarizeDayUseCase 内置「已有非空总结则跳过」保护，BUG-10） ──
        when (val summaryResult = summarizeDayUseCase(dayId)) {
            is EchoResult.Success -> Unit
            is EchoResult.Failure -> {
                // BUG-11 修复：当天无转写内容是**确定性终态**（片段已全部 DONE，
                // 兜底扫描只取 PENDING/FAILED 片段，重试不可能产出文字）。
                // 原行为写回 95% 并 return false → 该天被兜底扫描反复重算、
                // 永远停在 95%（与「合并失败卡 5%」同一失败模式）。
                // 现改为：跳过总结，直接归档 DONE 并写入提示。
                if (summaryResult.code == Constants.ERROR_SUMMARY_NO_CONTENT) {
                    recordingRepository.updateSettleProgress(
                        dayId,
                        PROGRESS_DONE,
                        finalNote(skipFinalErrorOverwrite),
                    )
                    recordingRepository.updateDayState(dayId, DayState.DONE)
                    return true
                }
                recordingRepository.updateSettleProgress(
                    dayId,
                    PROGRESS_AFTER_SUMMARIZE,
                    summaryResult.message,
                )
                return false
            }
        }

        // ── 4. 归档 ──
        // BUG-11 修复：合并已失败时保留合并提示（用户需知道合并产物缺失）
        recordingRepository.updateSettleProgress(
            dayId,
            PROGRESS_DONE,
            if (skipFinalErrorOverwrite) MERGE_FAILED_NOTE else null,
        )
        recordingRepository.updateDayState(dayId, DayState.DONE)
        return true
    }

    /** 空录音日归档：DONE + 进度 100，错误信息说明无内容。 */
    private suspend fun finishEmptyDay(dayId: String) {
        recordingRepository.updateSettleProgress(dayId, PROGRESS_DONE, EMPTY_DAY_NOTE)
        recordingRepository.updateDayState(dayId, DayState.DONE)
    }

    /**
     * 构造合并失败的非致命提示（BUG-11 修复）。
     *
     * 带 [Constants.SETTLE_NOTE_PREFIX] 前缀，UI 据此区分「提示」与「错误」：
     * 提示不展示「重试结算」按钮（合并失败是确定性的，重试仍会失败）。
     */
    private fun mergeNote(reason: String?): String =
        "${Constants.SETTLE_NOTE_PREFIX}合并音频失败（${reason ?: "未知错误"}），转写照常进行"

    /**
     * 合成最终归档备注（BUG-11 修复）。
     *
     * 合并失败与「无转写内容」可能同时发生——两者都要告知用户，
     * 不能只报其一（否则用户会误以为文字稿已生成）。
     */
    private fun finalNote(mergeFailed: Boolean): String =
        if (mergeFailed) {
            "${Constants.SETTLE_NOTE_PREFIX}合并音频失败，且当天未识别到语音内容，已完成录音归档（无文字稿）"
        } else {
            NO_CONTENT_NOTE
        }

    private companion object {
        const val SETTLE_START_PROGRESS = 5
        const val PROGRESS_AFTER_MERGE = 15
        const val PROGRESS_AFTER_TRANSCRIBE = 80
        const val PROGRESS_AFTER_SUMMARIZE = 95
        const val PROGRESS_DONE = 100

        /** 空录音日归档备注（UI 据此展示「当天无录音内容」）。 */
        const val EMPTY_DAY_NOTE = "当天无录音片段"

        /** 合并失败但转写完成时的归档备注（BUG-11）。 */
        const val MERGE_FAILED_NOTE = "${Constants.SETTLE_NOTE_PREFIX}合并音频失败，转写已完成"

        /** 当天无转写内容时的归档备注（BUG-11：确定性终态，不无限重试）。 */
        const val NO_CONTENT_NOTE =
            "${Constants.SETTLE_NOTE_PREFIX}当天未识别到语音内容，已完成录音归档（无文字稿）"
    }
}
