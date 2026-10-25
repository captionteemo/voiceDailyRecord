package com.echolog.app.domain.model

/**
 * 音频片段的转写状态（架构 3.1，落库于 `audio_segment.transcript_status`）。
 */
enum class TranscriptStatus {
    /** 待转写（新建片段默认态；兜底 Worker 扫描此态与 FAILED）。 */
    PENDING,

    /** 转写中。 */
    RUNNING,

    /** 转写完成。 */
    DONE,

    /** 转写失败（携带 `transcript_error`，可由充电兜底 Worker 重试）。 */
    FAILED,
}
