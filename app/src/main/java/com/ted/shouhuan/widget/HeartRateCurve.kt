package com.ted.shouhuan.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.ted.shouhuan.data.HeartRateSample
import kotlin.math.roundToInt

/**
 * 心率曲线的位图渲染 —— 桌面控件是远程视图，画不了自定义 View，只能喂画好的图。
 *
 * 横轴是固定的时间窗（近 24 小时），纵轴按窗口内的最低/最高动态收放；
 * 跨度不足时撑到最小跨度，不然平稳的睡眠心率会抖成锯齿。样本按像素列分桶取均值，
 * 1 分钟 1440 个点压到控件宽度个点。断采超过半小时的段落直接断线 ——
 * 把没数据的区间连成直线，比留白更误导（摘下手环的那几小时像是心率归零）。
 *
 * 配色和应用内一致：心率红描边 + 向下渐隐的填充；低/高值以角标画进图里，
 * 免得在控件布局里再挤一行字。
 */
object HeartRateCurve {

    private const val LINE_COLOR = 0xFFFF3B5C.toInt()
    private const val FILL_TOP = 0x59FF3B5C.toInt()
    private const val FILL_BOTTOM = 0x00FF3B5C.toInt()
    private const val LABEL_COLOR = 0xFF8FA0B3.toInt()

    /** 纵轴最小跨度（bpm）。 */
    private const val MIN_SPAN_BPM = 30

    /** 纵轴上下各留的余量（bpm），让最高/最低点不顶着边。 */
    private const val AXIS_PADDING_BPM = 4

    /** 断采超过这么多分钟就断线。 */
    private const val GAP_BREAK_MINUTES = 30L

    /**
     * 画曲线。窗口内有效样本不足两点时返回 null（调用方显示「暂无数据」），
     * 尺寸太小也返回 null —— 画出来也看不清。
     */
    fun render(
        samples: List<HeartRateSample>,
        widthPx: Int,
        heightPx: Int,
        nowMillis: Long = System.currentTimeMillis(),
        windowMs: Long = 24 * 60 * 60 * 1000L,
    ): Bitmap? {
        if (widthPx < 24 || heightPx < 24) return null
        val start = nowMillis - windowMs
        val pts = samples.filter { it.atMillis in start..nowMillis }
        if (pts.size < 2) return null

        var lo = pts.minOf { it.bpm } - AXIS_PADDING_BPM.toFloat()
        var hi = pts.maxOf { it.bpm } + AXIS_PADDING_BPM.toFloat()
        if (hi - lo < MIN_SPAN_BPM) {
            val mid = (hi + lo) / 2f
            lo = mid - MIN_SPAN_BPM / 2f
            hi = mid + MIN_SPAN_BPM / 2f
        }

        // 按像素列分桶取均值：横轴是时间，一列 ≈ 窗口/宽度 分钟
        val colSum = FloatArray(widthPx)
        val colCount = IntArray(widthPx)
        for (p in pts) {
            val col = ((p.atMillis - start) * widthPx / windowMs).toInt().coerceIn(0, widthPx - 1)
            colSum[col] += p.bpm
            colCount[col]++
        }
        val points = ArrayList<Pair<Int, Float>>(widthPx)
        for (c in 0 until widthPx) {
            if (colCount[c] > 0) points.add(c to colSum[c] / colCount[c])
        }
        if (points.size < 2) return null

        // 断采的空档切段：列间空隙超过阈值就另起一段
        val gapCols = ((GAP_BREAK_MINUTES * 60_000L) * widthPx / windowMs).toInt() + 1
        val runs = ArrayList<List<Pair<Int, Float>>>()
        var run = ArrayList<Pair<Int, Float>>(points.size)
        for (p in points) {
            val prev = run.lastOrNull()
            if (prev != null && p.first - prev.first > gapCols) {
                runs.add(run)
                run = ArrayList(points.size)
            }
            run.add(p)
        }
        if (run.isNotEmpty()) runs.add(run)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bottom = heightPx - 1f

        fun yOf(bpm: Float): Float =
            bottom - (((bpm - lo) / (hi - lo)).coerceIn(0f, 1f) * bottom)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, heightPx.toFloat(),
                FILL_TOP, FILL_BOTTOM, Shader.TileMode.CLAMP,
            )
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = (heightPx * 0.045f).coerceIn(2f, 4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val linePath = Path()
        val fillPath = Path()
        for (r in runs) {
            if (r.size < 2) continue
            linePath.moveTo(r[0].first.toFloat(), yOf(r[0].second))
            for (i in 1 until r.size) linePath.lineTo(r[i].first.toFloat(), yOf(r[i].second))
            fillPath.moveTo(r[0].first.toFloat(), bottom)
            for ((c, bpm) in r) fillPath.lineTo(c.toFloat(), yOf(bpm))
            fillPath.lineTo(r.last().first.toFloat(), bottom)
            fillPath.close()
        }
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // 低/高角标：低位画左下、高位画右上，跟曲线的最高最低点天然对齐
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL_COLOR
            textSize = (heightPx * 0.24f).coerceIn(20f, 64f)
        }
        val pad = labelPaint.textSize * 0.3f
        val loText = "低${pts.minOf { it.bpm }}"
        val hiText = "高${pts.maxOf { it.bpm }}"
        canvas.drawText(loText, pad, heightPx - pad, labelPaint)
        canvas.drawText(
            hiText,
            widthPx - pad - labelPaint.measureText(hiText),
            labelPaint.textSize + pad,
            labelPaint,
        )
        return bitmap
    }

    /** 控件宽度（dp）→ 位图宽度（px）。超过上限就没必要再宽，缩小才是常见的。 */
    fun bitmapWidth(widthDp: Int, density: Float): Int =
        (widthDp * density).roundToInt().coerceIn(24, 1024)
}
