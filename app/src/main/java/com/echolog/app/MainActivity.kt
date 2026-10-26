package com.echolog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.echolog.app.ai.model.ModelKind
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.ai.model.ModelState
import com.echolog.app.core.common.ApplicationScope
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.EchoLog
import com.echolog.app.domain.model.RecordingSettings
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.service.notify.NotificationHelper
import com.echolog.app.ui.model.ModelDownloadScreen
import com.echolog.app.ui.navigation.EchoNavHost
import com.echolog.app.ui.navigation.PrivacyOnboardingScreen
import com.echolog.app.ui.theme.EchoLogTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 日志标签（首启入口建立等一次性引导动作的排查入口）。 */
private const val TAG = "MainActivity"

/**
 * 首启快捷录音入口守卫（**进程级**标记）。
 *
 * 为什么需要守卫：`enterStandby()` 走 `startForegroundService`，若每次
 * `onCreate` / `onResume` 都投递一次，logcat 会刷满重复的起服务记录，
 * 且对已处于待命态的服务是完全无意义的重复投递。
 *
 * 为什么**不落盘**：标记一旦持久化，重装后（DataStore 残留场景除外）以及
 * 每次冷启动都不会再触发，就失去了「开机/重装后自动恢复入口」的效果。
 * 进程内布尔量在冷启动时自然复位 —— 下次冷启动会再建一次入口，
 * 而 `RecordingService.handleEnterStandby()` 对「已在监听/暂停/待命」直接返回，
 * 重复投递天然幂等。
 */
private object QuickRecordEntryGuard {

    @Volatile
    private var claimed: Boolean = false

