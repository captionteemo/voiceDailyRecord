package com.echolog.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.data.local.EchoDatabase
import com.echolog.app.service.RecordingService
import com.echolog.app.service.notify.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
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
 * - **不能用 `ActivityScenario.launch`**：它阻塞等待 Activity 进入 `RESUMED`，而本机
 *   息屏/锁屏时拿不到 `mResumedActivity`（只有 `mLastPausedActivity`）⇒ 必然挂死
 *   （实测卡满 9 分钟无进展）。这是**环境问题，不是产品缺陷**。
 *   故这里改用**非阻塞** `startActivity`（仅把任务拉到前台，立即返回）。
 * - **麦克风型 FGS 的「可见状态」由服务自身已在前台满足**：`RecordingService` 是
 *   START_STICKY，进程存活期间服务实例一直在；本用例先确认服务存在，再投递动作。
 * - `handleEnterStandby` 对「已在待命态」会早退（不重启、不清 1004），
 *   因此每个用例都先 `stopService()` 并等 1001 消失，把服务拉回可控状态。
 * - 用例方法名**不得含空格**：androidTest 要转 DEX，DEX 040 不允许 SimpleName 含空格
 *   （中文可以），否则 `dexBuilderDebugAndroidTest` 直接失败。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QuickRecordServiceInstrumentationTest {

    private companion object {
        /** 等待通知出现/消失的上限（真机系统调度有延迟，不能用固定 sleep）。 */
        const val POLL_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 250L

        /**
         * `RecordingService.resetServiceStateToIdle` 的日志文案（两处必须一致）。
         *
         * 之所以要「读日志」而不是读内存态：`RecordingStateHolder` 是 Hilt 单例，
         * androidTest 未接 `HiltTestRunner`，测试进程拿不到注入后的同一个实例；
         * 而 `RecordingUiState` 又没有对外暴露的读出口。日志是当前唯一可观测的落点。
         */
        const val RESET_MARKER = "服务状态复位为 IDLE"
    }

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val notificationManager: NotificationManager =
        requireNotNull(context.getSystemService(NotificationManager::class.java))

    private val notificationHelper = NotificationHelper(context)

    @Before
    fun setUp() {
        // 从「服务不在待命」开始：已在待命时 handleEnterStandby 会早退，用例将无法推进。
        stopService()
        awaitNotificationAbsent(Constants.NOTIFICATION_ID_MONITOR, timeoutMs = 5_000L)
    }

    @After
    fun tearDown() {
        stopService()
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
        assumeNoMonitoringDay()
        val foregroundLaunched = launchForegroundApp()
        stopService()
        awaitNotificationAbsent(Constants.NOTIFICATION_ID_MONITOR, timeoutMs = 5_000L)

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
     * 1004 是 **ongoing**（用户滑不掉），所以只能由应用主动 cancel。
     *
     * ## 断言的是「不变式」，不是「唯一 cancel 来源」
     *
     * 应用里有**两处**会 cancel 1004：
     * 1. `RecordingService.handleEnterStandby`（入口建立成功时）；
     * 2. `MainActivity.onResume`（应用回到前台时的无条件兜底 cancel）。
     * 本用例走的是「服务已在前台 → 直接投递 ACTION_STANDBY」的路径，
     * 中途不拉起 Activity，故 cancel 的来源是 (1)。
     * 但**无法在测试内证明「不是 (2)」**（息屏下 Activity 拿不到 RESUMED，
     * `onResume` 本就不会触发；屏幕亮时则会触发）。
     * 因此这里钉的是**用户可见的不变式**：
     * 「入口确实建成了（1001 存在且走快捷渠道）时，状态栏里**不得**同时挂着
     * 一条「入口没建好」的矛盾提示」。
     */
    @Test
    fun `t03_入口建立成功后失败提醒1004被清除`() {
        assumeNoMonitoringDay()
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

        // 先确认入口真的建成了（否则"1004 被清掉"可能只是因为服务压根没起来）
        assertNotNull(
            "入口未建立成功，无法验证 1004 的消除",
            awaitActiveNotification(Constants.NOTIFICATION_ID_MONITOR),
        )
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
        awaitNotificationAbsent(Constants.NOTIFICATION_ID_MONITOR, timeoutMs = 5_000L)
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

    /**
     * 「结束（结算当天）」后，服务必须回到**待命态**，且 UI 可见状态必须复位为 IDLE。
     *
     * ## 为什么这条用例存在（P1 缺陷回归）
     *
     * 缺陷现象：点「结束」没反应——界面永远停在「录音中 + 结束按钮」。
     * 根因是 `onAudioLoopExited` 进入待命态时**只换通知、不复位 `serviceState`**，
     * 而 `TodayUiState.isSettling` 的判据是 `dayState == SETTLING && !isServiceActive`，
     * `isServiceActive` 又只看 `serviceState`。于是结算**其实成功了**
     * （DB 已置 SETTLING、Worker 已入队、进度照常推进），UI 却认为还在录音。
     *
     * ## 为什么不用点 UI 的方式验
     *
     * 本机（MIUI）`adb shell input` 注入被拒（INJECT_EVENTS），且 `isSettling`
     * 是纯派生属性（已被 `TodayUiStateTest` 覆盖）。这里验的是**它的前提**：
     * 「结束动作走完后，服务侧 `serviceState` 真的复位了」。
     *
     * 故本用例投递的是**与「结束」按钮完全相同的服务动作**
     * （[Constants.ACTION_STOP_SETTLE]，按钮经 `ServiceController.stopSettle` 发出它）：
     * 差别只在于 DB 的 SETTLING 迁移由 use case 完成、本用例不触发，
     * 避免把设备上的真实录音日结算掉。
     *
     * ## 判据（两重，缺一不可）
     *
     * 1. **通知渠道从 monitor 切回 quick_record**：证明 `onAudioLoopExited →
     *    enterStandbyMode` 成功走完（失败会走 `stopSelfSafely`，通知直接消失）。
     * 2. **复位日志条数增加**：`resetServiceStateToIdle` 恰好落在这条成功路径上，
     *    只靠 (1) 无法区分「换通知成功但忘了复位」——那正是本次缺陷。
     *    故用「投递前后计数比较」而不是「日志里存在该字符串」。
     */
    @Test
    fun `t05_结束后服务回到待命态且状态复位为IDLE`() {
        val dayId = TimeUtils.todayDayId()
        launchForegroundApp()
        stopService()
        awaitNotificationAbsent(Constants.NOTIFICATION_ID_MONITOR, timeoutMs = 5_000L)

        // 前置：直接开始录音（进入监听态）。
        //
        // 这里**刻意不走**「先进待命态」那条前置：`handleEnterStandby` 会异步核对
        // 持久化状态，一旦设备上存在监听中的录音日就按设计「交回监听链路」
        // （真机日志实测："存在监听中的录音日，待命入口交回监听链路"），
        // 于是待命通知永远不会出现——那是设计的正确行为，不是缺陷，
        // 但会让本用例卡在与待命态无关的前置上。本用例要验的是**收尾**方向
        // （监听 → 待命），与「怎么进的监听态」无关，故直接进监听态。
        runCatching { ContextCompat.startForegroundService(context, quickStartIntent()) }
        assertTrue(
            "前置条件不成立：未进入监听态，无法验证「结束」后的收尾",
            awaitChannel(Constants.NOTIFICATION_ID_MONITOR, Constants.CHANNEL_MONITOR),
        )
        // 给音频循环一点时间真正跑起来（handleStop 对 `!running` 会早退）
        Thread.sleep(1_500L)

        val resetCountBefore = countLogcat(RESET_MARKER)

        // 与「结束」按钮同源的服务动作
        runCatching {
            ContextCompat.startForegroundService(context, stopSettleIntent(dayId))
        }

        assertTrue(
            "结束后服务未回到待命态（1001 通知应从 channel_monitor 切回 channel_quick_record）" +
                "；当前渠道=" +
                "${activeNotification(Constants.NOTIFICATION_ID_MONITOR)?.notification?.channelId}",
            awaitChannel(Constants.NOTIFICATION_ID_MONITOR, Constants.CHANNEL_QUICK_RECORD),
        )
        assertTrue(
            "服务回到了待命态，但 serviceState 未复位为 IDLE —— 这正是「点结束没反应」的缺陷：" +
                "UI 会一直以为在录音，isSettling 永远为 false（日志中「$RESET_MARKER」条数未增加）",
            awaitLogcatCountAbove(RESET_MARKER, resetCountBefore),
        )
    }

    // ── 辅助 ──
    /**
     * 把应用拉到前台，**但不等待 RESUMED**。
     *
     * ## 为什么不能用 `ActivityScenario`
     *
     * `ActivityScenario.launch` 会阻塞等待 Activity 进入 `RESUMED`。本机（MIUI）
     * 息屏/锁屏时拿不到 `mResumedActivity`（`dumpsys activity activities` 里只有
     * `mLastPausedActivity`），于是 `launch` 永远等不到 —— 实测卡死 9 分钟无任何进展，
     * **不是产品缺陷，是环境问题**。
     *
     * ## 这里为什么「不等 RESUMED」也够用
     *
     * 快捷入口的建立链路是 `MainActivity.onCreate → requestCorePermissionsIfNeeded →
     * bootstrapQuickRecordEntry → ServiceController.enterStandby → startForegroundService`，
     * **入口通知是服务侧 `handleEnterStandby` 发出来的**，不依赖 Activity 处于前台。
     * 麦克风型 FGS 的「可见状态」要求由**服务自身已在前台**满足：
     * `RecordingService` 是 START_STICKY，进程存活期间服务实例一直在；
     * 本用例先确认服务存在，再触发 `ACTION_STANDBY`。
     *
     * 用 `startActivity`（非阻塞）而非 `ActivityScenario`：
     * 前者只是「把任务拉到前台」，立即返回；后者会等 RESUMED 而挂死。
     */
    private fun launchForegroundApp(): Boolean {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
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

    private fun stopSettleIntent(dayId: String): Intent =
        Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_STOP_SETTLE
            putExtra(Constants.EXTRA_DAY_ID, dayId)
        }

    /**
     * 统计 logcat 中某标记出现的次数。
     *
     * 用「次数」而不是「是否存在」：复位日志也会由 `onDestroy`／首次进待命态产生，
     * 只判存在等于永远为真。`-t` 限制尾部行数，避免长日志拖慢轮询。
     */
    private fun countLogcat(marker: String): Int =
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "brief", "-t", "4000")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text.lines().count { it.contains(marker) }
        }.getOrDefault(0)

    private fun awaitLogcatCountAbove(
        marker: String,
        baseline: Int,
        timeoutMs: Long = POLL_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (countLogcat(marker) > baseline) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return countLogcat(marker) > baseline
    }

    private fun quickStartIntent(): Intent =
        Intent(context, RecordingService::class.java).apply {
            action = Constants.ACTION_QUICK_START
            putExtra(Constants.EXTRA_DAY_ID, TimeUtils.todayDayId())
        }

    /**
     * 读取持久化状态：设备上是否存在**监听中**的录音日。
     *
     * ## 为什么本类需要它
     *
     * `handleEnterStandby` 会异步核对持久化状态（铁律 13）：**有监听日就交回监听链路**
     * 而不是停在待命态。这是设计，不是缺陷——但意味着「投递 ACTION_STANDBY 必得
     * 快捷入口通知」这个前提**只在没有监听日时成立**。
     *
     * 之前的用例默认前提永远成立，于是一旦设备上残留一个未结算的监听日
     * （上一次真机验证留下的），待命类用例就会红在「入口未建立」上，
     * 让人误以为是入口功能坏了。真机实测正是这种情况：
     * 日志里出现过 `存在监听中的录音日，待命入口交回监听链路`。
     *
     * 故这里**只读**地把前提摆到台面上（openDatabase 用 OPEN_READONLY，
     * 绝不改用户数据）：前提不成立就 skip 并说明原因，而不是伪造一条红色回归。
     *
     * 返回 null 表示读不到（库不存在/被锁），调用方按「无法判定」处理。
     */
    private fun hasMonitoringDay(): Boolean? =
        runCatching {
            val dbFile = context.getDatabasePath(EchoDatabase.DATABASE_NAME)
            if (!dbFile.exists()) return@runCatching null
            val db = SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                db.rawQuery(
                    "SELECT COUNT(*) FROM recording_day WHERE state = ?",
                    arrayOf("MONITORING"),
                ).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) > 0
                }
            } finally {
                db.close()
            }
        }.getOrNull()

    /** 待命类用例的统一前提：设备上不得有监听中的录音日（见 [hasMonitoringDay]）。 */
    private fun assumeNoMonitoringDay() {
        Assume.assumeFalse(
            "设备上存在「监听中」的录音日：handleEnterStandby 会按设计交回监听链路，" +
                "待命通知本就不会出现，本用例的前提不成立（这不是回归）。" +
                "处理方式：在应用内把该日「结束（结算当天）」，或清除应用数据后重跑。",
            hasMonitoringDay() == true,
        )
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
