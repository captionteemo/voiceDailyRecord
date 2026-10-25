package com.echolog.app.ui.navigation

import androidx.annotation.StringRes
import com.echolog.app.R

/**
 * 应用导航目的地（架构 2.5：今天 / 历史 / 设置三 Tab + 详情路由）。
 *
 * 详情页（时间线 / 总结 / 模型管理）为栈内页面，不显示底部 Tab；
 * dayId 经 [Timeline.createRoute] / [Summary.createRoute] 拼入路径。
 */
sealed class Destination(val route: String) {

    /** 今天页（录音主界面，Tab 根）。 */
    data object Today : Destination("today")

    /** 历史列表页（按月分组，Tab 根）。 */
    data object History : Destination("history")

    /** 设置页（Tab 根）。 */
    data object Settings : Destination("settings")

    /** 当天时间线详情（PRD 4.2，FR-017/104）。 */
    data object Timeline : Destination("timeline/{dayId}") {
        const val ARG_DAY_ID: String = "dayId"

        /** 组装带参路由。 */
        fun createRoute(dayId: String): String = "timeline/$dayId"
    }

    /** 当天总结详情（PRD 4.3，FR-011）。 */
    data object Summary : Destination("summary/{dayId}") {
        const val ARG_DAY_ID: String = "dayId"

        /** 组装带参路由。 */
        fun createRoute(dayId: String): String = "summary/$dayId"
    }

    /** 模型管理 / 下载引导（FR-012，设置页与首启流共用）。 */
    data object ModelManager : Destination("model")

    /** 课表模板（用户需求 4：模板录音），设置页进入。 */
    data object ScheduleTemplate : Destination("schedule-template")
}

/**
 * 底部 Tab 条目（今天 / 历史 / 设置，PRD 4.1）。
 *
 * @param labelRes Tab 文案资源（中文，strings.xml）。
 */
enum class TopLevelDestination(
    val destination: Destination,
    @StringRes val labelRes: Int,
) {
    TODAY(Destination.Today, R.string.tab_today),
    HISTORY(Destination.History, R.string.tab_history),
    SETTINGS(Destination.Settings, R.string.tab_settings),
    ;

    companion object {
        /** 全部 Tab 路由（用于底部栏显隐与选中态判定）。 */
        val routes: List<String> = entries.map { it.destination.route }
    }
}
