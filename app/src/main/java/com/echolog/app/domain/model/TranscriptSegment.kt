package com.echolog.app.domain.model

/**
 * 领域模型：转写分段（架构 3.1，FR-009）。
 *
 * `startMs/endMs` 已叠加所属片段的起始偏移，即全天绝对时间（epoch millis）；
 * 一行文字对应 ASR 输出的一个带时间戳分段。
 */
data class TranscriptSegment(
    val tsId: Long = 0L,
    val segmentId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
