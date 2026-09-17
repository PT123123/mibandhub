package com.ted.shouhuan.widget

/**
 * 「昨晚睡眠」自适应控件（主控件，2×2 起步）：时长 + 入睡→醒来，
 * 拉大后显示得分、分期占比条、夜间清醒。
 *
 * 三个控件统一走原生 AppWidget 路径（不再带 miuiWidget 标识）：侧载 App 进不了
 * 小米小部件体系，声明了标识反而会被澎湃OS 从「安卓小组件」分类过滤掉。它的
 * 2×2 起步 + 自适应尺寸在原生 Android 12+ 网格上同样成立；拉大后
 * SleepDetailWidgetProvider 按实际尺寸在 1×1 / 1×2 / 2×2 布局间切换。
 */
class SleepDetailWidgetProvider : BandWidgetProvider()
