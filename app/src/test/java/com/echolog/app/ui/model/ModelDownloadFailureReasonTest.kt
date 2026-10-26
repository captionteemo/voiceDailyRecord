package com.echolog.app.ui.model

import android.content.Context
import com.echolog.app.ai.model.ModelDownloader
import com.echolog.app.ai.model.ModelKind
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

/**
 * 「模型下载失败原因必须到达 UI」回归测试（**红线**）。
 *
 * ## 这条链路曾经断在哪
 *
 * 修复前有两处断点，缺一不可地导致用户只看到一个「ERROR」徽标、看不到任何原因：
 *
 * 1. [ModelManager.ensureModels] 的失败分支只 `publishState(kind, ModelState.ERROR)`，
 *    `result.failureOrNull()?.message` 被**完全丢弃**——原因在这一层就没了；
 * 2. [ModelUiState.messageRes] 只能承载**预定义 string 资源**，装不下动态原因文本。
 *
 * 修复后：`ModelManager.lastErrors`（StateFlow，持久可查）+ `ModelManager.errorEvents`
 * （SharedFlow，即时提示）→ `ModelUiState.errorMessage` / `ModelRowState.error`
 * → `ModelDownloadScreen` 的 `LaunchedEffect(state.errorMessage)` 弹 Snackbar。
 *
 * ## 本测试覆盖什么
 *
 * 端到端走**真实**的 `ModelManager` + 真实 `ModelDownloader`，只把「网络」换成一个
 * 永远返回 403 的 OkHttp 拦截器。于是链路上的每一环都是被测代码：
 *
 * ```
 * 403 响应 → ModelDownloader 造出 "下载失败：HTTP 403"
 *          → ModelManager.lastErrors / errorEvents
 *          → ModelDownloadViewModel.uiState.errorMessage / asr.error
 * ```
 *
 * ## 为什么用拦截器而不是本地 HTTP 服务端
 *
 * - `jdk.httpserver`（`com.sun.net.httpserver`）**不在 Android 单元测试的编译 classpath 上**
 *   （已在 `ModelDownloaderRealBytesTest` 上踩过：编译报 `Unresolved reference 'sun'`）；
 * - 也不用 Mockito 打桩 `ModelDownloader.download`：它是 **suspend** 且参数非空，
 *   Kotlin 下用 `Mockito.any()` 会因空检查抛 NPE。
 *
 * 拦截器短路返回是纯 JVM 手法，不依赖任何额外依赖。
 */
class ModelDownloadFailureReasonTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        // 用 Unconfined 作 Main：viewModelScope 的协程立即执行、恢复不换线程，
        // 于是「发事件 → 界面状态更新」在断言前即可见（无需 test scheduler）。
        Dispatchers.setMain(Dispatchers.Unconfined)

        val filesDir: File = tempFolder.newFolder("files")
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(filesDir)

        modelManager = ModelManager(
            context = context,
            downloader = ModelDownloader(
                okHttpClient = forbiddenClient(),
                ioDispatcher = Dispatchers.Unconfined,
            ),
            // SettingsRepository 不参与本测试（只用到 setModelsReady），mock 即可
            settingsRepository = Mockito.mock(SettingsRepository::class.java),
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 1. ModelManager 层：原因必须被记录下来（这正是曾经被丢弃的那一步） ──

    @Test
    fun `下载失败时失败原因必须记录到lastErrors且含真实状态码`() {
        modelManager.ensureModels()

        val recorded = awaitUntil { modelManager.lastErrors.value[ModelKind.ASR] != null }
        val reason = modelManager.lastErrors.value[ModelKind.ASR]
        assertTrue(
            "失败原因必须被记录下来（修复前这里被直接丢弃），实际：$reason",
            recorded,
        )
        assertTrue(
            "失败原因必须包含真实状态码 403，实际：$reason",
            reason?.contains("403") == true,
        )
    }

    // ── 2. UI 状态层：原因必须能到达 ModelUiState ──

    @Test
    fun `失败原因必须到达UI状态且不是预定义占位符`() {
        val vm = ModelDownloadViewModel(
            context = context,
            modelManager = modelManager,
            workScheduler = Mockito.mock(WorkScheduler::class.java),
        )

        modelManager.ensureModels()

        // 行内原因（ModelRowState.error）：进页面即见，错过 Snackbar 也能看到
        val rowShown = awaitUntil { vm.uiState.value.asr.error?.contains("403") == true }
        assertTrue(
            "行内失败原因必须包含真实状态码 403，实际：${vm.uiState.value.asr.error}",
            rowShown,
        )

        // Snackbar 文案（ModelUiState.errorMessage）：失败当下的即时提示
        val snackbarShown = awaitUntil { vm.uiState.value.errorMessage?.contains("403") == true }
        assertTrue(
            "Snackbar 文案必须包含真实状态码 403，实际：${vm.uiState.value.errorMessage}",
            snackbarShown,
        )
        assertTrue(
            "失败原因不得是预定义占位符（那等于没透传真因），实际：${vm.uiState.value.errorMessage}",
            vm.uiState.value.errorMessage != PLACEHOLDER_REASON,
        )
    }

    // ── 工具 ──

    /**
     * 永远返回 403 的 OkHttp 客户端（**完全不走网络**）。
     *
     * 应用拦截器直接短路返回响应，因此不会有任何 socket 行为。
     */
    private fun forbiddenClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body("forbidden".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        .build()

    /**
     * 等待条件成立（最多 [timeoutMs] 毫秒）。
     *
     * 用轮询而非 `advanceUntilIdle()`：本测试的 Main 是 [Dispatchers.Unconfined]
     * （不是 test scheduler），没有可推进的虚拟时间；而下载与状态传播都是
     * 同步或近同步的，轮询几毫秒即可稳定命中。
     */
    private fun awaitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private companion object {
        /** [com.echolog.app.ai.model.ModelManager] 的兜底占位符，出现即说明原因没透传。 */
        const val PLACEHOLDER_REASON = "下载失败（未提供原因）"
    }
}
