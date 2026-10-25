package com.echolog.app.core.util

/**
 * 录音文件命名规范（用户需求 1）。
 *
 * 每个 VAD 语音片段（一次连续发声到静音断段）按「生成日期 + 当天第几次」命名：
 * - 录音：`{dayId}_第{N}次.wav`（如 `2026-10-25_第3次.wav`）
 * - 转写：`{dayId}_第{N}次.md`（与录音同名，仅扩展名不同）
 * - 全天合并：`{dayId}_全天合并.wav`
 *
 * dayId 即本地时区日期 `yyyy-MM-dd`（架构 2.1 唯一时间口径），
 * N 为当天片段序号（1 起，按 DB 内片段数推导，跨服务重启保持连续）。
 */
object RecordingNamer {

    private val ORDINAL_PATTERN = Regex("第(\\d+)次")

    /** 片段基名（不含扩展名）：`2026-10-25_第3次`。 */
    fun segmentBaseName(dayId: String, ordinal: Int): String =
        "${dayId}_第${ordinal.coerceAtLeast(1)}次"

    /** 片段 WAV 文件名：`2026-10-25_第3次.wav`。 */
    fun segmentFileName(dayId: String, ordinal: Int): String =
        "${segmentBaseName(dayId, ordinal)}.wav"

    /** 片段转写 Markdown 文件名：`2026-10-25_第3次.md`。 */
    fun transcriptFileName(dayId: String, ordinal: Int): String =
        "${segmentBaseName(dayId, ordinal)}.md"

    /** 全天合并文件名：`2026-10-25_全天合并.wav`。 */
    fun mergedFileName(dayId: String): String = "${dayId}_全天合并.wav"

    /**
     * 从文件名/基名解析当天序号（导出转写时与录音文件严格对齐用）。
     * 旧命名（`seg_*`）或不含序号时返回 null。
     */
    fun parseOrdinal(baseName: String): Int? =
        ORDINAL_PATTERN.find(baseName)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
