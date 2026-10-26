package com.echolog.app.ai.model

import com.echolog.app.core.common.EchoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ModelDownloader] 的**真实落盘字节数**回归测试（用本地 HTTP 服务端，不打真网）。
 *
 * ## 为什么需要这个测试
 *
 * `ModelDownloader.streamDownload()` 曾有一个**影子变量 bug**：它在 `use {}` 块**内**
 * 声明了 `var totalBytes`，而 `return totalBytes` 在块**外**——Kotlin 的块内局部变量
 * 会**遮蔽**外层同名变量，于是方法返回的其实是「下载**前**已有的字节数」
 * （首次下载恒为 0），而不是实际落盘字节。
 *
 * 后果链：
 * ```
 * streamDownload 返回 0
 *      ↓
 * 上层判定「下载文件过小（0 B），可能为错误页」
 *      ↓
 * 把刚下好的 .part 删掉
 *      ↓
 * 下载永远无法成功，且失败原因是**假的**
 * ```
 *
 * 这个 bug 靠「读代码」极难发现（变量名一模一样），但用本地 HTTP 服务端发几百字节
 * 就能稳定复现——故固化为回归测试。
 *
 * 真机实证（本 bug 的发现现场）：设备上 LLM 权重 `.part` 一路写到 851,516,367 字节，
 * 随后文件被删除、状态落 ERROR、界面提示「下载文件过小，可能为错误页」——
 * 与本测试复现的路径完全一致。
 *
 * ## 为什么不用 `com.sun.net.httpserver.HttpServer`
 *
 * 该类位于 JDK 模块 `jdk.httpserver`，**不在 Android 单元测试的编译 classpath 上**
 * （Android 的单元测试编译走 `android.jar` + JDK 核心 API，`jdk.httpserver` 不在内）。
 * 实测报 `Unresolved reference 'sun'` / `Unresolved reference 'HttpServer'`。
 * 项目也未引入 MockWebServer 依赖。
 *
 * 故此处用 JDK 核心的 [ServerSocket] 手写一个最小 HTTP/1.1 服务端：
 * 只依赖 `java.net`（必然可用），不新增任何依赖。
 *
 * ## 与其它测试的关系
 *
 * [ModelManagerReadinessTest] 守的是「就绪判定不认半成品」；
 * 本测试守的是「下载器必须报告**真实**落盘字节数」——两者合起来才保证
 * 「下载成功 → 文件转正 → 判定就绪」这条链路成立。
 *
 * 本文件不使用 Mockito（避免 suspend 函数的桩 difficulties），只依赖 JDK 自带的
 * `java.net` 与项目已有的 OkHttp。
 */
class ModelDownloaderRealBytesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: TinyHttpServer
    private lateinit var targetDir: File

    @Before
    fun setUp() {
        targetDir = tempFolder.newFolder("models")
        server = TinyHttpServer().start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ── 1. 成功路径：返回值必须等于真实落盘字节数 ──

    @Test
    fun `下载成功时返回的字节数必须等于实际落盘字节数`() {
        val body = ByteArray(PAYLOAD_BYTES) { (it % 251).toByte() }
        server.serve("/model.bin", body)
        val entry = entryOf("model.bin", body.size.toLong())

        val result = runBlocking { downloader().download(entry, targetDir) }

        assertTrue(
            "下载应成功，实际失败：${result.failureOrNull()?.message}",
            result.isSuccess,
        )
        val targetFile = File(targetDir, entry.fileName)
        assertTrue("目标文件应存在：${targetFile.absolutePath}", targetFile.isFile)
        assertEquals(
            "目标文件长度必须等于实际落盘字节数（影子变量 bug 会让此断言失败）",
            body.size.toLong(),
            targetFile.length(),
        )
    }

    // ── 2. 失败路径：原因必须是真实状态码，且不得留下半成品 ──

    @Test
    fun `服务端返回错误状态时失败原因必须包含真实状态码`() {
        server.serve("/missing.bin", ByteArray(0), status = 404)
        val entry = entryOf("missing.bin", PAYLOAD_BYTES.toLong())

        val result = runBlocking { downloader().download(entry, targetDir) }

        assertTrue("HTTP 404 必须判为失败", result.isFailure)
        val message = result.failureOrNull()?.message.orEmpty()
        assertTrue(
            "失败原因必须包含真实状态码 404（UI 要把它透给用户），实际：$message",
            message.contains("404"),
        )
        assertTrue(
            "正式路径下不得留下半成品文件",
            !File(targetDir, entry.fileName).exists(),
        )
    }

    // ── 工具 ──

    private fun downloader(): ModelDownloader = ModelDownloader(
        okHttpClient = OkHttpClient.Builder().build(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun entryOf(fileName: String, sizeBytes: Long): ModelEntry = ModelEntry(
        kind = ModelKind.ASR,
        fileName = fileName,
        url = "http://127.0.0.1:${server.port}/$fileName",
        sizeBytes = sizeBytes,
        // 内置清单同样留空 sha256：即不做校验和比对，只校验体积门槛
        sha256 = "",
        additionalFiles = emptyList(),
    )

    private companion object {
        /** 载荷字节数：大于 90% 门槛（1024 × 0.9）即可，足够小以保证测试快。 */
        const val PAYLOAD_BYTES = 1024
    }
}

/**
 * 最小 HTTP/1.1 服务端（仅用 [ServerSocket] 实现）。
 *
 * 支持的最小能力：按路径返回固定字节、指定 HTTP 状态码。
 * 只处理**请求行 + 头部**，读完即响应，不做 keep-alive——
 * 本测试的客户端（OkHttp）每次都是新连接，够用。
 */
private class TinyHttpServer {

    private val routes = ConcurrentHashMap<String, Route>()
    private val running = AtomicBoolean(true)
    private val serverSocket = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TinyHttpServer").apply { isDaemon = true }
    }

    val port: Int get() = serverSocket.localPort

    fun start(): TinyHttpServer {
        executor.submit {
            while (running.get()) {
                try {
                    serverSocket.accept().use { socket -> handle(socket) }
                } catch (e: IOException) {
                    // accept 在 close() 时抛异常，属正常退出路径
                    if (running.get()) throw e
                }
            }
        }
        return this
    }

    /** 注册一个路径的响应。[status] 非 200 时响应体为空。 */
    fun serve(path: String, body: ByteArray, status: Int = 200) {
        routes[path] = Route(body, status)
    }

    fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        runCatching { executor.shutdownNow() }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream().bufferedReader()
            // 读请求行 + 头部（读到空行结束）
            val requestLine = input.readLine() ?: return
            var line = input.readLine()
            while (line != null && line.isNotEmpty()) {
                line = input.readLine()
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val route = routes[path]
            val output = client.getOutputStream()
            if (route == null) {
                output.write(
                    ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(),
                )
                output.flush()
                return
            }
            val header = buildString {
                append("HTTP/1.1 ${route.status} ${statusText(route.status)}\r\n")
                append("Content-Length: ${route.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray())
            if (route.status == 200 && route.body.isNotEmpty()) {
                output.write(route.body)
            }
            output.flush()
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    private data class Route(val body: ByteArray, val status: Int)
}
