package com.echolog.app.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.echolog.app.R
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.EchoLog
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.usecase.MergeDayAudioUseCase
import com.echolog.app.domain.usecase.SummarizeDayUseCase
import com.echolog.app.domain.usecase.TranscribeDayUseCase
import com.echolog.app.service.notify.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * 手动结束结算 Worker（架构 2.5/4.2，FR-006/007/009/011）。
 *
 * 流程（进度以 Room `settleProgress` 为唯一真相源，架构 7.5；
 * 本 Worker 只负责推进并同步前台进度通知）：
 * 1. 合并当天片段为全天 WAV（进度 5 → 15）；
 * 2. 逐段转写（进度 → 80）；
 * 3. 生成总结（进度 → 95）；
 * 4. 置 DONE（进度 100）并撤下进度通知。
 *
 * 失败策略（架构 7.4，BUG-11 修复后）：
 * - 合并失败（E-5001，**确定性失败**）：**不再** [Result.retry] 无限重试，
 *   改为降级为「跳过合并 → 继续转写与总结」，失败原因写入
 *   [com.echolog.app.domain.model.RecordingDay.settleError] 让用户知情
 *   （原行为：写回 5% + 无限 retry → 真机永久卡在 5%）；
 * - 合并超限（E-5002）：非重试软失败，跳过合并继续转写与总结；
 * - 当天无任何片段：直接归档为 DONE（空录音日，不生成总结）；
 * - 转写模型缺失（E-3001）：[Result.failure]（重试也无济于事，UI 引导下载模型），
 *   其余转写失败重试；
 * - 总结失败：重试一次仍失败则由 UI 呈现错误
 *   （[com.echolog.app.domain.model.RecordingDay.settleError]）；
 * - **任何未捕获异常**：总兜底写入 `settleError` 并返回 [Result.failure]
 *   （否则用户只看到进度停在 5% 且无任何错误提示）。
 *
 * 当天无任何片段时直接归档为 DONE（空录音日，不生成总结）。
 *
 * 进度通知经 [stageUpdates] 事件流驱动：用例的进度回调是非 suspend 的普通
 * lambda（`(Int) -> Unit`），无法直接调用 suspend 的
 * [setForeground][androidx.work.CoroutineWorker.setForeground]，故由专用
 * 协程消费事件流代为刷新前台通知。
 */
