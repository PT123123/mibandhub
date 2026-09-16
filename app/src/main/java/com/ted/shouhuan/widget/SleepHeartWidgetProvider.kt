package com.ted.shouhuan.widget

/**
 * 1×2 睡眠 + 心率曲线控件。
 *
 * 心率曲线是按控件宽度现场画的位图，所以拖拽缩放后必须重刷一遍 ——
 * 这条在 [BandWidgetProvider.onAppWidgetOptionsChanged] 里统一处理。
 */
class SleepHeartWidgetProvider : BandWidgetProvider()
