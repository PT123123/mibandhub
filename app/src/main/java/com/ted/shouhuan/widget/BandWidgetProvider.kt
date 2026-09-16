package com.ted.shouhuan.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 三个桌面控件的公共外壳：把系统广播、尺寸变化和澎湃OS 的小米小部件刷新广播
 * 全部汇到 [WidgetRenderer]（渲染幂等，多刷一遍没有副作用）。
 *
 * 为什么自己接管 onReceive：澎湃OS 的桌面在「展现刷新 / 曝光刷新」时发的是
 * 小米自己的 action [ACTION_MIUI_APPWIDGET_UPDATE]（小米小部件技术规范 §2）。
 * `AppWidgetProvider` 原生只认 `android.appwidget.action.APPWIDGET_UPDATE`，
 * 小米那条会被直接丢掉 —— 控件就永远停在旧内容上，只有等 30 分钟的系统周期
 * 或下一次手环同步才更新。接住它当一次普通 onUpdate 处理即可。
 */
abstract class BandWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.refreshAsync(this, context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        // 拖拽缩放落定 → 按新尺寸重画（自适应布局换形态、心率曲线按新宽度重画）
        WidgetRenderer.refreshAsync(this, context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIUI_APPWIDGET_UPDATE) {
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: IntArray(0)
            onUpdate(context, AppWidgetManager.getInstance(context), ids)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        /** 澎湃OS / MIUI 小米小部件的「展现刷新」action（小米小部件技术规范 §2.2）。 */
        const val ACTION_MIUI_APPWIDGET_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"
    }
}
