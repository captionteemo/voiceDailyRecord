package com.echolog.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.echolog.app.core.util.WavIo
import com.echolog.app.data.media.AudioMerger
import com.echolog.app.data.media.AudioPlayer
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.ui.transcript.TranscriptPlaybackLocator
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机播放链路验证（QA：转写全文页「点击文字 → 跳到对应声音位置」）。
 *
 * ## 为什么需要这个测试
 *
 * JVM 单测（`TranscriptPlaybackLocatorTest`）已覆盖**偏移计算**逻辑，
 * 但「算出的偏移喂给真实 [AudioPlayer] 后，播放头是否真落在期望位置」
 * 从未在真机上验证过。真实数据里的风险是实打实的：合并文件时长
 * 1,184,685ms，而转写分段的绝对时间最大到 epoch millis 量级
 * （远超文件长度）——若有人误用绝对时间 seek，MediaPlayer 只会 clamp
 * 到文件末尾，用户点了文字却跳到一个莫名其妙的位置。
 *
 * 本测试把这条错误路径焊死：用**生产级** [AudioMerger] 拼出合并音频，
 * 再把 [TranscriptPlaybackLocator] 算出的偏移喂给真实 [AudioPlayer]，
 * 轮询 `currentPositionMs()` 断言播放头落在期望位置（容差 500ms）。
 *
 * ## 数据构造（自包含，不依赖设备上应用的真实数据）
 *
 * - 3 个片段各 5000ms，PCM16 单声道 16kHz，不同频率正弦波（440/587/880Hz）；
 * - 片段之间留空闲间隔模拟真实录音：片段1 [10:00:00, 10:00:05]、
 *   片段2 [10:00:20, 10:00:25]、片段3 [10:01:00, 10:01:05]；
 * - 合并文件由 [AudioMerger] 生成——PCM 纯直拼、无静音间隙，
 *   合并文件内时间轴 = 片段时长累加（15000ms），与绝对时间无任何关系。
 *
 * ## 轮询而非即时断言
 *
 * [AudioPlayer.play] 内部是同步 `prepare()` + `start()`，但 MediaPlayer
 * 刚启动时可能还在 buffer，`currentPositionMs()` 会短暂停在 0——
 * 直接断言会 flaky。因此播放后按 50ms 间隔轮询，最多等 3s，
 * 到达「期望偏移 - 容差」即认为就位。
 */
@RunWith(AndroidJUnit4::class)
class TranscriptPlaybackInstrumentationTest {

    private lateinit var cacheDir: File
    private lateinit var segmentFiles: List<File>
    private lateinit var mergedFile: File

    /** 当前用例持有的播放器（@After 统一释放，杜绝 MediaPlayer 泄漏）。 */
    private var player: AudioPlayer? = null

    private val dayId = "2026-10-24"

    /** 片段绝对时间基准：10:00:00（epoch millis 仅用于构造数据）。 */
    private val baseMs = 10L * 60 * 60 * 1000

    private lateinit var segments: List<AudioSegment>
    private lateinit var transcripts: List<TranscriptSegment>

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = context.cacheDir

        // 1) 生成 3 个片段 WAV（不同频率，便于真机排查时听出差异）
        val frequencies = listOf(440.0, 587.33, 880.0)
        segmentFiles = frequencies.mapIndexed { index, frequency ->
            File(cacheDir, "itest_segment_$index.wav").also {
                writeSineWav(it, frequency, SEGMENT_DURATION_MS)
            }
        }

        // 2) 用**生产合并器**拼出合并音频（复刻真实拼接规则，而非测试自己另写一套）
        mergedFile = File(cacheDir, "itest_merged.wav")
        AudioMerger().mergeSegments(
            paths = segmentFiles.map { it.absolutePath },
            outPath = mergedFile.absolutePath,
        )

        // 拼接规则实证：3 段 PCM 直拼 = 3 × (16000Hz × 2byte × 5s) + 44 字节单一文件头
        val expectedBytes = (WAV_HEADER_BYTES + SEGMENT_COUNT * SEGMENT_PCM_BYTES).toLong()
        assertEquals(
            "合并文件应为 3 段 PCM 直拼 + 单一 44 字节头（无静音间隙）",
            expectedBytes,
            mergedFile.length(),
        )

