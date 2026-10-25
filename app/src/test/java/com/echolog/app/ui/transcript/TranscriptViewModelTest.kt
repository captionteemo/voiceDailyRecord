package com.echolog.app.ui.transcript

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.TranscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import com.echolog.app.domain.usecase.RegenerateSummaryUseCase
import com.echolog.app.core.common.EchoResult

/**
 * [TranscriptViewModel] 行为测试（QA：转写全文页 UI 层）。
 *
 * 用**自建局部 fake 仓库**驱动（不依赖 `testing/FakeRepositories.kt`，
 * 避免与正在并行修改该文件的工程师冲突），并真实驱动 ViewModel 的组合流、
 * 播放定位、就地编辑与重新生成总结四条主路径。
 *
 * 播放器不在 ViewModel 内（见 [TranscriptViewModel] KDoc）：测试断言的是
 * 「点击某条 → 计算出的播放路径与文件内偏移」，而不是真实 MediaPlayer。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var transcriptionRepository: FakeTranscriptionRepo
    private lateinit var recordingRepository: FakeRecordingRepo
    private lateinit var regenerateSummaryUseCase: RegenerateSummaryUseCase

    private lateinit var viewModel: TranscriptViewModel

    // ── 时间基准（与定位测试一致：A 占 10:00-10:05，B 占 14:00-14:03） ──

    private val dayId = "2026-10-24"
    private val segAStart = 10L * 60 * 60 * 1000
    private val segBStart = 14L * 60 * 60 * 1000
    private val segADurationMs = 5L * 60 * 1000
    private val segBDurationMs = 3L * 60 * 1000

    private val segmentA = AudioSegment(
        segmentId = 1L,
        dayId = dayId,
        filePath = "/tmp/segA.wav",
        startMs = segAStart,
        endMs = segAStart + segADurationMs,
        durationMs = segADurationMs,
    )

    private val segmentB = AudioSegment(
        segmentId = 2L,
        dayId = dayId,
        filePath = "/tmp/segB.wav",
        startMs = segBStart,
        endMs = segBStart + segBDurationMs,
        durationMs = segBDurationMs,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        transcriptionRepository = FakeTranscriptionRepo()
        recordingRepository = FakeRecordingRepo()
        regenerateSummaryUseCase = mock(RegenerateSummaryUseCase::class.java)

        viewModel = TranscriptViewModel(
            transcriptionRepository = transcriptionRepository,
            recordingRepository = recordingRepository,
            regenerateSummaryUseCase = regenerateSummaryUseCase,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 需求 1：按时间顺序连续阅读 ──

    @Test
    fun `转写按全天绝对时间升序展示`() = runTest {
        // 故意乱序插入，且插入的是「第二个片段的句在前」
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 33L, segmentId = 2L, startMs = segBStart + 120_000L, text = "B 的二句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentB, segmentA))
        recordingRepository.seedDay(RecordingDay(dayId = dayId, state = DayState.DONE))

        viewModel.load(dayId)

        val state = viewModel.uiState.value
        assertEquals(listOf(11L, 22L, 33L), state.lines.map { it.tsId })
        assertEquals(listOf("A 的一句", "B 的一句", "B 的二句"), state.lines.map { it.text })
        assertTrue(state.hasTranscript)
    }

    @Test
    fun `当天没有转写时进入空态`() = runTest {
        recordingRepository.seedSegments(listOf(segmentA))
        recordingRepository.seedDay(RecordingDay(dayId = dayId, state = DayState.DONE))

        viewModel.load(dayId)

        val state = viewModel.uiState.value
        assertFalse(state.hasTranscript)
        assertTrue(state.lines.isEmpty())
    }

    @Test
    fun `load 会透出日级合并音频路径与总结状态`() = runTest {
        val merged = tempFolder.newFile("merged.wav")
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        recordingRepository.seedDay(
            RecordingDay(
                dayId = dayId,
                state = DayState.DONE,
                mergedAudioPath = merged.absolutePath,
                summaryStatus = SummaryStatus.DONE,
            ),
        )

        viewModel.load(dayId)

        val state = viewModel.uiState.value
        assertEquals(merged.absolutePath, state.mergedAudioPath)
        assertEquals(SummaryStatus.DONE, state.summaryStatus)
    }

    // ── 需求 2：点击文字 → 跳到对应声音位置并播放 ──

    @Test
    fun `点击第二个片段的句子时用合并音频与累计偏移`() = runTest {
        val merged = tempFolder.newFile("merged.wav")
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentA, segmentB))
        recordingRepository.seedDay(
            RecordingDay(dayId = dayId, state = DayState.DONE, mergedAudioPath = merged.absolutePath),
        )
        viewModel.load(dayId)

        val target = viewModel.onTranscriptClicked(22L)

        requireNotNull(target)
        assertEquals(TranscriptPlaybackSource.MERGED_AUDIO, target.source)
        assertEquals(merged.absolutePath, target.path)
        // 5 分钟（A 的时长）+ 1 分钟（B 内偏移）= 6 分钟；**不是**绝对时间 14:01
        assertEquals(6L * 60 * 1000, target.offsetMs)
        assertEquals(22L, viewModel.uiState.value.playingTsId)
        assertNull("点击成功不应留下错误提示", viewModel.uiState.value.message)
    }

    @Test
    fun `合并音频缺失时点击回退到片段 WAV 与片段内偏移`() = runTest {
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentA, segmentB))
        recordingRepository.seedDay(RecordingDay(dayId = dayId, state = DayState.DONE))
        viewModel.load(dayId)

        val target = viewModel.onTranscriptClicked(22L)

        requireNotNull(target)
        assertEquals(TranscriptPlaybackSource.SEGMENT_AUDIO, target.source)
        assertEquals("/tmp/segB.wav", target.path)
        assertEquals(60_000L, target.offsetMs)
        assertEquals(22L, viewModel.uiState.value.playingTsId)
    }

    @Test
    fun `合并音频路径非空但文件已删除时回退片段 WAV`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        recordingRepository.seedDay(
            RecordingDay(
                dayId = dayId,
                state = DayState.DONE,
                mergedAudioPath = "/tmp/gone-merged.wav",
            ),
        )
        viewModel.load(dayId)

        val target = viewModel.onTranscriptClicked(11L)

        requireNotNull(target)
        assertEquals(TranscriptPlaybackSource.SEGMENT_AUDIO, target.source)
        assertEquals("/tmp/segA.wav", target.path)
        assertEquals(30_000L, target.offsetMs)
    }

    @Test
    fun `点击不存在的转写返回 null 并给出可见错误提示`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        val target = viewModel.onTranscriptClicked(999L)

        assertNull(target)
        assertNull("无法定位时不得高亮", viewModel.uiState.value.playingTsId)
        assertEquals(
            TranscriptMessage.PlaybackUnavailable,
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun `播放停止后清除高亮`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.onTranscriptClicked(11L)
        viewModel.onPlaybackStopped()

        assertNull(viewModel.uiState.value.playingTsId)
    }

    @Test
    fun `播放失败给出可见错误提示并清除高亮`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.onTranscriptClicked(11L)
        viewModel.onPlaybackFailed()

        assertNull(viewModel.uiState.value.playingTsId)
        assertEquals(
            TranscriptMessage.PlaybackUnavailable,
            viewModel.uiState.value.message,
        )
    }

    // ── 需求 3：就地编辑（直接覆盖原文） ──

    @Test
    fun `保存编辑覆盖原文并退出编辑态`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "原始文字")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.startEditing(11L)
        assertEquals("原始文字", viewModel.uiState.value.editingText)

        viewModel.updateEditingText("改过的文字")
        viewModel.saveEdit()

        val state = viewModel.uiState.value
        assertNull("保存成功应退出编辑态", state.editingTsId)
        assertEquals("", state.editingText)
        assertFalse(state.saving)
        assertEquals(TranscriptMessage.Saved, state.message)

        // 仓库真的被调用，且覆盖的是同一条（tsId=11）
        assertEquals(listOf(11L to "改过的文字"), transcriptionRepository.updateCalls)
        // 流重新发射后 UI 读到新文字
        assertEquals("改过的文字", viewModel.uiState.value.lines.first().text)
    }

    @Test
    fun `保存失败时保留编辑态并给出可见错误提示`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "原始文字")),
        )
        transcriptionRepository.updateResult = 0 // 覆盖 0 行 = 该行已不存在
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.startEditing(11L)
        viewModel.updateEditingText("改过的文字")
        viewModel.saveEdit()

        val state = viewModel.uiState.value
        assertEquals("失败时应留在编辑态，便于用户重试", 11L, state.editingTsId)
        assertEquals("改过的文字", state.editingText)
        assertFalse(state.saving)
        assertEquals(TranscriptMessage.SaveFailed, state.message)
    }

    @Test
    fun `保存时仓库抛异常也保留编辑态并给出错误提示`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "原始文字")),
        )
        transcriptionRepository.updateThrowable = IllegalStateException("数据库炸了")
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.startEditing(11L)
        viewModel.saveEdit()

        val state = viewModel.uiState.value
        assertEquals(11L, state.editingTsId)
        assertFalse(state.saving)
        assertEquals(TranscriptMessage.SaveFailed, state.message)
    }

    @Test
    fun `编辑内容为空白时拒绝保存且不调用仓库`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "原始文字")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.startEditing(11L)
        viewModel.updateEditingText("   ")
        viewModel.saveEdit()

        assertEquals(TranscriptMessage.EditEmpty, viewModel.uiState.value.message)
        assertTrue("空白内容不应写库", transcriptionRepository.updateCalls.isEmpty())
        assertEquals(11L, viewModel.uiState.value.editingTsId)
    }

    @Test
    fun `取消编辑丢弃改动`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "原始文字")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.startEditing(11L)
        viewModel.updateEditingText("改过的文字")
        viewModel.cancelEditing()

        val state = viewModel.uiState.value
        assertNull(state.editingTsId)
        assertEquals("", state.editingText)
        assertEquals("原始文字", state.lines.first().text)
        assertTrue(transcriptionRepository.updateCalls.isEmpty())
    }

    // ── 需求 4：重新生成总结 ──

    @Test
    fun `重新生成总结成功给出成功提示并关闭 loading`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        recordingRepository.seedDay(RecordingDay(dayId = dayId, state = DayState.DONE))
        // suspend operator invoke 必须在协程内存根（Mockito 存根记录在同一线程）
        runBlocking { `when`(regenerateSummaryUseCase(dayId)).thenReturn(EchoResult.success("新总结")) }
        viewModel.load(dayId)

        viewModel.regenerateSummary()

        val state = viewModel.uiState.value
        assertFalse("成功后应关闭 loading", state.regeneratingSummary)
        assertEquals(TranscriptMessage.SummaryRegenerated, state.message)
    }

    @Test
    fun `重新生成总结失败给出可见错误提示与原因`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        recordingRepository.seedDay(RecordingDay(dayId = dayId, state = DayState.DONE))
        runBlocking {
            `when`(regenerateSummaryUseCase(dayId))
                .thenReturn(EchoResult.failure("E-4002", "重新生成总结失败：模型推理失败"))
        }
        viewModel.load(dayId)

        viewModel.regenerateSummary()

        val state = viewModel.uiState.value
        assertFalse(state.regeneratingSummary)
        assertEquals(
            TranscriptMessage.SummaryRegenerateFailed("重新生成总结失败：模型推理失败"),
            state.message,
        )
    }

    @Test
    fun `重新生成总结进行中重复点击被忽略`() = runTest {
        // 守卫「regeneratingSummary 为 true 时直接返回」需要协程**挂起中**才可观测：
        // UnconfinedTestDispatcher 下同步跑完，看不到中间态。本测试换用与
        // testScheduler 共享的 StandardTestDispatcher，让 launch 排队不执行，
        // 从而确定性地验证：loading 中再次点击不会重复触发内层用例。
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            transcriptionRepository.seed(
                listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
            )
            recordingRepository.seedSegments(listOf(segmentA))
            recordingRepository.seedDay(RecordingDay(dayId = dayId, state = DayState.DONE))
            runBlocking {
                `when`(regenerateSummaryUseCase(dayId)).thenReturn(EchoResult.success("新总结"))
            }

            // viewModelScope 在首次 launch 时才捕获 Dispatchers.Main.immediate，
            // 此时已是 StandardTestDispatcher：launch 排队而不立即执行
            val vm = TranscriptViewModel(
                transcriptionRepository = transcriptionRepository,
                recordingRepository = recordingRepository,
                regenerateSummaryUseCase = regenerateSummaryUseCase,
            )
            vm.load(dayId)
            advanceUntilIdle()
            assertTrue("数据加载后应有转写行", vm.uiState.value.lines.isNotEmpty())

            vm.regenerateSummary()
            assertTrue("第一次点击后应进入 loading 态", vm.uiState.value.regeneratingSummary)

            // loading 中再次点击：被守卫忽略，不会重复触发内层用例
            vm.regenerateSummary()
            advanceUntilIdle()

            runBlocking {
                Mockito.verify(regenerateSummaryUseCase, times(1))(dayId)
            }
            val state = vm.uiState.value
            assertFalse("放行后应退出 loading 态", state.regeneratingSummary)
            assertEquals(TranscriptMessage.SummaryRegenerated, state.message)
        } finally {
            // 还原类级 unconfined，tearDown 的 resetMain 才能配平
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun `dayId 为空时重新生成总结不会调用用例`() = runTest {
        viewModel.regenerateSummary()

        assertFalse(viewModel.uiState.value.regeneratingSummary)
        assertNull(viewModel.uiState.value.message)
    }

    // ── 需求 5：复制全部 / 复制所选 ──

    @Test
    fun `复制全部返回带时间戳的全量文本`() = runTest {
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentA, segmentB))
        viewModel.load(dayId)

        val text = viewModel.copyAll()
        assertEquals(TranscriptCopyFormatter.formatAll(viewModel.uiState.value.lines), text)
        assertTrue(text!!.contains("A 的一句"))
        assertTrue(text.contains("B 的一句"))
    }

    @Test
    fun `复制所选只含勾选内容`() = runTest {
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentA, segmentB))
        viewModel.load(dayId)

        viewModel.onTranscriptLongPressed(22L)

        val text = viewModel.copySelected()
        assertNotNull(text)
        assertFalse(text!!.contains("A 的一句"))
        assertTrue(text.contains("B 的一句"))
    }

    // ── 复制成功反馈（静默失败红线：复制成功必须有可见提示） ──

    @Test
    fun `复制全部成功给出 CopySucceeded 提示且条数为全部行数`() = runTest {
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentA, segmentB))
        viewModel.load(dayId)

        assertNotNull("有内容时复制全部应返回文本", viewModel.copyAll())
        assertEquals(
            TranscriptMessage.CopySucceeded(count = 2),
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun `复制所选成功给出 CopySucceeded 提示且条数为实际勾选数`() = runTest {
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentA, segmentB))
        viewModel.load(dayId)
        viewModel.onTranscriptLongPressed(11L)
        viewModel.toggleSelection(22L)
        viewModel.toggleSelection(11L) // 取消一条，只剩 22

        assertNotNull(viewModel.copySelected())
        assertEquals(
            "条数应为实际复制的条数（1），不是勾选集合历史大小",
            TranscriptMessage.CopySucceeded(count = 1),
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun `空态复制全部返回 null 且不产生提示`() = runTest {
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        assertNull("空态复制全部应返回 null", viewModel.copyAll())
        assertNull("空态不应产生任何提示", viewModel.uiState.value.message)
        assertNull("未勾选时复制所选应返回 null", viewModel.copySelected())
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `未勾选时复制所选返回 null 且不产生提示`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        // 先复制全部成功并消费提示，再验证「未勾选 → 无提示」
        assertNotNull(viewModel.copyAll())
        viewModel.consumeMessage()

        assertNull(viewModel.copySelected())
        assertNull("未勾选不应产生提示", viewModel.uiState.value.message)
    }

    // ── 选择模式 ──

    @Test
    fun `长按进入选择模式并勾选该条`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.onTranscriptLongPressed(11L)

        val state = viewModel.uiState.value
        assertTrue(state.selectionMode)
        assertEquals(1, state.selectedCount)
        assertTrue(state.isSelected(11L))
    }

    @Test
    fun `勾选可反复切换且退出选择模式清空勾选`() = runTest {
        transcriptionRepository.seed(
            listOf(
                transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句"),
                transcript(tsId = 22L, segmentId = 2L, startMs = segBStart + 60_000L, text = "B 的一句"),
            ),
        )
        recordingRepository.seedSegments(listOf(segmentA, segmentB))
        viewModel.load(dayId)

        viewModel.onTranscriptLongPressed(11L)
        viewModel.toggleSelection(22L)
        assertEquals(2, viewModel.uiState.value.selectedCount)

        viewModel.toggleSelection(11L)
        assertEquals(1, viewModel.uiState.value.selectedCount)

        viewModel.exitSelectionMode()
        val state = viewModel.uiState.value
        assertFalse(state.selectionMode)
        assertEquals(0, state.selectedCount)
    }

    // ── 一次性提示消费 ──

    @Test
    fun `consumeMessage 清空提示`() = runTest {
        transcriptionRepository.seed(
            listOf(transcript(tsId = 11L, segmentId = 1L, startMs = segAStart + 30_000L, text = "A 的一句")),
        )
        recordingRepository.seedSegments(listOf(segmentA))
        viewModel.load(dayId)

        viewModel.onPlaybackFailed()
        viewModel.consumeMessage()

        assertNull(viewModel.uiState.value.message)
    }

    // ── 工具 ──

    private fun transcript(tsId: Long, segmentId: Long, startMs: Long, text: String): TranscriptSegment =
        TranscriptSegment(
            tsId = tsId,
            segmentId = segmentId,
            startMs = startMs,
            endMs = startMs + 5L * 1000,
            text = text,
        )
}

/**
 * 局部 fake：[TranscriptionRepository]（仅实现转写全文页用到的行为）。
 *
 * 故意**不**继承 `testing/FakeRepositories.kt` 的共享 fake——该文件正被工程师
 * 并行修改，避免两边同时改导致冲突。
 */
