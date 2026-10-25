package com.echolog.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.echolog.app.R
import com.echolog.app.service.RecordingServiceState

/**
 * 呼吸动效大圆形开始/结束按钮（架构 2.5，PRD 4.1）。
 *
 * - 待机态：监听蓝大圆 + 「开始记录」；
 * - 运行态（MONITORING/RECORDING）：录音红大圆 + 双层呼吸光环
 *   （[rememberInfiniteTransition] 驱动 scale/alpha，2s 一个周期）；
 * - 结算中（SETTLING）：按钮禁用并展示结算进度。
 *
 * @param serviceState 服务运行态（决定颜色与文案）。
 * @param settleProgress 结算进度（SETTLING 态展示）。
 * @param enabled 是否可点击（进行中的开启/关闭动作置灰）。
 * @param onClick 点击回调（开启 / 结束结算）。
 */
@Composable
fun BreathingRecordButton(
    serviceState: RecordingServiceState,
    settleProgress: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = serviceState == RecordingServiceState.MONITORING ||
        serviceState == RecordingServiceState.RECORDING
    val settling = serviceState == RecordingServiceState.SETTLING

    val buttonColor = when {
        active -> MaterialTheme.colorScheme.secondary
        settling -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when {
        active -> MaterialTheme.colorScheme.onSecondary
        settling -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimary
    }

    val transition = rememberInfiniteTransition(label = "breathing")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) BREATHING_SCALE_PEAK else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BREATHING_PERIOD_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = if (active) BREATHING_ALPHA_START else 0f,
        targetValue = if (active) BREATHING_ALPHA_END else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BREATHING_PERIOD_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val label = when {
        settling -> stringResource(R.string.today_settling)
        active -> stringResource(R.string.today_stop_settle)
        else -> stringResource(R.string.today_start_record)
    }

    Box(
        modifier = modifier.size(BUTTON_BOX_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        // 呼吸光环：仅运行态可见（内外两层错相扩散）
        if (active) {
            Box(
                modifier = Modifier
                    .size(BUTTON_BOX_SIZE)
                    .scale(pulseScale)
                    .alpha(pulseAlpha)
                    .background(buttonColor.copy(alpha = 0.25f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(BUTTON_BOX_SIZE * 0.82f)
                    .scale(2f - pulseScale)
                    .alpha(pulseAlpha * 0.6f)
                    .background(buttonColor.copy(alpha = 0.2f), CircleShape),
            )
        }

        Column(
            modifier = Modifier
                .size(BUTTON_SIZE)
                .background(color = buttonColor, shape = CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
            if (settling) {
                Text(
                    text = "$settleProgress%",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                )
            }
        }
    }
}

/** 呼吸周期（ms）：2s 一个完整吸气-呼气。 */
private const val BREATHING_PERIOD_MS = 2000
private const val BREATHING_SCALE_PEAK = 1.18f
private const val BREATHING_ALPHA_START = 0.55f
private const val BREATHING_ALPHA_END = 0.12f

private val BUTTON_SIZE = 168.dp
private val BUTTON_BOX_SIZE = 224.dp
