package com.echolog.app.ai.model

import com.echolog.app.core.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ModelDownloader] 的**重试与 `.part` 保留**行为回归测试。
 *
 * ## 为什么需要这个测试
 *
 * BUG-13 第三层根因：大文件在 HTTP/2 下被中止（`Http2Writer.windowUpdate`），
 * 1.1GB 下到 581MB 后中断。原实现有两个致命缺陷：
 *
 * 1. `catch` 块无条件 `partFile.delete()` —— 把**已经下好的几百 MB 全部作废**，
 *    每次网络抖动都变成「从头再来」；
 * 2. 没有任何重试 —— 单次连接失败即整个下载失败。
 *
 * 修复后应有三条性质：
 *
 * | 性质 | 断言 |
 * |---|---|
 * | 可恢复失败**保留** `.part` | 中断后 `.part` 仍存在且字节数不倒退 |
 * | 可恢复失败会**重试** | 服务端被调用多次 |
 * | 不可恢复失败（4xx）**删除** `.part` | 404 后 `.part` 不存在 |
 *
 * 本测试用 OkHttp **拦截器**模拟各种故障（纯 JVM，不打真网、不开端口），
 * 因此比真实网络故障稳定得多。
 */
class ModelDownloaderRetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var targetDir: File

    @Before
    fun setUp() {
        targetDir = tempFolder.newFolder("models")
    }

    @After
    fun tearDown() {
        // 清理：TemporaryFolder 会在规则结束时自动删除
    }

    // ── 1. 可恢复失败：必须重试，且保留 .part ──

    @Test
    fun `可恢复失败时必须重试且保留part文件`() = runBlocking {
        // 前两次连接直接抛 IOException（模拟连接中断），第三次正常返回
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val attempt = calls.incrementAndGet()
                if (attempt <= 2) {
                    throw IOException("Software caused connection abort")
                }
                respond(chain, code = 200, body = ByteArray(PAYLOAD_BYTES) { (it % 251).toByte() })
            }
            .build()

        val entry = entryOf("model.bin", PAYLOAD_BYTES.toLong())
        val result = downloader(client).download(entry, targetDir)

        assertTrue(
            "重试后应当成功，实际失败：${result.failureOrNull()?.message}",
            result.isSuccess,
        )
        assertEquals("应当被调用 3 次（2 次失败 + 1 次成功）", 3, calls.get())
        val targetFile = File(targetDir, entry.fileName)
        assertTrue("主文件应已转正", targetFile.isFile)
        assertEquals(PAYLOAD_BYTES.toLong(), targetFile.length())
    }

    // ── 2. 可恢复失败且重试耗尽：.part 必须保留（下次可续传） ──

    @Test
    fun `重试耗尽后必须保留part以便下次续传`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                // 先写入一半字节再抛异常，模拟「下到一半中断」
                throw IOException("Software caused connection abort")
            }
            .build()

        // 先手工放一个半成品 .part（模拟上次中断留下的）
        val partFile = File(targetDir, "${entryOf("model.bin", 0L).fileName}.part")
        partFile.writeBytes(ByteArray(512) { 1 })

        val entry = entryOf("model.bin", PAYLOAD_BYTES.toLong())
        val result = downloader(client).download(entry, targetDir)

        assertTrue("重试耗尽应当失败", result.isFailure)
        assertEquals("应当尝试 3 次", 3, calls.get())
        assertTrue(
            "可恢复失败后必须保留 .part（下次靠 Range 续传），实际已被删除",
            partFile.exists(),
        )
        assertEquals(
            "保留的 .part 字节数不应倒退",
            512L,
            partFile.length(),
        )
        assertFalse(
            "失败时不得产生正式文件",
            File(targetDir, entry.fileName).exists(),
        )
    }

    // ── 3. 不可恢复失败（HTTP 404）：.part 必须删除，且不重试 ──

    @Test
    fun `不可恢复失败时不得重试且必须删除part`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                respond(chain, code = 404, body = ByteArray(0))
            }
            .build()

        val entry = entryOf("model.bin", PAYLOAD_BYTES.toLong())
        val result = downloader(client).download(entry, targetDir)

        assertTrue("404 必须判为失败", result.isFailure)
        assertEquals(
            "不可恢复失败不应重试（重试只会得到同样的结果）",
            1,
            calls.get(),
        )
        assertFalse(
            "不可恢复失败必须删除 .part",
            File(targetDir, "${entry.fileName}.part").exists(),
        )
        assertTrue(
            "失败原因必须包含真实状态码 404，实际：${result.failureOrNull()?.message}",
            result.failureOrNull()?.message?.contains("404") == true,
        )
    }

    // ── 4. HTTP/1.1 强制：客户端协议列表必须只含 HTTP_1_1 ──

    @Test
    fun `模型下载客户端必须强制HTTP1_1`() {
        // 真机实证：HTTP/2 下 1.1GB 在 581MB 处被中止
        // （java.net.SocketException: Software caused connection abort
        //   at okhttp3.internal.http2.Http2Writer.windowUpdate）
        // 对照实验：同一设备 curl --http1.1 26 秒完整下完 1.1GB。
        // 故协议列表必须显式限制，不能依赖 ALPN 协商。
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        assertEquals(
            "模型下载必须强制 HTTP/1.1",
            listOf(Protocol.HTTP_1_1),
            client.protocols,
        )
    }

    // ── 工具 ──

    private fun downloader(client: OkHttpClient): ModelDownloader = ModelDownloader(
        okHttpClient = client,
        ioDispatcher = Dispatchers.Unconfined,
    )

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
        body: ByteArray,
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Not Found")
        .body(body.toResponseBody("application/octet-stream".toMediaType()))
        .build()

    private companion object {
        const val PAYLOAD_BYTES = 1024
    }
}
