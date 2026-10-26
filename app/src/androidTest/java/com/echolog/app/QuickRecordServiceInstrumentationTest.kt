package com.echolog.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.service.RecordingService
import com.echolog.app.service.notify.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * 「锁屏/状态栏快捷录音」的**真机**验证（QA：设备级行为，不可用 JVM 单测替代的部分）。
 *
 * ## 覆盖的是哪些「只有真机才算数」的事实
 *
 * 1. **渠道真的注册了，且真的独立**：从 [NotificationManager] 读回系统里的渠道对象，
 *    断言它与监听渠道是两条、且**不比通知自身更严格**（PRIVATE/SECRET 会把 PUBLIC 的
 *    通知在锁屏上降级为看不见——这正是「独立渠道」这条设计理由的落点）。
 *    注意断言的是「不得更严格」而不是「字面等于 PUBLIC」：系统**不允许更新已创建渠道的
 *    lockscreenVisibility**（真机实测该渠道的 `lockscreenVisibility` 为 UNSPECIFIED，
 *    因为它是旧版本创建、`install -r` 保留数据所致），断言字面值只会因历史数据而红。
 * 2. **入口通知在真机上真的是常驻、锁屏可见、只有一个操作**：读系统里活跃通知的真实对象，
 *    而不是内存里 build 出来的对象。
 * 3. **入口建立成功后，「入口未建立」提醒（1004）被清掉**：该提醒是 ongoing（用户滑不掉），
 *    入口一旦建成就必须主动 cancel，否则变成甩不掉的打扰。
 * 4. **ACTION_QUICK_START 能到达服务并把通知切回监听态**：与监听通知共用同一 id，
 *    渠道从 `channel_quick_record` 变为 `channel_monitor` 即证明链路走通。
 *
 * ## 设备前提（MIUI 实测）
 *
 * - 麦克风型 FGS 要求应用处于「可见」状态，故每个用例都先用 [ActivityScenario]
 *   把 [MainActivity] 拉起来（不依赖 `input tap`，本机 INJECT_EVENTS 被拒）。
 * - 用例方法名**不得含空格**：androidTest 要转 DEX，DEX 040 不允许 SimpleName 含空格
 *   （中文可以），否则 `dexBuilderDebugAndroidTest` 直接失败。
 * - `handleEnterStandby` 对「已在待命态」会早退（不重启、不清 1004），
 *   因此每个用例都先 `stopService()` 把服务拉回可控状态。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QuickRecordServiceInstrumentationTest {

    private companion object {
        /** 等待通知出现/消失的上限（真机系统调度有延迟，不能用固定 sleep）。 */
        const val POLL_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 250L
    }

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val notificationManager: NotificationManager =
        requireNotNull(context.getSystemService(NotificationManager::class.java))

    private val notificationHelper = NotificationHelper(context)

    /** 前台 Activity 句柄：麦克风型 FGS 需要应用处于可见状态，用例期间必须保持存活。 */
    private var foregroundScenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        // 从「服务不在待命」开始：已在待命时 handleEnterStandby 会早退，用例将无法推进。
        stopService()
    }

    @After
    fun tearDown() {
        stopService()
        runCatching { foregroundScenario?.close() }
        foregroundScenario = null
        // 清掉测试期间人为挂的提醒，避免把设备上的真实状态弄脏。
        runCatching {
            notificationManager.cancel(Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED)
        }
    }

    // ── 用例 ──

    /**
     * 快捷渠道在真机上已注册、独立，且不比通知自身更严格。
     *
     * 这是「独立渠道」设计理由的落点：监听渠道的锁屏可见性是 PRIVATE（录音状态属隐私），
     * 系统在锁屏上取「渠道可见性」与「通知可见性」中**更严格**的一方——
     * 快捷渠道若被设成 PRIVATE/SECRET，入口就会在锁屏上静默消失。
     */
    @Test
    fun `t01_快捷渠道已注册且独立于监听渠道`() {
        val quick = requireNotNull(
            notificationManager.getNotificationChannel(Constants.CHANNEL_QUICK_RECORD),
        ) { "快捷渠道未在真机上注册（应用数据里没有该渠道），通知将发不出去" }
        val monitor = requireNotNull(
            notificationManager.getNotificationChannel(Constants.CHANNEL_MONITOR),
        ) { "监听渠道未注册，测试前提不成立" }

        assertNotEquals(
            "快捷渠道必须独立于监听渠道：复用会被监听渠道的 PRIVATE 可见性降级，入口在锁屏上消失",
            monitor.id,
            quick.id,
        )
        assertNotEquals(
            "快捷渠道不得为 PRIVATE：系统取「渠道可见性」与「通知可见性」中更严格的一方，" +
                "渠道为 PRIVATE 会把 PUBLIC 的入口通知在锁屏上降级为不可见",
            Notification.VISIBILITY_PRIVATE,
            quick.lockscreenVisibility,
        )
        assertNotEquals(
            "快捷渠道不得为 SECRET（比 PRIVATE 更严格，等于入口彻底不可见）",
            Notification.VISIBILITY_SECRET,
            quick.lockscreenVisibility,
        )
        // ── 边界说明（勿"顺手改成等于 PUBLIC"）──────────────────────────────
        // 这里断言的是「渠道**不得比通知更严格**」，而**不是**「渠道 lockscreenVisibility
        // 字面等于 PUBLIC」。原因：Android 系统**不允许更新已创建渠道的
        // lockscreenVisibility / importance**（应用侧改了会被系统忽略）。
        // 本机真机实测 `channel_quick_record` 的 lockscreenVisibility = UNSPECIFIED(-1000)，
        // 因为该渠道是早期版本创建、`adb install -r` 保留数据沿用下来的历史渠道；
        // 卸载重装可让它变成新渠道并采用当前代码里的 PUBLIC，但那会清掉用户数据，
        // 不是本次验证该做的事。
        // UNSPECIFIED(-1000) 在锁屏可见性上的语义等价于 PUBLIC（= 不额外降级），
        // 所以「不得为 PRIVATE/SECRET」才是既真实又能防回归的断言；
        // 若把断言写成 == Notification.VISIBILITY_PUBLIC，
        // 会因历史渠道数据而红，且诱导后人去"修"一个其实没有 bug 的问题。
        // 真正需要保证的「锁屏可见」由 t02 对**通知自身**的
        // visibility == VISIBILITY_PUBLIC 断言覆盖（那一条是新建通知，每次都生效）。
        // ────────────────────────────────────────────────────────────────
        assertEquals(
            "快捷入口是常驻入口，重要度应为 LOW（不该打扰用户）",
            NotificationManager.IMPORTANCE_LOW,
            quick.importance,
        )
    }

    /**
     * 快捷入口通知在真机上：常驻（滑不掉）、锁屏可见、只有一个操作、走独立渠道。
     *
     * 这里读的是**系统里活跃通知的真实对象**，不是内存里 build 出来的对象——
     * 「锁屏上真的能看到并点到入口」只能这样验。
     */
    @Test
    fun `t02_快捷入口通知为常驻锁屏可见且只有一个操作`() {
        val foregroundLaunched = launchForegroundApp()
        stopService()

        val startError = runCatching {
            ContextCompat.startForegroundService(context, standbyIntent())
        }.exceptionOrNull()

        val notification = requireNotNull(
            awaitActiveNotification(Constants.NOTIFICATION_ID_MONITOR)?.notification,
        ) {
            "快捷入口通知未建立。前台 Activity 启动成功=$foregroundLaunched，" +
                "startForegroundService 异常=${startError?.message}，" +
                "系统是否改为发出 1004 失败提醒=" +
                "${activeNotification(Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED) != null}"
        }

        assertNotEquals(
            "快捷入口必须常驻（ongoing）：被误滑掉用户就永久失去入口",
            0,
            notification.flags and Notification.FLAG_ONGOING_EVENT,
        )
        assertEquals(
            "快捷入口必须锁屏可见（通知自身 VISIBILITY_PUBLIC）",
            Notification.VISIBILITY_PUBLIC,
            notification.visibility,
        )
        assertEquals(
            "待命态只提供「开始录音」一个操作：多出来的按钮点了没反应，等于死按钮",
            1,
            notification.actions?.size ?: 0,
        )
        assertEquals(
            "快捷入口必须走独立渠道",
            Constants.CHANNEL_QUICK_RECORD,
            notification.channelId,
        )
    }

    /**
     * 入口建立成功后，「入口未建立」提醒（1004）必须被清掉。
     *
     * 1004 是 **ongoing**（用户滑不掉），所以只能由应用主动 cancel：
     * `handleEnterStandby` 在建立入口前先撤掉它。若这条不成立，用户会同时看到
     * 「入口已建好」和「入口没建好」两条互相矛盾且删不掉的提示。
     */
    @Test
    fun `t03_入口建立成功后失败提醒1004被清除`() {
        // 人为挂一条 1004，模拟「上一次失败残留」
        notificationManager.notify(
            Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED,
            notificationHelper.buildQuickEntryFailedNotification("测试用残留提醒"),
        )
        assertNotNull(
            "前置条件：1004 提醒已挂上",
            activeNotification(Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED),
        )

        launchForegroundApp()
        stopService()
        // 停服务是异步的：等 1001（前台服务通知）消失，确认旧实例真的退干净了，
        // 否则新的 startForegroundService 可能仍打到「已在待命」的旧实例（早退、不清 1004）。
        awaitNotificationAbsent(Constants.NOTIFICATION_ID_MONITOR, timeoutMs = 5_000L)

        runCatching {
            ContextCompat.startForegroundService(context, standbyIntent())
        }

        assertTrue(
            "入口建立成功后必须 cancel 掉 1004「入口未建立」提醒（它是 ongoing，用户滑不掉）",
            awaitNotificationAbsent(Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED),
        )
    }

    /**
     * 点击「开始录音」action：`ACTION_QUICK_START` 能到达服务，并把常驻通知切回监听态。
     *
     * 判据是**同一 id 的通知内容换人**：入口通知走 `channel_quick_record`，
     * 监听通知走 `channel_monitor`，id 都是 [Constants.NOTIFICATION_ID_MONITOR]——
     * 渠道一变即证明 `handleQuickStart → startMonitoringUseCase → handleStart` 整条链路走通。
     */
    @Test
    fun `t04_快捷开始录音action可到达服务并切到监听通知`() {
        launchForegroundApp()
        stopService()
        runCatching {
            ContextCompat.startForegroundService(context, standbyIntent())
        }
        awaitActiveNotification(Constants.NOTIFICATION_ID_MONITOR)

        // 点击「开始录音」：与通知 action 一致——PendingIntent.getForegroundService 直达服务
        runCatching {
            ContextCompat.startForegroundService(context, quickStartIntent())
        }

        assertTrue(
            "ACTION_QUICK_START 未把服务切到监听态（1001 通知的渠道应从 quick_record 变为 monitor）；" +
                "当前渠道=${activeNotification(Constants.NOTIFICATION_ID_MONITOR)?.notification?.channelId}，" +
                "是否已改为发出失败提醒=" +
                "${activeNotification(Constants.NOTIFICATION_ID_ALERT) != null}",
            awaitChannel(Constants.NOTIFICATION_ID_MONITOR, Constants.CHANNEL_MONITOR),
        )
    }

    // ── 辅助 ──

    /** 把应用拉到前台（麦克风型 FGS 需要可见状态；本机不能用 input tap）。 */
    private fun launchForegroundApp(): Boolean {
        val scenario = runCatching { ActivityScenario.launch(MainActivity::class.java) }
        if (scenario.isFailure) return false
        // 保持 Activity 存活直到用例结束：ForegroundService 的「可见状态」由它维持。
        foregroundScenario = scenario.getOrNull()
        return true
    }

    private fun stopService() {
        runCatching {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    private fun standbyIntent(): Intent =
        Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_STANDBY
        }

    private fun quickStartIntent(): Intent =
        Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_QUICK_START
            putExtra(Constants.EXTRA_DAY_ID, TimeUtils.todayDayId())
        }

    private fun activeNotification(id: Int): StatusBarNotification? =
        notificationManager.activeNotifications.firstOrNull { it.id == id }

    private fun awaitActiveNotification(
        id: Int,
        timeoutMs: Long = POLL_TIMEOUT_MS,
    ): StatusBarNotification? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            activeNotification(id)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return activeNotification(id)
    }

    private fun awaitNotificationAbsent(id: Int, timeoutMs: Long = POLL_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (activeNotification(id) == null) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return activeNotification(id) == null
    }

    /** 等待某 id 的通知切换到指定渠道（同一 id 换内容 = 状态推进的信号）。 */
    private fun awaitChannel(id: Int, expectedChannel: String, timeoutMs: Long = POLL_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val notification = activeNotification(id)?.notification
            if (notification != null && notification.channelId == expectedChannel) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }
}
