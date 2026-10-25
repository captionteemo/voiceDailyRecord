package com.echolog.app.work

import android.app.Notification
import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.echolog.app.R
import com.echolog.app.ai.asr.TimedText
import com.echolog.app.ai.llm.QwenSummarizer
import com.echolog.app.ai.llm.RuleBasedSummarizer
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.core.common.Constants
import com.echolog.app.core.util.WavIo
import com.echolog.app.data.media.StorageLocationManager
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.TranscriptStatus
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.usecase.MergeDayAudioUseCase
import com.echolog.app.domain.usecase.SummarizeDayUseCase
import com.echolog.app.domain.usecase.TranscribeDayUseCase
import com.echolog.app.service.notify.NotificationHelper
import com.echolog.app.testing.FakeAsrEngine
import com.echolog.app.testing.FakeAudioPathProvider
import com.echolog.app.testing.FakeRecordingRepository
import com.echolog.app.testing.FakeSegmentTranscriptExporter
import com.echolog.app.testing.FakeSettingsRepository
import com.echolog.app.testing.FakeSummaryRepository
import com.echolog.app.testing.FakeTranscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * [DaySettleWorker] 结算策略回归测试（QA：BUG-11 修复验证）。
 *
 * 用户报告的原始 Bug：**「实际安装后，尝试进行录音，结束录音后进度会卡在 5%，
 * 无法翻译成文字」**。本测试类聚焦「结算链路在各种失败/边界下是否仍能走到
 * DONE/100、且进度永不回退到 5%」。
 *
 * 覆盖的根因与修复：
 * - 修复 2（合并失败不再阻断转写）：合并失败 → **转写仍被调用** → 进度走到 100，
 *   `settleError` 保留合并失败提示；
 * - 修复 2（总兜底 try/catch）：未捕获异常写入 `settleError` 并返回 failure；
 * - QA 修复（进度写入竞态）：转写进度写入有序、且**早于**归档写入，
 *   不得把 100% 回退成中途值、不得覆盖 `settleError`；
 * - QA 修复（通知取消失败）：撤下通知失败不得让已归档的进度回退到 5%。
 *
 * Worker 在 JVM 单测中的可驱动性：`CoroutineWorker` 依赖 `Context` 与
 * `WorkerParameters`，本测试用 Mockito（inline mock maker，可 mock final 类）
 * 构造替身；`setForeground` 在无 WorkManager 环境下会抛异常，但源码已用
 * `runCatching` 包裹（降级为「只推 DB 进度」），不影响主链路断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaySettleWorkerSettlePolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var fakeRepo: FakeRecordingRepository
    private lateinit var repo: ProgressRecordingRepository
    private lateinit var transcriptionRepo: FakeTranscriptionRepository
    private lateinit var summaryRepo: FakeSummaryRepository
    private lateinit var asr: FakeAsrEngine
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var worker: DaySettleWorker
    private lateinit var audioDir: File

    /** 片段序号（避免文件名冲突）。 */
    private var nextOrdinal = 0

    @Before
    fun setUp() {
        audioDir = tempFolder.newFolder("audio")
        fakeRepo = FakeRecordingRepository()
        repo = ProgressRecordingRepository(fakeRepo)
        transcriptionRepo = FakeTranscriptionRepository(fakeRepo)
        summaryRepo = FakeSummaryRepository()
        asr = FakeAsrEngine()

        // ── Context 替身：只关心 getString（阶段文案）与 applicationContext ──
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.getString(Mockito.anyInt())).thenReturn("stage")
        Mockito.`when`(context.applicationContext).thenReturn(context)
        // 「当天无可合并片段」「当天无录音片段」提示必须与真实文案一致，
        // 否则空录音日分支判断 / 归档备注断言会失效
        Mockito.`when`(context.getString(R.string.worker_error_no_segment))
            .thenReturn(NO_SEGMENT_HINT)
        Mockito.`when`(context.getString(R.string.worker_empty_day_note))
            .thenReturn(EMPTY_DAY_NOTE)

        // ── WorkerParameters 替身 ──
        workerParams = Mockito.mock(WorkerParameters::class.java)
        Mockito.`when`(workerParams.inputData).thenReturn(
            Data.Builder().putString(Constants.EXTRA_DAY_ID, DAY).build(),
        )
        Mockito.`when`(workerParams.runAttemptCount).thenReturn(0)

        // ── 通知替身 ──
        notificationHelper = Mockito.mock(NotificationHelper::class.java)
        Mockito.`when`(
            notificationHelper.buildProgressNotification(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
            ),
        ).thenReturn(Mockito.mock(Notification::class.java))

        worker = DaySettleWorker(
            appContext = context,
            workerParams = workerParams,
            mergeDayAudioUseCase = MergeDayAudioUseCase(
                recordingRepository = repo,
                audioPathProvider = FakeAudioPathProvider(audioDir),
                settingsRepository = FakeSettingsRepository(),
                storageLocationManager = Mockito.mock(StorageLocationManager::class.java),
                ioDispatcher = dispatcher,
            ),
            transcribeDayUseCase = TranscribeDayUseCase(
                recordingRepository = repo,
                transcriptionRepository = transcriptionRepo,
                asrEngine = asr,
                transcriptExporter = FakeSegmentTranscriptExporter(),
                defaultDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ),
            summarizeDayUseCase = SummarizeDayUseCase(
                transcriptionRepository = transcriptionRepo,
                recordingRepository = repo,
                summaryRepository = summaryRepo,
                qwenSummarizer = Mockito.mock(QwenSummarizer::class.java),
                ruleBasedSummarizer = RuleBasedSummarizer(dispatcher),
                modelManager = mockModelManager(),
                ioDispatcher = dispatcher,
            ),
            recordingRepository = repo,
            notificationHelper = notificationHelper,
        )
    }

    // ── 关键回归：合并失败不再阻断转写 ──

    @Test
    fun `合并失败时转写仍被调用且进度走到100并保留合并失败提示`() = runTest {
        // 两个片段全是「占位头未回填」的损坏文件 → 合并必然失败（全部片段无效）
        val seg1 = seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.CORRUPT)
        val seg2 = seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.CORRUPT)
        asr.resultFor(seg1.filePath, listOf(TimedText(0L, 500L, "第一段内容")))
        asr.resultFor(seg2.filePath, listOf(TimedText(0L, 500L, "第二段内容")))

        val result = worker.doWork()

        assertTrue(
            "合并失败不应让结算失败，实际：$result",
            result is ListenableWorker.Result.Success,
        )
        assertEquals(
            "合并失败后转写仍必须被调用（每个待转写片段一次）",
            2,
            asr.transcribeCalls.size,
        )
        assertTrue(asr.transcribeCalls.contains(seg1.filePath))
        assertTrue(asr.transcribeCalls.contains(seg2.filePath))

        val day = fakeRepo.getDay(DAY)!!
        assertEquals("当天应归档为 DONE", DayState.DONE, day.state)
        assertEquals("结算进度应走到 100，实际 ${day.settleProgress}", 100, day.settleProgress)
        assertNotNull("合并失败提示应保留在 settleError", day.settleError)
        assertTrue(
            "settleError 应说明合并失败，实际：${day.settleError}",
            day.settleError!!.contains("合并"),
        )
        // 转写产物仍应落库（合并产物缺失不影响文字）
        assertEquals(2, transcriptionRepo.transcriptsOf(DAY).size)
    }

    @Test
    fun `合并失败时进度写入序列单调不回退`() = runTest {
        // 竞态回归：原实现用 `progressScope.cancel()` 取消进度写入，
        // 在途写入可能晚于归档的 100% 落库 → 进度回退 + settleError 被覆盖为 null。
        seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.CORRUPT)
        seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.CORRUPT)

        worker.doWork()

        val writes = repo.progressWrites
        assertTrue("应有多次进度写入，实际：$writes", writes.size >= 3)
        writes.forEachIndexed { index, (percent, _) ->
            if (index == 0) return@forEachIndexed
            val previous = writes[index - 1].first
            assertTrue(
                "进度不得回退：第 $index 次写入 $percent < 上一次 $previous（序列 $writes）",
                percent >= previous,
            )
        }
        assertEquals("最终进度必须是 100", 100, writes.last().first)
        assertNotNull("最终 settleError 必须保留合并失败提示", writes.last().second)
    }

    // ── 正常路径 ──

    @Test
    fun `合并成功时正常推进到DONE进度100且无错误`() = runTest {
        val seg = seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.VALID)
        asr.resultFor(seg.filePath, listOf(TimedText(0L, 1000L, "正常内容")))

        val result = worker.doWork()

        assertTrue("应成功，实际：$result", result is ListenableWorker.Result.Success)
        val day = fakeRepo.getDay(DAY)!!
        assertEquals(DayState.DONE, day.state)
        assertEquals(100, day.settleProgress)
        assertNull("成功路径不应有 settleError，实际：${day.settleError}", day.settleError)
        assertNotNull("应产出全天合并文件", day.mergedAudioPath)
        assertEquals(1000L, day.totalRecordMs)
    }

    // ── 兜底与边界 ──

    @Test
    fun `结算链路异常时写入settleError并返回失败`() = runTest {
        seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.CORRUPT)
        // 让「取空片段提示文案」抛异常 → 模拟结算链路内的未捕获异常
        Mockito.`when`(context.getString(R.string.worker_error_no_segment))
            .thenThrow(RuntimeException("模拟结算链路异常"))

        val result = worker.doWork()

        assertTrue("未捕获异常应返回失败，实际：$result", result is ListenableWorker.Result.Failure)
        val day = fakeRepo.getDay(DAY)!!
        assertNotNull("失败原因必须落库 settleError", day.settleError)
        assertTrue(
            "settleError 应包含异常信息，实际：${day.settleError}",
            day.settleError!!.contains("结算异常"),
        )
    }

    @Test
    fun `撤下进度通知失败不得让已归档的进度回退到5%`() = runTest {
        val seg = seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.VALID)
        asr.resultFor(seg.filePath, listOf(TimedText(0L, 1000L, "正常内容")))
        Mockito.doThrow(RuntimeException("模拟通知取消失败"))
            .`when`(notificationHelper)
            .cancel(Mockito.anyInt())

        val result = worker.doWork()

        assertTrue(
            "通知取消失败不应让结算失败，实际：$result",
            result is ListenableWorker.Result.Success,
        )
        val day = fakeRepo.getDay(DAY)!!
        assertEquals("当天已归档，进度不得回退到 5%", 100, day.settleProgress)
        assertEquals(DayState.DONE, day.state)
    }

    @Test
    fun `无可用片段时按空录音日归档为DONE进度100`() = runTest {
        // 片段行存在但音频文件缺失 → 合并报「当天无可合并片段」→ 空录音日
        seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.MISSING)

        val result = worker.doWork()

        assertTrue("空录音日应成功归档，实际：$result", result is ListenableWorker.Result.Success)
        val day = fakeRepo.getDay(DAY)!!
        assertEquals(DayState.DONE, day.state)
        assertEquals(100, day.settleProgress)
        assertEquals("应说明当天无录音片段", EMPTY_DAY_NOTE, day.settleError)
        assertTrue("空录音日不应调用 ASR", asr.transcribeCalls.isEmpty())
    }

    // ── 工具 ──

    /** 片段音频文件的构造方式。 */
    private enum class SegmentFileKind {
        /** 合法 PCM16 WAV。 */
        VALID,

        /** 44 字节全零占位头（模拟进程被杀、头部未回填）。 */
        CORRUPT,

        /** 文件不存在（模拟音频被清理）。 */
        MISSING,
    }

    /** 造一个片段并入库。 */
    private fun seedSegment(durationMs: Long, fileKind: SegmentFileKind): AudioSegment {
        val path = FakeAudioPathProvider(audioDir).segmentPath(DAY, nextOrdinal++)
        val file = File(path)
        if (fileKind != SegmentFileKind.MISSING) {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf ->
                if (fileKind == SegmentFileKind.CORRUPT) {
                    // 44 字节全零占位头：WavIo.readHeader 必然解析失败
                    raf.write(ByteArray(PLACEHOLDER_HEADER_BYTES))
                } else {
                    WavIo.writePlaceholderHeader(raf)
                    raf.write(ByteArray(SAMPLE_COUNT * 2))
                    WavIo.patchHeader(raf, SAMPLE_COUNT * 2L, 16000, 1)
                }
            }
        }
        fakeRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        return fakeRepo.addSegment(
            AudioSegment(
                segmentId = 0L,
                dayId = DAY,
                filePath = path,
                startMs = 0L,
                endMs = durationMs,
                durationMs = durationMs,
                sizeBytes = SAMPLE_COUNT * 2L,
                transcriptStatus = TranscriptStatus.PENDING,
            ),
        )
    }

    /** LLM 未就绪 → 强制走规则兜底总结路径。 */
    private fun mockModelManager(): ModelManager {
        val modelManager = Mockito.mock(ModelManager::class.java)
        Mockito.`when`(modelManager.isLlmReady()).thenReturn(false)
        return modelManager
    }

    private companion object {
        const val DAY = "2026-10-24"
        const val NO_SEGMENT_HINT = "当天无可合并片段"
        const val EMPTY_DAY_NOTE = "当天无录音片段"
        const val SAMPLE_COUNT = 1600
        const val PLACEHOLDER_HEADER_BYTES = 44
    }
}

/**
 * 记录 [RecordingRepository.updateSettleProgress] 写入序列的代理。
 *
 * 用于断言「进度写入单调不回退」——这是 QA 修复 `progressScope.cancel()`
 * 竞态（`DaySettleWorker`）的回归护栏。
 */
private class ProgressRecordingRepository(
    private val delegate: RecordingRepository,
) : RecordingRepository by delegate {

    private val lock = Any()
    private val writes = mutableListOf<Pair<Int, String?>>()

    /** 结算进度写入序列（按实际写入顺序，线程安全）。 */
    val progressWrites: List<Pair<Int, String?>>
        get() = synchronized(lock) { writes.toList() }

    override suspend fun updateSettleProgress(
        dayId: String,
        progressPercent: Int,
        error: String?,
    ) {
        synchronized(lock) { writes += progressPercent to error }
        delegate.updateSettleProgress(dayId, progressPercent, error)
    }
}
