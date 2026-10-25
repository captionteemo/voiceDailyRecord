package com.echolog.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.core.common.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 模型下载 Worker（架构 2.5，FR-012）。
 *
 * 约束：仅 Wi-Fi（[WorkScheduler.scheduleModelDownload] 的
 * `NetworkType.UNMETERED`）；重试由 WorkManager 退避策略承担。
 *
 * 职责边界：下载主体在 [ModelManager]（状态机 + 断点续传 + 校验，T04），
 * 本 Worker 仅作「充电/Wi-Fi 约束下的后台下载载体」：触发
 * [ModelManager.ensureModels] 后即返回。下载进度经
 * [ModelManager.downloadProgress] 透出，前台模型页可直接消费；
 * 应用进程存活时下载不随 Worker 结束而中断（[ModelManager] 使用应用级作用域）。
 *
 * **权重来源：远端下载**（ModelScope）。早期版本把 1.35GB 权重打进 `assets/`
 * 离线安装，导致 APK 达到 1.6GB 无法分发到代码托管平台，故改为用户自行下载。
 * 「仅安装内置模型」模式随该能力一同移除（相关常量与分支均已清理）。
 *
 * 之所以复用本 Worker 而非新建 `@HiltWorker`：新增 Hilt Worker 会触发 Hilt 在
 * `debugUnitTest` 变体重新生成 `Hilt_EchoLogApplication` 基类，与 `debug` 变体的
 * 字节码转换产物同名冲突，导致单元测试编译失败（自继承错误）。
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelManager: ModelManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // 触发下载：ensureModels() 内部在应用级作用域异步执行，本 Worker 触发后即返回。
        //
        // **不能 await 下载结果**：1.1GB 权重可能下载十几分钟，远超 WorkManager
        // 单次执行的 10 分钟上限（超时会被系统 kill 并记为失败）。
        // 断点续传由 ModelDownloader 的 `.part` 文件负责：进程被杀后下次启动
        // 重新触发时从已有字节继续，不会从头再来。
        //
        // 网络条件由入队方的 Constraints 决定（Wi-Fi = NetworkType.UNMETERED）。
        modelManager.ensureModels()
        return Result.success()
    }
}
