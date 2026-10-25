package com.echolog.app.data.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.echolog.app.core.util.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户自选存储目录管理（用户需求 3：录音存储位置）。
 *
 * 语义：
 * - 默认（未选择）：录音/转写全部写在 App 私有目录 `{filesDir}/audio/`；
 * - 自选目录（SAF tree Uri，已持久化授权）：片段 WAV、转写 .md、全天合并
 *   WAV 在写盘后**复制一份**到自选目录（应用内部路径仍是数据源，供
 *   合并/转写/播放使用，避免 SAF 流无法随机回写 WAV 头）。
 *
 * 复制失败（U 盘拔出/授权失效/空间不足）不影响录音主链路：内部文件始终存在，
 * 仅用户目录缺一份，下次录音会再次尝试。
 */
@Singleton
class StorageLocationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 用户目录是否可用（已选且授权有效）。 */
    fun isCustomLocationActive(treeUri: String?): Boolean =
        resolveTree(treeUri) != null

    /** 解析自选目录的展示名（文件夹名；不可用时返回 null）。 */
    fun displayNameOf(treeUri: String?): String? {
        val uri = parseTreeUri(treeUri) ?: return null
        return runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
    }

    /**
     * 将内部文件复制到自选目录（同名覆盖已存在文件）。
     *
     * @return true = 已复制或无需复制（未选自选目录）；false = 复制失败。
     */
    fun copyToUserDir(source: File, treeUri: String?, mimeType: String): Boolean {
        val tree = resolveTree(treeUri) ?: return true // 未选自选目录：无操作即成功
        if (!source.isFile) return true // 文件不存在视为无需复制（调用方已处理）
        return runCatching {
            val existing = tree.findFile(source.name)
            val target = existing?.takeIf { it.canWrite() }
                ?: tree.createFile(mimeType, source.name)
                ?: throw FileNotFoundException("无法在自选目录创建文件: ${source.name}")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw FileNotFoundException("无法打开自选目录输出流: ${source.name}")
            true
        }.getOrElse { false }
    }

    /** 将文本写入自选目录（转写 .md；同名覆盖）。内部目录由调用方自行写盘。 */
    fun writeTextToUserDir(fileName: String, content: String, treeUri: String?, mimeType: String): Boolean {
        val tree = resolveTree(treeUri) ?: return true
        return runCatching {
            val existing = tree.findFile(fileName)
            val target = existing?.takeIf { it.canWrite() }
                ?: tree.createFile(mimeType, fileName)
                ?: throw FileNotFoundException("无法在自选目录创建文件: $fileName")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw FileNotFoundException("无法打开自选目录输出流: $fileName")
            true
        }.getOrElse { false }
    }

    /** 默认（应用内部）音频目录——「打开文件夹」未选自选目录时的目标。 */
    fun defaultAudioDir(): File = FileUtils.audioDir(context)

    /** 解析 SAF tree Uri 字符串为 DocumentFile（授权失效时返回 null）。 */
    private fun resolveTree(treeUri: String?): DocumentFile? {
        val uri = parseTreeUri(treeUri) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?.takeIf { it.isDirectory && it.canWrite() }
    }

    private fun parseTreeUri(treeUri: String?): Uri? {
        if (treeUri.isNullOrBlank()) return null
        return runCatching { Uri.parse(treeUri) }.getOrNull()
    }

    companion object {
        /** WAV MIME（SAF createFile 用）。 */
        const val MIME_AUDIO_WAV = "audio/wav"

        /** Markdown MIME。 */
        const val MIME_MARKDOWN = "text/markdown"
    }
}
