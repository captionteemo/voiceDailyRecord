package com.echolog.app.domain.model

/**
 * 领域模型：音频片段（架构 3.1/1.6）。
 *
 * 一个 VAD 突变语音片段对应一个 WAV 文件；
 * `startMs/endMs` 为 epoch millis，`durationMs = endMs - startMs`。
 */
data class AudioSegment(
    val segmentId: Long = 0L,
    val dayId: String,
    val filePath: String,
    val startMs: Long,
    val endMs: Long = 0L,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val transcriptStatus: TranscriptStatus = TranscriptStatus.PENDING,
    val transcriptError: String? = null,
)
