package com.ted.shouhuan.widget

/**
 * 「昨晚睡眠」自适应控件（主控件，2×2 起步）：时长 + 入睡→醒来，
 * 拉大后显示得分、分期占比条、夜间清醒。
 *
 * 它是唯一一个按小米小部件规范配置的控件（`miuiWidget` 标识 + 2×2/4×2/4×4
 * 认可的尺寸 + 根布局 `@android:id/background`），所以「长按应用图标」菜单里
 * 认的就是它；另两个 1×1 / 1×2 控件按原生 android 小部件走「安卓小组件」入口。
 */
class SleepDetailWidgetProvider : BandWidgetProvider()