private class FakeTranscriptionRepo : TranscriptionRepository {

    private val transcripts = MutableStateFlow<List<TranscriptSegment>>(emptyList())

    /** 记录 updateTranscriptText 的调用（断言「真的覆盖了原文」）。 */
    val updateCalls = mutableListOf<Pair<Long, String>>()

    /** updateTranscriptText 的返回值（默认 1 = 覆盖 1 行）。 */
    var updateResult: Int = 1

    /** updateTranscriptText 抛出的异常（默认不抛）。 */
    var updateThrowable: Throwable? = null

    fun seed(items: List<TranscriptSegment>) {
        transcripts.value = items.sortedBy { it.startMs }
    }

    override fun observeTranscriptsForDay(dayId: String): Flow<List<TranscriptSegment>> = transcripts

    override suspend fun getTranscriptsForDay(dayId: String): List<TranscriptSegment> =
        transcripts.value

    override suspend fun upsertTranscripts(segmentId: Long, items: List<TranscriptSegment>) {
        transcripts.update { current ->
            (current.filterNot { it.segmentId == segmentId } + items).sortedBy { it.startMs }
        }
    }

    override suspend fun updateTranscriptText(tsId: Long, text: String): Int {
        updateCalls += tsId to text
        updateThrowable?.let { throw it }
        val existed = transcripts.value.any { it.tsId == tsId }
        if (!existed) return 0
        transcripts.update { list ->
            list.map { if (it.tsId == tsId) it.copy(text = text) else it }
        }
        return updateResult
    }

