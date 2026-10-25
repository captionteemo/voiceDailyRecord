package com.echolog.app.ui.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [TranscriptFeedbackText] 兜底文案组装回归测试（QA：S-1 静默失败红线）。
 *
 * 锁定「重新生成总结失败」提示的三级回落：
 * 1. 原因非空白 → 模板拼接；
 * 2. 拼接结果为空白（模板资源求值失败）→ 回落原因本体；
 * 3. 原因也空白 → 回落通用失败文案。
 *
 * 核心护栏：**任何入参组合都不得产出半截文案**（如「重新生成总结失败：」）。
 */
class TranscriptFeedbackTextTest {

    /** 与 strings.xml `transcript_regenerate_failed` 一致。 */
    private val template = "重新生成总结失败：%1\$s"

    /** 与 strings.xml `transcript_regenerate_failed_no_reason` 一致。 */
    private val noReasonText = "重新生成总结失败"

    @Test
    fun `原因非空白时按模板拼接完整句子`() {
        val text = TranscriptFeedbackText.summaryFailedText(
            template = template,
            noReasonText = noReasonText,
            reason = "模型推理失败",
        )

        assertEquals("重新生成总结失败：模型推理失败", text)
    }

    @Test
    fun `原因为 null 时回落通用失败文案`() {
        val text = TranscriptFeedbackText.summaryFailedText(
            template = template,
            noReasonText = noReasonText,
            reason = null,
        )

        assertEquals(noReasonText, text)
    }

    @Test
    fun `原因为空白字符串时回落通用失败文案`() {
        val text = TranscriptFeedbackText.summaryFailedText(
            template = template,
            noReasonText = noReasonText,
            reason = "   ",
        )

        assertEquals(noReasonText, text)
    }

    @Test
    fun `兜底文案不得是半截句子`() {
        // 红线护栏：null / 空白原因下不得出现「…失败：」这样光秃秃的文案
        listOf(null, "", "   ", "\t\n").forEach { reason ->
            val text = TranscriptFeedbackText.summaryFailedText(
                template = template,
                noReasonText = noReasonText,
                reason = reason,
            )
            assertFalse("reason=$reason 不得产出半截文案：$text", text.endsWith("："))
            assertFalse("reason=$reason 不得产出空白文案", text.isBlank())
        }
    }

    @Test
    fun `模板求值失败为空串时回落原因本体`() {
        // 资源异常拿到空模板：至少要把领域层原因给用户看
        val text = TranscriptFeedbackText.summaryFailedText(
            template = "",
            noReasonText = noReasonText,
            reason = "模型推理失败",
        )

        assertEquals("模型推理失败", text)
    }

    @Test
    fun `模板为空且原因为空白时回落通用失败文案`() {
        val text = TranscriptFeedbackText.summaryFailedText(
            template = "",
            noReasonText = noReasonText,
            reason = null,
        )

        assertEquals(noReasonText, text)
    }
}
