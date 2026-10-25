package com.echolog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echolog.app.ai.model.ModelKind
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.ai.model.ModelState
import com.echolog.app.domain.model.RecordingSettings
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.ui.model.ModelDownloadScreen
import com.echolog.app.ui.navigation.EchoNavHost
import com.echolog.app.ui.navigation.PrivacyOnboardingScreen
import com.echolog.app.ui.theme.EchoLogTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 单 Activity 入口（架构 1.2/2.1）。
 *
 * 职责：
 * 1. 申请监听态前置权限（RECORD_AUDIO + Android 13+ 的 POST_NOTIFICATIONS）；
 * 2. 首启流门控（PRD FR-014/FR-012）：隐私告知（privacy_ack）→ 模型下载
 *    引导（models_ready，可跳过）→ 主界面（[EchoNavHost] 三 Tab）；
 * 3. 以 Compose 承载界面，导航由 [EchoNavHost] 装配。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var modelManager: ModelManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 授权结果暂不入库；各页面按需在动作时校验 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestCorePermissionsIfNeeded()
        setContent {
            EchoLogTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        settingsFlow = settingsRepository.observeSettings(),
                        modelStatesFlow = modelManager.modelStates,
                        onAcknowledgePrivacy = {
                            settingsRepository.setPrivacyAck(true)
                        },
                        onDisagree = { finish() },
                    )
                }
            }
        }
    }

    /** 录音与通知权限是 FR-001/FR-013 的前置条件；未授权不阻断进入（动作时兜底提示）。 */
    private fun requestCorePermissionsIfNeeded() {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

/**
 * 首启流根容器：隐私告知 → 模型引导 → 主界面。
 *
 * - `settings == null`：DataStore 首帧未到达（留空一瞬，不阻断）；
 * - `!privacyAck`：隐私告知页（不同意即退出，FR-014）；
 * - `!modelsReady`：模型下载引导页（本次会话可跳过；主界面仍有提示卡）；
 * - 否则进入三 Tab 主界面。
 *
 * @param settingsFlow 设置流（privacyAck / modelsReady 判定）。
 * @param modelStatesFlow 模型状态流（空 map 视为未就绪）。
 * @param onAcknowledgePrivacy 写 privacy_ack（suspend，调用方协程内执行）。
 * @param onDisagree 退出应用。
 */
@Composable
private fun AppRoot(
    settingsFlow: Flow<RecordingSettings>,
    modelStatesFlow: StateFlow<Map<ModelKind, ModelState>>,
    onAcknowledgePrivacy: suspend () -> Unit,
    onDisagree: () -> Unit,
) {
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val modelStates by modelStatesFlow.collectAsStateWithLifecycle()
    val modelsReady = modelStates.isNotEmpty() &&
        modelStates.values.all { it == ModelState.READY }
    var skipModelGuide by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (settings?.privacyAck) {
        null -> Unit // 设置读取中：留空一瞬

        false -> PrivacyOnboardingScreen(
            onAcknowledge = { scope.launch { onAcknowledgePrivacy() } },
            onDisagree = onDisagree,
        )

        true -> if (!modelsReady && !skipModelGuide) {
            ModelDownloadScreen(
                onBack = null,
                onSkip = { skipModelGuide = true },
            )
        } else {
            EchoNavHost()
        }
    }
}