    override suspend fun updateSegmentTranscriptStatus(
        segmentId: Long,
        status: com.echolog.app.domain.model.TranscriptStatus,
        error: String?,
    ) = Unit

    override suspend fun getPendingSegments(
        dayId: String,
        limit: Int,
    ): List<AudioSegment> = emptyList()

    override suspend fun getAllPendingSegments(limit: Int): List<AudioSegment> = emptyList()

    override suspend fun searchTranscripts(
        keyword: String,
        limit: Int,
    ): List<TranscriptSegment> = transcripts.value.filter { it.text.contains(keyword) }
}

/** 局部 fake：[RecordingRepository]（仅片段流 + 日级流）。 */
private class FakeRecordingRepo : RecordingRepository {

    private val days = MutableStateFlow<RecordingDay?>(null)
    private val segments = MutableStateFlow<List<AudioSegment>>(emptyList())

    fun seedDay(day: RecordingDay) {
        days.value = day
    }

    fun seedSegments(items: List<AudioSegment>) {
        segments.value = items
    }

    override fun observeDays(): Flow<List<RecordingDay>> = days.map { day -> listOfNotNull(day) }

    override fun observeDay(dayId: String): Flow<RecordingDay?> = days

    override fun observeSegmentsForDay(dayId: String): Flow<List<AudioSegment>> = segments

