package com.echolog.app.domain.usecase

import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SummaryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 清理缓存用例（用户需求 2）。
 *
 * 三个一键操作，各自独立且互不牵连：
 * 1. [deleteAllOriginalAudio] —— 删除所有录音原文件（`.wav`），
 *    保留转写文字与总结内容；
 * 2. [clearDayConvertedArtifacts] —— 清除某天「转化后」的产物
 *    （全天合并 `.wav`、片段同名 `.md` 与 DB 转写分段），保留录音原文件；
 * 3. [deleteAllSummaries] —— 清除所有天的总结内容（总结 / 待办 / 关键时间线），
 *    保留录音与转写文字。
 *
 * 全部操作在 IO 线程执行；失败以 [EchoResult.Failure] 呈现，UI 层可提示原因。
 */
class CleanupCacheUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val summaryRepository: SummaryRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 一键删除所有录音原文件。
     *
     * @return 成功时携带删除的文件数。
     */
    suspend operator fun invoke(): EchoResult<Int> = withContext(ioDispatcher) {
        runCatching { recordingRepository.deleteAllOriginalAudio() }
            .fold(
                onSuccess = { EchoResult.success(it) },
                onFailure = { throwable ->
                    EchoResult.failure(
                        code = ERROR_CLEANUP,
                        message = "删除录音原文件失败：${throwable.message ?: "未知错误"}",
                        cause = throwable,
                    )
                },
            )
    }

    /**
     * 清除某天「转化后」的产物。
     *
     * @param dayId 目标录音日。
     * @return 成功时携带删除的文件数。
     */
    suspend fun clearDayConverted(dayId: String): EchoResult<Int> = withContext(ioDispatcher) {
        runCatching { recordingRepository.clearDayConvertedArtifacts(dayId) }
            .fold(
                onSuccess = { EchoResult.success(it) },
                onFailure = { throwable ->
                    EchoResult.failure(
                        code = ERROR_CLEANUP,
                        message = "清除转写内容失败：${throwable.message ?: "未知错误"}",
                        cause = throwable,
                    )
                },
            )
    }

    /**
     * 一键清除所有天的总结内容。
     *
     * @return 成功时携带被清理的天数。
     */
    suspend fun clearAllSummaries(): EchoResult<Int> = withContext(ioDispatcher) {
        runCatching { summaryRepository.deleteAllSummaries() }
            .fold(
                onSuccess = { EchoResult.success(it) },
                onFailure = { throwable ->
                    EchoResult.failure(
                        code = ERROR_CLEANUP,
                        message = "清除总结内容失败：${throwable.message ?: "未知错误"}",
                        cause = throwable,
                    )
                },
            )
    }

    private companion object {
        /** 清理类错误统一码（复用 E-1002 磁盘写入类语义外的独立标识）。 */
        const val ERROR_CLEANUP = "E-1003"
    }
}
