package com.echolog.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.Marker

/**
 * 时间轴上的一个时间区块（起止为全天绝对 epoch millis）。
 *
 * 由各页面把领域对象（片段/标记区间）映射为本结构，
 * 保持组件层不依赖具体领域模型。
 */
data class TimeRangeBlock(
    val startMs: Long,
    val endMs: Long,
)

/**
 * 0:00–24:00 全天时间轴（架构 2.5，PRD 4.2，FR-017）。
 *
 * Canvas 绘制：已录音片段为圆角色块，打点标记为旗标（旗杆 + 三角旗面）；
 * 可横向滚动（24h 映射为 [TOTAL_WIDTH_DP]），点击任意位置经
 * [onSeekTo] 回传全天绝对时刻（FR-104 打点跳转）。
 *
 * @param dayId 目标天（时刻换算基准）。
 * @param blocks 时间区块（起止为全天绝对 epoch millis）。
 * @param markers 打点旗标。
 * @param highlightStartMs 高亮区间起点（点击标记后的定位反馈，可空）。
 * @param highlightEndMs 高亮区间终点。
 * @param onSeekTo 点击时间轴回调（全天绝对 epoch millis）。
 */
@Composable
fun TimelineCanvas(
    dayId: String,
    blocks: List<TimeRangeBlock>,
    markers: List<Marker>,
    highlightStartMs: Long?,
    highlightEndMs: Long?,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val dayStart = TimeUtils.startOfDayEpochMs(dayId)
    val dayEnd = TimeUtils.endOfDayEpochMs(dayId)
    val daySpan = (dayEnd - dayStart).coerceAtLeast(1L)

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val blockColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.secondary
    val flagColor = MaterialTheme.colorScheme.error

    val description = stringResource(R.string.timeline_canvas_desc)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .width(TOTAL_WIDTH_DP)
                .height(TRACK_HEIGHT)
                .horizontalScroll(scrollState)
                .semantics { contentDescription = description }
                .pointerInput(dayId) {
                    detectTapGestures { offset ->
                        // offset.x 为画布内像素（含横向滚动位移）
                        val scrolledX = offset.x + scrollState.value
                        val fraction = (scrolledX / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekTo(dayStart + (daySpan * fraction).toLong())
                    }
                },
        ) {
            // 轨道底
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = CornerRadius(TRACK_CORNER_RADIUS.toPx()),
            )
            // 整点刻度线（每 2h 一条）
            val tickColor = trackColor.copy(alpha = 0.9f)
            for (hour in 0..24 step 2) {
                val x = size.width * hour / 24f
                drawLine(
                    color = tickColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
            // 片段色块
            blocks.forEach { block ->
                val startFraction =
                    ((block.startMs - dayStart).toFloat() / daySpan).coerceIn(0f, 1f)
                val endFraction =
                    ((block.endMs - dayStart).toFloat() / daySpan).coerceIn(0f, 1f)
                drawRoundRect(
                    color = blockColor,
                    topLeft = Offset(size.width * startFraction, 0f),
                    size = Size(
                        width = (size.width * (endFraction - startFraction)).coerceAtLeast(2f),
                        height = size.height,
                    ),
                    cornerRadius = CornerRadius(BLOCK_CORNER_RADIUS.toPx()),
                )
            }
            // 高亮区间（点击标记后的定位反馈）
            if (highlightStartMs != null && highlightEndMs != null && highlightEndMs > highlightStartMs) {
                val startFraction =
                    ((highlightStartMs - dayStart).toFloat() / daySpan).coerceIn(0f, 1f)
                val endFraction =
                    ((highlightEndMs - dayStart).toFloat() / daySpan).coerceIn(0f, 1f)
                drawRect(
                    color = highlightColor.copy(alpha = 0.35f),
                    topLeft = Offset(size.width * startFraction, 0f),
                    size = Size(
                        width = (size.width * (endFraction - startFraction)).coerceAtLeast(2f),
                        height = size.height,
                    ),
                )
            }
            // 打点旗标
            markers.forEach { marker ->
                val fraction =
                    ((marker.atMs - dayStart).toFloat() / daySpan).coerceIn(0f, 1f)
                val x = size.width * fraction
                drawFlag(x = x, height = size.height, color = flagColor)
            }
        }

        // 小时刻度标签（避免 Canvas drawText 的测量复杂度，用 Text 行承载）
        Row(
            modifier = Modifier
                .width(TOTAL_WIDTH_DP)
                .horizontalScroll(scrollState)
                .padding(top = 2.dp),
        ) {
            for (hour in 0..24 step 2) {
                Text(
                    text = "%02d:00".format(hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(HOUR_LABEL_WIDTH),
                )
            }
        }
    }
}

/** 绘制单面旗标：旗杆 + 三角旗面（PRD 4.2 旗标图标）。 */
private fun DrawScope.drawFlag(x: Float, height: Float, color: Color) {
    val poleWidth = FLAG_POLE_WIDTH.toPx()
    val flagHeight = height * 0.5f
    drawRect(
        color = color,
        topLeft = Offset(x - poleWidth / 2f, 0f),
        size = Size(poleWidth, height),
    )
    val path = Path().apply {
        moveTo(x, 0f)
        lineTo(x + FLAG_WIDTH.toPx(), FLAG_HEIGHT.toPx())
        lineTo(x, FLAG_HEIGHT.toPx() * 2f)
        close()
    }
    drawPath(path = path, color = color)
}

/** 24h 总宽度（每小时 100dp，横向滚动浏览）。 */
private val TOTAL_WIDTH_DP: Dp = 2400.dp
private val TRACK_HEIGHT: Dp = 56.dp
private val TRACK_CORNER_RADIUS: Dp = 8.dp
private val BLOCK_CORNER_RADIUS: Dp = 4.dp
private val HOUR_LABEL_WIDTH: Dp = 200.dp
private val FLAG_POLE_WIDTH: Dp = 2.dp
private val FLAG_WIDTH: Dp = 12.dp
private val FLAG_HEIGHT: Dp = 8.dp
