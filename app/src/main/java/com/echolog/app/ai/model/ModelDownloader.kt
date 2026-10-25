package com.echolog.app.ai.model

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.util.FileUtils
import kotlinx.coroutines.CoroutineDispatcher
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
     * @return 成功时携带已下载的主文件；失败携带错误码。
     */
    suspend fun download(
        entry: ModelEntry,
        targetDir: File,
        onProgress: (Int) -> Unit = {},
    ): EchoResult<File> = withContext(ioDispatcher) {
        FileUtils.ensureDirectory(targetDir)
        val targetFile = File(targetDir, entry.fileName)
        val partFile = File(targetDir, "${entry.fileName}.part")
        try {
            // 主文件已就绪（体积达标，下载时已校验过 SHA-256）则跳过重下：
            // 避免伴随文件缺失导致重试时重复下载大文件
            if (!(targetFile.isFile && targetFile.length() >= entry.minSizeBytes)) {
                val downloadedBytes = streamDownload(entry.url, entry.sizeBytes, partFile, onProgress)
                if (downloadedBytes < entry.minSizeBytes) {
                    partFile.delete()
                    return@withContext EchoResult.failure(
                        Constants.ERROR_MODEL_DOWNLOAD,
                        "下载文件过小（${FileUtils.formatBytes(downloadedBytes)}），可能为错误页",
                    )
                }
                if (entry.sha256.isNotBlank()) {
                    val actual = FileUtils.sha256Hex(partFile)
                    if (!actual.equals(entry.sha256, ignoreCase = true)) {
                        partFile.delete()
                        return@withContext EchoResult.failure(
                            Constants.ERROR_MODEL_CHECKSUM,
                            "SHA-256 校验失败",
                        )
                    }
                }
                if (!partFile.renameTo(targetFile)) {
                    partFile.delete()
                    return@withContext EchoResult.failure(Constants.ERROR_MODEL_DOWNLOAD, "临时文件转正失败")
                }
            }
            // 伴随文件（如 tokens.txt）缺失时按同目录约定下载，完成后校验存在性
            val missingExtras = entry.additionalFiles
                .filter { File(targetDir, it).let { file -> !file.isFile || file.length() == 0L } }
            if (missingExtras.isNotEmpty()) {
                val extrasResult = downloadAdditionalFiles(entry, targetDir)
                if (extrasResult.isFailure) {
                    return@withContext EchoResult.failure(
                        Constants.ERROR_MODEL_DOWNLOAD,
                        "伴随文件下载失败：${extrasResult.failureOrNull()?.message ?: "未知错误"}",
                    )
                }
            }
            val stillMissing = entry.additionalFiles
                .filter { File(targetDir, it).let { file -> !file.isFile || file.length() == 0L } }
            if (stillMissing.isNotEmpty()) {
                return@withContext EchoResult.failure(
                    Constants.ERROR_ASR_MODEL_MISSING,
                    "缺少伴随文件：${stillMissing.joinToString()}",
                )
            }
            onProgress(100)
            EchoResult.success(targetFile)
        } catch (throwable: Throwable) {
            runCatching { partFile.delete() }
            EchoResult.failure(
                Constants.ERROR_MODEL_DOWNLOAD,
                "下载失败：${throwable.message ?: "网络错误"}",
                throwable,
            )
        }
    }

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
                ?: return@withContext EchoResult.failure(
                    Constants.ERROR_MODEL_DOWNLOAD,
                    "无法推导伴随文件「$name」的下载地址（主 URL 不含主文件名）",
                )
            try {
                FileUtils.ensureDirectory(targetDir)
                val partFile = File(targetDir, "$name.part")
                val downloadedBytes = streamDownload(url, entry.sizeBytes, partFile) { }
                if (downloadedBytes <= 0L || !partFile.renameTo(targetFile)) {
                    partFile.delete()
                    return@withContext EchoResult.failure(
                        Constants.ERROR_MODEL_DOWNLOAD,
                        "伴随文件「$name」下载失败",
                    )
                }
                ready.add(targetFile)
            } catch (throwable: Throwable) {
                runCatching { File(targetDir, "$name.part").delete() }
                return@withContext EchoResult.failure(
                    Constants.ERROR_MODEL_DOWNLOAD,
                    "伴随文件「$name」下载失败：${throwable.message ?: "网络错误"}",
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

    /** 流式下载（含断点续传），返回本次落盘字节总数。 */
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
        var totalBytes = existingBytes
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("响应体为空")
            val append = response.code == PARTIAL_CONTENT && existingBytes > 0L
            // BUG-12 修复：服务端忽略 Range 返回 200 时从头写（append=false），
            // totalBytes 必须从 0 起算——否则与 existingBytes 双计，进度百分比虚高
            var totalBytes = if (append) existingBytes else 0L
            RandomAccessFile(partFile, "rw").use { raf ->
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
        return totalBytes
    }

    companion object {
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val PARTIAL_CONTENT = 206
    }
}
