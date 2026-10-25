package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.WavIo
import com.echolog.app.data.media.StorageLocationManager
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.testing.FakeAudioPathProvider
import com.echolog.app.testing.FakeRecordingRepository
import com.echolog.app.testing.FakeSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.io.RandomAccessFile

/**
 * [MergeDayAudioUseCase]「结束录音」时序竞态回归测试（BUG-11 修复验证）。
 *
 * 用户报告的原始 Bug：**「实际安装后，尝试进行录音，结束录音后进度会卡在 5%，
 * 无法翻译成文字」**。本测试聚焦合并入口的一个确定性缺陷：
 *
 * `RecordingService.handleStop` 只投递 Intent 就返回，片段的 `durationMs`
 * （由 `AudioSegmentDao.finishSegment` 的 `duration_ms = endMs - startMs` 派生）
 * 要等音频循环退出后才写入 DB。若合并流程此刻就读片段，最后一个片段会因
 * `durationMs == 0` 被过滤 → 音频丢失，甚至整天被判为「无片段」。
 *
 * 修复：`MergeDayAudioUseCase.awaitSettledSegments`（主源码 121~132 行）在
 * 读到 `durationMs <= 0` 的片段时按 [SEGMENT_POLL_INTERVAL_MS]（50ms）间隔轮询重读，
 * 最长等 [MAX_SETTLE_WAIT_MS]（3s）。
 *
 * 覆盖场景：
 * 1. 首次读到未收尾片段（`durationMs == 0`）→ 轮询后读到已收尾片段 → 合并成功；
 * 2. 片段始终未收尾 → 等满 3s 上限后按现状继续 → 无有效片段 → E-5001 失败；
 *    同时断言**轮询次数上界**（1 次初始读 + 3s/50ms = 61 次）与**虚拟等待时长**
 *    （≈3000ms），确保「等待有界」不会被后续改动悄悄放大；
 * 3. 片段已收尾但音频文件缺失 → 同样被过滤 → E-5001 失败。
 *
 * 调度器说明：
 * - 场景 1、3 用 [UnconfinedTestDispatcher] + 与 `runTest` 共享的
 *   [TestCoroutineScheduler]：协程立即执行，无需驱动调度器；
 * - 场景 2 用 [StandardTestDispatcher] + 同一 [TestCoroutineScheduler]：
 *   `awaitSettledSegments` 内部的 `delay(50)` 走**虚拟时间快进**，
 *   可用 `testScheduler.currentTime` 精确断言等待时长（真实耗时为 0）。
 *
 * 说明：本文件不修改共享的 `FakeRepositories.kt`，而是使用本文件内的
 * 委托代理 [SettlingSegmentsRepository] 模拟「收尾落库」的时序，
 * 避免与主理人/QA 的并行修改冲突。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MergeDayAudioUseCaseSettleRaceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var recordingRepo: FakeRecordingRepository
    private lateinit var audioDir: File
    private lateinit var pathProvider: FakeAudioPathProvider

    @Before
    fun setUp() {
        audioDir = tempFolder.newFolder("audio")
        recordingRepo = FakeRecordingRepository()
        pathProvider = FakeAudioPathProvider(audioDir)
    }

    // ── 1. 竞态主场景：首次读到未收尾片段 ──

    @Test
    fun `首次读到未收尾片段时轮询等待后合并成功`() = runTest {
        val seg1 = seedSegment(ordinal = 1, durationMs = 1000L, sampleCount = 16_000)
        val seg2 = seedSegment(ordinal = 2, durationMs = 2000L, sampleCount = 32_000)
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))

        // 首次读取把 durationMs 强制为 0（模拟 finishSegment 尚未落库）
        val repo = SettlingSegmentsRepository(recordingRepo, unsettledReads = 1)
        val useCase = newMergeUseCase(repo, UnconfinedTestDispatcher(testScheduler))

        val result = useCase(DAY)

        assertTrue("应成功：${result.failureOrNull()?.message}", result.isSuccess)
        // 必须发生「重读」——否则说明竞态未被修复
        assertTrue("应至少读取片段 2 次，实际 ${repo.readCount}", repo.readCount >= 2)
        // 上界：正常场景只需极少几次轮询，绝不能逼近等待上限
        assertTrue(
            "读取次数不得超过上界 $MAX_READS_BOUND，实际 ${repo.readCount}",
            repo.readCount <= MAX_READS_BOUND,
        )
        assertEquals("只需重读一次即可拿到已收尾片段", 2, repo.readCount)

        val mergedPath = requireNotNull(result.getOrNull()) { "成功时应返回合并文件路径" }
        val mergedFile = File(mergedPath)
        assertTrue("合并文件应真实生成：$mergedPath", mergedFile.isFile)

        // WAV 头校验：采样率/声道与片段一致，dataSize 为两个片段的数据总长
        val format = WavIo.readHeader(mergedPath)
        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertEquals(
            "合并后 dataSize 应等于各片段 PCM 数据之和",
            (16_000 + 32_000) * 2L,
            format.dataSize,
        )

        // 回写 DB：合并路径与总时长
        val day = recordingRepo.getDay(DAY)!!
        assertEquals(mergedPath, day.mergedAudioPath)
        assertEquals("总时长应等于各片段时长之和", 3000L, day.totalRecordMs)

        // 参与合并的片段文件本身不应被改动
        assertTrue(File(seg1.filePath).isFile)
        assertTrue(File(seg2.filePath).isFile)
    }

    // ── 2. 反例：片段始终未收尾（等待有界） ──

    @Test
    fun `片段始终未收尾时等待上限后按无可合并片段失败`() = runTest {
        // 文件存在且是合法 WAV，但 durationMs 永远为 0（音频循环永不退出）
        seedSegment(ordinal = 1, durationMs = 0L, sampleCount = 16_000)
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))

        val repo = SettlingSegmentsRepository(recordingRepo, unsettledReads = Int.MAX_VALUE)
        // StandardTestDispatcher：delay 走虚拟时间，可精确断言等待时长与轮询次数
        val useCase = newMergeUseCase(repo, StandardTestDispatcher(testScheduler))

        val startVirtualTimeMs = testScheduler.currentTime
        val result = useCase(DAY)
        val elapsedVirtualMs = testScheduler.currentTime - startVirtualTimeMs

        assertTrue("应失败", result.isFailure)
        val failure = result.failureOrNull()!!
        assertEquals(Constants.ERROR_MERGE_FAILED, failure.code)
        assertTrue(
            "错误信息应说明当天无可合并片段，实际：${failure.message}",
            failure.message.contains("当天无可合并片段"),
        )
        // 必须发生轮询（否则说明未进入等待逻辑）
        assertTrue("应轮询读取片段，实际 ${repo.readCount}", repo.readCount >= 2)
        // 上界断言：读取次数必须恰好等于「1 次初始读 + 等待上限/轮询间隔」。
        // 若后续把轮询间隔调小或等待上限调大，此处会立刻失败（防止等待被无限放大）。
        assertEquals(
            "轮询读取次数应恰好为 1 + ${MAX_SETTLE_WAIT_MS}/${SEGMENT_POLL_INTERVAL_MS} = $MAX_READS_BOUND",
            MAX_READS_BOUND,
            repo.readCount,
        )
        assertTrue(
            "读取次数不得超过上界 $MAX_READS_BOUND，实际 ${repo.readCount}",
            repo.readCount <= MAX_READS_BOUND,
        )
        // 虚拟时间断言：等待时长 ≈ 3s（真实耗时为 0，说明 delay 走的是虚拟时钟）
        assertTrue(
            "虚拟等待时长应约等于 ${MAX_SETTLE_WAIT_MS}ms，实际 ${elapsedVirtualMs}ms",
            elapsedVirtualMs in
                (MAX_SETTLE_WAIT_MS - VIRTUAL_TIME_TOLERANCE_MS)..
                (MAX_SETTLE_WAIT_MS + VIRTUAL_TIME_TOLERANCE_MS),
        )

        // 合并失败不得留下「已合并」的假象
        val day = recordingRepo.getDay(DAY)!!
        assertNull("合并失败不得回写合并路径", day.mergedAudioPath)
        assertEquals(0L, day.totalRecordMs)
    }

    // ── 3. 反例：片段已收尾但音频文件缺失 ──

    @Test
    fun `片段文件缺失时返回无可合并片段失败`() = runTest {
        val missing = File(audioDir, "missing.wav").absolutePath
        recordingRepo.addSegment(
            AudioSegment(
                segmentId = 0L,
                dayId = DAY,
                filePath = missing,
                startMs = 0L,
                endMs = 1000L,
                durationMs = 1000L,
            ),
        )
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))

        // 片段已收尾（durationMs > 0），无需等待，直接读一次即可
        val repo = SettlingSegmentsRepository(recordingRepo, unsettledReads = 0)
        val useCase = newMergeUseCase(repo, UnconfinedTestDispatcher(testScheduler))

        val result = useCase(DAY)

        assertTrue("应失败", result.isFailure)
        val failure = result.failureOrNull()!!
        assertEquals(Constants.ERROR_MERGE_FAILED, failure.code)
        assertTrue(
            "错误信息应说明当天无可合并片段，实际：${failure.message}",
            failure.message.contains("当天无可合并片段"),
        )
        assertEquals("已收尾的片段无需轮询等待", 1, repo.readCount)
        assertTrue(
            "读取次数不得超过上界 $MAX_READS_BOUND，实际 ${repo.readCount}",
            repo.readCount <= MAX_READS_BOUND,
        )

        val day = recordingRepo.getDay(DAY)!!
        assertNull("合并失败不得回写合并路径", day.mergedAudioPath)
    }

    // ── 工具 ──

    /**
     * 造一个合并用例。
     *
     * @param repo 录音仓库（可能是收尾时序代理）。
     * @param dispatcher 与 `runTest` 共享同一 [TestCoroutineScheduler]，
     *   使 `awaitSettledSegments` 中的 `delay` 走虚拟时间快进（无真实耗时）。
     */
    private fun newMergeUseCase(
        repo: RecordingRepository,
        dispatcher: CoroutineDispatcher,
    ): MergeDayAudioUseCase = MergeDayAudioUseCase(
        recordingRepository = repo,
        audioPathProvider = pathProvider,
        settingsRepository = FakeSettingsRepository(),
        // 未选自选目录（storageTreeUri = null）→ 不触发 SAF 复制
        storageLocationManager = Mockito.mock(StorageLocationManager::class.java),
        ioDispatcher = dispatcher,
    )

    /** 造一个真实 WAV 片段并入库。 */
    private fun seedSegment(
        ordinal: Int,
        durationMs: Long,
        sampleCount: Int,
    ): AudioSegment {
        val path = pathProvider.segmentPath(DAY, ordinal)
        writeWav(File(path), sampleCount)
        return recordingRepo.addSegment(
            AudioSegment(
                segmentId = 0L,
                dayId = DAY,
                filePath = path,
                startMs = 0L,
                endMs = durationMs,
                durationMs = durationMs,
                sizeBytes = sampleCount * 2L,
            ),
        )
    }

    /** 写一个 16kHz 单声道 PCM16 WAV。 */
    private fun writeWav(file: File, sampleCount: Int) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            WavIo.writePlaceholderHeader(raf)
            raf.write(ByteArray(sampleCount * 2))
            WavIo.patchHeader(raf, sampleCount * 2L, 16000, 1)
        }
    }

    private companion object {
        const val DAY = "2026-10-24"

        /**
         * 片段收尾等待上限（毫秒）——与 `MergeDayAudioUseCase.MAX_SETTLE_WAIT_MS` 保持一致。
         *
         * 这里是**镜像常量**：生产代码把两者设为 private，测试无法直接引用，
         * 故在此复刻并在断言里绑定二者关系（轮询次数上界）。
         */
        const val MAX_SETTLE_WAIT_MS = 3_000L

        /**
         * 片段收尾轮询间隔（毫秒）——与 `MergeDayAudioUseCase.SEGMENT_POLL_INTERVAL_MS` 保持一致。
         */
        const val SEGMENT_POLL_INTERVAL_MS = 50L

        /**
         * 轮询读取次数上界：1 次初始读 + 等待上限 / 轮询间隔 = 1 + 3000/50 = 61。
         *
         * 用 [Int] 类型以便与 `readCount` 直接比较（避免装箱后 `Long != Int`）。
         */
        val MAX_READS_BOUND: Int = 1 + (MAX_SETTLE_WAIT_MS / SEGMENT_POLL_INTERVAL_MS).toInt()

        /** 虚拟等待时长的允许误差（毫秒）——容忍调度器取整。 */
        const val VIRTUAL_TIME_TOLERANCE_MS = 100L
    }
}

/**
 * 模拟「结束录音 → 片段收尾落库」时序的录音仓库代理。
 *
 * 前 [unsettledReads] 次读取把片段的 `durationMs` 强制为 0（模拟
 * `finishSegment` 尚未落库），之后返回真实值（已收尾）。
 * 其余方法全部委托给 [delegate]。
 *
 * @param unsettledReads 前多少次读取返回「未收尾」片段；[Int.MAX_VALUE] 表示永不收尾。
 */
private class SettlingSegmentsRepository(
    private val delegate: RecordingRepository,
    private val unsettledReads: Int,
) : RecordingRepository by delegate {

    private var reads = 0

    /** `getSegmentsForDay` 的实际调用次数（断言轮询是否发生、是否越界）。 */
    val readCount: Int get() = reads

    override suspend fun getSegmentsForDay(dayId: String): List<AudioSegment> {
        reads++
        val segments = delegate.getSegmentsForDay(dayId)
        return if (reads <= unsettledReads) {
            segments.map { it.copy(durationMs = 0L) }
        } else {
            segments
        }
    }
}