@HiltWorker
class DaySettleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mergeDayAudioUseCase: MergeDayAudioUseCase,
    private val transcribeDayUseCase: TranscribeDayUseCase,
    private val summarizeDayUseCase: SummarizeDayUseCase,
    private val recordingRepository: RecordingRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(
            applicationContext.getString(R.string.worker_stage_prepare),
            SETTLE_START_PROGRESS,
        )

    override suspend fun doWork(): Result {
        val dayId = inputData.getString(Constants.EXTRA_DAY_ID)
            ?: return Result.failure()

        EchoLog.i(TAG, "结算开始: dayId=$dayId attempt=${runAttemptCount}")

        runCatching {
            setForeground(
                buildForegroundInfo(
                    applicationContext.getString(R.string.worker_stage_prepare),
                    SETTLE_START_PROGRESS,
                ),
            )
        }.onFailure {
            // 前台通知起不来不应阻断结算主链路（不同 ROM 对 FGS 类型权限校验有差异）
            EchoLog.w(TAG, "setForeground(准备) 失败，继续执行结算", it)
        }

        val stageUpdates = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 16)

        return coroutineScope {
            // 进度通知协程：消费阶段事件并刷新前台通知（API 31+ 后台启动限制
            // 可能抛异常，逐次 runCatching 降级为「只推 DB 进度」）
            val notifier = launch {
                stageUpdates.collect { (stage, percent) ->
                    runCatching {
                        setForeground(buildForegroundInfo(stage, percent))
                    }.onFailure {
                        EchoLog.w(TAG, "setForeground($stage) 失败", it)
                    }
                }
            }
            // BUG-11 修复：总兜底——任何未捕获异常都必须写入 settleError，
            // 否则用户只会看到进度停在 5% 且无任何错误提示（真机已复现该现象）。
            val result = try {
                runSettlePipeline(dayId, stageUpdates)
            } catch (throwable: Throwable) {
                EchoLog.e(TAG, "结算链路异常: dayId=$dayId", throwable)
                runCatching {
                    // QA 修复：已归档（DONE/100）的天**不得**被回写成 5%——
                    // 那正是用户报告的「进度卡在 5%」症状（此时当天其实已结算完成）。
                    val current = recordingRepository.getDay(dayId)
                    if (current == null || current.settleProgress < PROGRESS_DONE) {
                        recordingRepository.updateSettleProgress(
                            dayId,
                            SETTLE_START_PROGRESS,
                            "结算异常：${throwable.message ?: "未知错误"}",
                        )
                    }
                }
                Result.failure()
            }
            notifier.cancel()
            result
        }
    }

    /**
     * 结算主链：合并 → 转写 → 总结 → 归档。
     *
     * @param stageUpdates 阶段进度事件（stage 文案资源键已解析为字符串 + 0~100）。
     */
    private suspend fun runSettlePipeline(
        dayId: String,
        stageUpdates: MutableSharedFlow<Pair<String, Int>>,
    ): Result {
        // ── 1. 合并（架构 1.4 决策 3：PCM 直拼免转码） ──
        // BUG-11 修复：合并开始即写 DB 进度（此前只在失败时写 DB，成功路径从不写，
        // 导致用户看到的进度从 5% 直接跳到 80%，且前台通知起不来时永远停在 5%）。
        runCatching {
            recordingRepository.updateSettleProgress(dayId, PROGRESS_MERGING, null)
        }
        EchoLog.i(TAG, "开始合并: dayId=$dayId")
        val mergeResult = mergeDayAudioUseCase(dayId) { percent ->
            // onProgress 回调为 0~100，映射到结算进度区间 5~15
            val settlePercent = SETTLE_START_PROGRESS +
                (percent * (PROGRESS_AFTER_MERGE - SETTLE_START_PROGRESS) / 100)
            stageUpdates.tryEmit(
                applicationContext.getString(R.string.worker_stage_merge) to settlePercent,
            )
        }
        if (mergeResult.isFailure) {
            val failure = mergeResult.failureOrNull() ?: return Result.retry()
            // 用户需求 4：全天录音 + 高采样率下合并结果超出 WAV 4GB 上限——
            // 非重试的软失败：片段完好，跳过合并继续转写与总结（总时长已回写 DB）
            if (failure.code == Constants.ERROR_MERGE_OVERSIZE) {
                EchoLog.w(TAG, "当天音频超出 WAV 4GB 上限，跳过合并: $dayId")
                stageUpdates.tryEmit(
                    applicationContext.getString(R.string.worker_stage_merge) to PROGRESS_AFTER_MERGE,
                )
                return@runSettlePipeline transcribeSummarizeArchive(dayId, stageUpdates)
            }
            val noSegmentHint = applicationContext.getString(R.string.worker_error_no_segment)
            if (failure.code != Constants.ERROR_MERGE_FAILED ||
                !failure.message.contains(noSegmentHint)
            ) {
                // BUG-11 修复：合并失败【不再】阻断转写。
                // 转写是逐片段进行的（TranscribeDayUseCase → getPendingSegments），
                // 完全不依赖合并文件；合并文件只是给用户下载/导出的产物。
                // 因此合并失败时降级为「跳过合并，继续转写与总结」，
                // 并把失败原因写入 settleError 让用户知情（UI 可见）。
                // 原行为是写回 5% + Result.retry()，而 WorkManager 的 retry 无次数上限，
                // 对确定性失败（如采样率混用）会无限重试 → 真机上永久卡在 5%。
                EchoLog.e(TAG, "合并失败，降级为跳过合并继续转写: $dayId", failure.cause)
                stageUpdates.tryEmit(
                    applicationContext.getString(R.string.worker_stage_merge) to PROGRESS_AFTER_MERGE,
                )
                return@runSettlePipeline transcribeSummarizeArchive(
                    dayId,
                    stageUpdates,
                    mergeErrorHint = mergeNote(failure.message),
                )
            }
            // 当天无片段：空录音日，直接归档（跳过转写/总结）
            finishEmptyDay(dayId)
            return Result.success()
        }
        EchoLog.i(TAG, "合并完成: dayId=$dayId")
        stageUpdates.tryEmit(
            applicationContext.getString(R.string.worker_stage_merge) to PROGRESS_AFTER_MERGE,
        )
        // BUG-11 修复：合并成功后把 15 写入 DB（此前只更新前台通知，DB 进度不推进）
        runCatching {
            recordingRepository.updateSettleProgress(dayId, PROGRESS_AFTER_MERGE, null)
        }

        return transcribeSummarizeArchive(dayId, stageUpdates)
    }

    /**
     * 结算后段：转写 → 总结 → 归档（合并之后）。
     *
     * 独立成方法以便「合并超限降级」路径复用——该路径跳过合并但后续流程不变。
     *
     * @param mergeErrorHint 合并阶段的失败提示（非空时作为结算备注写入 DB，
     *   让用户知道合并产物缺失，但转写已完成）。
     */
    private suspend fun transcribeSummarizeArchive(
        dayId: String,
        stageUpdates: MutableSharedFlow<Pair<String, Int>>,
        mergeErrorHint: String? = null,
    ): Result {
        // ── 2. 转写（架构 4.2：片段间串行，模型缺失不重试风暴） ──
        EchoLog.i(TAG, "开始转写: dayId=$dayId")
        // BUG-11 修复：转写进度实时写 DB（UI 进度真相源是 Room，不依赖前台通知）。
        // onSegmentDone 是非 suspend 回调，无法直接调用 suspend 的仓储方法，
        // 故经由「单一写入协程 + 无界通道」异步落库。
        //
        // QA 修复（原实现 `progressScope.cancel()` 有两处竞态）：
        // 1. **乱序**：多个 `progressScope.launch` 并发写库，IO 调度器不保证完成顺序，
        //    低进度可能覆盖高进度（UI 进度条来回跳）。
        // 2. **截断 / 回退**：`cancel()` 只发出取消信号，不等待在途写入完成——
        //    (a) 尚未开始的写入被丢弃 → DB 进度停在中途；
        //    (b) 已在执行的写入可能**晚于**归档的 100% 落库 → 进度从 100 回退到
        //        中途值，并把 settleError（合并失败提示）覆盖成 null。
        // 改为「单写入协程 + 通道」后：写入严格 FIFO 有序；转写结束关闭通道并
        // `join()` 写入协程，确保所有进度已落库且早于后续的 80/95/100 写入。
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val progressChannel = Channel<Int>(Channel.UNLIMITED)
        val progressWriter = progressScope.launch {
            for (percent in progressChannel) {
                runCatching {
                    recordingRepository.updateSettleProgress(dayId, percent, null)
                }
            }
        }
        val transcribeResult = try {
            transcribeDayUseCase(dayId) { done, total ->
                val percent = if (total <= 0) PROGRESS_AFTER_TRANSCRIBE else {
                    PROGRESS_AFTER_MERGE +
                        (done * (PROGRESS_AFTER_TRANSCRIBE - PROGRESS_AFTER_MERGE) / total)
                }
                stageUpdates.tryEmit(
                    applicationContext.getString(R.string.worker_stage_transcribe) to percent,
                )
                progressChannel.trySend(percent)
            }
        } finally {
            // 关闭通道 → 写入协程处理完队列后退出；join 确保在途写入全部落库。
            // finally 保证异常路径下写入协程也会退出（不泄漏协程）。
            progressChannel.close()
            runCatching { progressWriter.join() }
            progressScope.cancel()
        }
        if (transcribeResult.isFailure) {
            val failure = transcribeResult.failureOrNull()
            EchoLog.e(TAG, "转写失败: dayId=$dayId", failure?.cause)
            runCatching {
                recordingRepository.updateSettleProgress(
                    dayId,
                    PROGRESS_AFTER_MERGE,
                    failure?.message
                        ?: applicationContext.getString(R.string.worker_error_transcribe),
                )
            }
            return if (failure?.code == Constants.ERROR_ASR_MODEL_MISSING) {
                // 模型未就绪：重试无意义，等用户下载后由 UI 重触发
                Result.failure()
            } else {
                Result.retry()
            }
        }
        EchoLog.i(TAG, "转写完成: dayId=$dayId")
        stageUpdates.tryEmit(
            applicationContext.getString(R.string.worker_stage_transcribe) to
                PROGRESS_AFTER_TRANSCRIBE,
        )

        // ── 3. 总结（LLM 失败已在用例内降级规则兜底，此处失败为硬错误） ──
        stageUpdates.tryEmit(
            applicationContext.getString(R.string.worker_stage_summarize) to
                PROGRESS_AFTER_SUMMARIZE,
        )
        runCatching {
            recordingRepository.updateSettleProgress(dayId, PROGRESS_AFTER_SUMMARIZE, null)
        }
        val summarizeResult = summarizeDayUseCase(dayId)
        if (summarizeResult.isFailure) {
            val failure = summarizeResult.failureOrNull()
            // BUG-11 修复：当天无转写内容是**确定性终态**（片段已全部 DONE，
            // 重试不可能产出文字）。若按普通失败 Result.retry()，
            // WorkManager 会无限退避重试（retry 无次数上限）→ 该天永久停在 95%。
            // 处理：降级为「无总结但归档 DONE」，并写入提示让用户知情。
            if (failure?.code == Constants.ERROR_SUMMARY_NO_CONTENT) {
                EchoLog.w(TAG, "当天无转写内容，跳过总结并归档: $dayId")
                stageUpdates.tryEmit(
                    applicationContext.getString(R.string.worker_stage_summarize) to PROGRESS_DONE,
                )
                runCatching {
                    recordingRepository.updateSettleProgress(
                        dayId,
                        PROGRESS_DONE,
                        finalNote(mergeErrorHint),
                    )
                    recordingRepository.updateDayState(dayId, DayState.DONE)
                }
                runCatching { notificationHelper.cancel(Constants.NOTIFICATION_ID_PROGRESS) }
                    .onFailure { EchoLog.w(TAG, "撤下进度通知失败（不影响归档结果）", it) }
                return Result.success()
            }
            runCatching {
                recordingRepository.updateSettleProgress(
                    dayId,
                    PROGRESS_AFTER_SUMMARIZE,
                    failure?.message
                        ?: applicationContext.getString(R.string.worker_error_summarize),
                )
            }
            return Result.retry()
        }

        // ── 4. 归档 ──
        runCatching {
            recordingRepository.updateSettleProgress(dayId, PROGRESS_DONE, mergeErrorHint)
            recordingRepository.updateDayState(dayId, DayState.DONE)
        }
        // QA 修复：撤下进度通知失败**不得**中断结算——此处若抛出，会被 doWork()
        // 的总兜底捕获并把进度**回写成 5%**（用户又看到「卡在 5%」），
        // 而此时当天其实已经归档为 DONE/100 了。
        runCatching { notificationHelper.cancel(Constants.NOTIFICATION_ID_PROGRESS) }
            .onFailure { EchoLog.w(TAG, "撤下进度通知失败（不影响结算结果）", it) }
        return Result.success()
    }

    /** 空录音日归档：DONE + 进度 100，错误信息说明无内容。 */
    private suspend fun finishEmptyDay(dayId: String) {
        runCatching {
            recordingRepository.updateSettleProgress(
                dayId,
                PROGRESS_DONE,
                applicationContext.getString(R.string.worker_empty_day_note),
            )
            recordingRepository.updateDayState(dayId, DayState.DONE)
        }
        // 同上：通知取消失败不得让空录音日归档回退到 5%
        runCatching { notificationHelper.cancel(Constants.NOTIFICATION_ID_PROGRESS) }
            .onFailure { EchoLog.w(TAG, "撤下进度通知失败（不影响归档结果）", it) }
    }

    /**
     * 构造合并失败的非致命提示（BUG-11 修复）。
     *
     * 带 [Constants.SETTLE_NOTE_PREFIX] 前缀，UI 据此区分「提示」与「错误」：
     * 提示不展示「重试结算」按钮（合并失败是确定性的，重试仍会失败）。
     */
    private fun mergeNote(reason: String?): String =
        "${Constants.SETTLE_NOTE_PREFIX}合并音频失败（${reason ?: "未知错误"}），转写已照常进行"

    /**
     * 当天无转写内容的归档提示（BUG-11 修复）。
     *
     * 同样是**确定性**终态（重试无意义），故用提示前缀而非错误前缀，
     * UI 不展示「重试结算」按钮。
     */
    private fun noContentNote(): String =
        "${Constants.SETTLE_NOTE_PREFIX}当天未识别到语音内容，已完成录音归档（无文字稿）"

    /**
     * 合成最终归档备注（BUG-11 修复）。
     *
     * 合并失败与「无转写内容」可能同时发生——此时两者都要告知用户，
     * 不能只报其一（否则用户会误以为文字稿已生成）。
     */
    private fun finalNote(mergeErrorHint: String?): String =
        if (mergeErrorHint == null) {
            noContentNote()
        } else {
            "${Constants.SETTLE_NOTE_PREFIX}合并音频失败，且当天未识别到语音内容，已完成录音归档（无文字稿）"
        }

    /** 前台进度通知（channel_progress，架构 7.5）。 */
    private fun buildForegroundInfo(stage: String, progressPercent: Int): ForegroundInfo {
        val dayId = inputData.getString(Constants.EXTRA_DAY_ID).orEmpty()
        val notification: Notification =
            notificationHelper.buildProgressNotification(dayId, stage, progressPercent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ForegroundInfo(
                Constants.NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(Constants.NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private companion object {
        const val TAG = "DaySettleWorker"
        const val SETTLE_START_PROGRESS = 5

        /**
         * 合并进行中的进度（BUG-11 修复）。
         *
         * 此前合并阶段从不写 DB 进度，仅更新前台通知——前台通知起不来时
         * 用户看到的进度会永远停在 5%。
         */
        const val PROGRESS_MERGING = 8
        const val PROGRESS_AFTER_MERGE = 15
        const val PROGRESS_AFTER_TRANSCRIBE = 80
        const val PROGRESS_AFTER_SUMMARIZE = 95
        const val PROGRESS_DONE = 100
    }
}
