package com.echolog.app.work

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **Worker 装配契约**静态守卫测试（BUG-11 根因回归防线）。
 *
 * ## 它防的是什么
 *
 * 用户报告的真机 Bug：**「实际安装后，尝试进行录音，结束录音后进度会卡在 5%，
 * 无法翻译成文字」**。字节码级取证确认的真正根因**不是**前台服务权限缺失，而是
 * WorkManager 的初始化链断裂：
 *
 * 1. `androidx.work.WorkManagerInitializer`（由 `androidx.startup` 的
 *    `InitializationProvider` 承载）在 `Application.onCreate()` **之前**执行，
 *    它调用 `WorkManager.initialize(context, new Configuration.Builder().build())`
 *    —— **默认配置**，完全不读 `Configuration.Provider`；
 * 2. 于是 `WorkManagerImpl.sDelegatedInstance` 早已非 null，
 *    `WorkManagerImpl.getInstance(Context)` 的 `Configuration.Provider` 分支
 *    （字节码 offset 20-43）**永远不会执行** →
 *    `EchoLogApplication.workManagerConfiguration` 里的 `HiltWorkerFactory`
 *    **从不生效**；
 * 3. 所有 `@HiltWorker` 退化为 `DefaultWorkerFactory`（`createWorker` 返回 null）
 *    → `fallbackToReflection` 用 `getDeclaredConstructor(Context, WorkerParameters)`
 *    反射实例化，而 `DaySettleWorker` 等是 `@AssistedInject` 的**多参构造**（7 参数）
 *    → `NoSuchMethodException` → 抛 `"Could not instantiate"`
 *    → Worker 进入 FAILED，**`doWork()` 从未执行**。
 *
 * 症状精确吻合：进度停在 **5%**（5% 是入队方 `StopMonitoringAndSettleUseCase`
 * 写的，不是 Worker 写的）；`settleError` 恒为 null（没人写错误）；5 个 Worker
 * **全部**失效（模型下载 Worker 也不跑 → 权重装不上 → 无法翻译成文字）。
 *
 * ## 为什么用「静态守卫」而不是运行时测试
 *
 * 修复手段是 manifest 层面的（`tools:node="remove"` 移除默认初始化器），
 * JVM 单测无法启动真 WorkManager（需要真 Android 运行时）。
 * 因此本测试**只读文件系统**做结构断言，把「manifest 里的那两行」
 * 与「Hilt 生成的 Worker 注册表」钉死成可回归的契约：
 * 任何人误删/误改这两处，本测试立刻变红。
 *
 * ## 覆盖的契约
 *
 * 1. `AndroidManifest.xml` 中 `androidx.work.WorkManagerInitializer` 必须带
 *    `tools:node="remove"`（被移除）；
 * 2. `androidx.startup.InitializationProvider` 必须带 `tools:node="merge"`
 *    （保留 provider 本身，只摘掉其中的 initializer 元数据）；
 * 3. Hilt 生成的 Worker 注册表必须包含全部 5 个 Worker
 *    （缺一个 = 该 Worker 永远装配不上）；
 * 4. `EchoLogApplication` 必须实现 `Configuration.Provider` 且调用
 *    `setWorkerFactory`（否则 HiltWorkerFactory 不会生效）。
 */
class WorkerAssemblyContractTest {

    // ── 1. manifest：WorkManagerInitializer 必须被移除 ──

