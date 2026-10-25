package com.echolog.app.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.echolog.app.core.util.FileUtils

/**
 * 「一键打开文件目录」意图构建（用户需求 1）。
 *
 * 两种目标：
 * - 自选目录（SAF tree Uri）：`ACTION_VIEW` + 目录 MIME（持有持久授权，
 *   主流文件管理器/DocumentsUI 可直接定位到该文件夹）；
 * - 默认目录（App 私有 `{filesDir}/audio/`）：经 [FileProvider] 暴露 content Uri，
 *   部分 OEM 文件管理器支持按目录 MIME 打开；
 *
 * 两者都失败时调用方降级为 `ACTION_OPEN_DOCUMENT_TREE` 选择器（见
 * [openAudioFolder] 的 try/catch 约定），保证按钮永远有反馈。
 */
object FolderLauncher {

    /** 目录 MIME（DocumentsContract 规范值）。 */
    private const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR

    /** FileProvider authority（与 Manifest 声明一致：`{applicationId}.fileprovider`）。 */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * 构建打开录音目录的 Intent。
     *
     * @param storageTreeUri 用户自选目录的 SAF tree Uri 字符串（null = 默认内部目录）。
     */
    fun audioFolderIntent(context: Context, storageTreeUri: String?): Intent {
        val uri: Uri = if (!storageTreeUri.isNullOrBlank()) {
            Uri.parse(storageTreeUri)
        } else {
            FileProvider.getUriForFile(context, authority(context), FileUtils.audioDir(context))
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 降级路径：打开系统目录选择器（始终可用）。 */
    fun fallbackDocumentTreeIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
}
