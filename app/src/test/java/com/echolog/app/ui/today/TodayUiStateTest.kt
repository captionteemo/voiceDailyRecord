package com.echolog.app.ui.today

import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.service.RecordingServiceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TodayUiState] 派生状态单元测试（QA：用户需求 2 暂停/继续状态机与恢复判定）。
 *
 * 不依赖 Compose/Android，仅验证纯派生属性：运行态、暂停态、恢复入口的互斥逻辑。
 */
class TodayUiStateTest {

    private fun state(
        serviceState: RecordingServiceState,
        dayState: DayState = DayState.MONITORING,
    ): TodayUiState = TodayUiState(
        dayId = "2026-10-25",
        serviceState = serviceState,
        dayState = dayState,
    )

    @Test
    fun `监听与录音态属于运行态`() {
        assertTrue(state(RecordingServiceState.MONITORING).isServiceActive)
        assertTrue(state(RecordingServiceState.RECORDING).isServiceActive)
    }

    @Test
    fun `暂停态不属于运行态且标记为已暂停`() {
        val s = state(RecordingServiceState.PAUSED)
        assertFalse(s.isServiceActive)
        assertTrue(s.isPaused)
    }

    @Test
    fun `待机态既不运行也不暂停`() {
        val s = state(RecordingServiceState.IDLE)
        assertFalse(s.isServiceActive)
        assertFalse(s.isPaused)
    }

    @Test
    fun `DB为监听中但服务已死时展示恢复入口`() {
        // 典型：进程被杀后重新打开 App
        val s = state(serviceState = RecordingServiceState.IDLE, dayState = DayState.MONITORING)
        assertTrue(s.needsRecovery)
    }

    @Test
    fun `暂停态不展示恢复入口（暂停是用户主动态非崩溃）`() {
        val s = state(serviceState = RecordingServiceState.PAUSED, dayState = DayState.MONITORING)
        assertFalse(s.needsRecovery)
    }

    @Test
    fun `服务正在运行时无需恢复`() {
        val s = state(serviceState = RecordingServiceState.MONITORING, dayState = DayState.MONITORING)
        assertFalse(s.needsRecovery)
    }

    @Test
    fun `已归档日不触发任何恢复`() {
        val s = state(serviceState = RecordingServiceState.IDLE, dayState = DayState.DONE)
        assertFalse(s.needsRecovery)
        assertTrue(s.isDayDone)
    }

    @Test
    fun `结算态判定依赖DB态且服务已停`() {
        val settling = TodayUiState(
            dayId = "2026-10-25",
            serviceState = RecordingServiceState.IDLE,
            dayState = DayState.SETTLING,
            summaryStatus = SummaryStatus.NONE,
        )
        assertTrue(settling.isSettling)
        val stillRecording = settling.copy(serviceState = RecordingServiceState.RECORDING)
        assertFalse(stillRecording.isSettling)
    }
}
