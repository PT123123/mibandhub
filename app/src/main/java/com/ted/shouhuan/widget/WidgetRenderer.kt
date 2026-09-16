package com.ted.shouhuan.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.ted.shouhuan.MainActivity
import com.ted.shouhuan.R
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.HeartRateSample
import com.ted.shouhuan.data.SleepNightRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
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
        views.setOnClickPendingIntent(R.id.widget_sleep_root, openAppIntent(context))
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

        views.setOnClickPendingIntent(R.id.widget_sleep_hr_root, openAppIntent(context))
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
        } else {
            views.setTextViewText(
                R.id.widget_detail_value,
                compactDuration(night.totalMinutes),
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
            views.setTextViewText(
                R.id.widget_detail_awake,
                if (night.awakeMinutes > 0) {
                    "夜间清醒 ${compactDuration(night.awakeMinutes)}"
                } else {
                    "夜间未醒"
                },
            )
            // 分期占比条：以整晚（不含清醒）为满格。RemoteViews 没有直接的
            // setProgress API，走 setInt 反射调 ProgressBar 的 @RemotableViewMethod。
            // 先 setMax 再 setProgress，顺序反了会被旧的 max 截断。1×1 / 1×2
            // 布局里没有这些 id —— 已核对 AOSP（RemoteViews.BaseReflectionAction.apply
            // 里 `if (view == null) return`），找不到的 id 是静默跳过，不会中断整份刷新。
            val total = night.totalMinutes.coerceAtLeast(1)
            for (barId in intArrayOf(
                R.id.widget_detail_deep_bar,
                R.id.widget_detail_light_bar,
                R.id.widget_detail_rem_bar,
            )) {
                views.setInt(barId, "setMax", total)
            }
            views.setInt(R.id.widget_detail_deep_bar, "setProgress", night.deepMinutes)
            views.setInt(R.id.widget_detail_light_bar, "setProgress", night.lightMinutes)
            views.setInt(R.id.widget_detail_rem_bar, "setProgress", night.remMinutes)
            views.setTextViewText(R.id.widget_detail_deep_value, compactDuration(night.deepMinutes))
            views.setTextViewText(R.id.widget_detail_light_value, compactDuration(night.lightMinutes))
            views.setTextViewText(R.id.widget_detail_rem_value, compactDuration(night.remMinutes))
        }

        views.setOnClickPendingIntent(R.id.widget_detail_root, openAppIntent(context))
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
            views.setTextViewText(R.id.widget_sleep_value, compactDuration(night.totalMinutes))
            views.setTextViewText(R.id.widget_sleep_label, nightLabel(night))
        }
    }

    /** 「442」→「7H12分」，整小时不带零头。宽度交给 TextView 的 autoSize。 */
    private fun compactDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h <= 0 -> "${m}分"
            m == 0 -> "${h}H"
            else -> "${h}H${m}分"
        }
    }

    /** 「1421」→「23:41」。bed/wake 存的是当天第几分钟。 */
    private fun clockLabel(minutesOfDay: Int): String =
        "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)

    /** 醒来那天就是今天 → 昨晚；更早就直说日期，控件挂着旧数据不能装新。 */
    private fun nightLabel(night: SleepNightRecord): String {
        val wakeDay = LocalDate.ofEpochDay(night.epochDay)
        return if (wakeDay == LocalDate.now()) {
            "昨晚睡眠"
        } else {
            "${wakeDay.monthValue}月${wakeDay.dayOfMonth}日睡眠"
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
