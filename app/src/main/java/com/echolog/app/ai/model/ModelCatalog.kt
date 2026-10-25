package com.echolog.app.ai.model

import com.echolog.app.core.common.EchoResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 模型清单（架构 2.4/决策 2）。
 *
 * 内置条目为默认配置（URL 可整体替换，架构 8 待明确 #1 托管位置）；
 * 远端 manifest 可覆盖内置条目（[fetchManifest]），失败时回落内置。
 *
 * ASR 需两个文件（模型 + tokens），故条目带 [ModelEntry.additionalFiles]。
 */
object ModelCatalog {

    /**
     * 内置默认清单。
     *
     * [ModelEntry.sizeBytes] 必须是 **远端权重的真实字节数**（不是估算值），
     * 因为它同时承担两个职责：
     * 1. 下载进度估算的分母（真实值更准）；
     * 2. [ModelEntry.minSizeBytes] 的计算基数 —— 就绪判定的门槛。
     *
     * BUG-11 修复：原先 ASR 声明 460MB、LLM 声明 1GB，而真实权重是
     * 239,233,841 与 1,117,320,736 字节；配合原先 `minOf(sizeBytes/100, 1MB)`
     * 的阈值算法，两个模型的门槛都只有 1MB —— **拷贝中断留下的半成品文件
     * 也能骗过就绪判定**，导致 sherpa-onnx 加载失败后永不重试（永久无法翻译）。
     * 现改为真实字节数 + 90% 门槛，半成品无法冒充完整权重。
     *
     * 权重来源：**远端下载**（ModelScope），不再内置进 APK —— 1.35GB 权重会让
     * APK 体积达到 1.6GB，无法分发到代码托管平台（GitHub 单文件 100MB 限制）。
     *
     * 远端 manifest 可覆盖内置条目（[fetchManifest]），失败时回落内置。
     *
     * ASR 需两个文件（模型 + tokens），故条目带 [ModelEntry.additionalFiles]。
     */
    val builtInEntries: List<ModelEntry> = listOf(
        ModelEntry(
            kind = ModelKind.ASR,
            fileName = "model.int8.onnx",
            url = "https://modelscope.cn/models/poloniumrock/SenseVoiceSmallOnnx/resolve/master/model.int8.onnx",
            sizeBytes = 239_233_841L,
            sha256 = "",
            additionalFiles = listOf("tokens.txt"),
        ),
        ModelEntry(
            kind = ModelKind.LLM,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://modelscope.cn/models/qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            sha256 = "",
        ),
    )

    /** 远端 manifest 地址（未配置托管时返回内置清单）。 */
    const val MANIFEST_URL: String =
        "https://mirror.example.com/echolog/models/manifest.json"

    /**
     * 拉取远端 manifest 覆盖内置清单。
     *
     * 期望格式：`{"models":[{"kind":"ASR","fileName":"...","url":"...","sizeBytes":0,"sha256":"..."}]}`。
     * 任何失败（网络/格式）都回落 [builtInEntries]，不阻塞下载流程。
     */
    suspend fun fetchManifest(okHttpClient: OkHttpClient): EchoResult<List<ModelEntry>> {
        return try {
            val request = Request.Builder().url(MANIFEST_URL).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return EchoResult.failure("E-6001", "manifest HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: return EchoResult.failure("E-6001", "manifest 响应为空")
                EchoResult.success(parseManifest(body) ?: builtInEntries)
            }
        } catch (throwable: Throwable) {
            EchoResult.failure("E-6001", "manifest 拉取失败：${throwable.message}", throwable)
        }
    }

    private fun parseManifest(body: String): List<ModelEntry>? = runCatching {
        val root = org.json.JSONObject(body)
        val array = root.optJSONArray("models") ?: return null
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = when (item.optString("kind").uppercase()) {
                "ASR" -> ModelKind.ASR
                "LLM" -> ModelKind.LLM
                else -> return@mapNotNull null
            }
            ModelEntry(
                kind = kind,
                fileName = item.optString("fileName"),
                url = item.optString("url"),
                sizeBytes = item.optLong("sizeBytes"),
                sha256 = item.optString("sha256"),
                additionalFiles = item.optJSONArray("additionalFiles")?.let { files ->
                    (0 until files.length()).map { files.optString(it) }
                } ?: emptyList(),
            )
        }.filter { it.fileName.isNotBlank() && it.url.isNotBlank() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * 单个模型文件条目。
 *
 * @param additionalFiles 同目录必需伴随文件（如 ASR 的 tokens.txt）。
 * @param sizeBytes 该文件的**真实字节数**（远端权重的实际大小）；既作下载进度分母，
 *   也作 [minSizeBytes] 的计算基数。
 */
data class ModelEntry(
    val kind: ModelKind,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String = "",
    val additionalFiles: List<String> = emptyList(),
) {
    /**
     * 体积下限校验阈值：文件至少要有真实体积的 90% 才算「完整」。
     *
     * BUG-11 修复：原实现为 `minOf(sizeBytes / 100, 1MB)`，门槛低到 1MB ——
     * 拷贝中断留下的半成品文件也能通过判定，被误认为模型已就绪，
     * 导致推理引擎加载失败后永不重试（用户表现为「永久无法翻译成文字」）。
     * 90% 门槛可拦住任何截断，同时容忍极小的元数据差异。
     */
    val minSizeBytes: Long
        get() = (sizeBytes * READY_SIZE_RATIO_NUMERATOR / READY_SIZE_RATIO_DENOMINATOR)
            .coerceAtLeast(MIN_SIZE_FLOOR_BYTES)

    private companion object {
        /** 就绪门槛比例：90%（分子/分母，整数运算避免浮点误差）。 */
        const val READY_SIZE_RATIO_NUMERATOR = 9L
        const val READY_SIZE_RATIO_DENOMINATOR = 10L

        /** 门槛下限（字节）：防止 sizeBytes 异常小导致门槛为 0。 */
        const val MIN_SIZE_FLOOR_BYTES = 1024L
    }
}
