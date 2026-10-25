package com.echolog.app.ui.transcript

/**
 * 转写全文页的提示文案组装（**纯函数**，无 Android 依赖，可直接单测）。
 *
 * 与 [TranscriptCopyFormatter] 的分工：那个管「复制内容的格式」，
 * 这个管「给用户看的反馈文案」的兜底组装。
 */
object TranscriptFeedbackText {

    /**
     * 「重新生成总结失败」的提示文案（三级回落，见下方说明）。
     *
     * ## 为什么需要兜底（静默失败红线）
     *
     * UI 层模板 `transcript_regenerate_failed`（「重新生成总结失败：%1$s」）
     * 负责拼前缀，领域层（`RegenerateSummaryUseCase`）只提供**原因**——
     * 这是工程师确立的文案边界：前缀归 UI，原因归领域层。
     *
     * 但原因可能为 null/空白（领域层未给、透传了空消息、或资源求值异常），
     * 此时直接 `String.format(template, "")` 会给用户「重新生成总结失败：」
     * 这样**光秃秃的半截文案**——用户看不到任何失败原因，正是静默失败红线
     * 禁止的。因此按三级回落，保证任何情况下都有一个完整的句子：
     *
     * 1. 原因非空白 → 模板拼接（「重新生成总结失败：xxx」）；
     * 2. 拼接结果为空白（如模板资源求值失败拿到空串）→ 回落原因本体；
     * 3. 原因也空白 → 回落通用失败文案（由调用方从 strings.xml 注入）。
     *
     * @param template 带 `%1$s` 占位符的失败文案模板（`transcript_regenerate_failed`）。
     * @param noReasonText 通用失败文案（`transcript_regenerate_failed_no_reason`）。
     * @param reason 领域层给出的失败原因（可空 / 可空白）。
     * @return 完整的失败提示文案（不会以孤立冒号结尾）。
     */
    fun summaryFailedText(template: String, noReasonText: String, reason: String?): String {
        val safeReason = reason?.takeIf { it.isNotBlank() }
        val formatted = if (safeReason != null) String.format(template, safeReason) else ""
        return formatted.ifBlank { safeReason ?: noReasonText }
    }
}
