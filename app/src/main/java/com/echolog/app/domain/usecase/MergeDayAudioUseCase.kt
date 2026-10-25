package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.FileUtils
import com.echolog.app.data.media.AudioMerger
import com.echolog.app.data.media.MergeOversizeException
import com.echolog.app.data.media.StorageLocationManager
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 音频路径提供者接缝（T04 引入）。
 *
 * domain 层不依赖 Android SDK，而路径生成需要 `Context`（`FileUtils`）；
 * 本接口由 data 层实现（`AndroidAudioPathProvider`），单测可传假路径。
 */
interface AudioPathProvider {

    /** 全天合并文件路径（`{filesDir}/audio/{dayId}_全天合并.wav`）。 */
    fun mergedDayPath(dayId: String): String

    /** 片段文件路径（`{filesDir}/audio/{dayId}_第{ordinal}次.wav`，用户需求 1）。 */
    fun segmentPath(dayId: String, ordinal: Int): String
}

/**
 * 当天片段合并用例（架构 2.4/4.2，FR-007）。
 *
 * PCM 直拼免转码（架构 1.4 决策 3）；完成后回写 `totalRecordMs` 与 `mergedAudioPath`。
 * 用户需求 3：选自定存储目录时，合并文件额外复制一份到该目录。
 */
class MergeDayAudioUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val audioPathProvider: AudioPathProvider,
    private val settingsRepository: SettingsRepository,
    private val storageLocationManager: StorageLocationManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 合并当天全部有效片段为全天 WAV。
     *
     * 超大天降级（用户需求 4：全天录音 + 44.1k/48k 采样率）：
     * 16kHz 单声道全天约 2.76GB，已逼近 WAV（RIFF）4GB 上限；
     * 高采样率下合并结果必然超限。此时**不产出**合并文件，但把当天的
     * 总时长如实写入 DB（`totalRecordMs`），并以 [Constants.ERROR_MERGE_OVERSIZE]
     * 返回**非重试**的软失败——片段本身完好，转写与总结照常进行。
     *
     * @param dayId 目标录音日。
     * @param onProgress 进度回调（0~100，按片段个数估算）。
     * @return 成功时携带合并文件路径；无片段/合并失败携带 E-5001。
     */
    suspend operator fun invoke(
        dayId: String,
        onProgress: (Int) -> Unit = {},
    ): EchoResult<String> = withContext(ioDispatcher) {
        try {
            // BUG-11 修复：等待片段收尾落库，消除「结束录音」的时序竞态。
            // 背景：RecordingService.handleStop 只投递 Intent 就返回，片段的
            // durationMs（由 finishSegment 派生）要等音频循环退出（≤1 帧 25ms
            // + 协程调度）后才写入 DB。若此刻就开始读片段，最后一个片段会因
            // durationMs=0 被过滤 → 音频丢失，甚至整天被判为「无片段」。
            val segments = awaitSettledSegments(dayId)
                .filter { it.durationMs > 0L && File(it.filePath).isFile }
            if (segments.isEmpty()) {
                return@withContext EchoResult.failure(Constants.ERROR_MERGE_FAILED, "当天无可合并片段")
            }
            val totalRecordMs = segments.sumOf { it.durationMs }
            val outPath = audioPathProvider.mergedDayPath(dayId)
            try {
                AudioMerger().mergeSegments(
                    paths = segments.map { it.filePath },
                    outPath = outPath,
                    onProgress = onProgress,
                )
            } catch (oversize: MergeOversizeException) {
                // 超 WAV 4GB 上限：删除可能的半成品，回写总时长，跳过合并
                FileUtils.deleteFile(outPath)
                recordingRepository.updateMergeResult(dayId, totalRecordMs, null)
                return@withContext EchoResult.failure(
                    code = Constants.ERROR_MERGE_OVERSIZE,
                    message = oversize.message ?: "当天音频超出 WAV 4GB 上限，已跳过合并",
                    cause = oversize,
                )
            }
            recordingRepository.updateMergeResult(dayId, totalRecordMs, outPath)
            // 用户需求 3：选自定目录时复制一份全天合并音频（内部路径仍是数据源）
            storageLocationManager.copyToUserDir(
                source = File(outPath),
                treeUri = settingsRepository.getSettings().storageTreeUri,
                mimeType = StorageLocationManager.MIME_AUDIO_WAV,
            )
            EchoResult.success(outPath)
        } catch (throwable: Throwable) {
            EchoResult.failure(
                code = Constants.ERROR_MERGE_FAILED,
                message = "合并失败：${throwable.message ?: "未知错误"}",
                cause = throwable,
            )
        }
    }

    /**
     * 读取当天片段，并等待所有片段收尾落库（BUG-11 修复：消除时序竞态）。
     *
     * 判定「已收尾」：片段的 `durationMs > 0`（由 `finishSegment` 的
     * `duration_ms = endMs - start_ms` 派生）。存在 `durationMs == 0` 的片段
     * 说明其音频循环尚未退出、收尾尚未落库 —— 此时稍候重读。
     *
     * @param dayId 目标录音日。
     * @return 已收尾的片段列表（顺序按起始时间升序）。
     */
    private suspend fun awaitSettledSegments(
        dayId: String,
    ): List<AudioSegment> {
        var segments = recordingRepository.getSegmentsForDay(dayId)
        var waitedMs = 0L
        while (segments.any { it.durationMs <= 0L } && waitedMs < MAX_SETTLE_WAIT_MS) {
            delay(SEGMENT_POLL_INTERVAL_MS)
            waitedMs += SEGMENT_POLL_INTERVAL_MS
            segments = recordingRepository.getSegmentsForDay(dayId)
        }
        return segments
    }

    private companion object {
        /**
         * 片段收尾等待上限（毫秒）：超过则按现状继续，避免无限等待。
         *
         * QA 核查点 C：这是**防挂死安全网**，只在服务已死（片段永远不收尾）时
         * 才跑满；正常「点结束录音」场景下音频循环 ≤1 帧（25ms）即退出，
         * 收尾随即落库，通常 1~2 次轮询即满足。
         */
        const val MAX_SETTLE_WAIT_MS = 3_000L

        /**
         * 片段收尾轮询间隔（毫秒）。
         *
         * QA 核查点 C-2c：由 100ms 收紧到 50ms，让「已收尾」判定更快落地，
         * 减少用户看到进度停滞的时长；代价是 DB 读取次数翻倍（上限 60 次），
         * 代价可接受。
         */
        const val SEGMENT_POLL_INTERVAL_MS = 50L
    }
}
