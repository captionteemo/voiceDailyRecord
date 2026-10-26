package com.echolog.app.ai.model

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.EchoLog
import com.echolog.app.core.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型文件下载器（架构 2.4，FR-012）。
 *
 * - OkHttp 流式下载，64KB 缓冲顺序写盘；
 * - 断点续传：`.part` 临时文件 + `Range: bytes=<已下载>` 头；
 * - 完整性：下载完成后 SHA-256 校验（[ModelEntry.sha256] 非空时）；
 * - 失败分类：网络/IO → E-6001；校验不符 → E-6002（删除重下）。
 *
 * **可观测性（静默失败红线）**：本类此前**零日志**——HTTP 状态码、Content-Length、
 * 是否走了续传、实际落盘字节数全部不可见，失败原因只存在于返回值里，而上层
 * [ModelManager] 曾把它直接丢弃。现所有失败返回点与关键节点都有日志，
 * 且失败原因会经 [ModelManager.lastErrors] 透传到界面。
 *
 * ## 大文件下载的三条硬约束（改本文件前请读完）
 *
 * 1. **必须重试**（[downloadStreamWithRetry]）：单次连接随时可能因网络抖动或
 *    协议层错误而中断，重试时**从 `.part` 已有字节续传**，不从头再来；
 * 2. **`.part` 按可恢复性处置**：可恢复失败**保留**（下次续传），
 *    不可恢复失败（4xx、校验不符、体积异常）**删除**（续传只会续到同样的错误内容）；
 * 3. **HTTP/1.1**（由 [com.echolog.app.di.AppModule] 的 client 强制）：
 *    HTTP/2 的流控会在大文件长连接上被 CDN 中止（真机实测 1.1GB 在 581MB 处
 *    `Software caused connection abort`，堆栈 `Http2Writer.windowUpdate`）。
 */
