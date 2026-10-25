package com.echolog.app.ui.transcript

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment
import java.io.File

/**
 * 播放源类型（转写全文页播放定位）。
 *
 * - [MERGED_AUDIO]：当天合并音频（`RecordingDay.mergedAudioPath`）；
 * - [SEGMENT_AUDIO]：单条录音片段 WAV（`AudioSegment.filePath`）。
 */
enum class TranscriptPlaybackSource {
    /** 当天合并音频。 */
    MERGED_AUDIO,

    /** 单个片段 WAV。 */
    SEGMENT_AUDIO,
}

/**
 * 播放目标：文件路径 + **文件内偏移**（毫秒）。
 *
 * 偏移语义与 [com.echolog.app.data.media.AudioPlayer.play] 一致：
 * `startMs` 是相对于该文件起点的毫秒数，不是全天绝对时间。
 */
data class TranscriptPlaybackTarget(
    val path: String,
    val offsetMs: Long,
    val source: TranscriptPlaybackSource,
)

/**
 * 转写全文页的播放定位器（**纯函数**，无 Android 依赖，可直接单测）。
 *
 * ## 为什么不能直接用「全天绝对时间」跳转
 *
 * [com.echolog.app.data.media.AudioMerger] 是 **PCM 纯直拼**，合并文件里
 * **不插入任何静音间隙**——各片段音频首尾相接。因此：
 *
 * - **合并音频内的时间轴** = 各片段音频时长**累加**；
 * - **转写分段的 `startMs`** = 全天**绝对时间**（含片段之间的空闲间隔）。
 *
 * 例：片段 A 占 10:00–10:05（5 分钟），片段 B 占 14:00–14:03（3 分钟）。
 * B 的音频在合并文件里位于**第 5 分钟处**，但 B 的 `startMs` 是 14:00。
 * 直接拿绝对时间 seek 会跳到第 14 分钟处（错误位置）。
 *
 * ## 换算公式
 *
 * ```
 * withinSegmentOffset = transcript.startMs - segment.startMs        // 句在片段内的偏移
 * mergedOffset        = sum(该片段之前所有片段的 durationMs) + withinSegmentOffset
 * ```
 *
 * 片段列表按 `startMs` 升序；目标片段索引 i 之前的片段时长之和即前缀时长。
 *
 * ## 已知精度边界
 *
 * 合并时若某些片段被跳过（文件损坏 / 采样率不一致，见
 * [com.echolog.app.data.media.AudioMerger]），前缀时长会大于合并文件中的实际
 * 前缀长度，定位可能**偏后**。此为已知取舍：领域层无法获知合并时被跳过的片段，
 * 精确对齐需要合并器额外落库「片段 → 合并文件内偏移」映射。
 */
object TranscriptPlaybackLocator {

    /**
     * 句在所属片段内的偏移（毫秒）；负数（数据异常）按 0 处理。
     *
     * @param transcript 目标转写分段。
     * @param segment 目标转写分段所属的音频片段。
     */
    fun withinSegmentOffsetMs(
        transcript: TranscriptSegment,
        segment: AudioSegment,
    ): Long = (transcript.startMs - segment.startMs).coerceAtLeast(0L)

    /**
     * 目标转写分段在**合并音频内**的偏移（毫秒）。
     *
     * = 目标片段之前所有片段的时长之和 + 句在片段内的偏移。
     *
     * 边界处理（不抛异常）：
     * - 片段列表为空 → 返回 `0`；
     * - 找不到转写分段所属的片段 → 返回 `0`（安全回退，无法定位）。
     *
     * @param transcript 目标转写分段。
     * @param segments 当天片段（**内部会按 startMs 升序排序**，不依赖入参顺序）。
     */
    fun mergedOffsetMs(
        transcript: TranscriptSegment,
        segments: List<AudioSegment>,
    ): Long {
        if (segments.isEmpty()) return 0L
        val ordered = segments.sortedBy { it.startMs }
        val index = ordered.indexOfFirst { it.segmentId == transcript.segmentId }
        if (index < 0) return 0L
        val prefixDurationMs = ordered.take(index).sumOf { it.durationMs.coerceAtLeast(0L) }
        return prefixDurationMs + withinSegmentOffsetMs(transcript, ordered[index])
    }

    /**
     * 查找目标转写分段所属的音频片段（按 `startMs` 升序匹配）。
     *
     * @param transcript 目标转写分段。
     * @param segments 当天片段（内部按 `startMs` 升序排序）。
     * @return 所属片段；找不到返回 null。
     */
    fun locateSegment(
        transcript: TranscriptSegment,
        segments: List<AudioSegment>,
    ): AudioSegment? = segments.sortedBy { it.startMs }
        .firstOrNull { it.segmentId == transcript.segmentId }

    /**
     * 判定合并音频文件是否真实存在（**唯一做文件系统探测的入口**）。
     *
     * 单独拆出来是为了让 [resolveTarget] 保持纯函数（可单测）：
     * 文件存在性由调用方在运行时判定后传入。
     *
     * @param mergedAudioPath 合并音频路径（可空 / 空白）。
     * @return 路径非空且确实是文件时返回 true。
     */
    fun mergedAudioFileExists(mergedAudioPath: String?): Boolean {
        val path = mergedAudioPath?.takeIf { it.isNotBlank() } ?: return false
        return runCatching { File(path).isFile }.getOrDefault(false)
    }

    /**
     * 选定播放源与文件内偏移（**纯函数**）。
     *
     * 优先级（产品决策）：
     * 1. 合并音频路径非空**且文件真实存在** → 合并音频 + [mergedOffsetMs]；
     * 2. 否则回退该句所属片段的 WAV + [withinSegmentOffsetMs]。
     *
     * @param transcript 目标转写分段。
     * @param segments 当天片段（内部按 `startMs` 升序排序）。
     * @param mergedAudioPath 当天合并音频路径（可空）。
     * @param mergedAudioExists 合并音频文件是否真实存在（由 [mergedAudioFileExists] 判定）。
     * @return 播放目标；无法定位（如所属片段缺失）时返回 `null`。
     */
    fun resolveTarget(
        transcript: TranscriptSegment,
        segments: List<AudioSegment>,
        mergedAudioPath: String?,
        mergedAudioExists: Boolean,
    ): TranscriptPlaybackTarget? {
        val mergedPath = mergedAudioPath?.takeIf { it.isNotBlank() }
        if (mergedPath != null && mergedAudioExists) {
            return TranscriptPlaybackTarget(
                path = mergedPath,
                offsetMs = mergedOffsetMs(transcript, segments),
                source = TranscriptPlaybackSource.MERGED_AUDIO,
            )
        }
        val segment = locateSegment(transcript, segments) ?: return null
        return TranscriptPlaybackTarget(
            path = segment.filePath,
            offsetMs = withinSegmentOffsetMs(transcript, segment),
            source = TranscriptPlaybackSource.SEGMENT_AUDIO,
        )
    }
}