        // 3) 构造领域数据：片段间留空闲间隔，转写时间为全天绝对时间
        segments = listOf(
            segment(segmentId = 1L, file = segmentFiles[0], startMs = baseMs),
            segment(segmentId = 2L, file = segmentFiles[1], startMs = baseMs + 20_000L),
            segment(segmentId = 3L, file = segmentFiles[2], startMs = baseMs + 60_000L),
        )
        transcripts = listOf(
            transcript(tsId = 11L, segmentId = 1L, startMs = baseMs + 1_000L),
            transcript(tsId = 12L, segmentId = 1L, startMs = baseMs + 4_000L),
            transcript(tsId = 21L, segmentId = 2L, startMs = baseMs + 20_500L),
            transcript(tsId = 22L, segmentId = 2L, startMs = baseMs + 23_000L),
            transcript(tsId = 31L, segmentId = 3L, startMs = baseMs + 61_000L),
            transcript(tsId = 32L, segmentId = 3L, startMs = baseMs + 63_500L),
        )
    }

    @After
    fun tearDown() {
        player?.release()
        player = null
        // setUp 中途失败时 lateinit 可能未初始化：防御性清理，避免 teardown 异常
        // 掩盖真正的失败原因（JUnit 在 @Before 失败后仍会执行 @After）
        if (::segmentFiles.isInitialized) segmentFiles.forEach { it.delete() }
        if (::mergedFile.isInitialized) mergedFile.delete()
    }

    // ── 点击播放：偏移喂给真实 MediaPlayer 后播放头落在期望位置 ──

    @Test
    fun `片段1内偏移_播放头落在期望位置`() {
        val expected = expectedOffsetOf(11L)
        // 第一个片段：无前缀时长，mergedOffset == 片段内偏移
        assertEquals(1_000L, expected)
        assertPlaybackHeadLandsAt(expected, "片段1内偏移")
    }

    @Test
    fun `片段1靠后偏移_播放头落在期望位置`() {
        val expected = expectedOffsetOf(12L)
        assertEquals(4_000L, expected)
        assertPlaybackHeadLandsAt(expected, "片段1靠后偏移")
    }

    @Test
    fun `片段2内偏移_前缀累加后播放头落在期望位置`() {
        val expected = expectedOffsetOf(21L)
        // 前缀累加：片段1时长 5000 + 片段内偏移 500 = 5500（不是绝对时间 10:00:20.5）
        assertEquals(5_500L, expected)
        assertPlaybackHeadLandsAt(expected, "片段2内偏移（前缀累加）")
    }

    @Test
    fun `片段2靠后偏移_前缀累加后播放头落在期望位置`() {
        val expected = expectedOffsetOf(22L)
        assertEquals(8_000L, expected)
        assertPlaybackHeadLandsAt(expected, "片段2靠后偏移（前缀累加）")
    }

    @Test
    fun `片段3首条_大空闲间隔后播放头落在期望位置`() {
        val expected = expectedOffsetOf(31L)
        // 前两段共 10000ms + 片段内 1000ms = 11000；
        // 若误用绝对时间（10:01:01，约 3606 万 ms）会被 clamp 到文件末尾
        assertEquals(11_000L, expected)
        assertPlaybackHeadLandsAt(expected, "片段3首条（大空闲间隔后）")
    }

    @Test
    fun `片段3末条_播放头落在期望位置`() {
        val expected = expectedOffsetOf(32L)
        // 前两段共 10000ms + 片段内 3500ms = 13500，距文件末尾（15000ms）留足余量，
        // 避免「期望偏移贴着文件尾」把容差顶满
        assertEquals(13_500L, expected)
        assertPlaybackHeadLandsAt(expected, "片段3末条")
    }

    // ── 错误路径焊死：误用绝对时间 seek 落不到正确位置 ──

    @Test
    fun `误用全天绝对时间落不到正确位置_证明偏移公式必要性`() {
        val transcript = transcripts.first { it.tsId == 21L }
        val correctOffset = expectedOffsetOf(21L)
        assertEquals(5_500L, correctOffset)

        // 故意把「全天绝对时间」当偏移喂进去（正是 BUG 的误解源头）
        startPlayback(transcript.startMs)
        val observed = observePositionFor(NEGATIVE_OBSERVE_MS)

        // 无论被 clamp 到文件末尾还是停在起点，都不可能落在正确偏移附近——
        // 这就是必须先做 mergedOffsetMs 换算的证据
        assertTrue(
            "误用绝对时间 ${transcript.startMs} 后播放头 $observed 竟落在正确偏移 " +
                "$correctOffset 附近（容差 ${POSITION_TOLERANCE_MS}ms）",
            abs(observed - correctOffset) > POSITION_TOLERANCE_MS,
        )
    }

    // ── 合并音频存在性判定 ──

    @Test
    fun `mergedAudioFileExists_不存在的路径返回false`() {
        assertFalse(
            TranscriptPlaybackLocator.mergedAudioFileExists(
                File(cacheDir, "itest_no_such_merged.wav").absolutePath,
            ),
        )
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists(null))
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists(""))
        assertFalse(TranscriptPlaybackLocator.mergedAudioFileExists("   "))
    }

    @Test
    fun `mergedAudioFileExists_真实合并文件返回true`() {
        assertTrue(TranscriptPlaybackLocator.mergedAudioFileExists(mergedFile.absolutePath))
    }

    // ── 工具 ──

    /** 用定位器算目标转写在合并音频内的偏移（测试的核心链路）。 */
    private fun expectedOffsetOf(tsId: Long): Long {
        val transcript = transcripts.first { it.tsId == tsId }
        return TranscriptPlaybackLocator.mergedOffsetMs(transcript, segments)
    }

    private fun startPlayback(offsetMs: Long) {
        player?.release()
        player = AudioPlayer().also { it.play(mergedFile.absolutePath, offsetMs) }
    }

    /**
     * 播放并断言播放头落在期望位置。
     *
     * 轮询至「期望偏移 - 容差」或超时（见类 KDoc 的 flaky 说明）。
     */
    private fun assertPlaybackHeadLandsAt(expectedOffsetMs: Long, label: String) {
        startPlayback(expectedOffsetMs)
        val threshold = (expectedOffsetMs - POSITION_TOLERANCE_MS).coerceAtLeast(0L)
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var position = player?.currentPositionMs() ?: -1L
        while (position < threshold && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            position = player?.currentPositionMs() ?: -1L
        }
        assertTrue(
            "$label：播放头 ${position}ms 未在 ${POLL_TIMEOUT_MS}ms 内接近期望偏移 " +
                "${expectedOffsetMs}ms（阈值 ${threshold}ms）",
            position >= threshold,
        )
        val delta = abs(position - expectedOffsetMs)
        assertTrue(
            "$label：播放头 ${position}ms 与期望偏移 ${expectedOffsetMs}ms 相差 ${delta}ms，" +
                "超过容差 ${POSITION_TOLERANCE_MS}ms",
            delta <= POSITION_TOLERANCE_MS,
        )
    }

    /** 观察一段时间内的播放头位置（取最后读到的值；负值视为未就绪）。 */
    private fun observePositionFor(durationMs: Long): Long {
        val deadline = System.currentTimeMillis() + durationMs
        var position = 0L
        while (System.currentTimeMillis() < deadline) {
            val current = player?.currentPositionMs() ?: -1L
            if (current >= 0L) position = current
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return position
    }

    private fun segment(segmentId: Long, file: File, startMs: Long) = AudioSegment(
        segmentId = segmentId,
        dayId = dayId,
        filePath = file.absolutePath,
        startMs = startMs,
        endMs = startMs + SEGMENT_DURATION_MS,
        durationMs = SEGMENT_DURATION_MS.toLong(),
    )

    private fun transcript(tsId: Long, segmentId: Long, startMs: Long) = TranscriptSegment(
        tsId = tsId,
        segmentId = segmentId,
        startMs = startMs,
        endMs = startMs + 3_000L,
        text = "片段 $segmentId 的句子 $tsId",
    )

    /**
     * 写一段正弦波 PCM16 WAV（头部用生产级 [WavIo] 工具回填，
     * 保证与真实录音产物同格式，可直接被 [AudioMerger] 消费）。
     */
    private fun writeSineWav(file: File, frequencyHz: Double, durationMs: Int) {
        val sampleCount = SAMPLE_RATE * durationMs / 1000
        val pcm = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            val value = sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE) * 0.5 * Short.MAX_VALUE
            pcm.putShort(value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            raf.write(pcm.array())
            WavIo.patchHeader(
                raf = raf,
                dataBytes = (sampleCount * 2).toLong(),
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS,
                bitsPerSample = BITS_PER_SAMPLE,
            )
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val SEGMENT_DURATION_MS = 5_000
        const val SEGMENT_COUNT = 3

        /** 44 字节 RIFF/WAVE 头（PCM16 无附加块）。 */
        const val WAV_HEADER_BYTES = 44

        /** 单片段 PCM 字节数：16000Hz × 2byte × 5s。 */
        const val SEGMENT_PCM_BYTES = SAMPLE_RATE * 2 * SEGMENT_DURATION_MS / 1000

        /** 播放头位置容差（MediaPlayer seek/汇报粒度 + buffer 抖动）。 */
        const val POSITION_TOLERANCE_MS = 500L

        /** 轮询间隔与超时（play 后 MediaPlayer 可能仍在 buffer，不能即时断言）。 */
        const val POLL_INTERVAL_MS = 50L
        const val POLL_TIMEOUT_MS = 3_000L

        /** 负向用例的观察时长（误用绝对时间时播放会很快结束）。 */
        const val NEGATIVE_OBSERVE_MS = 1_500L
    }
}