    @Test
    fun `manifest中WorkManagerInitializer必须被移除`() {
        val manifest = readAppFile("src/main/AndroidManifest.xml")

        // 定位到承载 WorkManagerInitializer 的那个 <meta-data> 元素
        val metaData = extractElement(manifest, "androidx.work.WorkManagerInitializer")

        assertTrue(
            "androidx.work.WorkManagerInitializer 必须带 tools:node=\"remove\"，" +
                "否则 androidx.startup 会在 Application.onCreate() 之前用默认配置" +
                "初始化 WorkManager，导致 HiltWorkerFactory 从不生效、全部 Worker FAILED。" +
                "实际元素：$metaData",
            metaData.contains("""tools:node="remove""""),
        )
    }

    @Test
    fun `manifest中InitializationProvider必须保留为merge`() {
        val manifest = readAppFile("src/main/AndroidManifest.xml")

        val provider = extractElement(manifest, "androidx.startup.InitializationProvider")

        assertTrue(
            "androidx.startup.InitializationProvider 必须带 tools:node=\"merge\"，" +
                "否则该 provider 会被整体覆盖，可能连带移除其他库的初始化器。" +
                "实际元素：$provider",
            provider.contains("""tools:node="merge""""),
        )
    }

    // ── 2. Hilt 生成的 Worker 注册表：5 个 Worker 必须齐全 ──

    @Test
    fun `hilt生成源码中五个Worker必须全部注册`() {
        val generated = File(
            appModuleDir(),
            "build/generated/hilt/component_sources/debug/com/echolog/app/" +
                "DaggerEchoLogApplication_HiltComponents_SingletonC.java",
        )
        // 该产物依赖 KSP + Hilt 聚合任务已跑过；未生成时跳过（而非误报失败）
        assumeTrue(
            "Hilt 生成产物不存在（KSP 尚未运行），跳过：${generated.absolutePath}",
            generated.isFile,
        )

        val source = generated.readText()

        val requiredWorkers = listOf(
            "com.echolog.app.work.DaySettleWorker",
            "com.echolog.app.work.ModelDownloadWorker",
            "com.echolog.app.work.TranscribeWorker",
            "com.echolog.app.work.DayRolloverWorker",
            "com.echolog.app.work.HealthCheckWorker",
        )
        for (worker in requiredWorkers) {
            assertTrue(
                "Hilt Worker 注册表缺少 $worker —— 该 Worker 将无法被 Hilt 构造，" +
                    "会退化为 DefaultWorkerFactory + 反射兜底并最终 FAILED。",
                source.contains(worker),
            )
        }

        assertTrue(
            "Hilt 生成源码应包含 HiltWorkerFactory（Configuration.Provider 分支的产物）",
            source.contains("HiltWorkerFactory"),
        )
        assertTrue(
            "Hilt 生成源码应包含 WorkerFactoryModule_ProvideFactoryFactory" +
                "（Worker → AssistedFactory 的映射工厂）",
            source.contains("WorkerFactoryModule_ProvideFactoryFactory"),
        )
    }

    // ── 3. Application：必须实现 Configuration.Provider 并注入 WorkerFactory ──

    @Test
    fun `Application必须实现ConfigurationProvider并设置WorkerFactory`() {
        val application = readAppFile("src/main/java/com/echolog/app/EchoLogApplication.kt")

        assertTrue(
            "EchoLogApplication 必须实现 Configuration.Provider，" +
                "否则 WorkManager 无从获取 HiltWorkerFactory（见本测试类的根因说明）",
            application.contains("Configuration.Provider"),
        )
        assertTrue(
            "EchoLogApplication 的 workManagerConfiguration 必须调用 setWorkerFactory，" +
                "否则 @HiltWorker 全部失效",
            application.contains("setWorkerFactory"),
        )
    }

    // ── 工具 ──

    /**
     * 读取 `app` 模块下的文件（相对模块根目录）。
     *
     * @throws AssertionError 文件不存在时失败（说明模块结构被破坏，属真实回归）。
     */
    private fun readAppFile(relativePath: String): String {
        val file = File(appModuleDir(), relativePath)
        assertTrue("找不到文件：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * 定位 `app` 模块根目录。
     *
     * 单元测试的工作目录在 Gradle 下通常是**模块目录**（`app/`），
     * 但不同 IDE / 运行方式下可能是仓库根目录。故从 `user.dir` 向上逐级查找，
     * 两种布局都能命中。
     */
    private fun appModuleDir(): File {
        val candidates = ArrayList<File>()
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            candidates += current
            current = current.parentFile
        }
        // 布局 A：某级目录下有 app/src/main/AndroidManifest.xml
        for (candidate in candidates) {
            val nested = File(candidate, "app")
            if (File(nested, "src/main/AndroidManifest.xml").isFile) return nested
        }
        // 布局 B：某级目录本身就是 app 模块
        for (candidate in candidates) {
            if (File(candidate, "src/main/AndroidManifest.xml").isFile) return candidate
        }
        throw AssertionError(
            "无法定位 app 模块目录（user.dir=${System.getProperty("user.dir")}）；" +
                "请确认单元测试运行在 echolog-app 仓库内",
        )
    }

    /**
     * 从 XML 文本中抽出**包含指定标记的那个元素**的源码片段。
     *
     * 只做字符串级的定位（不引入 XML 解析依赖），但取的是**整个元素**
     * （从该标记所在的 `<` 到对应的 `>`），因此可以可靠地断言
     * `tools:node` 等**同元素内的**属性，而不是全文里任意位置的同名字符串。
     *
     * 先剥离 XML 注释：manifest 里那段解释根因的长注释中**也出现了**
     * `androidx.work.WorkManagerInitializer` 字样，不剥离会命中注释而非真实元素。
     */
    private fun extractElement(xml: String, marker: String): String {
        val stripped = stripComments(xml)
        val index = stripped.indexOf(marker)
        assertTrue("manifest 中未找到标记：$marker", index >= 0)

        val tagStart = stripped.lastIndexOf('<', index)
        val tagEnd = stripped.indexOf('>', index)
        assertTrue("无法解析标记 $marker 所在的元素边界", tagStart >= 0 && tagEnd > tagStart)

        return stripped.substring(tagStart, tagEnd + 1)
    }

    /** 去掉 `<!-- ... -->` 注释块。 */
    private fun stripComments(xml: String): String =
        xml.replace(Regex("""<!--[\s\S]*?-->"""), "")
}
