package com.echolog.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.echolog.app.R
import com.echolog.app.ui.history.HistoryScreen
import com.echolog.app.ui.model.ModelDownloadScreen
import com.echolog.app.ui.schedule.ScheduleTemplateScreen
import com.echolog.app.ui.settings.SettingsScreen
import com.echolog.app.ui.summary.SummaryScreen
import com.echolog.app.ui.timeline.DayTimelineScreen
import com.echolog.app.ui.today.TodayScreen
import com.echolog.app.ui.transcript.TranscriptScreen

/**
 * 应用主导航框架（架构 2.5/4.1，PRD 4.1）。
 *
 * 三 Tab（今天 / 历史 / 设置）经底部 [NavigationBar] 切换，栈状态
 * save/restore；详情页（时间线 / 总结 / 模型管理）为栈内页面，进入时
 * 隐藏底部栏。首次启动的隐私告知与模型引导在入口层
 * （MainActivity）以全屏门控承载，不进入本 NavHost。
 *
 * @param modifier 外层修饰符。
 * @param navController 导航控制器（默认可恢复实例）。
 */
@Composable
fun EchoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TopLevelDestination.routes

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        val selected = currentRoute == tab.destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(tab) },
                            icon = {
                                Icon(
                                    imageVector = tab.icon(selected = selected),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Today.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Today.route) {
                TodayScreen(
                    onOpenTimeline = { dayId ->
                        navController.navigate(Destination.Timeline.createRoute(dayId))
                    },
                    onOpenSummary = { dayId ->
                        navController.navigate(Destination.Summary.createRoute(dayId))
                    },
                    onOpenTranscript = { dayId ->
                        navController.navigate(Destination.Transcript.createRoute(dayId))
                    },
                    onOpenModelManager = {
                        navController.navigate(Destination.ModelManager.route)
                    },
                )
            }
            composable(Destination.History.route) {
                HistoryScreen(
                    onOpenDay = { dayId ->
                        navController.navigate(Destination.Timeline.createRoute(dayId))
                    },
                )
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    onOpenModelManager = {
                        navController.navigate(Destination.ModelManager.route)
                    },
                    onOpenScheduleTemplate = {
                        navController.navigate(Destination.ScheduleTemplate.route)
                    },
                )
            }
            composable(Destination.ScheduleTemplate.route) {
                ScheduleTemplateScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Destination.Timeline.route) {
                DayTimelineScreen(
                    dayId = it.arguments?.getString(Destination.Timeline.ARG_DAY_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenSummary = { dayId ->
                        navController.navigate(Destination.Summary.createRoute(dayId))
                    },
                )
            }
            composable(Destination.Summary.route) {
                SummaryScreen(
                    dayId = it.arguments?.getString(Destination.Summary.ARG_DAY_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Destination.Transcript.route) {
                TranscriptScreen(
                    dayId = it.arguments?.getString(Destination.Transcript.ARG_DAY_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenSummary = { dayId ->
                        navController.navigate(Destination.Summary.createRoute(dayId))
                    },
                )
            }
            composable(Destination.ModelManager.route) {
                ModelDownloadScreen(
                    onBack = { navController.popBackStack() },
                    onSkip = null,
                )
            }
        }
    }
}

/**
 * 隐私告知页（FR-014，首启流第一步）。
 *
 * 全屏门控页，不属于主导航；由 MainActivity 在 privacyAck = false 时展示，
 * 确认后写 DataStore，不同意则退出应用。
 *
 * @param onAcknowledge 确认（写入 privacy_ack）。
 * @param onDisagree 不同意（退出应用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyOnboardingScreen(
    onAcknowledge: () -> Unit,
    onDisagree: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.privacy_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onAcknowledge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.privacy_confirm))
            }
            TextButton(
                onClick = onDisagree,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.privacy_disagree),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Tab 切换：单一实例 + 保存/恢复各 Tab 栈状态（Material 导航规范）。
 */
private fun NavHostController.navigateToTopLevel(tab: TopLevelDestination) {
    navigate(tab.destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Tab 图标（选中实心 / 未选中描边）。 */
private fun TopLevelDestination.icon(selected: Boolean): ImageVector = when (this) {
    TopLevelDestination.TODAY ->
        if (selected) Icons.Filled.Today else Icons.Outlined.Today

    TopLevelDestination.HISTORY ->
        if (selected) Icons.Filled.History else Icons.Outlined.History

    TopLevelDestination.SETTINGS ->
        if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}
