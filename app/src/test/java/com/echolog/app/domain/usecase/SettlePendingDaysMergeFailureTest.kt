package com.echolog.app.domain.usecase

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
import com.echolog.app.testing.FakeAsrEngine
import com.echolog.app.testing.FakeAudioPathProvider
import com.echolog.app.testing.FakeRecordingRepository
import com.echolog.app.testing.FakeSegmentTranscriptExporter
import com.echolog.app.testing.FakeSettingsRepository
import com.echolog.app.testing.FakeSummaryRepository
import com.echolog.app.testing.FakeTranscriptionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.io.RandomAccessFile

/**
 * [SettlePendingDaysUseCase]「合并失败不阻断转写」回归测试（BUG-11 修复验证）。
 *
 * 用户报告的原始 Bug：**「实际安装后，尝试进行录音，结束录音后进度会卡在 5%，
 * 无法翻译成文字」**。兜底结算扫描（[SettlePendingDaysUseCase]）在合并失败时的
 * 行为，决定了当天能否走出「合并」这一步：
 *
 * - **旧行为**：合并失败 → 写回 5% + `return false` → 兜底扫描下次再来一遍，
 *   无限循环 → 真机永久卡在 5%，文字永远出不来；
 * - **新行为（BUG-11 修复 6）**：合并失败 → 写 15% + 合并失败提示 →
 *   **继续转写与总结** → 归档 DONE/100，`settleError` 保留「合并」提示
 *   （用户知道合并产物缺失，但文字已经拿到）。
 *
 * 转写是**逐片段**进行的（[TranscribeDayUseCase] → `getPendingSegments`），
 * 完全不依赖全天合并文件——合并文件只是给用户下载/导出的产物。
 *
 * 覆盖场景：
 * 1. 合并失败（片段文件是 44 字节全零占位头，`durationMs > 0`）
 *    → 转写仍被调用并成功 → DONE/100 + `settleError` 为
 *    「提示：合并音频失败，转写已完成」；
 * 2. 反例：当天所有片段 `durationMs == 0` → 空录音日归档 →
 *    进度 100、`settleError` 为「当天无录音片段」，**不得**出现「合并」字样；
 * 3. 合并失败 **且** 转写也失败（ASR 模型未就绪 E-3001）→
 *    进度必须**停在 15**（下界 ≥15、上界 ≤15，绝不能被写成 100），
 *    当天保持 SETTLING 等待下次兜底重试。
 *
 * 说明：本文件不修改共享的 `FakeRepositories.kt`，而是用本文件内的委托代理
 * [ProgressSpyRepository] 记录进度写入序列，避免与主理人/QA 的并行修改冲突。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettlePendingDaysMergeFailureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var recordingRepo: FakeRecordingRepository
    private lateinit var spy: ProgressSpyRepository
    private lateinit var transcriptionRepo: FakeTranscriptionRepository
    private lateinit var summaryRepo: FakeSummaryRepository
    private lateinit var asr: FakeAsrEngine
    private lateinit var audioDir: File

    @Before
    fun setUp() {
        audioDir = tempFolder.newFolder("audio")
        recordingRepo = FakeRecordingRepository()
        spy = ProgressSpyRepository(recordingRepo)
        transcriptionRepo = FakeTranscriptionRepository(recordingRepo)
        summaryRepo = FakeSummaryRepository()
        asr = FakeAsrEngine()
    }

    // ── 核心契约：合并失败不阻断转写 ──

    @Test
    fun `合并失败时转写照常进行并归档DONE进度100`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        // 片段文件是 44 字节全零占位头（进程被杀，头部未回填）→ 合并必然失败；
        // 但 durationMs > 0 → 不走「当天无有效片段 → 空录音日」分支
        val seg = seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.CORRUPT)
        asr.resultFor(seg.filePath, listOf(TimedText(0L, 1000L, "合并失败也要转写的内容")))

        val useCase = newSettleUseCase(UnconfinedTestDispatcher(testScheduler))
        val result = useCase()

        assertTrue("结算应成功：${result.failureOrNull()?.message}", result.isSuccess)
        assertEquals("应结算 1 天", 1, result.getOrNull())

        // 转写必须真的执行了
        assertTrue("ASR 必须被调用，实际 ${asr.transcribeCalls}", asr.transcribeCalls.isNotEmpty())
        assertTrue(asr.transcribeCalls.contains(seg.filePath))
        assertEquals("转写结果应落库", 1, transcriptionRepo.transcriptsOf(DAY).size)
        assertEquals(
            "片段转写态应为 DONE",
            TranscriptStatus.DONE,
            recordingRepo.segmentsOf(DAY).single().transcriptStatus,
        )

        // 归档语义
        val day = recordingRepo.getDay(DAY)!!
        assertEquals("当天应归档为 DONE", DayState.DONE, day.state)
        assertEquals("结算进度应走到 100，实际 ${day.settleProgress}", 100, day.settleProgress)
        assertNotNull("合并失败提示必须可见（settleError）", day.settleError)
        // 新契约：合并失败归档提示带「提示：」前缀（UI 据此区分提示与错误，
        // 提示不展示「重试结算」按钮——合并失败是确定性的，重试仍会失败）
        assertTrue(
            "settleError 应以「${Constants.SETTLE_NOTE_PREFIX}」开头，实际：${day.settleError}",
            day.settleError!!.startsWith(Constants.SETTLE_NOTE_PREFIX),
        )
        assertTrue(
            "settleError 应说明合并失败，实际：${day.settleError}",
            day.settleError!!.contains("合并"),
        )

        // 进度序列：合并失败后应立刻推进到 15（而不是回退/停留在低进度）
        val writes = spy.progressWrites
        assertTrue("应有多次进度写入，实际：$writes", writes.size >= 3)
        val mergeHintWrite = writes.firstOrNull { it.first == 15 }
        assertNotNull("合并失败后应写入 15%，实际：$writes", mergeHintWrite)
        assertTrue(
            "15% 的写入应携带合并失败提示，实际：$mergeHintWrite",
            mergeHintWrite!!.second?.contains("合并") == true,
        )
        // 进度不得回退（真机症状就是进度往回掉）
        writes.forEachIndexed { index, (percent, _) ->
            if (index == 0) return@forEachIndexed
            assertTrue(
                "进度不得回退：第 $index 次写入 $percent < 上一次 ${writes[index - 1].first}",
                percent >= writes[index - 1].first,
            )
        }
        assertEquals("最终进度必须是 100", 100, writes.last().first)
    }

    // ── 反例对照：当天确实没有有效片段 ──

    @Test
    fun `所有片段均未收尾时按空录音日归档且不出现合并字样`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        // durationMs == 0 → 无有效片段 → 走「空录音日」分支
        seedSegment(durationMs = 0L, fileKind = SegmentFileKind.CORRUPT)

        val useCase = newSettleUseCase(UnconfinedTestDispatcher(testScheduler))
        val result = useCase()

        assertTrue("空录音日应成功归档：${result.failureOrNull()?.message}", result.isSuccess)
        val day = recordingRepo.getDay(DAY)!!
        assertEquals(DayState.DONE, day.state)
        assertEquals(100, day.settleProgress)
        // 用「包含」而非「完全相等」：备注前缀（Constants.SETTLE_NOTE_PREFIX）
        // 后续可能追加，此处只关心「当天无录音片段」这一语义
        assertTrue(
            "应说明当天无录音片段，实际：${day.settleError}",
            day.settleError?.contains(EMPTY_DAY_NOTE) == true,
        )
        assertTrue(
            "空录音日不应出现「合并」字样，实际：${day.settleError}",
            day.settleError?.contains("合并") != true,
        )
        assertTrue("空录音日不应调用 ASR，实际 ${asr.transcribeCalls}", asr.transcribeCalls.isEmpty())
    }

    // ── 转写失败：进度必须停在 15（不得被写成 100） ──

    @Test
    fun `合并失败且转写失败时进度停在15且当天保持SETTLING`() = runTest {
        recordingRepo.seedDay(RecordingDay(dayId = DAY, state = DayState.SETTLING))
        // 合并必失败（损坏占位头），但 durationMs > 0 → 走「合并失败降级」分支
        seedSegment(durationMs = 1000L, fileKind = SegmentFileKind.CORRUPT)
        // ASR 模型未就绪（E-3001）→ 转写直接失败
        asr.setReady(false)

        val useCase = newSettleUseCase(UnconfinedTestDispatcher(testScheduler))
        val result = useCase()

        // 兜底扫描整体不失败（单天失败只影响当天），但当天不计入结算完成数
        assertTrue("兜底扫描本身应成功：${result.failureOrNull()?.message}", result.isSuccess)
        assertEquals("当天未归档，不应计入结算完成数", 0, result.getOrNull())

        val day = recordingRepo.getDay(DAY)!!
        val progress = day.settleProgress
        // 关键断言：进度必须**恰好**停在「合并之后」的 15%。
        // 下界：合并降级路径已写入 15%；上界：转写未完成，绝不能被写成 80/95/100。
        // 只断言下界会漏检「进度被错误写成 100」这类回归。
        assertTrue("进度下界应为 15，实际 $progress", progress >= PROGRESS_AFTER_MERGE)
        assertTrue("进度上界应为 15（不得越过合并阶段），实际 $progress", progress <= PROGRESS_AFTER_MERGE)
        assertEquals("进度应精确停在 15", PROGRESS_AFTER_MERGE, progress)

        // 当天必须保持 SETTLING 等待下次兜底重试（不得假归档）
        assertEquals("转写失败当天应保持 SETTLING", DayState.SETTLING, day.state)

        // 错误信息应说明是转写/模型问题（最后一次写入覆盖了合并提示）
        assertNotNull("应记录转写失败原因", day.settleError)
        assertTrue(
            "settleError 应说明 ASR 模型未就绪，实际：${day.settleError}",
            day.settleError!!.contains("模型"),
        )

        // 整个进度写入序列都不得越过 15
        val writes = spy.progressWrites
        assertTrue("应有进度写入，实际：$writes", writes.isNotEmpty())
        writes.forEachIndexed { index, (percent, _) ->
            assertTrue(
                "第 $index 次写入 $percent 不得越过 15（转写未完成）",
                percent <= PROGRESS_AFTER_MERGE,
            )
        }
        assertEquals("最后一次写入应为 15", PROGRESS_AFTER_MERGE, writes.last().first)
        // 模型未就绪时不得尝试转写
        assertTrue("模型未就绪不应调用 ASR，实际 ${asr.transcribeCalls}", asr.transcribeCalls.isEmpty())
    }

    // ── 工具 ──

    /** 片段音频文件的构造方式。 */
    private enum class SegmentFileKind {
        /** 44 字节全零占位头（模拟进程被杀、头部未回填）→ 合并必然失败。 */
        CORRUPT,

        /** 合法 PCM16 WAV。 */
        VALID,
    }

    /** 造一个片段并入库。 */
    private fun seedSegment(durationMs: Long, fileKind: SegmentFileKind): AudioSegment {
        val path = FakeAudioPathProvider(audioDir).segmentPath(DAY, 1)
        val file = File(path)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (fileKind == SegmentFileKind.CORRUPT) {
                raf.write(ByteArray(PLACEHOLDER_HEADER_BYTES))
            } else {
                WavIo.writePlaceholderHeader(raf)
                raf.write(ByteArray(SAMPLE_COUNT * 2))
                WavIo.patchHeader(raf, SAMPLE_COUNT * 2L, 16000, 1)
            }
        }
        return recordingRepo.addSegment(
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

    /** 组装兜底结算用例（依赖链与生产代码一致）。 */
    private fun newSettleUseCase(dispatcher: CoroutineDispatcher): SettlePendingDaysUseCase {
        val modelManager = Mockito.mock(ModelManager::class.java)
        Mockito.`when`(modelManager.isLlmReady()).thenReturn(false) // 强制规则兜底路径
        return SettlePendingDaysUseCase(
            recordingRepository = spy,
            mergeDayAudioUseCase = MergeDayAudioUseCase(
                recordingRepository = spy,
                audioPathProvider = FakeAudioPathProvider(audioDir),
                settingsRepository = FakeSettingsRepository(),
                storageLocationManager = Mockito.mock(StorageLocationManager::class.java),
                ioDispatcher = dispatcher,
            ),
            transcribeDayUseCase = TranscribeDayUseCase(
                recordingRepository = spy,
                transcriptionRepository = transcriptionRepo,
                asrEngine = asr,
                transcriptExporter = FakeSegmentTranscriptExporter(),
                defaultDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ),
            summarizeDayUseCase = SummarizeDayUseCase(
                transcriptionRepository = transcriptionRepo,
                recordingRepository = spy,
                summaryRepository = summaryRepo,
                qwenSummarizer = Mockito.mock(QwenSummarizer::class.java),
                ruleBasedSummarizer = RuleBasedSummarizer(dispatcher),
                modelManager = modelManager,
                ioDispatcher = dispatcher,
            ),
            ioDispatcher = dispatcher,
        )
    }

    private companion object {
        const val DAY = "2026-10-24"

        /** 空录音日归档备注（与 `SettlePendingDaysUseCase.EMPTY_DAY_NOTE` 一致）。 */
        const val EMPTY_DAY_NOTE = "当天无录音片段"

        /**
         * 「合并完成 / 合并失败降级」后的进度常量（与
         * `SettlePendingDaysUseCase.PROGRESS_AFTER_MERGE` 一致）。
         */
        const val PROGRESS_AFTER_MERGE = 15

        const val SAMPLE_COUNT = 1600
        const val PLACEHOLDER_HEADER_BYTES = 44
    }
}

/**
 * 记录 [RecordingRepository.updateSettleProgress] 写入序列的代理。
 *
 * 用于断言「合并失败后进度继续推进到 15/80/100 且不回退」——
 * 这正是真机「进度卡在 5%」症状的反面。
 */
private class ProgressSpyRepository(
    private val delegate: RecordingRepository,
) : RecordingRepository by delegate {

    private val lock = Any()
    private val writes = mutableListOf<Pair<Int, String?>>()

    /** 结算进度写入序列（按实际写入顺序）。 */
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
