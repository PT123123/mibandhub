package com.ted.shouhuan.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * 长按拖拽排序的纵向列表（设备页的菜单顺序 / 快捷方式用）。
 *
 * 实现口径：装着所有行的这层 Column 只挂**一个**长按拖动手势，按按下位置
 * 算出抓到的是第几行 —— 行内容重组后手势闭包不过期（per-row 挂手势会在
 * 拖动交换时把 pointerInput 销毁重建，手势当场断掉）。
 *
 * 拖动中的行用 zIndex + translationY + 投影抬起来；其余行在交换瞬间直接落位。
 * 行高按「第一行实测高度」算，所有行必须等高 —— 排序列表都是单行文本，够用。
 *
 * @param items 当前列表（顺序即显示顺序）
 * @param onMove 交换发生时回调 (from, to)；调用方据此更新数据源，重组后本组件跟随
 * @param row 单行内容（各行必须等高）
 */
@Composable
fun <T> DragReorderList(
    items: List<T>,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T) -> Unit,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    Column(modifier) {
        Column(
            Modifier
                .pointerInput(items.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val height = rowHeightPx
                            if (height > 0f) {
                                draggingIndex = (start.y / height).toInt().coerceIn(0, items.size - 1)
                                dragOffsetPx = 0f
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val from = draggingIndex
                            val height = rowHeightPx
                            if (from < 0 || height <= 0f) return@detectDragGesturesAfterLongPress
                            dragOffsetPx += dragAmount.y
                            val target = (from + (dragOffsetPx / height).roundToInt())
                                .coerceIn(0, items.size - 1)
                            if (target != from) {
                                onMove(from, target)
                                // 数据源交换后，拖动行的视觉位置 = 手指位置 - 已被交换吃掉的位移
                                dragOffsetPx -= (target - from) * height
                                draggingIndex = target
                            }
                        },
                        onDragEnd = {
                            draggingIndex = -1
                            dragOffsetPx = 0f
                        },
                        onDragCancel = {
                            draggingIndex = -1
                            dragOffsetPx = 0f
                        },
                    )
                },
        ) {
            items.forEachIndexed { index, item ->
                val isDragging = draggingIndex == index
                Box(
                    Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .onSizeChanged {
                            if (rowHeightPx == 0f && it.height > 0) rowHeightPx = it.height.toFloat()
                        }
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffsetPx
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .shadow(if (isDragging) 10.dp else 0.dp, RoundedCornerShape(14.dp)),
                ) {
                    row(item)
                }
            }
        }
    }
}
