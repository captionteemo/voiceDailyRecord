package com.echolog.app.core.util

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 文件系统工具（架构 2.1/7.2）。
 *
 * 路径规范（架构 7.2，minSdk 29 分区存储下全部位于 App 私有目录，无需存储权限）：
 * - 片段音频：`{filesDir}/audio/{dayId}_第{N}次.wav`（用户需求 1：日期 + 当天序号）
 * - 片段转写：`{filesDir}/audio/{dayId}_第{N}次.md`
 * - 合并音频：`{filesDir}/audio/{dayId}_全天合并.wav`
 * - 模型目录：`{filesDir}/models/`（ASR：`models/asr/`，LLM：`models/llm/`）
 *
 * 用户自选存储目录（SAF）时，上述文件在写盘后会复制一份到自选目录
 * （见 `StorageLocationManager`），应用内部路径仍是数据源。
 */
object FileUtils {

    /** 音频根目录。 */
    fun audioDir(context: Context): File = File(context.filesDir, "audio")

    /** 片段文件对象（用户需求 1 命名规范：日期 + 当天第几次）。 */
    fun segmentFile(context: Context, dayId: String, ordinal: Int): File =
        File(audioDir(context), RecordingNamer.segmentFileName(dayId, ordinal))

    /** 片段文件绝对路径。 */
    fun segmentPath(context: Context, dayId: String, ordinal: Int): String =
        segmentFile(context, dayId, ordinal).absolutePath

    /** 全天合并文件对象。 */
    fun mergedDayFile(context: Context, dayId: String): File =
        File(audioDir(context), RecordingNamer.mergedFileName(dayId))

    /** 全天合并文件绝对路径。 */
    fun mergedDayPath(context: Context, dayId: String): String =
        mergedDayFile(context, dayId).absolutePath

    /** 模型根目录。 */
    fun modelsDir(context: Context): File = File(context.filesDir, "models")

    /** ASR 模型目录（SenseVoiceSmall）。 */
    fun asrModelDir(context: Context): File = File(modelsDir(context), "asr")

    /** LLM 模型目录（Qwen GGUF）。 */
    fun llmModelDir(context: Context): File = File(modelsDir(context), "llm")

    /** 确保目录存在，返回目录是否可用。 */
    fun ensureDirectory(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

    /** 删除文件；路径为空或文件不存在时视为成功。 */
    fun deleteFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return true
        val file = File(path)
        return !file.exists() || file.delete()
    }

    /** 取路径的文件名（不含父目录）；路径为空时返回空串。 */
    fun fileNameOf(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return File(path).name
    }

    /** 目录递归体积（字节）；传入文件时返回文件体积。 */
    fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** App 私有目录可用空间（字节），用于「存储不足」预检（E-1002）。 */
    fun availableStorageBytes(context: Context): Long = context.filesDir.usableSpace

    /** 体积格式化：`1.4GB` / `460MB` / `820B`。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "${bytes}B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f%s", value, units[unitIndex])
    }

    /**
     * 文件 SHA-256 十六进制串（模型完整性校验，架构决策 2）。
     * 调用方应置于 IO 线程；文件不存在时抛 [java.io.FileNotFoundException]。
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
