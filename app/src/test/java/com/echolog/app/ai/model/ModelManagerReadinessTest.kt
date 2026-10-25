package com.echolog.app.ai.model

import android.content.Context
import com.echolog.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.io.RandomAccessFile

/**
 * [ModelManager] 就绪判定的「半成品必须被拒绝」回归测试。
 *
 * ## 背景与演进
 *
 * BUG-11 的「永久无法翻译成文字」根因链：
 *
 * ```
 * 门槛过松（原 minOf(sizeBytes/100, 1MB)）
 *      ↓
 * 拷贝/下载中断 → 正式路径留下半成品文件
 *      ↓
 * filesReady() 误判「模型已就绪」
 *      ↓
 * 推理引擎加载失败 → 记住失败指纹 → 永不重试
 * ```
 *
 * 修复分两层：**门槛收紧到真实体积的 90%**（见 [ModelCatalogSizeThresholdTest]）
 * + **写入原子化**（`.part` 临时文件 → 校验通过后 `rename` 到正式路径）。
 *
 * 本测试覆盖「就绪判定」这一层，并**针对权重改为远端下载后的新形态**加固：
 * 下载中断留下的临时文件名为 `model.int8.onnx.part`（旧形态是 `.tmp`），
 * 它**绝不能**被当成已就绪的权重。
 *
 * ## 与被替代的旧测试的关系
 *
 * 早期版本存在「assets 内置拷贝」路径（把权重从 APK 资源拷贝到正式路径），
 * 其原子安装语义曾计划用 Mockito mock `AssetManager` + 反射调用私有方法来验证。
 * 权重改为远端下载后，**该路径已整体删除**（内置拷贝 / 内置安装 / 仅安装内置
 * 模式等相关方法、常量、分支均已移除），对应的测试目标不复存在；原子性改由
 * `ModelDownloader` 的 `.part` + `renameTo` 保证。
 * 本文件即其**替代守卫**：在新的下载形态下守住同一条不变式——
 * **不完整/非正式路径上的文件，永远不算「模型已就绪」**。
 */
class ModelManagerReadinessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var context: Context
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(filesDir)
        // ModelDownloader / SettingsRepository 在本测试中不参与就绪判定，
        // 仅需满足构造签名（Mockito 5 inline mock maker 支持 final Kotlin 类）。
        modelManager = ModelManager(
            context = context,
            downloader = Mockito.mock(ModelDownloader::class.java),
            settingsRepository = Mockito.mock(SettingsRepository::class.java),
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    // ── 1. 目录为空 ──

    @Test
    fun `目录为空时ASR与LLM都不得判定为就绪`() {
        assertFalse("ASR 目录为空，不得判定为就绪", modelManager.isAsrReady())
        assertFalse("LLM 目录为空，不得判定为就绪", modelManager.isLlmReady())
    }

    // ── 2. 半成品主文件（体积远低于门槛） ──

    @Test
    fun `主文件体积不足门槛时不得判定为就绪`() {
        writeFile(asrDir().resolve(ASR_FILE_NAME), SMALL_FILE_BYTES)

        assertFalse(
            "ASR 主文件仅 $SMALL_FILE_BYTES 字节（远低于门槛），不得判定为就绪",
            modelManager.isAsrReady(),
        )
    }

    @Test
    fun `主文件体积不足时即使伴随文件齐备也不得判定为就绪`() {
        writeFile(asrDir().resolve(ASR_FILE_NAME), SMALL_FILE_BYTES)
        writeFile(asrDir().resolve(TOKENS_FILE_NAME), 1024L)

        assertFalse(
            "ASR 主文件体积不足时，伴随文件齐备也不能通过就绪判定",
            modelManager.isAsrReady(),
        )
    }

    // ── 3. 下载中断残留的临时文件（.part） ──

    @Test
    fun `下载中断残留的part文件不得被判定为就绪`() {
        writeFile(asrDir().resolve("$ASR_FILE_NAME$PART_SUFFIX"), SMALL_FILE_BYTES)
        writeFile(asrDir().resolve("$LLM_FILE_NAME$PART_SUFFIX"), SMALL_FILE_BYTES)

        assertFalse(
            "ASR 目录下只有 $ASR_FILE_NAME$PART_SUFFIX（下载中断残留），不得判定为就绪",
            modelManager.isAsrReady(),
        )
        assertFalse(
            "LLM 目录下只有 $LLM_FILE_NAME$PART_SUFFIX（下载中断残留），不得判定为就绪",
            modelManager.isLlmReady(),
        )
    }

    @Test
    fun `part临时文件不得覆盖正式文件的就绪判定`() {
        // 正式文件体积不足 + 临时文件体积很大：仍必须判为未就绪
        // （若实现误把 .part 也纳入判定，就会出现「就绪但加载失败」的假阳性）
        writeFile(asrDir().resolve(ASR_FILE_NAME), SMALL_FILE_BYTES)
        writeFile(asrDir().resolve("$ASR_FILE_NAME$PART_SUFFIX"), 1024L * 1024L)

        assertFalse(
            "存在大体积分的 .part 临时文件时，不得把正式文件判为就绪",
            modelManager.isAsrReady(),
        )
    }

    // ── 4. LLM 半成品 ──

    @Test
    fun `LLM主文件体积不足门槛时不得判定为就绪`() {
        writeFile(llmDir().resolve(LLM_FILE_NAME), SMALL_FILE_BYTES)

        assertFalse(
            "LLM 主文件仅 $SMALL_FILE_BYTES 字节（远低于门槛），不得判定为就绪",
            modelManager.isLlmReady(),
        )
    }

    // ── 5. 门槛本身的量级（防止有人把门槛改回 1MB） ──

    @Test
    fun `就绪门槛必须远大于一个MB`() {
        val asrEntry = ModelCatalog.builtInEntries.first { it.kind == ModelKind.ASR }
        val llmEntry = ModelCatalog.builtInEntries.first { it.kind == ModelKind.LLM }

        assertTrue(
            "ASR 门槛 ${asrEntry.minSizeBytes} 必须远大于 1MB" +
                "（否则半成品能骗过就绪判定 → 永久无法翻译，BUG-11 根因）",
            asrEntry.minSizeBytes > 1L * 1024L * 1024L,
        )
        assertTrue(
            "LLM 门槛 ${llmEntry.minSizeBytes} 必须远大于 1MB",
            llmEntry.minSizeBytes > 1L * 1024L * 1024L,
        )
    }

    // ── 工具 ──

    private fun asrDir(): File = File(File(filesDir, "models"), "asr")

    private fun llmDir(): File = File(File(filesDir, "models"), "llm")

    /** 写一个指定长度的文件（内容为零字节，仅长度有意义）。 */
    private fun writeFile(file: File, sizeBytes: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
        }
    }

    private companion object {
        const val ASR_FILE_NAME = "model.int8.onnx"
        const val LLM_FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val TOKENS_FILE_NAME = "tokens.txt"

        /** 下载器使用的断点续传临时文件后缀。 */
        const val PART_SUFFIX = ".part"

        /** 1MB —— 远低于 ASR（~205MB）/ LLM（~958MB）门槛。 */
        const val SMALL_FILE_BYTES = 1024L * 1024L
    }
}
