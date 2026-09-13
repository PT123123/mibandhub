package com.ted.shouhuan.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * 1×1 睡眠时长控件。
 *
 * 只是个壳：onUpdate / 尺寸变化全都汇到 [WidgetRenderer]（它会把两个控件
 * 一起重刷）。渲染是幂等的，多刷一遍没有副作用。
 */
class SleepWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.refreshAsync(this, context)
    }
}
