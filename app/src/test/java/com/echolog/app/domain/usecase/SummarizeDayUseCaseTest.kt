package com.echolog.app.domain.usecase

import com.echolog.app.ai.llm.QwenSummarizer
import com.echolog.app.ai.llm.RuleBasedSummarizer
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.model.TodoType
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.repository.SummaryRepository
import com.echolog.app.testing.FakeRecordingRepository
import com.echolog.app.testing.FakeSummaryRepository
import com.echolog.app.testing.FakeTranscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * [SummarizeDayUseCase] 单元测试（架构 2.4/4.2，FR-011；BUG-10 与 force 语义分叉）。
 *
 * 锁定契约：
 * 1. `force = false`（默认）且已有非空总结 → **跳过**：不重新生成、不覆盖旧总结、
 *    不调 `deleteSummary`、旧待办勾选态保留（BUG-10 修复初衷：兜底扫描
 *    （[SettlePendingDaysUseCase] / `DaySettleWorker`）重复结算 DONE 天时，
 *    不得用新总结覆盖旧总结并重置用户已勾选的待办）；
 * 2. `force = true` → **先 deleteSummary 再生成**：旧总结与旧待办清空，
 *    按当前转写文字重算（转写全文页「重新生成总结」经 [RegenerateSummaryUseCase]
 *    以 `force = true` 调用，是用户显式重算入口）；
 * 3. `force = false` 且当天无总结 → 默认路径正常生成落库（回归保护）；
 * 4. 当天无转写内容 → E-4003 确定性失败（BUG-11：片段已 DONE，重试无用，
 *    不落库、不删数据）。
 *
 * **跳过分支的返回语义**（与生成路径对齐，非总结正文）：`force = false` 命中
 * BUG-10 跳过时返回 `EchoResult.success(existing.modelName)`——即**旧总结的
 * 引擎标识**，与生成路径的 `EchoResult.success(summary.modelName)` 一致。
 * 本用例的成功载荷历来是「使用的引擎标识」（见 invoke 的 KDoc），
 * 总结正文请读 [com.echolog.app.domain.repository.SummaryRepository.getSummary]。
 *
 * 装配同 [SettlePendingDaysUseCaseTest]：真实用例 + Mockito（inline mock maker）
 * mock `ModelManager`（`isLlmReady = false` → 规则兜底路径）+ 共享内存 fake 仓库；
 * `SummaryRepository` 外包一层委托代理记录写操作次序（同
 * `DaySettleWorkerSettlePolicyTest` 的 ProgressRecordingRepository 模式）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummarizeDayUseCaseTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val dayId = "2026-10-24"

    private lateinit var recordingRepo: FakeRecordingRepository
    private lateinit var transcriptionRepo: FakeTranscriptionRepository
    private lateinit var summaryRepo: FakeSummaryRepository

    /** 记录写操作次序的包装（其余行为委托 [summaryRepo]）。 */
    private lateinit var summaryRepository: RecordingSummaryRepository

    private lateinit var useCase: SummarizeDayUseCase

    @Before
    fun setUp() {
        recordingRepo = FakeRecordingRepository()
        transcriptionRepo = FakeTranscriptionRepository(recordingRepo)
        summaryRepo = FakeSummaryRepository()
        summaryRepository = RecordingSummaryRepository(summaryRepo)
        // 与 SettlePendingDaysUseCaseTest 同款装配：LLM 未就绪 → 规则兜底路径
        val modelManager = Mockito.mock(ModelManager::class.java)
        Mockito.`when`(modelManager.isLlmReady()).thenReturn(false)
        val qwenSummarizer = Mockito.mock(QwenSummarizer::class.java)
        useCase = SummarizeDayUseCase(
            transcriptionRepository = transcriptionRepo,
            recordingRepository = recordingRepo,
            summaryRepository = summaryRepository,
            qwenSummarizer = qwenSummarizer,
            ruleBasedSummarizer = RuleBasedSummarizer(dispatcher),
            modelManager = modelManager,
            ioDispatcher = dispatcher,
        )
    }

    // ── 1. force = false：BUG-10 跳过保护（兜底扫描不得覆盖用户数据） ──

    @Test
    fun `force=false 时已有非空总结直接跳过且保留旧总结与旧待办勾选态`() = runTest {
        seedDay(SummaryStatus.DONE)
        // 旧总结的引擎标识故意用 Qwen（规则兜底只会产出 "rule-based"）：
        // 若返回值是该标识，证明返回的是**旧**数据而非重新生成的结果
        seedExistingSummary(
            summaryText = "旧总结正文",
            modelName = MODEL_NAME_QWEN,
            todoContent = "旧待办",
            todoDone = true,
        )
        // 预置可总结的转写内容：证明「跳过」是 BUG-10 保护生效，而非当天没内容
        seedTranscripts("记得安排会议")
        val saveCountBefore = summaryRepo.saveCount

        val result = useCase(dayId) // force 默认 false

        assertTrue("已有非空总结时应直接返回成功", result is EchoResult.Success)
        // 旧总结原文保留，未被重新生成覆盖
        val summary = summaryRepo.getSummary(dayId)!!
        assertEquals("旧总结正文", summary.summaryText)
        assertEquals(MODEL_NAME_QWEN, summary.modelName)
        assertEquals("不得再写一次总结", saveCountBefore, summaryRepo.saveCount)
        // 旧待办勾选态不重置（BUG-10 的用户体感痛点：「我勾的待办又回来了」）
        val todos = summaryRepo.todosOf(dayId)
        assertEquals(1, todos.size)
        assertEquals("旧待办", todos.single().content)
        assertTrue("旧待办勾选态必须保留", todos.single().done)
        // 非 force 路径绝不删除旧总结
        assertEquals(0, summaryRepository.deleteCount)
        // 跳过分支返回**旧总结的引擎标识**（与生成路径的成功载荷一致，
        // 见 invoke KDoc「携带使用的引擎标识」；不是总结正文）
        assertEquals(MODEL_NAME_QWEN, (result as EchoResult.Success).data)
    }

    @Test
    fun `force=false 时已有总结但正文空白则重新生成`() = runTest {
        seedDay(SummaryStatus.FAILED)
        // 兜底扫描曾写入空白总结（异常降级路径）：不得被 BUG-10 保护永久卡住
        summaryRepo.saveSummary(
            DaySummary(dayId = dayId, status = SummaryRunStatus.FAILED, summaryText = "   "),
            emptyList(),
        )
        seedTranscripts("记得安排会议")

        val result = useCase(dayId)

        assertTrue(result is EchoResult.Success)
        val summary = summaryRepo.getSummary(dayId)!!
        assertTrue("空白总结必须被真正重算覆盖", summary.summaryText.isNotBlank())
        // 预置 1 次 + 本次重算 1 次
        assertEquals(2, summaryRepo.saveCount)
        // 非 force 路径不删旧总结（空白总结被 saveSummary 直接覆盖）
        assertEquals(0, summaryRepository.deleteCount)
    }

    // ── 2. force = false 默认路径：无总结时正常生成（回归保护） ──

    @Test
    fun `force=false 时当天无总结则正常生成并落库`() = runTest {
        seedDay(SummaryStatus.NONE)
        seedTranscripts("记得安排会议")

        val result = useCase(dayId)

        assertTrue(result is EchoResult.Success)
        val summary = summaryRepo.getSummary(dayId)!!
        assertTrue("总结正文不得空白（FR-011 永不空白）", summary.summaryText.isNotBlank())
        assertEquals(SummaryRunStatus.DONE, summary.status)
        // 模型未就绪 → 规则兜底引擎标识
        assertEquals("rule-based", summary.modelName)
        assertEquals("rule-based", (result as EchoResult.Success).data)
        // 规则兜底按关键词提取待办
        assertTrue(summaryRepo.todosOf(dayId).any { it.content == "记得安排会议" })
        // 日级总结状态置 DONE
        assertEquals(SummaryStatus.DONE, recordingRepo.getDay(dayId)?.summaryStatus)
        // 非 force 路径不调 deleteSummary（无旧总结也不调）
        assertEquals(0, summaryRepository.deleteCount)
    }

    // ── 3. force = true：先删后建（用户显式重算，旧结果作废） ──

    @Test
    fun `force=true 时先删除旧总结与旧待办再按当前文字重算`() = runTest {
        seedDay(SummaryStatus.DONE)
        seedExistingSummary(
            summaryText = "旧总结正文",
            modelName = MODEL_NAME_QWEN,
            todoContent = "旧待办",
            todoDone = true,
        )
        seedTranscripts("记得安排会议")

        val result = useCase(dayId, force = true)

        assertTrue(result is EchoResult.Success)
        // 先 delete 再 save：次序锁定（先清旧总结与旧待办，再落新总结）
        assertEquals(listOf("delete", "save"), summaryRepository.callLog)
        // 旧总结被新总结替换
        val summary = summaryRepo.getSummary(dayId)!!
        assertNotEquals("旧总结必须被新总结替换", "旧总结正文", summary.summaryText)
        assertEquals("rule-based", summary.modelName)
        // 旧待办（已勾选）随 deleteSummary 清空；新待办按当前文字重算且不继承勾选态
        val todos = summaryRepo.todosOf(dayId)
        assertTrue("旧待办必须被清除", todos.none { it.content == "旧待办" })
        assertTrue("应按当前文字重算出新待办", todos.any { it.content == "记得安排会议" })
        assertTrue("新待办不得继承旧勾选态", todos.none { it.done })
        // 日级状态置 DONE
        assertEquals(SummaryStatus.DONE, recordingRepo.getDay(dayId)?.summaryStatus)
        assertEquals("rule-based", (result as EchoResult.Success).data)
    }

    @Test
    fun `force=true 时无既有总结也能正常生成`() = runTest {
        // 上一次重算失败没留下总结（或全新的一天）：delete 空总结是无害操作，
        // 仍须走完整生成流程（防止「先删后建」被实现成「有旧总结才生成」）
        seedDay(SummaryStatus.FAILED)
        seedTranscripts("记得安排会议")

        val result = useCase(dayId, force = true)

        assertTrue(result is EchoResult.Success)
        assertEquals(listOf("delete", "save"), summaryRepository.callLog)
        val summary = summaryRepo.getSummary(dayId)!!
        assertTrue(summary.summaryText.isNotBlank())
        assertEquals(SummaryStatus.DONE, recordingRepo.getDay(dayId)?.summaryStatus)
    }

    // ── 4. 无转写内容：E-4003 确定性失败（BUG-11，不得 retry） ──

    @Test
    fun `force=false 时当天无转写内容返回 E-4003 且不落库不删数据`() = runTest {
        seedDay(SummaryStatus.PENDING)
        // 不预置任何转写：片段已全部 DONE 却没有任何文字的确定性终态

        val result = useCase(dayId)

        assertTrue("无转写内容应返回失败，实际：$result", result is EchoResult.Failure)
        val failure = result as EchoResult.Failure
        assertEquals(Constants.ERROR_SUMMARY_NO_CONTENT, failure.code)
        assertEquals("当天无转写内容，无法生成总结", failure.message)
        // 终态软失败：不写总结、不删任何东西、不碰日级状态
        assertEquals(0, summaryRepo.saveCount)
        assertEquals(0, summaryRepository.deleteCount)
        assertEquals(SummaryStatus.PENDING, recordingRepo.getDay(dayId)?.summaryStatus)
    }

    @Test
    fun `force=true 时当天无转写内容返回 E-4003 且旧总结已被清空`() = runTest {
        seedDay(SummaryStatus.DONE)
        seedExistingSummary(
            summaryText = "旧总结正文",
            modelName = MODEL_NAME_QWEN,
            todoContent = "旧待办",
            todoDone = true,
        )
        // 不预置任何转写 → 确定性失败

        val result = useCase(dayId, force = true)

        assertTrue(result is EchoResult.Failure)
        assertEquals(
            Constants.ERROR_SUMMARY_NO_CONTENT,
            (result as EchoResult.Failure).code,
        )
        // 文档化取舍（见 invoke 的 KDoc「代价（有意取舍）」）：
        // force = true 先删旧总结，失败后旧总结不可恢复——这是「显式重新生成」的
        // 既定语义，失败原因经返回值交 UI 呈现，用户可再次点击重试
        assertNull(summaryRepo.getSummary(dayId))
        assertTrue(summaryRepo.todosOf(dayId).isEmpty())
        assertEquals(1, summaryRepository.deleteCount)
        // 日级 FAILED 由外层 RegenerateSummaryUseCase 置位；本用例失败路径不碰日级状态
        assertEquals(SummaryStatus.DONE, recordingRepo.getDay(dayId)?.summaryStatus)
    }

    // ── 工具 ──

    private fun seedDay(status: SummaryStatus) {
        recordingRepo.seedDay(
            RecordingDay(dayId = dayId, state = DayState.DONE, summaryStatus = status),
        )
    }

    /** 预置「已有非空总结 + 已勾选旧待办」（模拟上一轮生成的成果）。 */
    private suspend fun seedExistingSummary(
        summaryText: String,
        modelName: String,
        todoContent: String,
        todoDone: Boolean,
    ) {
        summaryRepo.saveSummary(
            DaySummary(
                dayId = dayId,
                status = SummaryRunStatus.DONE,
                summaryText = summaryText,
                modelName = modelName,
            ),
            listOf(
                TodoItem(
                    todoId = 1L,
                    dayId = dayId,
                    type = TodoType.TODO,
                    content = todoContent,
                    sourceMs = 0L,
                    done = todoDone,
                ),
            ),
        )
    }

    /** 造一段含待办关键词的转写（规则兜底可稳定产出摘要与待办）。 */
    private suspend fun seedTranscripts(text: String) {
        val segment = recordingRepo.addSegment(
            AudioSegment(
                segmentId = 0L,
                dayId = dayId,
                filePath = "/tmp/seg_$dayId.wav",
                startMs = 0L,
                endMs = 1000L,
                durationMs = 1000L,
            ),
        )
        transcriptionRepo.upsertTranscripts(
            segment.segmentId,
            listOf(
                TranscriptSegment(
                    segmentId = segment.segmentId,
                    startMs = 0L,
                    endMs = 1000L,
                    text = text,
                ),
            ),
        )
    }

    private companion object {
        /**
         * 端侧 LLM 引擎标识（与规则兜底的 "rule-based" 不同）。
         * 用于在测试里识别「旧总结的标识」——LLM 未就绪时重新生成只会产出
         * "rule-based"，若返回值是该常量则证明返回的是旧数据。
         */
        const val MODEL_NAME_QWEN = "qwen2.5-1.5b-instruct-q4"
    }
}

/**
 * 记录 [SummaryRepository] 写操作次序的小包装（委托代理模式，
 * 同 `DaySettleWorkerSettlePolicyTest` 的 ProgressRecordingRepository）。
 *
 * 用于锁定 force 语义的调用次序：`force = true` 必须先 [deleteSummary]
 * （清旧总结与旧待办）再 [saveSummary]（落新总结）。
 */
private class RecordingSummaryRepository(
    private val delegate: FakeSummaryRepository,
) : SummaryRepository by delegate {

    /** 写操作流水（`delete` / `save`），按调用顺序记录。 */
    val callLog = mutableListOf<String>()

    /** deleteSummary 调用次数（`force = false` 时必须为 0）。 */
    var deleteCount = 0
        private set

    override suspend fun deleteSummary(dayId: String) {
        deleteCount++
        callLog += "delete"
        delegate.deleteSummary(dayId)
    }

    override suspend fun saveSummary(summary: DaySummary, todos: List<TodoItem>) {
        callLog += "save"
        delegate.saveSummary(summary, todos)
    }
}
