package com.echolog.app.domain.usecase

import com.echolog.app.ai.asr.AsrEngine
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.DefaultDispatcher
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.model.TranscriptStatus
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 当天逐段转写用例（架构 2.4/4.2，FR-009）。
 *
 * 幂等（架构 2.4）：仅处理 `transcriptStatus` 为 PENDING/FAILED 的片段，
 * DONE 片段跳过；转写结果经仓库 upsert（先删后插）落库，
 * 时间戳叠加片段 `startMs` 转为全天绝对时间（架构 4.2）。
 */
class TranscribeDayUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val asrEngine: AsrEngine,
    private val transcriptExporter: SegmentTranscriptExporter,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 转写当天全部待转写片段。
     *
     * 失败隔离（BUG-1 修复）：单次调用内已失败的片段加入黑名单不再重取——
     * 跨调用重试（充电兜底 Worker）是失败恢复的职责，单次调用不得死循环。
     *
     * @param dayId 目标录音日。
     * @param onSegmentDone 单片段完成回调（本次调用已转写数 / 本次调用待转写总数）。
     * @return 成功时携带本次完成片段数；模型缺失 E-3001 / 推理失败 E-3002。
     */
    suspend operator fun invoke(
        dayId: String,
        onSegmentDone: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): EchoResult<Int> = withContext(defaultDispatcher) {
        try {
            if (!asrEngine.isReady()) {
                return@withContext EchoResult.failure(
                    Constants.ERROR_ASR_MODEL_MISSING,
                    "端侧 ASR 模型未就绪，请先下载模型",
                )
            }
            var completed = 0
            // 本次调用累计待转写总数（进度回调分母，BUG-7 修复：不再用批内偏移虚高）
            var totalCount = 0
            // 本次调用内已失败的片段：标记 FAILED 后不再被 getPendingSegments 重取
            val failedThisRun = mutableSetOf<Long>()
            while (true) {
                val pending = transcriptionRepository.getPendingSegments(dayId, BATCH_SIZE)
                    .filter { it.segmentId !in failedThisRun }
                if (pending.isEmpty()) break
                totalCount += pending.size
                for (segment in pending) {
                    transcriptionRepository.updateSegmentTranscriptStatus(
                        segment.segmentId,
                        TranscriptStatus.RUNNING,
                        null,
                    )
                    try {
                        val timedTexts = asrEngine.transcribe(segment.filePath)
                        // 引擎输出为相对片段起点的 TimedText；叠加 startMs 转全天绝对时间，
                        // 并映射为领域模型 TranscriptSegment 后经仓库 upsert（架构 4.2）
                        val absolute = timedTexts.map { line ->
                            TranscriptSegment(
                                tsId = 0L,
                                segmentId = segment.segmentId,
                                startMs = segment.startMs + line.startMs.coerceAtLeast(0L),
                                endMs = segment.startMs + line.endMs.coerceAtLeast(0L),
                                text = line.text,
                            )
                        }
                        transcriptionRepository.upsertTranscripts(segment.segmentId, absolute)
                        transcriptionRepository.updateSegmentTranscriptStatus(
                            segment.segmentId,
                            TranscriptStatus.DONE,
                            null,
                        )
                        // 用户需求 1：转写成功即导出同名 .md 文字稿（失败不影响主链路）
                        runCatching { transcriptExporter.exportSegment(dayId, segment, absolute) }
                        completed++
                        onSegmentDone(completed, totalCount)
                    } catch (throwable: Throwable) {
                        // 单片段失败不拖垮当天：标记 FAILED 供兜底 Worker 跨调用重试（E-3002），
                        // 但同一调用内不再重取（黑名单），否则失败→重取→再失败会死循环
                        failedThisRun += segment.segmentId
                        transcriptionRepository.updateSegmentTranscriptStatus(
                            segment.segmentId,
                            TranscriptStatus.FAILED,
                            throwable.message,
                        )
                    }
                }
            }
            EchoResult.success(completed)
        } catch (throwable: Throwable) {
            EchoResult.failure(
                code = Constants.ERROR_ASR_FAILED,
                message = "转写失败：${throwable.message ?: "未知错误"}",
                cause = throwable,
            )
        }
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}
