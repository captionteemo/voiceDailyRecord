package com.echolog.app.domain.usecase

import com.echolog.app.ai.asr.TimedText
import com.echolog.app.ai.llm.QwenSummarizer
import com.echolog.app.ai.llm.RuleBasedSummarizer
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.data.media.StorageLocationManager
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.TranscriptStatus
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * [SettlePendingDaysUseCase] 单元测试（QA：FR-010 兜底结算 / 架构 4.3）。
 *
 * 验证：空池、SETTLING 天完整推进（合并→转写→总结→DONE/100）、
 * DONE 天补转写不重复合并、按 dayId 升序处理、单天失败隔离、
 * 失败天不得标记 DONE（合并失败 / ASR 模型缺失）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettlePendingDaysUseCaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var recordingRepo: FakeRecordingRepository
    private lateinit var transcriptionRepo: FakeTranscriptionRepository
    private lateinit var summaryRepo: FakeSummaryRepository
    private lateinit var asr: FakeAsrEngine
    private lateinit var mergeUseCase: MergeDayAudioUseCase
    private lateinit var transcribeUseCase: TranscribeDayUseCase
    private lateinit var summarizeUseCase: SummarizeDayUseCase
    private lateinit var useCase: SettlePendingDaysUseCase

    private lateinit var audioDir: java.io.File

    @Before
    fun setUp() {
        audioDir = tempFolder.newFolder("audio")
        recordingRepo = FakeRecordingRepository()
        transcriptionRepo = FakeTranscriptionRepository(recordingRepo)
        summaryRepo = FakeSummaryRepository()
        asr = FakeAsrEngine()
        val pathProvider = FakeAudioPathProvider(audioDir)
        // 存储位置 / 设置用 fake（默认 storageTreeUri = null → 不触发 SAF 复制）
        val settingsRepository = FakeSettingsRepository()
        val storageLocationManager = Mockito.mock(StorageLocationManager::class.java)
        val transcriptExporter = FakeSegmentTranscriptExporter()

        mergeUseCase = MergeDayAudioUseCase(
            recordingRepository = recordingRepo,
            audioPathProvider = pathProvider,
            settingsRepository = settingsRepository,
            storageLocationManager = storageLocationManager,
            ioDispatcher = dispatcher,
        )
        transcribeUseCase = TranscribeDayUseCase(
            recordingRepository = recordingRepo,
            transcriptionRepository = transcriptionRepo,
            asrEngine = asr,
            transcriptExporter = transcriptExporter,
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
        val modelManager = Mockito.mock(ModelManager::class.java)
        Mockito.`when`(modelManager.isLlmReady()).thenReturn(false) // 强制规则兜底路径
        val qwenSummarizer = Mockito.mock(QwenSummarizer::class.java)
        summarizeUseCase = SummarizeDayUseCase(
            transcriptionRepository = transcriptionRepo,
            recordingRepository = recordingRepo,
            summaryRepository = summaryRepo,
            qwenSummarizer = qwenSummarizer,
            ruleBasedSummarizer = RuleBasedSummarizer(dispatcher),
            modelManager = modelManager,
            ioDispatcher = dispatcher,
        )
        useCase = SettlePendingDaysUseCase(
            recordingRepository = recordingRepo,
            mergeDayAudioUseCase = mergeUseCase,
            transcribeDayUseCase = transcribeUseCase,
            summarizeDayUseCase = summarizeUseCase,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `无待处理天时返回成功且结算数为0`() = runTest {
        val result = useCase()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `SETTLING天完整推进到DONE进度100`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        val seg1 = seedSegment(DAY, startMs = 0L, durationMs = 1000L)
        val seg2 = seedSegment(DAY, startMs = 5000L, durationMs = 2000L)
        asr.resultFor(seg1.filePath, listOf(TimedText(0L, 500L, "第一段话"), TimedText(500L, 1000L, "第二段话")))
        asr.resultFor(seg2.filePath, listOf(TimedText(0L, 2000L, "另一段话")))

        val result = useCase()

        assertTrue("应成功：${result.failureOrNull()?.message}", result.isSuccess)
        assertEquals(1, result.getOrNull())

        val day = recordingRepo.getDay(DAY)!!
        assertEquals(DayState.DONE, day.state)
        assertEquals(100, day.settleProgress)
        assertNotNull(day.mergedAudioPath)
        assertEquals(3000L, day.totalRecordMs)

        // 转写落库，时间叠加为全天绝对时间
        val transcripts = transcriptionRepo.transcriptsOf(DAY)
        assertEquals(3, transcripts.size)
        assertTrue(transcripts.any { it.startMs == 0L && it.text == "第一段话" })
        assertTrue(transcripts.any { it.startMs == 500L && it.text == "第二段话" })
        assertTrue(transcripts.any { it.startMs == 5000L && it.text == "另一段话" })

        // 片段全部 DONE
        assertTrue(recordingRepo.segmentsOf(DAY).all { it.transcriptStatus == TranscriptStatus.DONE })

        // 总结落库（模型缺失 → 规则兜底，FR-011 永不空白）
        val summary = summaryRepo.getSummary(DAY)
        assertNotNull(summary)
        assertTrue(summary!!.summaryText.isNotBlank())
        assertEquals("rule-based", summary.modelName)
    }

    @Test
    fun `DONE天有未转写片段时补转写且不重复合并`() = runTest {
        val mergedFile = java.io.File(audioDir, "day_$DAY.wav")
        writeWav(mergedFile, sampleCount = 16000)
        recordingRepo.seedDay(
            RecordingDay(
                dayId = DAY,
                state = DayState.DONE,
                totalRecordMs = 1000L,
                mergedAudioPath = mergedFile.absolutePath,
            ),
        )
        val seg = seedSegment(DAY, startMs = 0L, durationMs = 1000L, status = TranscriptStatus.FAILED)
        asr.resultFor(seg.filePath, listOf(TimedText(0L, 1000L, "补转写的内容")))

        val result = useCase()

        assertTrue(result.isSuccess)
        val day = recordingRepo.getDay(DAY)!!
        assertEquals(DayState.DONE, day.state)
        assertEquals(100, day.settleProgress)
        // 已合并过：不重复合并，保留原路径与时长
        assertEquals(mergedFile.absolutePath, day.mergedAudioPath)
        assertEquals(1000L, day.totalRecordMs)
        assertEquals(TranscriptStatus.DONE, recordingRepo.segmentsOf(DAY).single().transcriptStatus)
        assertEquals(1, transcriptionRepo.transcriptsOf(DAY).size)
    }

    @Test
    fun `多天按dayId升序处理且单天失败不影响其他天`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY_B, state = DayState.SETTLING))
        recordingRepo.seedDay(RecordingDay(dayId = DAY_A, state = DayState.SETTLING))
        val segA = seedSegment(DAY_A, startMs = 0L, durationMs = 1000L)
        val segB = seedSegment(DAY_B, startMs = 0L, durationMs = 1000L)
        asr.resultFor(segA.filePath, listOf(TimedText(0L, 1000L, "A天内容")))
        asr.resultFor(segB.filePath, listOf(TimedText(0L, 1000L, "B天内容")))

        val result = useCase()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        // dayId 升序处理
        val settleOrder = recordingRepo.stateChangeLog.filter { it.endsWith("DONE") }
        assertEquals(listOf("$DAY_A->DONE", "$DAY_B->DONE"), settleOrder)
        assertEquals(DayState.DONE, recordingRepo.getDay(DAY_A)!!.state)
        assertEquals(DayState.DONE, recordingRepo.getDay(DAY_B)!!.state)
    }

    @Test
    fun `合并失败的天降级归档为DONE并保留合并失败提示`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        // 片段文件不存在 → MergeDayAudioUseCase 返回 E-5001 失败
        recordingRepo.addSegment(
            AudioSegment(
                segmentId = 0L,
                dayId = DAY,
                filePath = java.io.File(audioDir, "missing.wav").absolutePath,
                startMs = 0L,
                endMs = 1000L,
                durationMs = 1000L,
            ),
        )

        useCase()

        // BUG-11 新契约：合并失败**不再**阻断归档——转写逐片段进行，
        // 完全不依赖合并文件（合并产物只是下载/导出用的副产物）。
        // 原行为（写回 5% + 不归档）会让兜底扫描无限重算同一天，
        // 真机上表现为进度永久停在 5%（用户报告的原始症状）。
        val day = recordingRepo.getDay(DAY)!!
        assertEquals("合并失败应降级归档为 DONE", DayState.DONE, day.state)
        assertEquals("降级归档进度应为 100", 100, day.settleProgress)
        assertNotNull("合并失败提示必须可见（settleError）", day.settleError)
        assertTrue(
            "settleError 应以提示前缀开头（确定性失败，不展示重试按钮），实际：${day.settleError}",
            day.settleError!!.startsWith(com.echolog.app.core.common.Constants.SETTLE_NOTE_PREFIX),
        )
    }

    @Test
    fun `ASR模型缺失导致转写失败的天不得标记为DONE`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        seedSegment(DAY, startMs = 0L, durationMs = 1000L)
        asr.setReady(false) // E-3001

        val result = useCase()

        val day = recordingRepo.getDay(DAY)!!
        assertNotEquals("转写失败不得归档为 DONE", DayState.DONE, day.state)
        assertTrue("转写失败进度不得到 100，实际 ${day.settleProgress}", day.settleProgress < 100)
        assertNotNull("失败原因应落库", day.settleError)
    }

    // ── 工具 ──

    /** 造一个真实 WAV 片段并入库（durationMs 决定样本数）。 */
    private fun seedSegment(
        dayId: String,
        startMs: Long,
        durationMs: Long,
        status: TranscriptStatus = TranscriptStatus.PENDING,
    ): AudioSegment {
        val path = FakeAudioPathProvider(audioDir).segmentPath(dayId, startMs.toInt())
        writeWav(java.io.File(path), sampleCount = (durationMs * 16L).toInt())
        val segment = recordingRepo.addSegment(
            AudioSegment(
                segmentId = 0L,
                dayId = dayId,
                filePath = path,
                startMs = startMs,
                endMs = startMs + durationMs,
                durationMs = durationMs,
                sizeBytes = durationMs * 32L,
                transcriptStatus = status,
            ),
        )
        return segment
    }

    private fun writeWav(file: java.io.File, sampleCount: Int) {
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { raf ->
            com.echolog.app.core.util.WavIo.writePlaceholderHeader(raf)
            raf.write(ByteArray(sampleCount * 2))
            com.echolog.app.core.util.WavIo.patchHeader(raf, sampleCount * 2L, 16000, 1)
        }
    }

    private companion object {
        const val DAY = "2026-10-24"
        const val DAY_A = "2026-10-23"
        const val DAY_B = "2026-10-24"
    }
}