@Singleton
class ModelDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 重试退避基数（毫秒）；第 n 次重试等待 `retryBackoffBaseMs × 2^(n-1)`。
     *
     * **仅供单元测试调小**：退避是真实等待，单测里等 1s+2s 会拖慢整个套件，
     * 故开放为可覆盖的内部属性（生产恒为 [RETRY_BACKOFF_BASE_MS]）。
     */
    internal var retryBackoffBaseMs: Long = RETRY_BACKOFF_BASE_MS

    /**
     * 下载单个模型条目到目标目录。
     *
     * @param entry 模型条目。
     * @param targetDir 目标目录（`models/asr/` 或 `models/llm/`）。
     * @param onProgress 进度回调（0~100，按 [ModelEntry.sizeBytes] 估算）。
     * @param onRetry 「即将重试」回调（见 [DownloadRetryInfo]），供上层提示
     *   「网络不稳定，正在重试」，不让用户对着不动的进度条干等。
     * @return 成功时携带已下载的主文件；失败携带错误码与**真实原因**。
     */
    suspend fun download(
        entry: ModelEntry,
        targetDir: File,
        onProgress: (Int) -> Unit = {},
        onRetry: (DownloadRetryInfo) -> Unit = {},
    ): EchoResult<File> = withContext(ioDispatcher) {
        FileUtils.ensureDirectory(targetDir)
        val targetFile = File(targetDir, entry.fileName)
        val partFile = File(targetDir, "${entry.fileName}.part")
        EchoLog.i(
            TAG,
            "开始下载 kind=${entry.kind} file=${entry.fileName} 期望字节=${entry.sizeBytes} " +
                "门槛字节=${entry.minSizeBytes} url=${entry.url}",
        )
        try {
            // 主文件已就绪（体积达标，下载时已校验过 SHA-256）则跳过重下：
            // 避免伴随文件缺失导致重试时重复下载大文件
            if (!(targetFile.isFile && targetFile.length() >= entry.minSizeBytes)) {
                // 大文件下载必须自带重试：单次连接随时可能因网络抖动/协议层 abort
                // 而中断（真机实测：1.1GB 在 HTTP/2 下于 581MB 处被中止，
                // 堆栈 okhttp3.internal.http2.Http2Writer.windowUpdate）。
                // 每次重试都从 .part 已有字节续传，不从头再来。
                val outcome = downloadStreamWithRetry(
                    url = entry.url,
                    expectedSizeBytes = entry.sizeBytes,
                    partFile = partFile,
                    onProgress = onProgress,
                    onRetry = onRetry,
                )
                val failure = outcome.failure
                if (failure != null) {
                    // 重试耗尽仍失败：`.part` 的处置已由可恢复性判定决定
                    // （可恢复 ⇒ 保留供下次续传；不可恢复 ⇒ 已删除）
                    return@withContext EchoResult.failure(
                        failure.code,
                        failure.message,
                        failure.cause,
                    )
                }
                val downloadedBytes = outcome.bytes
                EchoLog.i(
                    TAG,
                    "主文件下载结束 kind=${entry.kind} 落盘字节=$downloadedBytes " +
                        "门槛=${entry.minSizeBytes} 是否达标=${downloadedBytes >= entry.minSizeBytes}",
                )
                if (downloadedBytes < entry.minSizeBytes) {
                    // 体积过小 = 服务端返回了错误页/HTML 而非二进制内容。
                    // 这属于**不可恢复**失败：流已完整读完，说明服务端给的确实不是
                    // 权重本体；此时 `.part` 里装的是错误页内容，续传只会把错误内容
                    // 接在好数据后面 ⇒ 删除 .part，交给下一次重新下载。
                    runCatching { partFile.delete() }
                    val message = "下载文件过小（${FileUtils.formatBytes(downloadedBytes)}），可能为错误页"
                    EchoLog.e(
                        TAG,
                        "下载失败 kind=${entry.kind} code=${Constants.ERROR_MODEL_DOWNLOAD} " +
                            "原因=$message（已删除 .part）",
                    )
                    return@withContext EchoResult.failure(
                        Constants.ERROR_MODEL_DOWNLOAD,
                        message,
                    )
                }
                if (entry.sha256.isNotBlank()) {
                    val actual = FileUtils.sha256Hex(partFile)
                    if (!actual.equals(entry.sha256, ignoreCase = true)) {
                        // 校验不符 = 内容已损坏，续传只会续到同样的坏内容 ⇒ 删 .part
                        runCatching { partFile.delete() }
                        EchoLog.e(
                            TAG,
                            "下载失败 kind=${entry.kind} code=${Constants.ERROR_MODEL_CHECKSUM} " +
                                "原因=SHA-256 校验失败（期望=${entry.sha256} 实际=$actual，已删除 .part）",
                        )
                        return@withContext EchoResult.failure(
                            Constants.ERROR_MODEL_CHECKSUM,
                            "SHA-256 校验失败",
                        )
                    }
                }
                if (!partFile.renameTo(targetFile)) {
                    runCatching { partFile.delete() }
                    EchoLog.e(
                        TAG,
                        "下载失败 kind=${entry.kind} code=${Constants.ERROR_MODEL_DOWNLOAD} " +
                            "原因=临时文件转正失败（${partFile.name} → ${targetFile.name}，已删除 .part）",
                    )
                    return@withContext EchoResult.failure(Constants.ERROR_MODEL_DOWNLOAD, "临时文件转正失败")
                }
                EchoLog.i(TAG, "主文件已转正 kind=${entry.kind} 路径=${targetFile.absolutePath}")
            } else {
                EchoLog.i(
                    TAG,
                    "主文件已就绪，跳过下载 kind=${entry.kind} 路径=${targetFile.absolutePath}",
                )
            }
            // 伴随文件（如 tokens.txt）缺失时按同目录约定下载，完成后校验存在性
            val missingExtras = entry.additionalFiles
                .filter { File(targetDir, it).let { file -> !file.isFile || file.length() == 0L } }
            if (missingExtras.isNotEmpty()) {
                EchoLog.i(TAG, "需要下载伴随文件 kind=${entry.kind} 文件=$missingExtras")
                val extrasResult = downloadAdditionalFiles(entry, targetDir, onRetry)
                if (extrasResult.isFailure) {
                    val cause = extrasResult.failureOrNull()
                    EchoLog.e(
                        TAG,
                        "下载失败 kind=${entry.kind} code=${cause?.code} " +
                            "原因=伴随文件下载失败：${cause?.message}",
                    )
                    return@withContext EchoResult.failure(
                        Constants.ERROR_MODEL_DOWNLOAD,
                        "伴随文件下载失败：${cause?.message ?: "未知错误"}",
                    )
                }
            }
            val stillMissing = entry.additionalFiles
                .filter { File(targetDir, it).let { file -> !file.isFile || file.length() == 0L } }
            if (stillMissing.isNotEmpty()) {
                EchoLog.e(
                    TAG,
                    "下载失败 kind=${entry.kind} code=${Constants.ERROR_ASR_MODEL_MISSING} " +
                        "原因=缺少伴随文件：${stillMissing.joinToString()}",
                )
                return@withContext EchoResult.failure(
                    Constants.ERROR_ASR_MODEL_MISSING,
                    "缺少伴随文件：${stillMissing.joinToString()}",
                )
            }
            onProgress(100)
            EchoLog.i(TAG, "下载完成 kind=${entry.kind} 文件=${targetFile.absolutePath}")
            EchoResult.success(targetFile)
        } catch (cancellation: CancellationException) {
            // 协程取消**不是失败**：不吞、不转成 failure（项目约定：不得吞取消）。
            // 取消通常意味着用户主动停止或进程被杀 —— 此时**保留 .part**
            // （下次可续传）；只有「明确不可恢复」才删除。
            EchoLog.w(TAG, "下载被取消 kind=${entry.kind}（保留 .part 以便续传）")
            throw cancellation
        } catch (throwable: Throwable) {
            // 兜底 catch：这里的异常来自「建目录 / 校验 / 转正 / 伴随文件」等外围步骤。
            // 主文件的可恢复失败已在 [downloadStreamWithRetry] 内部处理完毕
            // （保留 .part 并重试）；此处的失败原因**尚不能判定为不可恢复**
            // （例如 SHA-256 计算时的 IO 抖动），故**保留 .part**：
            // 删掉等于把已经下好的几百 MB 作废，下次从零再来。
            // 真正「内容不可信」的路径（校验不符、转正失败）已各自显式删除 .part。
            val message = "下载失败：${throwable.message ?: "网络错误"}"
            EchoLog.e(
                TAG,
                "下载失败 kind=${entry.kind} code=${Constants.ERROR_MODEL_DOWNLOAD} 原因=$message（保留 .part）",
                throwable,
            )
            EchoResult.failure(
                Constants.ERROR_MODEL_DOWNLOAD,
                message,
                throwable,
            )
        }
    }

    /**
     * 流式下载 + 有限次重试（指数退避），**每次从 `.part` 已有字节续传**。
     *
     * ## 为什么必须有这一层
     *
     * 大文件（239MB / 1.1GB）在移动网络下的长连接极不可靠。真机实测：
     * 1.1GB 的 GGUF 在 HTTP/2 下于 581,647,329 字节处停滞 5 分半，随后
     * `java.net.SocketException: Software caused connection abort`
     * （堆栈 `okhttp3.internal.http2.Http2Writer.windowUpdate`）。
     * 对照实验：同一台设备 `curl --http1.1` 完整下完 1.1GB 只需 26 秒。
     *
     * ## 为什么不能靠 OkHttp 的 `retryOnConnectionFailure(true)`
     *
     * 该开关只覆盖**连接建立失败**与部分 IO 异常，**不覆盖协议层错误**
     * （如 HTTP/2 流控 abort）。所以应用层必须自己做重试。
     *
     * ## `.part` 的处置判据（活 B）
     *
     * - **可恢复失败**（连接中断/读超时/5xx/429/408）：**保留 `.part`**，
     *   下次用 `Range` 续传（581MB 已经下好，删掉等于从头再来）；
     * - **不可恢复失败**（HTTP 4xx、响应体为空）：**删除 `.part`** ——
     *   换连接/换时间只会得到同样的结果，续传无意义。
     *
     * 判据见 [isRecoverable]（**按异常类型 + HTTP 状态码判定，不解析 message 文本**）。
     *
     * @param expectedSizeBytes 期望总字节数（仅用于进度百分比；未知时传 0）。
     */
    private suspend fun downloadStreamWithRetry(
        url: String,
        expectedSizeBytes: Long,
        partFile: File,
        onProgress: (Int) -> Unit,
        onRetry: (DownloadRetryInfo) -> Unit,
    ): StreamOutcome {
        var lastFailure: EchoResult.Failure? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                val bytes = streamDownload(url, expectedSizeBytes, partFile, onProgress)
                return StreamOutcome(bytes = bytes, failure = null)
            } catch (cancellation: CancellationException) {
                // 取消不是失败，原样抛出（项目约定：不得吞取消）
                throw cancellation
            } catch (throwable: Throwable) {
                val recoverable = isRecoverable(throwable)
                val reason = throwable.message ?: "网络错误"
                EchoLog.w(
                    TAG,
                    "下载中断 url=$url 第 $attempt/$MAX_DOWNLOAD_ATTEMPTS 次 " +
                        "已落盘=${partFile.length()} 可恢复=$recoverable 原因=$reason",
                    throwable,
                )
                if (!recoverable) {
                    // 不可恢复：删掉 .part（续传只会续到同样的错误内容）
                    runCatching { partFile.delete() }
                    return StreamOutcome(
                        bytes = 0L,
                        failure = EchoResult.Failure(
                            code = Constants.ERROR_MODEL_DOWNLOAD,
                            message = "下载失败：$reason",
                            cause = throwable,
                        ),
                    )
                }
                lastFailure = EchoResult.Failure(
                    code = Constants.ERROR_MODEL_DOWNLOAD,
                    message = reason,
                    cause = throwable,
                )
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    // 指数退避：1s / 2s（首发 1 次 + 重试 2 次 = 共 3 次尝试）
                    val backoffMs = retryBackoffBaseMs * (1L shl (attempt - 1))
                    EchoLog.i(
                        TAG,
                        "第 $attempt 次失败，退避 ${backoffMs}ms 后重试" +
                            "（保留 .part，将从 ${partFile.length()} 字节处续传）",
                    )
                    // 通知上层「正在重试」，让 UI 提示用户而不是干等。
                    // 回调异常**不得**影响下载主链路（铁律 5：旁支动作不得中断主链路）。
                    runCatching {
                        onRetry(
                            DownloadRetryInfo(
                                attempt = attempt,
                                maxAttempts = MAX_DOWNLOAD_ATTEMPTS,
                                downloadedBytes = partFile.length(),
                                reason = reason,
                            ),
                        )
                    }.onFailure { callbackError ->
                        EchoLog.w(TAG, "重试通知回调异常（不影响下载）", callbackError)
                    }
                    delay(backoffMs)
                }
            }
        }
        EchoLog.e(
            TAG,
            "下载重试耗尽 url=$url 已落盘=${partFile.length()}" +
                "（保留 .part，下次继续续传）",
        )
        val last = lastFailure ?: EchoResult.Failure(
            code = Constants.ERROR_MODEL_DOWNLOAD,
            message = "网络错误",
        )
        return StreamOutcome(
            bytes = partFile.length(),
            failure = EchoResult.Failure(
                code = last.code,
                // 失败原因必须包含**最后一次的真实原因**，不能只说「重试失败」
                message = "下载失败（已重试 ${MAX_DOWNLOAD_ATTEMPTS - 1} 次）：${last.message}",
                cause = last.cause,
            ),
        )
    }

    /**
     * 判断失败是否**可恢复**（值得保留 `.part` 并重试）。
     *
     * ## 判据（按异常**类型**与 HTTP 状态码，**不解析 message 文本**）
     *
     * - **HTTP 状态码**（[HttpStatusException] 携带真实状态码）：
     *   5xx / 429 / 408 是「服务端暂时不行」，换个连接或换个时间可能成功 ⇒ 可恢复；
     *   其余 4xx（如 404）是「这个 URL 就是没有这份资源」，重试只会得到同样的
     *   结果 ⇒ 不可恢复；
     * - **其它 IO 异常**（`SocketException: Software caused connection abort`、
     *   `SocketTimeoutException`、`ProtocolException: unexpected end of stream` …）
     *   都是连接/协议层的**瞬时**故障 ⇒ 可恢复；
     * - **非 IO 异常** ⇒ 不可恢复（重试同样会失败）。
     *
     * **为什么不用「正则匹配 message」**：message 文本由第三方库产生，
     * 换个 OkHttp 版本就可能变形（例如把 `HTTP 429` 写成 `HTTP 429 Too Many Requests`），
     * 判据随之失效；而且正则很容易把 `HTTP 429` 误判成不可恢复
     * （`Regex("HTTP 4(\\d{2})")` 只会捕获到 `42`）。抛**携带状态码的异常**
     * 才能让判据与文本解耦。
     */
    private fun isRecoverable(throwable: Throwable): Boolean = when (throwable) {
        is HttpStatusException -> throwable.code >= 500 ||
            throwable.code == HTTP_TOO_MANY_REQUESTS ||
            throwable.code == HTTP_REQUEST_TIMEOUT
        is IOException -> true
        else -> false
    }

    /**
     * 下载伴随文件（如 ASR 的 `tokens.txt`，FR-012）。
     *
     * [ModelEntry] 仅持有主文件 URL，伴随文件按「同目录约定」推导地址：
     * 将主 URL 中主文件名替换为伴随文件名（内置清单的镜像布局满足该约定；
     * 远端 manifest 若采用异目录布局，需在条目中扩展 URL 字段）。
     *
     * 与主文件走**同一套**重试与 `.part` 处置策略（可恢复 ⇒ 保留 `.part` 续传）。
     *
     * @param onRetry 重试通知回调（与主文件同一套语义）。
     * @return 成功时携带已就绪的伴随文件列表；失败携带错误码。
     */
    suspend fun downloadAdditionalFiles(
        entry: ModelEntry,
        targetDir: File,
        onRetry: (DownloadRetryInfo) -> Unit = {},
    ): EchoResult<List<File>> = withContext(ioDispatcher) {
        val ready = mutableListOf<File>()
        for (name in entry.additionalFiles) {
            val targetFile = File(targetDir, name)
            if (targetFile.isFile && targetFile.length() > 0L) {
                ready.add(targetFile)
                continue
            }
            val url = deriveSiblingUrl(entry, name)
                ?: run {
                    EchoLog.e(
                        TAG,
                        "伴随文件下载失败 kind=${entry.kind} 文件=$name " +
                            "原因=无法推导下载地址（主 URL 不含主文件名）",
                    )
                    return@withContext EchoResult.failure(
                        Constants.ERROR_MODEL_DOWNLOAD,
                        "无法推导伴随文件「$name」的下载地址（主 URL 不含主文件名）",
                    )
                }
            val partFile = File(targetDir, "$name.part")
            val outcome = downloadStreamWithRetry(
                url = url,
                // 伴随文件没有独立体积门槛（清单里只有主文件的真实字节数），
                // 期望字节未知 ⇒ 传 0 表示不算进度百分比
                expectedSizeBytes = 0L,
                partFile = partFile,
                onProgress = { },
                onRetry = onRetry,
            )
            val failure = outcome.failure
            if (failure != null) {
                EchoLog.e(
                    TAG,
                    "伴随文件下载失败 kind=${entry.kind} 文件=$name " +
                        "code=${failure.code} 原因=${failure.message}",
                )
                return@withContext EchoResult.failure(
                    Constants.ERROR_MODEL_DOWNLOAD,
                    "伴随文件「$name」下载失败：${failure.message}",
                    failure.cause,
                )
            }
            if (outcome.bytes <= 0L || !partFile.renameTo(targetFile)) {
                runCatching { partFile.delete() }
                EchoLog.e(
                    TAG,
                    "伴随文件下载失败 kind=${entry.kind} 文件=$name " +
                        "原因=落盘字节=${outcome.bytes} 或转正失败（已删除 .part）",
                )
                return@withContext EchoResult.failure(
                    Constants.ERROR_MODEL_DOWNLOAD,
                    "伴随文件「$name」下载失败",
                )
            }
            EchoLog.i(
                TAG,
                "伴随文件下载完成 kind=${entry.kind} 文件=$name 落盘字节=${outcome.bytes}",
            )
            ready.add(targetFile)
        }
        EchoResult.success(ready)
    }

    /** 同目录约定：主 URL 中最后一次出现的主文件名替换为伴随文件名。 */
    private fun deriveSiblingUrl(entry: ModelEntry, siblingName: String): String? {
        val index = entry.url.lastIndexOf(entry.fileName)
        if (index < 0) return null
        return entry.url.substring(0, index) + siblingName
    }

    /**
     * 流式下载（含断点续传），返回本次落盘字节总数。
     *
     * **影子变量 bug 修复（会让下载永远失败）**：此前本方法在 `use {}` 块**内**
     * 声明了 `var totalBytes`，而 `return totalBytes` 在块**外**——Kotlin 的块内
     * 局部变量会**遮蔽**外层同名变量，于是方法返回的其实是「下载**前**已有的字节数」
     * （首次下载恒为 0），而不是实际落盘字节。
     * 后果链：上层拿到 0 → 判定「文件过小」→ **删掉刚下好的 .part** → 下载
     * 永远无法成功，且用户看到的失败原因是**假的**（"可能为错误页"）。
     * 现在 `totalBytes` 只声明一次（块外），块内只赋值，返回值即真实落盘字节。
     *
     * **续传判定依赖 `response.code == 206`**：服务端按 `Range` 返回 206 才追加；
     * 若忽略 `Range` 返回 200，则必须**截断重写**（否则旧尾部残留，文件长度虚高、
     * 通过体积门槛但内容是错的）。
     */
    private fun streamDownload(
        url: String,
        expectedSizeBytes: Long,
        partFile: File,
        onProgress: (Int) -> Unit,
    ): Long {
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val requestBuilder = Request.Builder().url(url).get()
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        EchoLog.i(
            TAG,
            "发起请求 url=$url 断点续传=${existingBytes > 0L} 已存在字节=$existingBytes " +
                "期望字节=$expectedSizeBytes 目标文件=${partFile.name}",
        )
        // 声明在 use 块外：块内同名局部变量会遮蔽它（见上方 KDoc 的 bug 说明）
        var totalBytes = 0L
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            EchoLog.i(
                TAG,
                "收到响应 url=$url HTTP=${response.code} Content-Length=${response.header("Content-Length")} " +
                    "已存在字节=$existingBytes",
            )
            if (!response.isSuccessful) {
                EchoLog.e(TAG, "响应非成功状态 url=$url HTTP=${response.code} ${response.message}")
                // 抛**携带状态码**的异常：让 [isRecoverable] 能按类型 + 状态码判定，
                // 而不是去正则匹配 message 文本（那会把 429 误判成不可恢复）
                throw HttpStatusException(response.code)
            }
            val body = response.body ?: throw IOException("响应体为空")
            val append = response.code == PARTIAL_CONTENT && existingBytes > 0L
            if (existingBytes > 0L && !append) {
                // 服务端忽略 Range 返回 200：必须从头写，否则会与已存在字节双计
                EchoLog.w(
                    TAG,
                    "服务端未按 Range 续传（HTTP=${response.code}，请求 bytes=$existingBytes-）→ 从头写入",
                )
            }
            // BUG-12 修复：服务端忽略 Range 返回 200 时从头写（append=false），
            // totalBytes 必须从 0 起算——否则与 existingBytes 双计，进度百分比虚高
            totalBytes = if (append) existingBytes else 0L
            RandomAccessFile(partFile, "rw").use { raf ->
                if (!append) {
                    // 从头写：先截断，否则旧文件尾部会残留上一轮的字节，
                    // 导致文件长度虚高、通过体积门槛但内容是错的
                    raf.setLength(0L)
                }
                raf.seek(if (append) existingBytes else 0L)
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        totalBytes += read.toLong()
                        if (expectedSizeBytes > 0L) {
                            onProgress(((totalBytes * 100L) / expectedSizeBytes).toInt().coerceIn(0, 99))
                        }
                    }
                }
            }
        }
        EchoLog.i(
            TAG,
            "写入完成 url=$url 落盘总字节=$totalBytes 本次新增=${totalBytes - existingBytes} " +
                "目标文件=${partFile.absolutePath}",
        )
        return totalBytes
    }

    /**
     * 服务端返回非成功状态码（携带真实状态码，供 [isRecoverable] 判定可恢复性）。
     *
     * 不用「把状态码拼进 message 再用正则匹配」的方式：那既脆弱又难维护
     * （曾经把 429 误判成不可恢复）。
     */
    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /**
     * 一次流式下载尝试的结果。
     *
     * @param bytes 已落盘字节数（失败时为 `.part` 当前大小）。
     * @param failure 非空表示本次尝试失败；`.part` 的处置已由可恢复性判定决定。
     */
    private data class StreamOutcome(
        val bytes: Long,
        val failure: EchoResult.Failure?,
    )

    /**
     * 一次「即将重试」的快照（供上层提示用户，别让用户对着不动的进度条干等）。
     *
     * @param attempt 刚刚失败的尝试序号（从 1 开始；1 = 第一次尝试失败）。
     * @param maxAttempts 总尝试次数上限（含首次）；例如 3 = 首发 1 次 + 重试 2 次。
     * @param downloadedBytes 失败瞬间**已落盘**的字节数 —— 下一次重试的**续传起点**
     *   （请求头 `Range: bytes=<downloadedBytes>-`）。重试时**保留 `.part`** 并从此处
     *   续传，**不从头再来**。
     * @param reason 本次失败的**真实原因**（如「HTTP 503」「Software caused connection
     *   abort」），不做归一化、不用占位符。
     *
     * 触发时机：**仅在确实还会再试一次时**才回调（即 `attempt < maxAttempts`）。
     * 最后一次失败不再回调——那时该提示的是「最终失败原因」而不是「正在重试」。
     */
    data class DownloadRetryInfo(
        val attempt: Int,
        val maxAttempts: Int,
        val downloadedBytes: Long,
        val reason: String,
    )

    companion object {
        private const val TAG = "ModelDownloader"
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val PARTIAL_CONTENT = 206

        /** 主文件下载的最大尝试次数（含首次）：1 次首发 + 2 次重试。 */
        private const val MAX_DOWNLOAD_ATTEMPTS = 3

        /** 重试退避基数（毫秒）；第 n 次重试等待 base × 2^(n-1)。 */
        private const val RETRY_BACKOFF_BASE_MS = 1_000L

        /** HTTP 429（Too Many Requests）：限流，可恢复。 */
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** HTTP 408（Request Timeout）：超时，可恢复。 */
        private const val HTTP_REQUEST_TIMEOUT = 408
    }
}
