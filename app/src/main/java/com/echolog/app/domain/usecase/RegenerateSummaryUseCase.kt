package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SummaryRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * 重新生成当天总结用例（转写全文页「重新生成总结」入口）。
 *
 * ## 与 [SummarizeDayUseCase] 的关系
 *
 * 本用例是 [SummarizeDayUseCase] 的**用户显式重算**入口：
 * - 以 `force = true` 调用 [SummarizeDayUseCase]——跳过 BUG-10 的「已有非空总结就跳过」
 *   保护（否则用户点了按钮没反应），并先清掉旧总结与旧待办再按当前文字重算；
 * - 生成成功后**确保**日级 `summaryStatus` 为 [SummaryStatus.DONE]（幂等补写，
 *   见 [onSummarizeSucceeded] 的说明）。
 *
 * ## 失败语义：**不向 UI 抛异常**
 *
 * UI 只需要「成功 / 失败 + 可读原因」，因此本用例把任何异常都转成
 * [EchoResult.failure]——上层（ViewModel）可以直接 `when` 分支处理，
 * 不需要 try/catch，也不会因为异常漏到 UI 而崩溃。
 *
 * 错误码策略：
 * - 底层 [SummarizeDayUseCase] 的失败**原样透传**其错误码
 *   （E-4002 总结失败 / E-4003 当天无转写内容）——两者对用户是不同处境：
 *   E-4003 是**确定性**失败（重试无用），UI 不应展示「重试」引导；
 * - 本用例自身产生的失败（落库校验不通过、异常兜底）用 [Constants.ERROR_SUMMARY_FAILED]。
 *
 * ## 失败消息的文案边界
 *
 * 返回的 [EchoResult.Failure.message] 只是**领域层原因**
 * （如「当天无转写内容，无法生成总结」「总结未落库」），**不带**
 * 「重新生成总结失败」前缀——该前缀由 UI 层统一拼接
 * （`strings.xml` 的 `transcript_regenerate_failed` = 「重新生成总结失败：%1$s」）。
 * 在领域层再加一层前缀会让用户看到重复前缀（「重新生成总结失败：重新生成总结失败：……」）。
 *
 * ## 失败时的日级状态
 *
 * `force = true` 会先删除旧总结，因此失败时**确实没有总结可用**——
 * 此时把日级状态置 [SummaryStatus.FAILED]（而不是留在 DONE），
 * 否则 UI 会显示「总结已完成」但总结页是空的（状态与数据不一致）。
 *
 * @param summarizeDayUseCase 总结用例（以 `force = true` 调用）。
 * @param recordingRepository 录音仓库（回写日级总结状态）。
 * @param summaryRepository 总结仓库（落库校验与失败态判定）。
 */
class RegenerateSummaryUseCase @Inject constructor(
    private val summarizeDayUseCase: SummarizeDayUseCase,
    private val recordingRepository: RecordingRepository,
    private val summaryRepository: SummaryRepository,
) {

    /**
     * 重新生成当天总结。
     *
     * @param dayId 目标录音日。
     * @return 成功时携带**新总结正文**（[com.echolog.app.domain.model.DaySummary.summaryText]）；
     *   失败时携带错误码与可读原因（**不抛异常**，[CancellationException] 除外）。
     */
    suspend operator fun invoke(dayId: String): EchoResult<String> {
        if (dayId.isBlank()) {
            return EchoResult.failure(
                code = Constants.ERROR_SUMMARY_FAILED,
                message = "录音日为空",
            )
        }
        return try {
            when (val result = summarizeDayUseCase(dayId, force = true)) {
                is EchoResult.Failure -> onSummarizeFailed(dayId, result)
                is EchoResult.Success -> onSummarizeSucceeded(dayId)
            }
        } catch (cancellation: CancellationException) {
            // 协程取消不是业务失败：原样抛出，保持结构化并发语义
            throw cancellation
        } catch (throwable: Throwable) {
            // 兜底：异常同样不得外泄给 UI；状态按「库里实际有无总结」回写，
            // 避免内层已写 DONE 而这里盲目置 FAILED 造成新的不一致
            reconcileSummaryStatus(dayId)
            EchoResult.failure(
                code = Constants.ERROR_SUMMARY_FAILED,
                message = throwable.message ?: "未知错误",
                cause = throwable,
            )
        }
    }

    /**
     * 生成失败：透传底层错误码，并把日级状态置 FAILED。
     *
     * 「旧总结是否还在」不作为判定依据——`force = true` 已先删除旧总结，
     * 失败后**确实没有总结可用**，状态必须与数据一致。
     */
    private suspend fun onSummarizeFailed(
        dayId: String,
        failure: EchoResult.Failure,
    ): EchoResult<String> {
        setSummaryStatusQuietly(dayId, SummaryStatus.FAILED)
        return EchoResult.failure(
            code = failure.code,
            message = failure.message,
            cause = failure.cause,
        )
    }

    /**
     * 生成成功：校验落库 + 确保日级状态为 DONE。
     *
     * **为什么还要再写一次状态**：[SummarizeDayUseCase] 内部成功时已写过 DONE，
     * 这里再写一次是**幂等补写**——把「日级状态为 DONE」这条不变式收拢到本用例，
     * 避免将来内层实现调整导致转写全文页显示的状态与数据不一致。
     */
    private suspend fun onSummarizeSucceeded(dayId: String): EchoResult<String> {
        val saved = summaryRepository.getSummary(dayId)
        if (saved == null) {
            // 内层报告成功但库里没有总结：属于数据不一致，按失败处理并置 FAILED，
            // 否则 UI 会显示「总结已完成」却取不到内容。
            setSummaryStatusQuietly(dayId, SummaryStatus.FAILED)
            return EchoResult.failure(
                code = Constants.ERROR_SUMMARY_FAILED,
                message = "总结未落库",
            )
        }
        // 状态位只是 UI 提示：回写失败不得把「总结已重算并落库」这一既成事实
        // 反转成失败（否则用户看到失败提示，总结页却已经是新内容）。
        setSummaryStatusQuietly(dayId, SummaryStatus.DONE)
        return EchoResult.success(saved.summaryText)
    }

    /**
     * 异常兜底时的状态回写：按库里**实际有无总结**判定 DONE / FAILED。
     *
     * 不能无条件置 FAILED——异常也可能发生在「总结已落库、状态位写入失败」之后，
     * 此时库里明明有新总结。
     */
    private suspend fun reconcileSummaryStatus(dayId: String) {
        try {
            val saved = summaryRepository.getSummary(dayId)
            setSummaryStatusQuietly(
                dayId,
                if (saved == null) SummaryStatus.FAILED else SummaryStatus.DONE,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // 读库也失败：无从判定，放弃回写状态（真正的失败原因已在返回值里）
        }
    }

    /**
     * 尽力回写日级总结状态。
     *
     * 状态位是 UI 提示而非业务事实：回写失败不得覆盖主结论，但取消信号必须继续
     * 向上传播（`runCatching` 会连 [CancellationException] 一起吞掉，故手写分支）。
     */
    private suspend fun setSummaryStatusQuietly(dayId: String, status: SummaryStatus) {
        try {
            recordingRepository.updateSummaryStatus(dayId, status)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // 状态回写失败：主结论（成功 / 失败 + 原因）已由返回值承载，此处静默
        }
    }
}