    override suspend fun getDay(dayId: String): RecordingDay? = days.value

    override suspend fun createOrResumeDay(
        dayId: String,
        mode: com.echolog.app.domain.model.RecordingMode,
    ): RecordingDay = throw UnsupportedOperationException()

    override suspend fun updateDayState(dayId: String, state: DayState) = Unit

    override suspend fun updateSettleProgress(
        dayId: String,
        progressPercent: Int,
        error: String?,
    ) = Unit

    override suspend fun updateSummaryStatus(dayId: String, status: SummaryStatus) = Unit

    override suspend fun updateMergeResult(
        dayId: String,
        totalRecordMs: Long,
        mergedAudioPath: String?,
    ) = Unit

    override suspend fun getSegmentsForDay(dayId: String): List<AudioSegment> = segments.value

    override suspend fun insertSegment(dayId: String, startMs: Long, filePath: String): Long = 0L

    override suspend fun finishSegment(segmentId: Long, endMs: Long, sizeBytes: Long) = Unit

    override fun observeMarkersForDay(dayId: String): Flow<List<com.echolog.app.domain.model.Marker>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    override suspend fun getMarkersForDay(dayId: String): List<com.echolog.app.domain.model.Marker> =
        emptyList()

    override suspend fun insertMarker(dayId: String, atMs: Long, note: String): Long = 0L

    override suspend fun deleteMarker(markerId: Long) = Unit

    override suspend fun deleteDay(dayId: String, deleteAudio: Boolean, deleteText: Boolean) = Unit

    override suspend fun deleteAllOriginalAudio(): Int = 0

    override suspend fun clearDayConvertedArtifacts(dayId: String): Int = 0
}
