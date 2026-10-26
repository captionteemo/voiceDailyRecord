package com.echolog.app.ui.settings

import android.content.Context
import com.echolog.app.ai.model.ModelKind
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.ai.model.ModelState
import com.echolog.app.data.media.StorageLocationManager
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.testing.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * 设置页「锁屏快捷录音」开关的联动契约（QA 第一轮，**红线**）。
 *
 * ## 红线所在
 *
 * 设置项打开后用户看不到任何入口，与「设置没生效」在体感上完全等价——
 * 属于本项目禁止的**设置静默失效**。[SettingsViewModel.setQuickRecordNotificationEnabled]
 * 因此必须在打开开关时立刻把服务拉进待命态（挂出常驻入口）。
 *
 * ## 覆盖的行为
 *
 * 1. 打开开关 → [ServiceController.enterStandby] 被调用（入口被建立）；
 * 2. 关闭开关 → 不调用（服务侧由 `startQuickRecordSettingWatch` 自行退出）；
 * 3. **先落盘再拉起**：顺序颠倒时服务内的开关监听
 *    （`observeSettings().first { !it.quickRecordNotificationEnabled }`）会读到旧值 false，
 *    服务立刻自行退出——用户刚打开开关入口就没了，且无任何提示；
 * 4. 拉起失败的行为契约（防回退）：`enterStandby()` 返回 `EchoResult<Unit>`，
 *    失败被消费不逃逸、**不静默回滚用户的开关选择**、且失败后仍可重试。
 *    可见反馈的载体（不可滑掉的 alert 通知）由 instrumentation 侧断言，此处不重复。
 *
 * ## 已知未覆盖点
 *
 * `MainActivity.bootstrapQuickRecordEntry()` 的「读设置抛异常 ⇒ 不调 `enterStandby()`」
 * 分支未自动化：落点在 Activity，JVM 不可达，走 instrumentation 需 Hilt 依赖替换 +
 * `ActivityScenario`，成本中高、收益属加固。**经 team-lead 决定不排期**，此处记录备查。
 *
 * ## 构造说明
 *
 * [SettingsViewModel] 的构造函数依赖 `Context` / [ModelManager] / [StorageLocationManager]，
 * 三者均用 Mockito（inline mock maker，可 mock final 类）替身，只补齐被真实调用到的
 * 最小面：`Context.filesDir`（存储统计）、[ModelManager.modelStates]（初始化合并流）、
 * [StorageLocationManager.displayNameOf]（未 stub，默认返回 null = 无自选目录）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelQuickRecordTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager
    private lateinit var storageLocationManager: StorageLocationManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(tempFolder.newFolder("files"))

        modelManager = Mockito.mock(ModelManager::class.java)
        val modelStates: StateFlow<Map<ModelKind, ModelState>> = MutableStateFlow(emptyMap())
        Mockito.`when`(modelManager.modelStates).thenReturn(modelStates)

        storageLocationManager = Mockito.mock(StorageLocationManager::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 用例 ──

    @Test
    fun `打开开关立即尝试建立快捷入口_红线`() = runTest {
        val log = mutableListOf<String>()
        val settings = FakeSettingsRepository()
        val repo = LoggingSettingsRepository(settings, log)
        val controller = FakeServiceController(log)
        val vm = newViewModel(repo, controller)

        vm.setQuickRecordNotificationEnabled(true)

        assertEquals("开关打开必须立即拉起服务挂入口，否则等同于设置静默失效", 1, controller.enterStandbyCalls)
        assertTrue("开关状态必须落盘", settings.getSettings().quickRecordNotificationEnabled)
    }

    @Test
    fun `关闭开关不拉起服务`() = runTest {
        val log = mutableListOf<String>()
        val settings = FakeSettingsRepository()
        val repo = LoggingSettingsRepository(settings, log)
        val controller = FakeServiceController(log)
        val vm = newViewModel(repo, controller)

        vm.setQuickRecordNotificationEnabled(false)

        assertEquals("关闭开关不应拉起服务（服务侧由开关监听自行退出）", 0, controller.enterStandbyCalls)
        assertFalse(settings.getSettings().quickRecordNotificationEnabled)
    }

    /**
     * 这个顺序是**保证**而非时序侥幸：两句写在同一个 `viewModelScope.launch` 内顺序执行，
     * 且 DataStore 的 `edit {}` 是在返回前就已 commit 的事务——
     * `enterStandby()` 被调用时新值必然已落盘，服务侧 watch 起来后读不到旧的 false。
     */
    @Test
    fun `先落盘开关再拉起服务_顺序颠倒会让服务读到旧值立刻退出`() = runTest {
        val log = mutableListOf<String>()
        val settings = FakeSettingsRepository()
        val repo = LoggingSettingsRepository(settings, log)
        val controller = FakeServiceController(log)
        val vm = newViewModel(repo, controller)

        vm.setQuickRecordNotificationEnabled(true)

        assertEquals(
            "必须先落盘再拉起：服务的开关监听 first { !enabled } 若读到旧值 false 会立刻 stopSelf，" +
                "用户刚打开开关入口就消失且无提示",
            listOf("setQuickRecordNotificationEnabled(true)", "enterStandby"),
            log,
        )
    }

    @Test
    fun `重复打开开关会重复投递_服务侧幂等早退`() = runTest {
        val log = mutableListOf<String>()
        val settings = FakeSettingsRepository()
        val repo = LoggingSettingsRepository(settings, log)
        val controller = FakeServiceController(log)
        val vm = newViewModel(repo, controller)

        vm.setQuickRecordNotificationEnabled(true)
        vm.setQuickRecordNotificationEnabled(true)

        // 去重不在 VM 层：RecordingService.handleEnterStandby 开头
        // `if (running || paused || standby) return`，已在待命态时直接早退、不会重启 watch，
        // 沿用旧 watch 协程（其 first { !enabled } 依旧挂着）行为仍然正确，也不会打断进行中的录音。
        assertEquals(2, controller.enterStandbyCalls)
        assertEquals(
            "重复投递可接受：去重在服务侧 handleEnterStandby 的早退分支，VM 只负责投递，" +
                "服务已在待命态时不会重启 watch，也不会打断进行中的录音",
            listOf(
                "setQuickRecordNotificationEnabled(true)",
                "enterStandby",
                "setQuickRecordNotificationEnabled(true)",
                "enterStandby",
            ),
            log,
        )
    }

    /**
     * 拉起失败的行为契约（S-A 修复后的**防回退**用例）。
     *
     * 修复前这条是「断言接缝返回 void」的结构 tripwire；修复落地后改为行为断言，钉三件事：
     * 1. **失败不逃逸**：VM 必须消费 `EchoResult` 而不是让它冒泡（从 `viewModelScope.launch`
     *    逃逸的未捕获异常在部分运行环境下会被判成测试失败，用户侧则等于什么都没发生）。
     *    「本用例能跑完」本身就是这条的证明——上一轮这条正是被逃逸异常判红的；
     * 2. **不静默回滚用户选择**：用户打开了开关，失败时值必须保持 true。
     *    偷偷把开关拨回去是第二次静默改动用户选择（失败要靠可见提示告知）；
     * 3. **失败后仍可重试**：一次性失败不得把后续触发卡死（用户再拨一次开关要能再试）。
     *
     * 关于「failure 是否被真正处理」：VM 侧的实现是 `onFailure { EchoLog.w(...) }`——
     * 只记日志，**可见反馈的载体由 controller 层代发**（`buildQuickEntryFailedNotification`，
     * 不可滑掉的 alert 通知）。载体断言在 `QuickRecordNotificationTest`（instrumentation），
     * VM 侧不重复断言通知细节；本用例负责「接缝有出口 + 不逃逸 + 不回滚 + 可重试」。
     */
    @Test
    fun `快捷入口拉起失败时失败被消费且不静默回滚用户选择`() = runTest {
        val log = mutableListOf<String>()
        val settings = FakeSettingsRepository()
        val repo = LoggingSettingsRepository(settings, log)
        val controller = FakeServiceController(
            log,
            standbyResult = EchoResult.failure(
                Constants.ERROR_QUICK_ENTRY_FAILED,
                "后台启动前台服务受限（模拟）",
            ),
        )
        val vm = newViewModel(repo, controller)

        vm.setQuickRecordNotificationEnabled(true)

        // 1 失败不得逃逸成异常（能执行到此处即通过）
        assertEquals("入口必须被尝试拉起过", 1, controller.enterStandbyCalls)

        // 2 不得静默回滚用户的选择
        assertTrue("开关已落盘为开启", settings.getSettings().quickRecordNotificationEnabled)
        assertTrue(
            "用户打开了开关，拉起失败时也不得悄悄把开关拨回关闭——" +
                "回滚等于第二次静默改动用户选择，失败必须靠可见提示告知",
            vm.uiState.value.settings.quickRecordNotificationEnabled,
        )

        // 3 失败不得把后续重试卡死
        vm.setQuickRecordNotificationEnabled(true)
        assertEquals(
            "一次性失败不得卡死后续触发：用户再拨一次开关必须能再次尝试拉起",
            2,
            controller.enterStandbyCalls,
        )
    }

    // ── 替身 ──

    private fun newViewModel(
        settingsRepository: SettingsRepository,
        serviceController: ServiceController,
    ): SettingsViewModel = SettingsViewModel(
        context = context,
        settingsRepository = settingsRepository,
        modelManager = modelManager,
        storageLocationManager = storageLocationManager,
        serviceController = serviceController,
    )

    /** 记录调用顺序的 [ServiceController] 替身。 */
    /** [ServiceController] 替身：`enterStandby` 返回值可注入，用于验证失败路径。 */
    private class FakeServiceController(
        private val log: MutableList<String>,
        private val standbyResult: EchoResult<Unit> = EchoResult.success(Unit),
    ) : ServiceController {

        var enterStandbyCalls = 0
            private set

        override fun enterStandby(): EchoResult<Unit> {
            enterStandbyCalls++
            log += "enterStandby"
            return standbyResult
        }

        override fun startMonitoring(dayId: String) {
            log += "startMonitoring"
        }

        override fun resumeMonitoring(dayId: String) {
            log += "resumeMonitoring"
        }

        override fun pauseMonitoring(dayId: String) {
            log += "pauseMonitoring"
        }

        override fun stopSettle(dayId: String) {
            log += "stopSettle"
        }

        override fun toggleManualRecording(dayId: String, recordingOn: Boolean?) {
            log += "toggleManualRecording"
        }
    }

    /**
     * 记录调用的 [SettingsRepository] 包装（接口委托，不改动共享的 [FakeSettingsRepository]）。
     *
     * 只覆盖本用例关心的开关写入，其余方法一律透传给内存替身。
     */
    private class LoggingSettingsRepository(
        private val delegate: SettingsRepository,
        private val log: MutableList<String>,
    ) : SettingsRepository by delegate {

        override suspend fun setQuickRecordNotificationEnabled(enabled: Boolean) {
            log += "setQuickRecordNotificationEnabled($enabled)"
            delegate.setQuickRecordNotificationEnabled(enabled)
        }
    }
}
