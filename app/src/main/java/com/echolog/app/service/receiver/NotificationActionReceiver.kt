package com.echolog.app.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.echolog.app.core.common.ApplicationScope
import com.echolog.app.core.common.Constants
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.domain.usecase.AddMarkerUseCase
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.domain.usecase.StopMonitoringAndSettleUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知栏快捷操作接收者（架构 2.3/4.1，FR-005/FR-006）。
 *
 * - `ACTION_ADD_MARKER`：一键打点（RemoteInput 备注可空）；
 * - `ACTION_STOP_SETTLE`：结束当天并进入结算。
 *
 * 广播处理为异步（goAsync + 应用级作用域），目标天经 `EXTRA_DAY_ID` 传递（架构 7.5）。
 *
 * 用户需求 2：通知栏「暂停」经 [ServiceController.pauseMonitoring] 投递
 * `ACTION_PAUSE` 给前台服务（服务已运行，`startService` 合法）。
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var addMarkerUseCase: AddMarkerUseCase

    @Inject
    lateinit var serviceController: ServiceController

    @Inject
    lateinit var stopMonitoringAndSettleUseCase: StopMonitoringAndSettleUseCase

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Constants.ACTION_ADD_MARKER -> handleAddMarker(intent)
            Constants.ACTION_PAUSE -> handlePause(intent)
            Constants.ACTION_STOP_SETTLE -> handleStopSettle(intent)
            Constants.ACTION_MANUAL_TOGGLE -> handleManualToggle(intent)
        }
    }

    private fun handleAddMarker(intent: Intent) {
        val dayId = intent.getStringExtra(Constants.EXTRA_DAY_ID) ?: return
        val note = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Constants.EXTRA_MARKER_NOTE)
            ?.toString()
            ?.trim()
            .orEmpty()
        val pendingResult = goAsync()
        scope.launch(ioDispatcher) {
            try {
                addMarkerUseCase(dayId, note)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handlePause(intent: Intent) {
        val dayId = intent.getStringExtra(Constants.EXTRA_DAY_ID) ?: return
        // 服务正在前台运行：startService 合法；异常时静默（用户可在应用内结束）
        runCatching { serviceController.pauseMonitoring(dayId) }
    }

    private fun handleStopSettle(intent: Intent) {
        val dayId = intent.getStringExtra(Constants.EXTRA_DAY_ID) ?: return
        val pendingResult = goAsync()
        scope.launch(ioDispatcher) {
            try {
                stopMonitoringAndSettleUseCase(dayId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 手动录音开停（用户需求 4 第四种：状态栏录音按键）。
     *
     * 目标态由 `EXTRA_RECORDING_ON` 携带（通知栏按钮总是带显式目标态，
     * 避免重复点击导致反复翻转）。
     */
    private fun handleManualToggle(intent: Intent) {
        val dayId = intent.getStringExtra(Constants.EXTRA_DAY_ID) ?: return
        val target = if (intent.hasExtra(Constants.EXTRA_RECORDING_ON)) {
            intent.getBooleanExtra(Constants.EXTRA_RECORDING_ON, false)
        } else {
            null
        }
        runCatching { serviceController.toggleManualRecording(dayId, target) }
    }
}
