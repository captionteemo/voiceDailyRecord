package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.repository.RecordingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 一键打点用例（架构 2.3/4.1，FR-005）。
 *
 * 监听态/录音态均可打点；文字备注可空。
 * 应用内（T05 TodayScreen）与通知栏（NotificationActionReceiver）共用本用例。
 */
class AddMarkerUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 插入打点标记。
     *
     * @param dayId 目标录音日。
     * @param note 文字备注（可空字符串）。
     * @param atMs 打点时刻（epoch millis，默认当前时间）。
     * @return 成功时携带 markerId。
     */
    suspend operator fun invoke(
        dayId: String,
        note: String,
        atMs: Long = TimeUtils.currentEpochMs(),
    ): EchoResult<Long> = withContext(ioDispatcher) {
        try {
            EchoResult.success(recordingRepository.insertMarker(dayId, atMs, note))
        } catch (throwable: Throwable) {
            EchoResult.failure(
                code = Constants.ERROR_DISK_WRITE,
                message = "打点保存失败：${throwable.message ?: "未知错误"}",
                cause = throwable,
            )
        }
    }
}
