package com.echolog.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.echolog.app.core.common.Constants
import com.echolog.app.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * EchoLog 应用入口。
 *
 * 职责（架构 2.1）：
 * 1. `@HiltAndroidApp` 依赖注入根节点；
 * 2. 预创建通知渠道（架构 7.5：monitor / progress / alert 三渠道）；
 * 3. 实现 [Configuration.Provider]，为 WorkManager 提供 HiltWorkerFactory（Worker 依赖注入）；
 * 4. 入队周期任务（T05）：健康检查 / 充电兜底转写 / 跨天归档（KEEP 策略幂等，
 *    任务内部自带运行前置判断，无监听时为零开销空转）。
 */
@HiltAndroidApp
class EchoLogApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workScheduler: WorkScheduler

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        schedulePeriodicWorkers()
        // BUG-11 修复：模型权重必须在首启自动开始下载。
        //
        // 权重来源为远端下载（ModelScope），不再内置进 APK（1.35GB 会让 APK 达到
        // 1.6GB，无法分发到代码托管平台）。若首启不触发下载，设备上没有任何权重，
        // ASR 永远 not ready → 转写必然失败（E-3001），表现为「无法翻译成文字」。
        //
        // 实现说明：此处**不注入 ModelManager**——Application 注入 ModelManager
        // 会让 SingletonComponent 依赖图在 Hilt 测试变体中产生「Hilt_EchoLogApplication
        // 自继承」的循环依赖错误。改为入队一个 Wi-Fi 约束的 ModelDownloadWorker，
        // 由 Worker 触发应用级作用域上的异步下载（不阻塞 onCreate），
        // 也不影响录音链路（录音不依赖模型）。
        workScheduler.scheduleModelDownload()
    }

    /**
     * WorkManager 配置（Hilt Worker 注入工厂）。
     *
     * BUG-11 根因修复：本项目必须**移除** androidx.startup 中的
     * `androidx.work.WorkManagerInitializer`（见 AndroidManifest.xml）。
     * 该初始化器会在 Application.onCreate() 之前用**默认配置**初始化 WorkManager，
     * 导致 [androidx.work.WorkManagerImpl.getInstance] 的
     * `Configuration.Provider` 分支永不执行 → [workerFactory] 形同虚设 →
     * 所有 `@HiltWorker` 反射实例化失败（"Could not instantiate"）→ doWork() 从未执行。
     *
     * 移除后 WorkManager 变为**惰性初始化**（首次 getInstance() 时才 initialize）。
     * 而 [workerFactory] 是 `lateinit`，若首次 getInstance() 早于 Hilt 注入完成，
     * 会抛 `UninitializedPropertyAccessException`。
     *
     * 保障：Hilt 的 `Hilt_EchoLogApplication.onCreate()` 中 `hiltInternalInject()`
     * **早于** 本类 `onCreate()` 函数体执行（已字节码验证），故此处读到
     * [workerFactory] 时必然已注入；同时本 getter 被 [Configuration.Provider]
     * 在 `getApplicationContext()` 上调用，而 `getApplicationContext()` 返回的正是
     * 本 Application 实例，`instanceof Configuration.Provider` 成立。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** 周期任务统一入队（架构 7.5 唯一工作名；重复调用不打断已入队任务）。 */
    private fun schedulePeriodicWorkers() {
        workScheduler.scheduleHealthCheck()
        workScheduler.scheduleTranscribePending()
        workScheduler.scheduleDayRollover()
    }

    /** 渠道创建幂等，重复调用无副作用；常驻渠道设为 LOW 避免每次状态刷新都响铃。 */
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return

        val monitorChannel = NotificationChannel(
            Constants.CHANNEL_MONITOR,
            getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_monitor_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val progressChannel = NotificationChannel(
            Constants.CHANNEL_PROGRESS,
            getString(R.string.channel_progress_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_progress_desc)
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            Constants.CHANNEL_ALERT,
            getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_alert_desc)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(
            listOf(monitorChannel, progressChannel, alertChannel),
        )
    }
}
