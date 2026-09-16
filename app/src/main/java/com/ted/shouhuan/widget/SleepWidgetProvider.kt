package com.ted.shouhuan.widget

/**
 * 1×1 睡眠时长控件。
 *
 * 只是个壳 —— 广播 / 尺寸变化都在 [BandWidgetProvider] 里汇到 [WidgetRenderer]，
 * 由它把三个控件一起重刷。
 */
class SleepWidgetProvider : BandWidgetProvider()
