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
 */
@Singleton
class ModelDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 下载单个模型条目到目标目录。
     *
     * @param entry 模型条目。
     * @param targetDir 目标目录（`models/asr/` 或 `models/llm/`）。
     * @param onProgress 进度回调（0~100，按 [ModelEntry.sizeBytes] 估算）。
     * @param onRetry 可恢复失败后的**重试通知**回调（供上层提示「网络不稳定，正在重试」）。
     * @return 成功时携带已下载的主文件；失败携带错误码。
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
                val mainOutcome = downloadMainFileWithRetry(entry, partFile, onProgress, onRetry)
                val downloadedBytes = mainOutcome.bytes
                val mainFailure = mainOutcome.failure
                if (mainFailure != null) {
                    // 重试耗尽仍失败：`.part` 已保留（可恢复失败），下次继续续传
                    return@withContext EchoResult.failure(
                        mainFailure.code,
                        mainFailure.message.ifBlank { "下载失败" },
                        mainFailure.cause,
                    )
                }
                EchoLog.i(
                    TAG,
                    "主文件下载结束 kind=${entry.kind} 落盘字节=$downloadedBytes " +
                        "门槛=${entry.minSizeBytes} 是否达标=${downloadedBytes >= entry.minSizeBytes}",
                )
                if (downloadedBytes < entry.minSizeBytes) {
                    // 体积过小 = 服务端返回了错误页/HTML 而非二进制内容。
                    // 这属于**不可恢复**失败：续传无意义（续下去还是同样的错误页），
                    // 故删除 .part，避免下次继续在错误的字节上续。
                    partFile.delete()
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
                        partFile.delete()
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
                    partFile.delete()
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
                val extrasResult = downloadAdditionalFiles(entry, targetDir)
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
            // 兜底 catch：这里的异常来自「主文件已就绪跳过」之外的路径
            // （伴随文件下载、建目录等）。主文件的可恢复失败已在
            // downloadMainFileWithRetry 内部处理完毕（保留 .part 并重试），
            // 因此这里只删除 .part —— 主文件此时要么已转正（.part 已不存在，
            // delete 是 no-op），要么失败原因已在上层判定。
            runCatching { partFile.delete() }
            val message = "下载失败：${throwable.message ?: "网络错误"}"
            EchoLog.e(
                TAG,
                "下载失败 kind=${entry.kind} code=${Constants.ERROR_MODEL_DOWNLOAD} 原因=$message",
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
     * 下载主文件，失败时按指数退避重试，**每次从 `.part` 已有字节续传**。
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
     * ## `.part` 的处置
     *
     * - **可恢复失败**（连接中断/超时/5xx）：**保留 `.part`**，下次 Range 续传；
     * - **不可恢复失败**（HTTP 4xx、SHA-256 不符、体积异常）：删除 `.part`。
     *
     * 判据见 [isRecoverable]。
     */
    private suspend fun downloadMainFileWithRetry(
        entry: ModelEntry,
        partFile: File,
        onProgress: (Int) -> Unit,
        onRetry: (DownloadRetryInfo) -> Unit,
    ): MainDownloadOutcome {
        var lastFailure: EchoResult.Failure? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                val bytes = streamDownload(entry.url, entry.sizeBytes, partFile, onProgress)
                return MainDownloadOutcome(bytes = bytes, failure = null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val recoverable = isRecoverable(throwable)
                val message = throwable.message ?: "网络错误"
                EchoLog.w(
                    TAG,
                    "主文件下载中断 kind=${entry.kind} 第 $attempt/$MAX_DOWNLOAD_ATTEMPTS 次 " +
                        "已落盘=${partFile.length()} 可恢复=$recoverable 原因=$message",
                    throwable,
                )
                if (!recoverable) {
                    // 不可恢复：删掉 .part（续传只会续到同样的错误内容）
                    runCatching { partFile.delete() }
                    return MainDownloadOutcome(
                        bytes = 0L,
                        failure = EchoResult.Failure(
                            code = Constants.ERROR_MODEL_DOWNLOAD,
                            message = "下载失败：$message",
                            cause = throwable,
                        ),
                    )
                }
                lastFailure = EchoResult.Failure(
                    code = Constants.ERROR_MODEL_DOWNLOAD,
                    message = "下载失败：$message",
                    cause = throwable,
                )
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    // 指数退避：1s / 2s（最多 3 次尝试）
                    val backoffMs = RETRY_BACKOFF_BASE_MS * (1L shl (attempt - 1))
                    EchoLog.i(TAG, "第 $attempt 次失败，退避 ${backoffMs}ms 后重试（保留 .part 续传）")
                    // 通知上层「正在重试」，让 UI 提示用户而非干等
                    runCatching {
                        onRetry(
                            DownloadRetryInfo(
                                attempt = attempt,
                                maxAttempts = MAX_DOWNLOAD_ATTEMPTS,
                                downloadedBytes = partFile.length(),
                                reason = message,
                            ),
                        )
                    }.onFailure { throwable ->
                        // 通知失败不得影响下载主链路
                        EchoLog.w(TAG, "重试通知回调异常（不影响下载）", throwable)
                    }
                    delay(backoffMs)
                }
            }
        }
        EchoLog.e(
            TAG,
            "主文件下载重试耗尽 kind=${entry.kind} 已落盘=${partFile.length()} " +
                "（保留 .part，下次继续续传）",
        )
        return MainDownloadOutcome(
            bytes = partFile.length(),
            failure = lastFailure ?: EchoResult.Failure(
                code = Constants.ERROR_MODEL_DOWNLOAD,
                message = "下载失败：重试 $MAX_DOWNLOAD_ATTEMPTS 次后仍失败",
            ),
        )
    }

    /**
     * 判断失败是否**可恢复**（值得保留 `.part` 并重试）。
     *
     * 可恢复：连接中断、读超时、5xx、429 —— 都是「换个时间/换个连接可能成功」。
     * 不可恢复：HTTP 4xx（除 429）、响应体为空 —— 重试只会得到同样的结果。
     */
    private fun isRecoverable(throwable: Throwable): Boolean {
        val message = throwable.message.orEmpty()
        // 4xx（除 429）视为不可恢复
        val clientError = Regex("HTTP 4(\\d{2})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        if (clientError != null && clientError != 429) return false
        if (message.contains("HTTP 4") && !message.contains("HTTP 429")) return false
        if (message.contains("响应体为空")) return false
        // 其余（SocketException / SocketTimeoutException / 5xx / 未知 IO 异常）均可恢复
        return true
    }

    /**
     * 主文件下载结果。[bytes] 为已落盘字节数（失败时为 `.part` 当前大小）。
     *
     * @param failure 非空表示最终失败；此时 `.part` 的处置已由 [downloadMainFileWithRetry] 决定。
     */
    private data class MainDownloadOutcome(
        val bytes: Long,
        val failure: EchoResult.Failure?,
    )

    /**
     * 可恢复失败后的**重试通知**（供上层提示用户「网络不稳定，正在重试」）。
     *
     * @param attempt 本次是第几次尝试（从 1 开始）。
     * @param maxAttempts 总尝试次数上限。
     * @param downloadedBytes 已落盘字节数（重试时会从该处续传）。
     * @param reason 本次失败的真实原因。
     */
    data class DownloadRetryInfo(
        val attempt: Int,
        val maxAttempts: Int,
        val downloadedBytes: Long,
        val reason: String,
    )

    /**
     * 下载伴随文件（如 ASR 的 `tokens.txt`，FR-012）。
     *
     * [ModelEntry] 仅持有主文件 URL，伴随文件按「同目录约定」推导地址：
     * 将主 URL 中主文件名替换为伴随文件名（内置清单的镜像布局满足该约定；
     * 远端 manifest 若采用异目录布局，需在条目中扩展 URL 字段）。
     *
     * @return 成功时携带已就绪的伴随文件列表；失败携带错误码。
     */
    suspend fun downloadAdditionalFiles(
        entry: ModelEntry,
        targetDir: File,
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
            try {
                FileUtils.ensureDirectory(targetDir)
                val partFile = File(targetDir, "$name.part")
                val downloadedBytes = streamDownload(url, entry.sizeBytes, partFile) { }
                EchoLog.i(
                    TAG,
                    "伴随文件下载结束 kind=${entry.kind} 文件=$name 落盘字节=$downloadedBytes",
                )
                if (downloadedBytes <= 0L || !partFile.renameTo(targetFile)) {
                    partFile.delete()
                    EchoLog.e(
                        TAG,
                        "伴随文件下载失败 kind=${entry.kind} 文件=$name " +
                            "原因=落盘字节=$downloadedBytes 或转正失败（已删除 .part）",
                    )
                    return@withContext EchoResult.failure(
                        Constants.ERROR_MODEL_DOWNLOAD,
                        "伴随文件「$name」下载失败",
                    )
                }
                ready.add(targetFile)
            } catch (cancellation: CancellationException) {
                runCatching { File(targetDir, "$name.part").delete() }
                EchoLog.w(TAG, "伴随文件下载被取消 kind=${entry.kind} 文件=$name")
                throw cancellation
            } catch (throwable: Throwable) {
                runCatching { File(targetDir, "$name.part").delete() }
                val message = "伴随文件「$name」下载失败：${throwable.message ?: "网络错误"}"
                EchoLog.e(
                    TAG,
                    "伴随文件下载失败 kind=${entry.kind} 文件=$name " +
                        "code=${Constants.ERROR_MODEL_DOWNLOAD} 原因=$message",
                    throwable,
                )
                return@withContext EchoResult.failure(
                    Constants.ERROR_MODEL_DOWNLOAD,
                    message,
                    throwable,
                )
            }
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
                throw IOException("HTTP ${response.code}")
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

    companion object {
        private const val TAG = "ModelDownloader"
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val PARTIAL_CONTENT = 206

        /** 主文件下载的最大尝试次数（含首次）。 */
        private const val MAX_DOWNLOAD_ATTEMPTS = 3

        /** 重试退避基数（毫秒）；第 n 次重试等待 base × 2^(n-1)。 */
        private const val RETRY_BACKOFF_BASE_MS = 1_000L
    }
}
