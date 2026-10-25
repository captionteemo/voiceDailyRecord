package com.echolog.app.domain.usecase

import com.echolog.app.ai.asr.TimedText
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.TranscriptStatus
import com.echolog.app.testing.FakeAsrEngine
import com.echolog.app.testing.FakeRecordingRepository
import com.echolog.app.testing.FakeSegmentTranscriptExporter
import com.echolog.app.testing.FakeTranscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TranscribeDayUseCase] 单元测试（QA：FR-009 端侧转写 / 架构 4.2）。
 *
 * 验证：模型未就绪快速失败（E-3001）、绝对时间叠加、幂等跳过 DONE、
 * 单片段失败隔离（E-3002）、失败片段不得在同一调用内无限重试、
 * 进度回调口径正确。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscribeDayUseCaseTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var recordingRepo: FakeRecordingRepository
    private lateinit var transcriptionRepo: FakeTranscriptionRepository
    private lateinit var asr: FakeAsrEngine
    private lateinit var exporter: FakeSegmentTranscriptExporter
    private lateinit var useCase: TranscribeDayUseCase

    @Before
    fun setUp() {
        recordingRepo = FakeRecordingRepository()
        transcriptionRepo = FakeTranscriptionRepository(recordingRepo)
        // 循环护栏：单次调用内 getPendingSegments 超过 50 次即视为死循环
        transcriptionRepo.guardAfterQueries = 50
        asr = FakeAsrEngine()
        exporter = FakeSegmentTranscriptExporter()
        useCase = TranscribeDayUseCase(
            recordingRepository = recordingRepo,
            transcriptionRepository = transcriptionRepo,
            asrEngine = asr,
            transcriptExporter = exporter,
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `模型未就绪时返回E3001且不触碰片段`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        val seg = seedSegment(DAY, startMs = 0L)
        asr.setReady(false)

        val result = useCase(DAY)

        assertTrue(result.isFailure)
        assertEquals("E-3001", result.failureOrNull()?.code)
        assertEquals(TranscriptStatus.PENDING, recordingRepo.segmentsOf(DAY).single().transcriptStatus)
        assertTrue(transcriptionRepo.transcriptsOf(DAY).isEmpty())
        assertEquals(0, asr.callsFor(seg.filePath))
    }

    @Test
    fun `逐段转写并叠加片段起点为全天绝对时间`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        val seg1 = seedSegment(DAY, startMs = 1_000L)
        val seg2 = seedSegment(DAY, startMs = 9_000L)
        asr.resultFor(seg1.filePath, listOf(TimedText(100L, 600L, "上午的事")))
        asr.resultFor(seg2.filePath, listOf(TimedText(0L, 500L, "下午的事")))

        val result = useCase(DAY)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        val transcripts = transcriptionRepo.transcriptsOf(DAY)
        assertEquals(2, transcripts.size)
        // 引擎输出为相对片段起点偏移 → 落库为全天绝对时间
        assertEquals(1_100L, transcripts.first { it.text == "上午的事" }.startMs)
        assertEquals(1_600L, transcripts.first { it.text == "上午的事" }.endMs)
        assertEquals(9_000L, transcripts.first { it.text == "下午的事" }.startMs)
        // 片段状态 DONE
        assertTrue(recordingRepo.segmentsOf(DAY).all { it.transcriptStatus == TranscriptStatus.DONE })
    }

    @Test
    fun `转写成功时同步导出与录音同名的md且失败不影响主链路`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        val seg1 = seedSegment(DAY, startMs = 1_000L)
        val seg2 = seedSegment(DAY, startMs = 9_000L)
        asr.resultFor(seg1.filePath, listOf(TimedText(100L, 600L, "上午的事")))
        asr.resultFor(seg2.filePath, listOf(TimedText(0L, 500L, "下午的事")))
        // 导出失败不应拖垮当天转写（调用方 runCatching 吞掉）
        exporter.throwPaths += seg2.filePath

        val result = useCase(DAY)

        assertTrue(result.isSuccess)
        // seg1 成功导出被记录；seg2 导出失败被跳过但不影响转写完成
        assertEquals(listOf(DAY to seg1.filePath), exporter.exportedPaths)
        assertTrue(recordingRepo.segmentsOf(DAY).all { it.transcriptStatus == TranscriptStatus.DONE })
    }

    @Test
    fun `已完成片段被跳过保证幂等`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.DONE))
        val done = seedSegment(DAY, startMs = 0L, status = TranscriptStatus.DONE)
        asr.resultFor(done.filePath, listOf(TimedText(0L, 100L, "旧内容")))

        val result = useCase(DAY)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        assertEquals(0, asr.callsFor(done.filePath))
        assertTrue(transcriptionRepo.transcriptsOf(DAY).isEmpty())
    }

    @Test
    fun `单片段推理失败被标记FAILED且不拖垮当天`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        val ok = seedSegment(DAY, startMs = 0L)
        val bad = seedSegment(DAY, startMs = 5_000L)
        asr.resultFor(ok.filePath, listOf(TimedText(0L, 100L, "正常片段")))
        asr.throwPaths += bad.filePath // E-3002

        val result = useCase(DAY)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        assertEquals(TranscriptStatus.DONE, recordingRepo.segmentsOf(DAY).first { it.segmentId == ok.segmentId }.transcriptStatus)
        val failed = recordingRepo.segmentsOf(DAY).first { it.segmentId == bad.segmentId }
        assertEquals(TranscriptStatus.FAILED, failed.transcriptStatus)
        assertTrue(failed.transcriptError?.isNotBlank() == true)
    }

    @Test
    fun `同一调用内失败片段不应被无限重试`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        val bad = seedSegment(DAY, startMs = 0L)
        asr.throwPaths += bad.filePath // 确定性失败（如文件损坏）

        // 护栏兜底：若实现陷入「失败→重取→再失败」死循环，fake 抛异常中断
        val result = runCatching { useCase(DAY) }

        // 无论护栏是否触发，失败片段在一次调用中最多应尝试 1 次
        //（跨调用重试是充电兜底 Worker 的职责，不是单次调用的）
        assertEquals("同一调用内失败片段只应尝试一次", 1, asr.callsFor(bad.filePath))
        assertFalse("调用应正常收敛而非死循环", result.isFailure)
    }

    @Test
    fun `进度回调的总数不超过待转写片段数`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        val segs = (0 until 3).map { seedSegment(DAY, startMs = it * 10_000L) }
        segs.forEachIndexed { index, seg ->
            asr.resultFor(seg.filePath, listOf(TimedText(0L, 100L, "片段$index"))
            )
        }

        val callbacks = mutableListOf<Pair<Int, Int>>()
        val result = useCase(DAY) { done, total -> callbacks += done to total }

        assertTrue(result.isSuccess)
        assertEquals(3, callbacks.size)
        callbacks.forEach { (done, total) ->
            assertTrue("done=$done 不应超过 total=$total", done <= total)
            assertTrue("total=$total 不应超过待转写总数 3", total <= 3)
        }
        assertEquals(3 to 3, callbacks.last())
    }

    // ── 工具 ──

    private fun seedSegment(
        dayId: String,
        startMs: Long,
        status: TranscriptStatus = TranscriptStatus.PENDING,
    ): AudioSegment = recordingRepo.addSegment(
        AudioSegment(
            segmentId = 0L,
            dayId = dayId,
            filePath = "/tmp/seg_${dayId}_$startMs.wav",
            startMs = startMs,
            endMs = startMs + 1_000L,
            durationMs = 1_000L,
            transcriptStatus = status,
        ),
    )

    private companion object {
        const val DAY = "2026-10-24"
    }
}
