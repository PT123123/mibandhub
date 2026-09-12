package com.ted.shouhuan.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ted.shouhuan.data.SleepStage

/** 折线图：带渐变填充，数值区间自动贴合数据（不写死 0 基线，心率看着才有起伏）。 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color,
    strokeWidth: Dp = 2.5.dp,
    showFill: Boolean = true,
) {
    if (values.size < 2) {
        Box(modifier)
        return
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it > 0.0001f } ?: 1f
        val dx = w / (values.size - 1)

        fun yOf(v: Float) = h - ((v - minV) / range) * (h * 0.82f) - h * 0.09f

        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * dx
            val y = yOf(v)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }

        if (showFill) {
            val filled = Path().apply {
                addPath(line)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(
                path = filled,
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)),
                ),
            )
        }

        drawPath(
            path = line,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 把一条曲线上的某个位置画个点（用于高亮最高/最低点）。 */
@Composable
fun SparklineMarker(
    values: List<Float>,
    index: Int,
    modifier: Modifier = Modifier,
    color: Color,
) {
    if (values.size < 2 || index !in values.indices) {
        Box(modifier)
        return
    }
    Canvas(modifier) {
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it > 0.0001f } ?: 1f
        val dx = size.width / (values.size - 1)
        val x = index * dx
        val y = size.height - ((values[index] - minV) / range) * (size.height * 0.82f) - size.height * 0.09f
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, y))
        drawCircle(Color.White, radius = 1.6.dp.toPx(), center = Offset(x, y))
    }
}

fun SleepStage.displayColor(): Color = when (this) {
    SleepStage.DEEP -> Color(0xFF5B4BE0)
    SleepStage.LIGHT -> Color(0xFF7C6CF7)
    SleepStage.REM -> Color(0xFF3AA0FF)
    SleepStage.AWAKE -> Color(0xFFFFA23A)
}

fun SleepStage.label(): String = when (this) {
    SleepStage.DEEP -> "深睡"
    SleepStage.LIGHT -> "浅睡"
    SleepStage.REM -> "快速眼动"
    SleepStage.AWAKE -> "清醒"
}

/** 睡眠分期条：按分钟数横向堆叠，一眼看出结构。 */
@Composable
fun SleepStageBar(
    segments: List<SleepStageSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val shape = RoundedCornerShape(height / 2)
    // 0 分钟的段不能进 weight() —— Compose 对 weight(0) 直接抛
    // IllegalArgumentException（真机闪退过），过滤掉；全部为 0 时画空轨道。
    val visible = segments.filter { it.minutes > 0 }
    if (visible.isEmpty()) {
        Box(
            modifier
                .height(height)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        return
    }
    val total = visible.sumOf { it.minutes }.toFloat()
    Row(
        modifier
            .height(height)
            .clip(shape),
    ) {
        visible.forEach { seg ->
            Box(
                Modifier
                    .weight(seg.minutes / total)
                    .fillMaxHeight()
                    .background(seg.stage.displayColor()),
            )
        }
    }
}

/** 图表用的极简数据结构，避免把界面逻辑渗进数据层。 */
data class SleepStageSegment(val stage: SleepStage, val minutes: Int)

/**
 * 柱状图：近 N 天的睡眠时长。
 *
 * 传 [onBarTap] 后整张图可点 —— 按 x 落点换算成第几根柱子，睡眠页靠它实现
 * 「点任意一天看那一晚」。已选中时其余柱子压暗一点，选中项一眼可见。
 */
@Composable
fun MiniBarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color,
    selectedIndex: Int = -1,
    selectedColor: Color = color,
    onBarTap: ((Int) -> Unit)? = null,
) {
    if (values.isEmpty()) {
        Box(modifier)
        return
    }
    Box(
        modifier.then(
            if (onBarTap != null) {
                Modifier.pointerInput(values, onBarTap) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        val gap = w * 0.02f
                        val slot = (w - gap * (values.size - 1)) / values.size + gap
                        val index = (offset.x / slot).toInt().coerceIn(0, values.size - 1)
                        onBarTap(index)
                    }
                }
            } else {
                Modifier
            },
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val maxV = values.max().takeIf { it > 0f } ?: 1f
            val gap = size.width * 0.02f
            val barW = (size.width - gap * (values.size - 1)) / values.size
            val hasSelection = selectedIndex in values.indices
            values.forEachIndexed { i, v ->
                val h = (v / maxV) * size.height
                val x = i * (barW + gap)
                val base = if (i == selectedIndex) selectedColor else color
                val paint = if (hasSelection && i != selectedIndex) base.copy(alpha = 0.55f) else base
                drawRoundRect(
                    color = paint,
                    topLeft = Offset(x, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2.5f, barW / 2.5f),
                )
            }
        }
    }
}