    /** 同进程内首次调用返回 `true`（取得建入口资格），此后一律返回 `false`。 */
    @Synchronized
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

/**
 * 单 Activity 入口（架构 1.2/2.1）。
 *
 * 职责：
 * 1. 申请监听态前置权限（RECORD_AUDIO + Android 13+ 的 POST_NOTIFICATIONS）；
 * 2. 首启流门控（PRD FR-014/FR-012）：隐私告知（privacy_ack）→ 模型下载
 *    引导（models_ready，可跳过）→ 主界面（[EchoNavHost] 三 Tab）；
 * 3. 首启建立锁屏快捷录音入口（见 [bootstrapQuickRecordEntry]）；
 * 4. 以 Compose 承载界面，导航由 [EchoNavHost] 装配。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var modelManager: ModelManager

    @Inject
    lateinit var serviceController: ServiceController

    @Inject
    lateinit var notificationHelper: NotificationHelper

    /** 一次性引导动作的作用域：随进程存活，避免被 Activity 重建打断。 */
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // 项目红线：授权结果不得静默丢弃。**通知权限被拒会让「锁屏快捷录音入口」
        // 彻底不可用且无任何提示**，故此处必须给可见提示；
        // 录音权限缺失由各动作页在动作时按需提示（保持既有行为）。
        val notificationDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            results[Manifest.permission.POST_NOTIFICATIONS] == false
        if (notificationDenied) {
            Toast.makeText(
                this,
                getString(R.string.permission_notification_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
        // 授权结果已确定（授予或拒绝）——此时才轮到「是否建快捷入口」的决策，
        // 顺序不可颠倒：未授权时发不出通知，起了服务入口也不存在。
        bootstrapQuickRecordEntry()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestCorePermissionsIfNeeded()
        setContent {
            EchoLogTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        settingsFlow = settingsRepository.observeSettings(),
                        modelStatesFlow = modelManager.modelStates,
                        onAcknowledgePrivacy = {
                            settingsRepository.setPrivacyAck(true)
                        },
                        onDisagree = { finish() },
                    )
                }
            }
        }
    }

    /**
     * 应用回到前台：撤销「快捷录音入口未建立」的失败提醒。
     *
     * **为什么必须给消除途径**：那条提醒是 `setOngoing(true)`（滑不掉）
     * + `setAutoCancel(false)`（点击不消失）——这是「一划就没的提醒等于没有」
     * 的反面要求，但代价是若不给出口，它会变成一条**甩不掉的常驻通知**，
     * 等于从「静默失败」跳到「甩不掉的打扰」。
     *
     * 三个消除时机：
     * 1. **入口成功建立**（`RecordingService.handleEnterStandby`）——覆盖「冷启动
     *    重试成功 / 用户重拨开关成功」；保证不变式「失败提醒只在入口确实没建立时才存在」；
     * 2. **用户点击该通知进入应用** —— contentIntent 只负责拉起 `MainActivity`
     *    （PendingIntent 里不放业务逻辑），因此这一条**合流到本方法**；
     * 3. **应用回到前台（本方法）** —— 兜住「通知发出后进程被杀、通知却留在状态栏」
     *    的路径：那种情况下入口其实已需重建，残留的失败提醒是**错的**。
     *
     * **无条件 cancel**：cancel 一个不存在的通知是 no-op，成本近零，因此不去判定
     * 「入口当前是否存在」——不引入新的状态源。
     *
     * 铁律 5：撤通知属旁支动作，失败不得中断主链路。
     */
    override fun onResume() {
        super.onResume()
        runCatching { notificationHelper.cancel(Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED) }
            .onFailure { throwable ->
                EchoLog.w(TAG, "撤下「入口未建立」提醒失败（不影响界面恢复）", throwable)
            }
    }

    /** 录音与通知权限是 FR-001/FR-013 的前置条件；未授权不阻断进入（动作时兜底提示）。 */
    private fun requestCorePermissionsIfNeeded() {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            // 权限已齐：不会走 launcher 回调，需在此处直接触发入口决策，
            // 否则「老用户 / 已授权用户」永远等不到入口建立时机。
            bootstrapQuickRecordEntry()
        }
    }

    /**
     * 首启建立锁屏快捷录音入口（用户需求第一落地面）。
     *
     * **为什么首启必须建**：待命态入口原本只在「一次录音结束之后」
     * （`RecordingService.onAudioLoopExited`）建立。首次安装的用户从未结束过录音，
     * 就永远等不到入口 —— 装完 App 想在锁屏一键录音却找不到，需求直接落空。
     * 因此首启要主动投递一次 `ACTION_STANDBY`。**请勿当冗余代码删除。**
     *
     * 四道闸：
     * 1. 通知权限已授予 —— 未授权发不出通知，起服务等于白起；
     * 2. **录音权限已授予** —— 服务是 microphone 型前台服务，`startForeground`
     *    在缺 `RECORD_AUDIO` 时抛 `SecurityException` ⇒ **进程崩溃**
     *    （P0：装机启动即闪退，见 `RecordingService.handleEnterStandby` 注释）。
     *    缺权限时入口本就无意义（点了也不能录），不建；
     * 3. 进程内只建一次（[QuickRecordEntryGuard]）—— 避免重复 `startForegroundService`；
     * 4. `quickRecordNotificationEnabled == true` —— 开关关闭的用户永不被起服务，
     *    与「开关关闭后服务自行退出」（`RecordingService` 的设置监听）自洽。
     *
     * 第 2、4 道闸在 `RecordingServiceController.enterStandby()` 里**也各有一份**
     * （那里是所有调用方的必经之路）。此处重复第 2 道是为了**连起服务这一步都省掉**，
     * 并与第 1 道对称——两道权限闸放在一起才看得出「入口需要两个权限」。
     *
     * 读取设置是 DataStore IO，放到 [appScope] 异步执行，**不阻塞 onCreate**。
     *
     * 两条失败分支都**不得静默**（项目红线）：
     * - 读设置失败：值未知，按「不建入口」处理（理由见下），并**弹可见通知**
     *   ——静默不建等于「默认开启的功能悄悄不生效」，且用户不知道要重试；
     * - [ServiceController.enterStandby] 失败：实现方已代发可见通知，此处补日志。
     */
    private fun bootstrapQuickRecordEntry() {
        if (!hasNotificationPermission()) return
        if (!hasRecordAudioPermission()) return
        if (!QuickRecordEntryGuard.claim()) return
        appScope.launch {
            // 读失败按 false —— **不是**与「默认值 true」矛盾，两者是不同状态：
            // - 值缺失（首装、DataStore 无此 key）→ SettingsStore 映射 `?: true`，默认开启；
            // - 读取失败（IO 异常）→ 值**未知**。此时按 true 猜，可能给一个**已明确关闭
            //   开关的用户**重新挂上常驻通知（推翻用户已表达的意愿，且通知会一直挂着）；
            //   按 false 只是这一次不建入口，下次冷启动自愈（QuickRecordEntryGuard 不落盘）。
            // 非对称成本：漏建入口可恢复，误起服务是对用户明确选择的侵犯 —— 故取 false。
            val enabledResult = runCatching {
                settingsRepository.observeSettings().first().quickRecordNotificationEnabled
            }
            val enabled = enabledResult.getOrNull()
            if (enabled == null) {
                enabledResult.exceptionOrNull()?.let { throwable ->
                    EchoLog.w(TAG, "读取快捷入口开关失败，本次不建入口", throwable)
                }
                // 用通知而非 Toast：[appScope] 跑在 Default 线程上，Toast 会抛
                // "Can't toast on a thread that has not called Looper.prepare()"，
                // 且该 scope 无 CoroutineExceptionHandler ⇒ 异常会冒泡成进程崩溃。
                notificationHelper.notify(
                    Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED,
                    notificationHelper.buildQuickEntryFailedNotification(
                        getString(R.string.notify_quick_entry_reason_settings),
                    ),
                )
                return@launch
            }
            if (!enabled) return@launch
            serviceController.enterStandby().onFailure { failure ->
                // 不带 cause：Controller 内部已用完整堆栈打过一次，此处只留业务上下文
                EchoLog.w(TAG, "快捷入口建立失败：${failure.code} ${failure.message}")
            }
        }
    }

    /** Android 13+ 才有 `POST_NOTIFICATIONS`；低版本视为已授予。 */
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 是否持有录音权限。
     *
     * 不只是「录音需要它」：快捷入口走的是 microphone 型前台服务，
     * `Service.startForeground` 在缺 `RECORD_AUDIO` 时会抛 `SecurityException`
     * 并**崩掉进程**。故建立入口前必须先有这道闸。
     */
    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * 首启流根容器：隐私告知 → 模型引导 → 主界面。
 *
 * - `settings == null`：DataStore 首帧未到达（留空一瞬，不阻断）；
 * - `!privacyAck`：隐私告知页（不同意即退出，FR-014）；
 * - `!modelsReady`：模型下载引导页（本次会话可跳过；主界面仍有提示卡）；
 * - 否则进入三 Tab 主界面。
 *
 * @param settingsFlow 设置流（privacyAck / modelsReady 判定）。
 * @param modelStatesFlow 模型状态流（空 map 视为未就绪）。
 * @param onAcknowledgePrivacy 写 privacy_ack（suspend，调用方协程内执行）。
 * @param onDisagree 退出应用。
 */
@Composable
private fun AppRoot(
    settingsFlow: Flow<RecordingSettings>,
    modelStatesFlow: StateFlow<Map<ModelKind, ModelState>>,
    onAcknowledgePrivacy: suspend () -> Unit,
    onDisagree: () -> Unit,
) {
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val modelStates by modelStatesFlow.collectAsStateWithLifecycle()
    val modelsReady = modelStates.isNotEmpty() &&
        modelStates.values.all { it == ModelState.READY }
    var skipModelGuide by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (settings?.privacyAck) {
        null -> Unit // 设置读取中：留空一瞬

        false -> PrivacyOnboardingScreen(
            onAcknowledge = { scope.launch { onAcknowledgePrivacy() } },
            onDisagree = onDisagree,
        )

        true -> if (!modelsReady && !skipModelGuide) {
            ModelDownloadScreen(
                onBack = null,
                onSkip = { skipModelGuide = true },
            )
        } else {
            EchoNavHost()
        }
    }
}
