package com.ted.shouhuan.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.ted.shouhuan.MainActivity
import com.ted.shouhuan.R
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.HeartRateSample
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.util.formatHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 桌面控件的统一渲染入口：从 [BandPrefs] 读一屏快照，把两个控件整份刷一遍。
 *
 * 两个控件（1×1 睡眠、1×2 睡眠+心率）都走标准 RemoteViews —— 只用
 * LinearLayout/ImageView/TextView 这类远程视图白名单控件，澎湃OS 和
 * 联想 ZUI 的桌面都认这一种，不依赖任何厂商私有接口。
 *
 * [updateAll] 要读 DataStore 还要画曲线位图，必须在协程里调；
 * BroadcastReceiver 侧用 [refreshAsync]（goAsync）包住，别在主线程阻塞读。
 */
object WidgetRenderer {

    private const val TAG = "WidgetRenderer"

    /** 心率曲线的时间窗：近 24 小时。 */
    private const val HR_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** 曲线位图的固定高度（dp），布局里的 ImageView 也是这个高度。 */
    private const val CURVE_HEIGHT_DP = 48

    /** 2×2 里「睡眠分期」label 的宽度（dp）：分段条位图要扣掉它，免得被 ImageView 压扁。 */
    private const val STAGE_BAR_LABEL_DP = 62

    /** 分段条位图的固定高度（dp），布局里的 ImageView 也是这个高度。 */
    private const val STAGE_BAR_HEIGHT_DP = 12

    /** 拿不到控件尺寸时的兜底宽度（dp），常见 1 列格子的量级。 */
    private const val FALLBACK_WIDTH_DP = 80

    /** 渲染要的一屏数据。 */
    private data class Snapshot(
        val night: SleepNightRecord?,
        val heartRate: List<HeartRateSample>,
    )

    /**
     * 两个控件一起刷。读的是同一份快照，重复调用是幂等的 ——
     * 同步完成、开机、选项变化、30 分钟周期到点，全都汇到这一处。
     */
    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val prefs = BandPrefs(context)
        val now = System.currentTimeMillis()
        val snapshot = Snapshot(
            night = prefs.sleepHistory.first().firstOrNull(),
            heartRate = prefs.heartRateSamples.first()
                .filter { it.atMillis >= now - HR_WINDOW_MS },
        )

        val sleepIds = manager.getAppWidgetIds(ComponentName(context, SleepWidgetProvider::class.java))
        for (id in sleepIds) renderSleep(context, manager, id, snapshot)

        val hrIds = manager.getAppWidgetIds(
            ComponentName(context, SleepHeartWidgetProvider::class.java),
        )
        for (id in hrIds) renderSleepHeart(context, manager, id, snapshot)

