package com.echolog.app

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.echolog.app.core.common.Constants
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.service.RecordingServiceState
import com.echolog.app.service.notify.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁屏快捷录音入口的通知构建契约（QA 第一轮，**仪器测试**）。
 *
 * ## 为什么走 instrumentation 而不是 JVM 单测
 *
 * [NotificationHelper.buildQuickRecordNotification] 最终落到
 * `NotificationCompat.Builder.build()`，而后者在 API 26+ 上委托给框架类
 * `android.app.Notification.Builder`。JVM 单测的 android.jar 是 mockable 桩：
 * 除字段外的方法一律抛 `RuntimeException("Stub!")`，且 `Context.getSystemService`
 * 返回 null——**任何构造真实 Notification 的尝试都拿不到对象**。
 * 业界做法是 Robolectric，本项目没有该依赖（也不擅自新增），
 * 因此放在 androidTest：用真实 `Context` + 真实 `NotificationManager` 构建后再断言。
 *
 * ## 断言的是「用户能否在锁屏上看到并点到入口」
 *
 * - ongoing：入口被误滑掉就永久失去（与监听通知一致）；
 * - visibility = PUBLIC：渠道已设为 PUBLIC，但系统取「渠道可见性」与「通知可见性」中
 *   **更严格**的一方，故通知自身也必须显式声明，少一处就在锁屏上静默消失；
 * - 有且仅有一个操作：待命态没有进行中的录音，暂停/打点/结束均不适用，
 *   多一个按钮就是给用户一个点了没反应的死按钮；
 * - 失败提示走 alert 渠道且可点：项目红线要求失败不得静默。
 *
 * ## 已知未覆盖：失败提醒的消除闭环
 *
 * `buildQuickEntryFailedNotification` 是不可滑掉的 ongoing 通知，必须有主动 `cancel`
 * 出口，否则「滑不掉又清不掉」是从静默失败跳到甩不掉的打扰（同样是红线问题）。
 * 已落地的 cancel 时机：`RecordingService` 入口建立成功时 +
 * `MainActivity.onResume` 无条件 cancel（`cancel` 对不存在的通知是 no-op）。
 * 两者都需 Android 组件环境，JVM/instrumentation 均不便断言，故列入**真机验收清单**：
 * ① 制造一次拉起失败 → 通知出现且滑不掉；② 再成功建立入口 → 通知消失；
 * ③ 从 launcher 直接进应用 → 通知消失。
 */
@RunWith(AndroidJUnit4::class)
class QuickRecordNotificationTest {

    private companion object {
        const val DAY_ID = "2026-10-25"
        /** 失败原因透传断言用的可辨识串（不能与文案模板本身重合）。 */
        const val FAIL_REASON = "测试用失败原因-XYZ"
    }

