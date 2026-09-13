package com.ted.shouhuan.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * 1×2 睡眠 + 心率曲线控件。
 *
 * 心率曲线是按控件宽度现场画的位图，所以拖拽缩放（onAppWidgetOptionsChanged）
 * 也要重刷一遍 —— 否则曲线会跟着布局被拉伸变形。
 */
class SleepHeartWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.refreshAsync(this, context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetRenderer.refreshAsync(this, context)
    }
}