        val detailIds = manager.getAppWidgetIds(
            ComponentName(context, SleepDetailWidgetProvider::class.java),
        )
        for (id in detailIds) renderSleepDetail(context, manager, id, snapshot)
    }

    /**
     * BroadcastReceiver 侧的异步刷新：goAsync 给 DataStore 读 + 画图留足
     * 广播处理时间，不占主线程。失败只记日志 —— 控件留着旧内容比变空白好。
     */
    fun refreshAsync(receiver: BroadcastReceiver, context: Context) {
        val pending = receiver.goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAll(context)
            } catch (e: Exception) {
                Log.w(TAG, "桌面控件刷新失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    // ------------------------------------------------------------------
    // 1×1：睡眠时长
    // ------------------------------------------------------------------

    private fun renderSleep(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: Snapshot,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_sleep_1x1)
        fillSleepBlock(views, snapshot.night)
        views.setOnClickPendingIntent(R.id.widget_sleep_root, openSleepIntent(context))
        manager.updateAppWidget(appWidgetId, views)
    }

    // ------------------------------------------------------------------
    // 1×2：睡眠时长 + 心率曲线
    // ------------------------------------------------------------------

    private fun renderSleepHeart(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: Snapshot,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_sleep_hr_1x2)
        fillSleepBlock(views, snapshot.night)

        // 曲线位图按当前控件宽度重画 —— 拖拽缩放（onAppWidgetOptionsChanged）也会走到这
        val hr = snapshot.heartRate
        val current = hr.lastOrNull()?.bpm
        views.setTextViewText(R.id.widget_hr_value, current?.toString() ?: "—")
        val opts = manager.getAppWidgetOptions(appWidgetId)
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            .takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
        val density = context.resources.displayMetrics.density
        val curve = HeartRateCurve.render(
            samples = hr,
            widthPx = HeartRateCurve.bitmapWidth(widthDp, density),
            heightPx = (CURVE_HEIGHT_DP * density).roundToInt(),
            nowMillis = System.currentTimeMillis(),
            windowMs = HR_WINDOW_MS,
        )
        if (curve == null) {
            views.setViewVisibility(R.id.widget_hr_curve, View.GONE)
            views.setViewVisibility(R.id.widget_hr_empty, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_hr_curve, View.VISIBLE)
            views.setViewVisibility(R.id.widget_hr_empty, View.GONE)
            views.setImageViewBitmap(R.id.widget_hr_curve, curve)
        }

        views.setOnClickPendingIntent(R.id.widget_sleep_hr_root, openSleepIntent(context))
        manager.updateAppWidget(appWidgetId, views)
    }

    // ------------------------------------------------------------------
    // 自适应「昨晚睡眠」：1×1 时长+入睡/醒来 → 1×2 / 2×2 更多细节
    // ------------------------------------------------------------------

    private fun renderSleepDetail(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: Snapshot,
    ) {
        // 按当前格子尺寸选布局：格子估算沿用「(dp + 30) / 70」的桌面惯用公式
        val opts = manager.getAppWidgetOptions(appWidgetId)
        val wCells = ((opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) + 30) / 70)
            .coerceAtLeast(1)
        val hCells = ((opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) + 30) / 70)
            .coerceAtLeast(1)
        val layout = when {
            wCells >= 2 && hCells >= 2 -> R.layout.widget_sleep_detail_2x2
            wCells >= 2 -> R.layout.widget_sleep_detail_1x2
            else -> R.layout.widget_sleep_detail_1x1
        }

        val views = RemoteViews(context.packageName, layout)
        val night = snapshot.night
        if (night == null) {
            views.setTextViewText(R.id.widget_detail_value, "—")
            views.setTextViewText(R.id.widget_detail_bedwake, "—")
            views.setTextViewText(R.id.widget_detail_label, "暂无睡眠数据")
            views.setTextViewText(R.id.widget_detail_score, "")
            views.setTextViewText(R.id.widget_detail_stages, "")
            views.setTextViewText(R.id.widget_detail_awake, "")
            views.setViewVisibility(R.id.widget_detail_stages_bar, View.GONE)
        } else {
            views.setTextViewText(
                R.id.widget_detail_value,
                formatHours(night.totalMinutes),
            )
            views.setTextViewText(R.id.widget_detail_label, nightLabel(night))
            views.setTextViewText(
                R.id.widget_detail_bedwake,
                "${clockLabel(night.bedMinutes)} → ${clockLabel(night.wakeMinutes)}",
            )
            views.setTextViewText(R.id.widget_detail_score, "${night.score} 分")
            views.setTextViewText(
                R.id.widget_detail_stages,
                "深睡 ${compactDuration(night.deepMinutes)} · " +
                    "浅睡 ${compactDuration(night.lightMinutes)} · " +
                    "眼动 ${compactDuration(night.remMinutes)}",
            )
            // 已醒 = 现在 → 最后一次起床时间（不是夜间清醒分钟：用户在控件里
            // 要看的是「醒来后到现在清醒了多久」，比如 9/16 13:00 起醒来，到现在
            // 就显示「已醒 50h」这种。夜间清醒不在这里展示，那是页面统计口径。）
            views.setTextViewText(
                R.id.widget_detail_awake,
                "已醒 ${compactDuration(sinceWakeMinutes(night))}",
            )
            // 分期占比：单条三段（深睡/浅睡/眼动）彩条，一条里蕴含三个比例。
            // 位图按当前控件宽度画，2×2 布局才渲染（1×1/1×2 没有这个 ImageView，
            // 白画一张位图没必要）。RemoteViews 对不存在的 id 静默跳过，无副作用。
            if (layout == R.layout.widget_sleep_detail_2x2) {
                val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    .takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
                val bar = stageBarBitmap(context, night, widthDp - STAGE_BAR_LABEL_DP)
                if (bar != null) {
                    views.setViewVisibility(R.id.widget_detail_stages_bar, View.VISIBLE)
                    views.setImageViewBitmap(R.id.widget_detail_stages_bar, bar)
                } else {
                    views.setViewVisibility(R.id.widget_detail_stages_bar, View.GONE)
                }
            }
        }

        views.setOnClickPendingIntent(R.id.widget_detail_root, openSleepIntent(context))
        manager.updateAppWidget(appWidgetId, views)
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    /** 睡眠块：时长 + 标签。两个控件共用同一个 id（widget_sleep_*），填法一致。 */
    private fun fillSleepBlock(views: RemoteViews, night: SleepNightRecord?) {
        if (night == null) {
            views.setTextViewText(R.id.widget_sleep_value, "—")
            views.setTextViewText(R.id.widget_sleep_label, "暂无睡眠数据")
        } else {
            views.setTextViewText(R.id.widget_sleep_value, formatHours(night.totalMinutes))
            views.setTextViewText(R.id.widget_sleep_label, nightLabel(night))
        }
    }

    /** 从最后一次起床（epochDay 是醒来那天，wakeMinutes 是当天第几分钟）到现在过了多久。 */
    private fun sinceWakeMinutes(night: SleepNightRecord): Int {
        val wake = LocalDate.ofEpochDay(night.epochDay)
            .atStartOfDay()
            .plusMinutes(night.wakeMinutes.toLong())
        return Duration.between(wake, LocalDateTime.now()).toMinutes().coerceAtLeast(0).toInt()
    }

    /** 一分半的时长：≥1h 写「Xh」或「XhYY分」，不足 1h 写「YY分」；h 小写。 */
    private fun compactDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h <= 0 -> "${m}分"
            m == 0 -> "${h}h"
            else -> "${h}h${m}分"
        }
    }

    /** 「1421」→「23:41」。bed/wake 存的是当天第几分钟。 */
    private fun clockLabel(minutesOfDay: Int): String =
        "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)

    /** 醒来那天就是今天 → 昨晚；更早就直说日期（不带「睡眠」二字，控件空间金贵）。 */
    private fun nightLabel(night: SleepNightRecord): String {
        val wakeDay = LocalDate.ofEpochDay(night.epochDay)
        return if (wakeDay == LocalDate.now()) {
            "昨晚睡眠"
        } else {
            "${wakeDay.monthValue}月${wakeDay.dayOfMonth}日"
        }
    }

    /** 单条三段分期彩条（深睡/浅睡/眼动按分钟比例），颜色对齐应用内 Charts.kt 的分期配色。 */
    private fun stageBarBitmap(context: Context, night: SleepNightRecord, widthDp: Int): Bitmap? {
        val deep = night.deepMinutes
        val light = night.lightMinutes
        val rem = night.remMinutes
        val total = deep + light + rem
        if (total <= 0) return null
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).roundToInt().coerceAtLeast(2)
        val h = (STAGE_BAR_HEIGHT_DP * density).roundToInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val radius = h / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF.toInt() }
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, bg)
        // 三段按比例画，段间留 1dp 缝
        val gap = density
        var left = 0f
        fun seg(minutes: Int, color: Int) {
            if (minutes <= 0) return
            val segW = w * minutes / total.toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawRoundRect(
                left, 0f, (left + segW).coerceAtMost(w.toFloat()), h.toFloat(),
                radius, radius, paint,
            )
            left += segW + gap
        }
        seg(deep, STAGE_COLOR_DEEP)
        seg(light, STAGE_COLOR_LIGHT)
        seg(rem, STAGE_COLOR_REM)
        return bmp
    }

    /** 点控件进应用后落到的 tab：睡眠页（route 见 AppRoot）。 */
    private fun openSleepIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TARGET_ROUTE, "sleep"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val STAGE_COLOR_DEEP = 0xFF5B4BE0.toInt()
    private val STAGE_COLOR_LIGHT = 0xFF7C6CF7.toInt()
    private val STAGE_COLOR_REM = 0xFF3AA0FF.toInt()
}