    private lateinit var appContext: Context
    private lateinit var helper: NotificationHelper

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        helper = NotificationHelper(appContext)
    }

    @Test
    fun `待命通知为常驻不可滑掉`() {
        val notification = helper.buildQuickRecordNotification(DAY_ID)

        assertNotEquals(
            "快捷入口必须 ongoing：被误滑掉用户就永久失去入口（与监听通知一致）",
            0,
            notification.flags and Notification.FLAG_ONGOING_EVENT,
        )
    }

    @Test
    fun `待命通知锁屏可见`() {
        val notification = helper.buildQuickRecordNotification(DAY_ID)

        assertEquals(
            "系统取「渠道可见性」与「通知可见性」中更严格的一方，两处都必须 PUBLIC，" +
                "少一处入口就在锁屏上静默消失",
            Notification.VISIBILITY_PUBLIC,
            notification.visibility,
        )
    }

    @Test
    fun `待命通知有且仅有一个操作且是开始录音`() {
        val notification = helper.buildQuickRecordNotification(DAY_ID)
        val actions = notification.actions

        assertNotNull("必须有操作数组", actions)
        assertEquals(
            "待命态只提供「开始录音」：暂停/打点/结束在此态点了没反应，等于死按钮",
            1,
            actions.size,
        )
        assertEquals(
            appContext.getString(R.string.notify_action_start_record),
            actions[0].title.toString(),
        )
    }

    @Test
    fun `待命通知点击通知体可打开应用`() {
        val notification = helper.buildQuickRecordNotification(DAY_ID)

        assertNotNull("点击通知体必须回应用（否则用户无法进入 App 处理）", notification.contentIntent)
    }

    @Test
    fun `待命通知标题与正文均非空`() {
        val notification = helper.buildQuickRecordNotification(DAY_ID)
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        assertTrue("标题不得为空（空文案的常驻通知在锁屏上等于一块白板）：$title", title.isNotBlank())
        assertTrue("正文不得为空：$text", text.isNotBlank())
    }

    @Test
    fun `待命通知走独立渠道_不与监听渠道混用`() {
        val quick = helper.buildQuickRecordNotification(DAY_ID)
        val monitor = helper.buildMonitorNotification(
            dayId = DAY_ID,
            serviceState = RecordingServiceState.MONITORING,
            monitoredMs = 0L,
            currentSegmentMs = 0L,
            segmentCount = 0,
            markerCount = 0,
            privacyMode = PrivacyMode.PERSONAL,
        )

        assertEquals(Constants.CHANNEL_QUICK_RECORD, quick.channelId)
        assertEquals(Constants.CHANNEL_MONITOR, monitor.channelId)
        assertNotEquals(
            "两条通知必须落在不同渠道：监听渠道锁屏可见性为 PRIVATE，" +
                "混用会让快捷入口在锁屏上被降级到看不见",
            monitor.channelId,
            quick.channelId,
        )
    }

    @Test
    fun `快捷入口启动失败时给出可见提示且透出原因`() {
        val notification = helper.buildQuickStartFailedNotification(FAIL_REASON)

        assertEquals(
            "失败提示必须走高优先级 alert 渠道（项目红线：失败不得静默）",
            Constants.CHANNEL_ALERT,
            notification.channelId,
        )
        assertNotNull("失败提示必须可点击回应用内处理", notification.contentIntent)
        assertNotEquals(
            "失败提示应可滑掉，不应像常驻入口那样 ongoing",
            0,
            notification.flags and Notification.FLAG_AUTO_CANCEL,
        )
        assertEquals(
            "失败提示不得常驻（入口才需要常驻）",
            0,
            notification.flags and Notification.FLAG_ONGOING_EVENT,
        )

        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("失败原因必须透出给用户，不能只写进日志：实际正文「$text」", text.contains(FAIL_REASON))
    }

    /**
     * 快捷入口**建立失败**（S-A 修复）的可见提醒：必须不可滑掉、可点、且透出原因。
     *
     * 四条断言一一对应设计意图：
     * - `CHANNEL_ALERT` + `CATEGORY_ERROR`：高优先级、归类为错误；
     * - `FLAG_AUTO_CANCEL` 未设：点击后不自动消失（`setAutoCancel(false)`）；
     * - `FLAG_ONGOING_EVENT` 已设：**不可滑掉**（`setOngoing(true)`）。
     *   注意这两个不是一回事——`setAutoCancel(false)` 只管点击后不消失，管不住滑动；
     * - `contentIntent` + 正文含原因：可点回应用处理，且失败原因必须透出（防只写日志）。
     *
     * 消除闭环（入口建立成功时 `cancel(NOTIFICATION_ID_QUICK_ENTRY_FAILED)`）位于
     * `RecordingService`，需 Android 服务环境，未自动化 —— 已列入真机验收清单。
     */
    @Test
    fun `快捷入口建立失败时给出不可滑掉的可见提醒`() {
        val notification = helper.buildQuickEntryFailedNotification(FAIL_REASON)

        assertEquals(
            "入口建立失败必须走高优先级 alert 渠道（项目红线：失败不得静默）",
            Constants.CHANNEL_ALERT,
            notification.channelId,
        )
        assertEquals(
            "失败提醒应归类为错误（CATEGORY_ERROR）",
            NotificationCompat.CATEGORY_ERROR,
            notification.category,
        )
        assertEquals(
            "失败提醒不得自动消失（setAutoCancel(false)）",
            0,
            notification.flags and Notification.FLAG_AUTO_CANCEL,
        )
        assertNotEquals(
            "失败提醒必须不可滑掉：需要 setOngoing(true)（FLAG_ONGOING_EVENT）。" +
                "setAutoCancel(false) 只管点击后不消失，管不住滑动——划掉后与静默失败无差别",
            0,
            notification.flags and Notification.FLAG_ONGOING_EVENT,
        )
        assertNotNull("必须可点击回应用内处理", notification.contentIntent)

        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("失败原因必须透出给用户，不能只写进日志：实际正文「$text」", text.contains(FAIL_REASON))
    }
}
