package com.echolog.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.echolog.app.core.common.Constants
import com.echolog.app.domain.usecase.SettleScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台任务统一入队/取消封装（架构 2.5/7.5）。
 *
 * 唯一工作名（架构 7.5，全部经 [Constants] 常量引用，禁止散落字符串）：
 * - `settle-{dayId}`：手动结束结算（OneTime，REPLACE，用户主动触发可重入队）；
 * - `transcribe-pending`：充电兜底转写（Periodic，KEEP，约束充电）；
 * - `health-check`：服务健康检查（Periodic 15min，KEEP）；
 * - `day-rollover`：跨天归档（Periodic 每日，KEEP）；
 * - `model-download`：模型后台下载（OneTime，KEEP，约束 Wi-Fi）。
 *
 * 周期任务入队幂等（KEEP 策略），应用每次启动由
 * [com.echolog.app.EchoLogApplication] 统一调用一次。
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettleScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    // ── 手动结束结算（架构 4.2：DaySettleWorker，合并→转写→总结） ──

    override fun scheduleDaySettle(dayId: String) {
        val request = OneTimeWorkRequest.Builder(DaySettleWorker::class.java)
            .setInputData(workDataOf(Constants.EXTRA_DAY_ID to dayId))
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            "${Constants.WORK_SETTLE_PREFIX}$dayId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** 取消某天的结算任务（用户删除当天等场景）。 */
    fun cancelDaySettle(dayId: String) {
        workManager.cancelUniqueWork("${Constants.WORK_SETTLE_PREFIX}$dayId")
    }

    // ── 充电兜底转写（架构 4.3：TranscribeWorker） ──

    /**
     * 入队周期兜底转写（约束：充电中；电量 ≥50% 在 Worker 内校验）。
     *
     * 周期取 6h：充电约束下 WorkManager 会在插入电源时重新评估，
     * 6h 周期在「及时性」与「低端机唤醒开销」之间取平衡。
     */
    fun scheduleTranscribePending() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()
        // WorkManager 2.10：Kotlin 侧入口为 reified 顶级函数 PeriodicWorkRequestBuilder
        val request = PeriodicWorkRequestBuilder<TranscribeWorker>(
            Duration.ofHours(TRANSCRIBE_INTERVAL_HOURS),
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            Constants.WORK_TRANSCRIBE_PENDING,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    // ── 服务健康检查（架构决策 3 / 4.3：HealthCheckWorker） ──

    /** 入队 15min 周期健康检查（WorkManager 周期任务最小间隔 15min）。 */
    fun scheduleHealthCheck() {
        val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            Duration.ofMinutes(HEALTH_CHECK_INTERVAL_MINUTES),
        )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            Constants.WORK_HEALTH_CHECK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    // ── 跨天归档（架构 4.3：DayRolloverWorker） ──

    /** 入队每日跨天归档检查。 */
    fun scheduleDayRollover() {
        val request = PeriodicWorkRequestBuilder<DayRolloverWorker>(
            Duration.ofHours(DAY_ROLLOVER_INTERVAL_HOURS),
        )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            Constants.WORK_DAY_ROLLOVER,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    // ── 模型后台下载（FR-012：ModelDownloadWorker，仅 Wi-Fi） ──

    /**
     * 入队模型下载（约束：Wi-Fi；重复调用不打断进行中的下载）。
     *
     * 首启由 [com.echolog.app.EchoLogApplication] 调用一次：权重改为远端下载后，
     * 设备上首次使用时 ASR/LLM 权重尚不存在，若无人触发下载，转写必然 E-3001
     * 「模型未就绪」（真机反馈的「无法翻译成文字」）。
     *
     * 这里用 Wi-Fi 约束而非静默强推：1.1GB 权重在蜂窝网络下静默下载既不礼貌
     * 也可能招致用户投诉；计量网络下用户可在「模型下载」页点「重试」立即下载。
     */
    fun scheduleModelDownload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequest.Builder(ModelDownloadWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            Constants.WORK_MODEL_DOWNLOAD,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        /** 兜底转写周期（小时）：充电约束下插入电源即触发评估。 */
        const val TRANSCRIBE_INTERVAL_HOURS = 6L

        /** 健康检查周期（分钟）：WorkManager 周期任务下限 15min。 */
        const val HEALTH_CHECK_INTERVAL_MINUTES = 15L

        /** 跨天归档周期（小时）：每日一次。 */
        const val DAY_ROLLOVER_INTERVAL_HOURS = 24L

        /** 失败重试基准退避（秒）。 */
        const val BACKOFF_DELAY_SECONDS = 30L
    }
}
