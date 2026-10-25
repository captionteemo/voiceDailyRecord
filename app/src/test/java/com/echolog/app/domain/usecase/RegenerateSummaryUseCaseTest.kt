package com.echolog.app.domain.usecase

import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.EchoResult
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.SummaryRunStatus
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.testing.FakeRecordingRepository
import com.echolog.app.testing.FakeSummaryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * [RegenerateSummaryUseCase] 单元测试（QA：转写全文页「重新生成总结」入口，架构 4.2）。
 *
 * 覆盖转写全文页重新生成总结的关键契约：
 * 1. 以 `force = true` 调用内层 [SummarizeDayUseCase]——跳过 BUG-10 的
 *   「已有非空总结则跳过」保护（否则用户点了按钮没反应）；
 * 2. 成功后**幂等补写**日级 [SummaryStatus.DONE]；
 * 3. 「内层报成功但库里没数据」按失败处理并置 FAILED（状态与数据一致）；
 * 4. 内层失败**原样透传**错误码（E-4002 总结失败 / E-4003 当天无转写内容）；
 * 5. 协程取消原样抛出（结构化并发语义，不吞取消）；
 * 6. dayId 为空白直接失败，不调用内层。
 *
 * 内层用例是 final 类，用 Mockito（5.x inline mock maker）mock；
 * 仓库用共享内存 fake（`testing/FakeRepositories.kt`），真实驱动落库状态回写。
 *
 * 与工程师的 `SummarizeDayUseCase` force 语义测试分工：那边验证 force=true 时
 * **先 deleteSummary 再生成**的内层行为；这里验证**外层包装**的编排与错误语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegenerateSummaryUseCaseTest {

    private val dayId = "2026-10-24"

    private lateinit var summarizeDayUseCase: SummarizeDayUseCase
    private lateinit var recordingRepository: FakeRecordingRepository
    private lateinit var summaryRepository: FakeSummaryRepository
    private lateinit var useCase: RegenerateSummaryUseCase

    @Before
    fun setUp() {
        summarizeDayUseCase = Mockito.mock(SummarizeDayUseCase::class.java)
        recordingRepository = FakeRecordingRepository()
        summaryRepository = FakeSummaryRepository()
        useCase = RegenerateSummaryUseCase(
            summarizeDayUseCase = summarizeDayUseCase,
            recordingRepository = recordingRepository,
            summaryRepository = summaryRepository,
        )
        recordingRepository.seedDay(RecordingDay(dayId = dayId, state = DayState.DONE))
    }

    // ── 1. force = true 跳过 BUG-10 保护 ──

    @Test
    fun `以 force=true 调用内层用例从而跳过已有总结的跳过保护`() = runTest {
        // 预置「已有非空总结 + 日级 DONE」：若内层以 force=false 调用会直接跳过，
        // 用户点「重新生成」将没有反应。这里锁定必须传 force=true。
        summaryRepository.saveSummary(
            DaySummary(
                dayId = dayId,
                status = SummaryRunStatus.DONE,
                summaryText = "旧总结",
                modelName = "rule-based",
            ),
            emptyList(),
        )
        stubInnerSuccess("rule-based")

        val result = useCase(dayId)

        // 内层确实被调用，且第二个参数为 true（跳过 BUG-10 保护）
        runBlocking { Mockito.verify(summarizeDayUseCase)(dayId, true) }
        assertTrue("预置旧总结后仍必须真的重新生成", result is EchoResult.Success)
    }

    // ── 2. 成功后幂等补写 DONE ──

    @Test
    fun `成功后幂等补写日级 DONE 状态并返回新总结正文`() = runTest {
        // 模拟内层生成成功后的落库结果（SummarizeDayUseCase 内部已 saveSummary）
        summaryRepository.saveSummary(
            DaySummary(
                dayId = dayId,
                status = SummaryRunStatus.DONE,
                summaryText = "新总结正文",
                modelName = "rule-based",
            ),
            emptyList(),
        )
        stubInnerSuccess("rule-based")

        val result = useCase(dayId)

        val success = assertIsSuccess(result)
        assertEquals("新总结正文", success)
        // 幂等补写：日级状态必须是 DONE（即使内层已写过，外层也保证这条不变式）
        assertEquals(SummaryStatus.DONE, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    @Test
    fun `成功时补写 DONE 不受预置状态影响`() = runTest {
        // 预置 FAILED（上一次生成失败）：重新生成成功后必须翻回 DONE
        recordingRepository.seedDay(
            RecordingDay(dayId = dayId, state = DayState.DONE, summaryStatus = SummaryStatus.FAILED),
        )
        summaryRepository.saveSummary(
            DaySummary(dayId = dayId, status = SummaryRunStatus.DONE, summaryText = "恢复后的总结"),
            emptyList(),
        )
        stubInnerSuccess("rule-based")

        val result = useCase(dayId)

        assertIsSuccess(result)
        assertEquals(SummaryStatus.DONE, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    // ── 3. 内层报成功但库里没数据 ──

    @Test
    fun `内层报成功但库里没有总结时按失败处理并置 FAILED`() = runTest {
        // 内层说成功，但 summaryRepository 里没有任何总结（数据不一致）
        stubInnerSuccess("rule-based")

        val result = useCase(dayId)

        val failure = assertIsFailure(result)
        assertEquals(Constants.ERROR_SUMMARY_FAILED, failure.code)
        assertTrue("原因应说明总结未落库", failure.message.contains("总结未落库"))
        // 状态必须与数据一致：没有可用总结 → FAILED，而不是留在/写成 DONE
        assertEquals(SummaryStatus.FAILED, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    // ── 4. 内层失败透传错误码 ──

    @Test
    fun `内层失败时原样透传错误码 E-4002 与可读原因`() = runTest {
        stubInnerFailure(Constants.ERROR_SUMMARY_FAILED, "总结失败：模型推理失败")

        val result = useCase(dayId)

        val failure = assertIsFailure(result)
        assertEquals("E-4002", failure.code)
        // 消息只承载领域层原因，「重新生成总结失败」前缀由 UI 层
        // strings.xml（transcript_regenerate_failed）统一拼接
        assertEquals("总结失败：模型推理失败", failure.message)
        assertEquals(SummaryStatus.FAILED, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    @Test
    fun `内层失败时透传确定性无内容错误码 E-4003`() = runTest {
        // E-4003 是确定性失败（当天无转写内容，重试无用），UI 依据错误码决定
        // 不展示「重试」引导——错误码必须原样透传，不得被改写成 E-4002。
        stubInnerFailure(Constants.ERROR_SUMMARY_NO_CONTENT, "当天无转写内容，无法生成总结")

        val result = useCase(dayId)

        val failure = assertIsFailure(result)
        assertEquals("E-4003", failure.code)
        assertEquals(SummaryStatus.FAILED, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    @Test
    fun `内层失败时透传 cause 便于排查`() = runTest {
        val rootCause = IllegalStateException("推理引擎崩了")
        runBlocking {
            Mockito.`when`(summarizeDayUseCase(dayId, true))
                .thenReturn(EchoResult.Failure(Constants.ERROR_SUMMARY_FAILED, "总结失败：x", rootCause))
        }

        val result = useCase(dayId)

        val failure = assertIsFailure(result)
        assertSame("底层 cause 必须原样透传", rootCause, failure.cause)
    }

    // ── 5. 异常与取消语义 ──

    @Test
    fun `内层抛普通异常时不向 UI 抛异常而是转 failure 并置 FAILED`() = runTest {
        // suspend 函数的异常存根用 thenAnswer { throw }（Mockito 对 suspend +
        // thenThrow 的组合在不同版本上行为有差异，thenAnswer 是稳定模式）
        runBlocking {
            Mockito.`when`(summarizeDayUseCase(dayId, true))
                .thenAnswer { throw IllegalStateException("数据库炸了") }
        }

        val result = useCase(dayId)

        val failure = assertIsFailure(result)
        assertEquals(Constants.ERROR_SUMMARY_FAILED, failure.code)
        assertTrue(failure.message.contains("数据库炸了"))
        assertNotNull("cause 应保留以便日志排查", failure.cause)
        assertEquals(SummaryStatus.FAILED, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    @Test
    fun `协程取消原样抛出且不置 FAILED`() = runTest {
        runBlocking {
            Mockito.`when`(summarizeDayUseCase(dayId, true))
                .thenAnswer { throw CancellationException("用户离开页面") }
        }

        // 取消不是业务失败：必须原样抛出，保持结构化并发语义
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { useCase(dayId) }
        }
        assertTrue(thrown.message!!.contains("用户离开页面"))
        // 取消时不得把状态改写成 FAILED（那是业务失败的处置）
        assertEquals(SummaryStatus.NONE, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    // ── 6. 入参防护 ──

    @Test
    fun `dayId 为空白时不调用内层用例直接返回失败`() = runTest {
        val blank = useCase("")
        val whitespace = useCase("   ")

        assertEquals(Constants.ERROR_SUMMARY_FAILED, assertIsFailure(blank).code)
        // 原因不带 UI 前缀（UI 层负责拼「重新生成总结失败：」）
        assertEquals("录音日为空", assertIsFailure(blank).message)
        assertEquals(Constants.ERROR_SUMMARY_FAILED, assertIsFailure(whitespace).code)
        Mockito.verifyNoInteractions(summarizeDayUseCase)
        // 空白 dayId 不应触碰任何仓库写路径
        assertEquals(SummaryStatus.NONE, recordingRepository.getDay(dayId)?.summaryStatus)
    }

    // ── 工具 ──

    private fun stubInnerSuccess(modelName: String) {
        runBlocking {
            Mockito.`when`(summarizeDayUseCase(dayId, true))
                .thenReturn(EchoResult.success(modelName))
        }
    }

    private fun stubInnerFailure(code: String, message: String) {
        runBlocking {
            Mockito.`when`(summarizeDayUseCase(dayId, true))
                .thenReturn(EchoResult.failure(code, message))
        }
    }

    private fun assertIsSuccess(result: EchoResult<String>): String {
        assertTrue("期望成功，实际：$result", result is EchoResult.Success)
        return (result as EchoResult.Success).data
    }

    private fun assertIsFailure(result: EchoResult<String>): EchoResult.Failure {
        assertTrue("期望失败，实际：$result", result is EchoResult.Failure)
        return result as EchoResult.Failure
    }
}
