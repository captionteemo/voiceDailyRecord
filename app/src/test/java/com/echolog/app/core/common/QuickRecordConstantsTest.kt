package com.echolog.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁屏快捷录音入口的常量不变量（QA 第一轮）。
 *
 * ## 为什么要用测试钉这些常量
 *
 * [Constants] 是跨层通信的唯一字符串来源，而快捷入口依赖两个**极易被合并**的约定：
 *
 * - **渠道必须独立**：监听渠道 [Constants.CHANNEL_MONITOR] 的锁屏可见性是 PRIVATE
 *   （录音状态属隐私），而系统在锁屏上取「渠道可见性」与「通知可见性」中**更严格**
 *   的一方。一旦有人把 [Constants.CHANNEL_QUICK_RECORD] 合并成监听渠道，
 *   锁屏入口不会报错、只是**静默消失**——正是本项目红线禁止的静默失效。
 * - **动作必须互异**：[Constants.ACTION_QUICK_START]（点入口 → 建档录音）、
 *   [Constants.ACTION_STANDBY]（只建入口）、[Constants.ACTION_MANUAL_TOGGLE]
 *   （手动模式下翻转「正在录」标志）语义完全不同；重名会让通知按钮投递到
 *   错误的处理分支，且不会有任何编译期提示。
 *
 * ## 反射而非硬编码清单
 *
 * 动作清单用反射遍历 `Constants` 中所有 `ACTION_*` 字段得到，
 * 将来新增动作若与既有动作重名会被本文件自动抓住，不需要手工维护清单。
 */
class QuickRecordConstantsTest {

    /** `Constants` 中所有 `ACTION_*` 常量（名称 → 值）。 */
    private fun actionConstants(): List<Pair<String, String>> =
        Constants::class.java.declaredFields
            .filter { it.name.startsWith("ACTION_") && it.type == String::class.java }
            .map { it.name to it.get(null) as String }

    @Test
    fun `快捷录音渠道与既有三个渠道互不相等`() {
        val channels = listOf(
            Constants.CHANNEL_MONITOR,
            Constants.CHANNEL_PROGRESS,
            Constants.CHANNEL_ALERT,
            Constants.CHANNEL_QUICK_RECORD,
        )

        assertEquals("渠道 id 必须全局唯一（重名会让通知静默走错渠道）", channels.size, channels.toSet().size)
        assertNotEquals(
            "快捷入口必须独立渠道：复用监听渠道会被其 PRIVATE 锁屏可见性降级，入口在锁屏上静默消失",
            Constants.CHANNEL_MONITOR,
            Constants.CHANNEL_QUICK_RECORD,
        )
        assertNotEquals(
            "快捷入口渠道不得与失败提示渠道合并（入口 LOW 常驻 / 失败 HIGH 提醒，语义相反）",
            Constants.CHANNEL_ALERT,
            Constants.CHANNEL_QUICK_RECORD,
        )
    }

    @Test
    fun `四个通知渠道均非空且命名风格一致`() {
        val channels = listOf(
            "CHANNEL_MONITOR" to Constants.CHANNEL_MONITOR,
            "CHANNEL_PROGRESS" to Constants.CHANNEL_PROGRESS,
            "CHANNEL_ALERT" to Constants.CHANNEL_ALERT,
            "CHANNEL_QUICK_RECORD" to Constants.CHANNEL_QUICK_RECORD,
        )

        channels.forEach { (name, value) ->
            assertTrue("$name 不得为空白（空渠道 id 在 Android 8+ 上通知不会展示）", value.isNotBlank())
            assertTrue("$name 应遵循 channel_* 命名：$value", value.startsWith("channel_"))
        }
    }

    @Test
    fun `快捷动作与既有动作均不冲突`() {
        val actions = actionConstants()
        val values = actions.map { it.second }

        assertTrue("反射应至少取到 6 个既有动作，实际：${actions.map { it.first }}", actions.size >= 6)
        assertEquals(
            "Intent action 必须全局唯一（重名会让通知按钮静默投递到错误分支）：$actions",
            values.size,
            values.toSet().size,
        )

        assertNotEquals(
            "ACTION_QUICK_START 与 ACTION_STANDBY 语义相反（开始录音 / 只建入口），不得合并",
            Constants.ACTION_QUICK_START,
            Constants.ACTION_STANDBY,
        )
        assertNotEquals(
            "ACTION_QUICK_START 不得复用 ACTION_MANUAL_TOGGLE：后者在手动模式下翻转标志，无法建档",
            Constants.ACTION_MANUAL_TOGGLE,
            Constants.ACTION_QUICK_START,
        )
        assertNotEquals(
            "ACTION_QUICK_START 不得复用 ACTION_START_MONITORING：两者走服务里不同分支（快捷入口需先鉴权）",
            Constants.ACTION_START_MONITORING,
            Constants.ACTION_QUICK_START,
        )
    }

    @Test
    fun `所有动作统一使用包名前缀_防止与系统或三方广播串台`() {
        val actions = actionConstants()

        actions.forEach { (name, value) ->
            assertTrue(
                "$name 应使用 com.echolog.app.action. 前缀，实际：$value",
                value.startsWith("com.echolog.app.action."),
            )
        }
    }

    @Test
    fun `通知 id 全局互不相同_失败提醒用独立 id`() {
        val ids = listOf(
            Constants.NOTIFICATION_ID_MONITOR,
            Constants.NOTIFICATION_ID_PROGRESS,
            Constants.NOTIFICATION_ID_ALERT,
            Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED,
        )

        assertEquals(
            "通知 id 必须互不相同：监听通知与快捷入口共用 MONITOR id 实现互斥，" +
                "若与其它 id 重名，两条通知会互相覆盖",
            ids.size,
            ids.toSet().size,
        )
        assertNotEquals(
            "入口建立失败提醒必须用**独立** id：复用 ALERT id 会让「cancel 该 id」" +
                "顺带取消中断恢复等其它真实告警，也会让两条失败通知互相覆盖",
            Constants.NOTIFICATION_ID_ALERT,
            Constants.NOTIFICATION_ID_QUICK_ENTRY_FAILED,
        )
    }
}
