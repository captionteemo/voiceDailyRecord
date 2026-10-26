package com.echolog.app.ai.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ModelDownloader] 的**失败分类策略**回归测试（可恢复 / 不可恢复 / 续传 / 原因透出）。
 *
 * ## 为什么单独一个文件
 *
 * [ModelDownloaderRetryTest] 钉的是「会不会重试、`.part` 留不留」；
 * 本文件钉的是**判定依据本身**——即 [ModelDownloader.isRecoverable] 的分类结果，
 * 以及「失败原因必须是最后一次的真实原因」。
 *
 * ## 曾经的真实缺陷（正则判据）
 *
 * 旧实现用**异常 message 的正则**判断可恢复性：
 * ```kotlin
 * val clientError = Regex("HTTP 4(\\d{2})").find(message)?.groupValues?.get(1)?.toIntOrNull()
 * if (clientError != null && clientError != 429) return false
 * ```
 * `"HTTP 429"` 会被这个正则捕获成 `"29"`（`HTTP 4` + 两位数字 = `42`，剩下的 `9` 被丢掉）
 * ⇒ `29 != 429` ⇒ **429（限流，可恢复）被误判为不可恢复**，
 * 于是「服务端稍后就好」的情况被当成「这个 URL 就是没有这份资源」，
 * 直接删掉 `.part`、不重试。
 *
 * 现实现改为**抛携带状态码的异常**（`HttpStatusException`），
 * 按**类型 + 状态码**判定 ⇒ 429 / 408 / 5xx 可恢复，其余 4xx 不可恢复。
 * 本文件用测试把这个分类结果钉死，防止将来又退回文本匹配。
 */
class ModelDownloaderRetryPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var targetDir: File

    @Before
    fun setUp() {
        targetDir = tempFolder.newFolder("models")
    }

    // ── 1. HTTP 429（限流）必须判为可恢复 ⇒ 会重试 ──

    @Test
    fun `HTTP429必须判为可恢复并触发重试`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (calls.incrementAndGet() == 1) {
                    respond(chain, code = 429)
                } else {
                    respond(chain, code = 200, body = ByteArray(PAYLOAD_BYTES) { (it % 251).toByte() })
                }
            }
            .build()

        val entry = entryOf("model.bin", PAYLOAD_BYTES.toLong())
        val result = downloader(client).download(entry, targetDir)

        assertTrue(
            "429 属于限流（可恢复），重试后应当成功，实际失败：${result.failureOrNull()?.message}",
            result.isSuccess,
        )
        assertEquals("429 后必须重试一次（共 2 次调用）", 2, calls.get())
    }

    // ── 2. HTTP 404（资源不存在）必须判为不可恢复 ⇒ 不重试 + 删 .part ──

    @Test
    fun `HTTP404必须判为不可恢复且不重试并删除part`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                respond(chain, code = 404)
            }
            .build()

        // 预置一个半成品 .part：不可恢复失败必须把它删掉
        // （续传只会续到同样的错误内容）
        val partFile = File(targetDir, "model.bin.part")
        partFile.writeBytes(ByteArray(512) { 7 })

        val entry = entryOf("model.bin", PAYLOAD_BYTES.toLong())
        val result = downloader(client).download(entry, targetDir)

        assertTrue("404 必须判为失败", result.isFailure)
        assertEquals("404 不可恢复，不得重试", 1, calls.get())
        assertFalse("404 不可恢复，必须删除 .part", partFile.exists())
        assertTrue(
            "失败原因必须包含真实状态码 404，实际：${result.failureOrNull()?.message}",
            result.failureOrNull()?.message?.contains("404") == true,
        )
    }

    // ── 3. 重试耗尽 ⇒ 失败原因必须含「最后一次的真实原因」 ──

    @Test
    fun `重试耗尽后失败原因必须包含最后一次真实原因`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                throw IOException("Software caused connection abort")
            }
            .build()

        val entry = entryOf("model.bin", PAYLOAD_BYTES.toLong())
        val result = downloader(client).download(entry, targetDir)

        assertTrue("连续中断必须判为失败", result.isFailure)
        val message = result.failureOrNull()?.message.orEmpty()
        assertTrue(
            "失败原因必须包含最后一次的真实原因「Software caused connection abort」，实际：$message",
            message.contains("Software caused connection abort"),
        )
        assertTrue(
            "失败原因必须说明已重试几次，实际：$message",
            message.contains("已重试"),
        )
    }

    // ── 4. 重试必须从 .part 已有字节续传（Range），而不是从头再来 ──

    @Test
    fun `重试必须带Range头从part已有字节续传`() = runBlocking {
        val requestedRanges = mutableListOf<String?>()
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedRanges += chain.request().header("Range")
                if (calls.incrementAndGet() == 1) {
                    throw IOException("Software caused connection abort")
                }
                // 第二次按 Range 语义返回 206 + 剩余字节
                respond(chain, code = 206, body = ByteArray(PAYLOAD_BYTES / 2) { (it % 251).toByte() })
            }
            .build()

        // 预置 512 字节（= 目标大小的一半），模拟「上次中断留下的半成品」
        val partFile = File(targetDir, "model.bin.part")
        partFile.writeBytes(ByteArray(PAYLOAD_BYTES / 2) { 3 })

        val entry = entryOf("model.bin", PAYLOAD_BYTES.toLong())
        val result = downloader(client).download(entry, targetDir)

        assertTrue(
            "续传后应当成功，实际失败：${result.failureOrNull()?.message}",
            result.isSuccess,
        )
        assertEquals("必须携带 Range 头续传", "bytes=${PAYLOAD_BYTES / 2}-", requestedRanges.first())
        val targetFile = File(targetDir, "model.bin")
        assertEquals(
            "续传后文件长度必须是「已有字节 + 新增字节」",
            PAYLOAD_BYTES.toLong(),
            targetFile.length(),
        )
    }

    // ── 工具 ──

    private fun downloader(client: OkHttpClient): ModelDownloader = ModelDownloader(
        okHttpClient = client,
        ioDispatcher = Dispatchers.Unconfined,
    ).apply {
        // 退避是真实等待，单测里调小（生产恒为 1s/2s）
        retryBackoffBaseMs = 10L
    }

    private fun entryOf(fileName: String, sizeBytes: Long): ModelEntry = ModelEntry(
        kind = ModelKind.ASR,
        fileName = fileName,
        url = "http://127.0.0.1/$fileName",
        sizeBytes = sizeBytes,
        sha256 = "",
        additionalFiles = emptyList(),
    )

    /** 构造一个 OkHttp 响应（不走真实网络）。 */
    private fun respond(
        chain: okhttp3.Interceptor.Chain,
        code: Int,
        body: ByteArray = ByteArray(0),
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200 || code == 206) "OK" else "Error")
        .body(body.toResponseBody("application/octet-stream".toMediaType()))
        .build()

    private companion object {
        /** 载荷字节数；大于体积门槛（90%）即可。 */
        const val PAYLOAD_BYTES = 1024
    }
}
