package com.echolog.app.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelCatalog] 体积阈值契约测试（BUG-11 回归防线）。
 *
 * ## 它防的是什么
 *
 * BUG-11 的「永久无法翻译成文字」有一条隐蔽的根因链：
 *
 * ```
 * minSizeBytes = minOf(sizeBytes / 100, 1MB)   // 门槛只有 1MB！
 *          ↓
 * 拷贝/下载中断 → 正式路径留下半成品文件（约 1MB 就能骗过门槛）
 *          ↓
 * filesReady() 判定「模型已就绪」
 *          ↓
 * sherpa-onnx 加载失败 → SenseVoiceAsrEngine.failedModelStamp 记住指纹
 *          ↓
 * 永不重试 → 用户永远拿不到文字
 * ```
 *
 * 修复是「真实字节数 + 90% 门槛」。本测试把该契约**钉死**，
 * 防止有人日后把门槛改回 1% / 1MB 那种宽松值。
 *
 * ## 注意：不断言具体字节数
 *
 * 模型可能随版本升级（ASR/LLM 换权重），断言 `sizeBytes == 239233841`
 * 会在升级当天把测试搞红。因此这里只断言**量级**与**比例**：
 * 门槛必须落在真实体积的 85%~95% 区间，且真实体积不得是「估算的小值」。
 *
 * 纯 Kotlin，无 Android 依赖（`ModelCatalog` 只用 okhttp + org.json，
 * 两者都在单测 classpath 上）。
 */
class ModelCatalogSizeThresholdTest {

    private val entries = ModelCatalog.builtInEntries

    private val asrEntry = entries.first { it.kind == ModelKind.ASR }
    private val llmEntry = entries.first { it.kind == ModelKind.LLM }

    // ── 1. 门槛比例：85%~95% ──

    @Test
    fun `ASR条目的门槛必须落在真实体积的85到95之间`() {
        assertThresholdRatio(asrEntry)
    }

    @Test
    fun `LLM条目的门槛必须落在真实体积的85到95之间`() {
        assertThresholdRatio(llmEntry)
    }

    // ── 2. 门槛下界：不能是 1MB 那种宽松值 ──

    @Test
    fun `门槛必须显著大于真实体积的一半`() {
        for (entry in entries) {
            assertTrue(
                "${entry.kind} 条目的 minSizeBytes=${entry.minSizeBytes} 必须 > " +
                    "sizeBytes/2=${entry.sizeBytes / 2}；" +
                    "否则截断一半的半成品文件就能骗过就绪判定（BUG-11 根因）",
                entry.minSizeBytes > entry.sizeBytes / 2,
            )
        }
    }

    @Test
    fun `门槛不得小于1024字节`() {
        for (entry in entries) {
            assertTrue(
                "${entry.kind} 条目的 minSizeBytes=${entry.minSizeBytes} 必须 >= 1024",
                entry.minSizeBytes >= 1024L,
            )
        }
    }

    // ── 3. 真实体积量级：不得是估算的小值 ──

    @Test
    fun `ASR权重真实体积必须大于100MB`() {
        assertTrue(
            "ASR sizeBytes=${asrEntry.sizeBytes} 应为真实权重大小（> 100MB），" +
                "不能是估算的小值——它同时是下载进度分母与门槛基数",
            asrEntry.sizeBytes > 100L * 1024L * 1024L,
        )
    }

    @Test
    fun `LLM权重真实体积必须大于500MB`() {
        assertTrue(
            "LLM sizeBytes=${llmEntry.sizeBytes} 应为真实权重大小（> 500MB），" +
                "不能是估算的小值——它同时是下载进度分母与门槛基数",
            llmEntry.sizeBytes > 500L * 1024L * 1024L,
        )
    }

    // ── 4. 半成品必然被拦住（BUG-11 核心不变式） ──

    @Test
    fun `截断一半的权重必然低于门槛`() {
        for (entry in entries) {
            val half = entry.sizeBytes / 2
            assertTrue(
                "${entry.kind}：截断一半的权重（$half 字节）必须低于门槛 " +
                    "${entry.minSizeBytes}，否则半成品会被判为已就绪",
                half < entry.minSizeBytes,
            )
        }
    }

    // ── 5. 伴随文件与 URL 契约（权重改为远端下载后的关键链路） ──

    @Test
    fun `ASR必须声明tokens伴随文件`() {
        assertTrue(
            "ASR 权重需要 tokens.txt 伴随文件，否则端侧 ASR 无法构建词表",
            asrEntry.additionalFiles.contains("tokens.txt"),
        )
    }

    @Test
    fun `条目URL必须包含自身文件名以支持伴随文件同目录推导`() {
        for (entry in entries) {
            assertTrue(
                "${entry.kind} 条目的 url 必须包含主文件名（${entry.fileName}），" +
                    "否则 ModelDownloader.deriveSiblingUrl 无法推导伴随文件 URL：" +
                    "${entry.url}",
                entry.url.contains(entry.fileName),
            )
        }
    }

    @Test
    fun `权重必须来自https远端地址`() {
        for (entry in entries) {
            assertTrue(
                "${entry.kind} 条目的 url 必须是 https（当前：${entry.url}）",
                entry.url.startsWith("https://"),
            )
        }
    }

    // ── 工具 ──

    /**
     * 断言门槛落在真实体积的 85%~95% 区间。
     *
     * 用整数运算比较，避免浮点误差；区间而非等值，是为了允许后续微调
     * （如 90% → 95%），但拒绝「改回 1% / 1MB」这种致命放宽。
     */
    private fun assertThresholdRatio(entry: ModelEntry) {
        val minSize = entry.minSizeBytes
        val size = entry.sizeBytes

        assertTrue(
            "${entry.kind} 条目的 minSizeBytes=$minSize 必须 >= sizeBytes 的 85%" +
                "（${size * 85 / 100}）",
            minSize >= size * 85 / 100,
        )
        assertTrue(
            "${entry.kind} 条目的 minSizeBytes=$minSize 必须 <= sizeBytes 的 95%" +
                "（${size * 95 / 100}）",
            minSize <= size * 95 / 100,
        )
        // 门槛不得超过真实体积本身
        assertTrue(
            "${entry.kind} 条目的 minSizeBytes=$minSize 不得超过 sizeBytes=$size",
            minSize <= size,
        )
    }

    @Test
    fun `内置清单必须恰好包含ASR与LLM两项`() {
        assertEquals(
            "内置清单应恰好包含 ASR 与 LLM 两个条目，实际：" +
                entries.map { "${it.kind}:${it.fileName}" },
            2,
            entries.size,
        )
        assertEquals(1, entries.count { it.kind == ModelKind.ASR })
        assertEquals(1, entries.count { it.kind == ModelKind.LLM })
    }
}
