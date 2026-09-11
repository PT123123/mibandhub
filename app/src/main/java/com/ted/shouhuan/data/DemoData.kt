package com.ted.shouhuan.data

import kotlin.math.sin

/**
 * 界面用的演示数据。
 *
 * 现阶段 UI 与协议层还没接通，先用它把界面撑起来看效果；
 * 等 BLE 层跑通后，这些会被真实数据源替换掉（接口形状保持一致）。
 */
object DemoData {

    /** 一天 288 个采样点（每 5 分钟一个）的模拟心率曲线。 */
    fun heartRateDay(): List<HeartRatePoint> {
        val out = ArrayList<HeartRatePoint>(288)
        for (i in 0 until 288) {
            val minute = i * 5
            val hour = minute / 60.0
            // 夜里低、白天高，午后再压一个小凹陷，叠一点随机感
            val base = 62.0 +
                sin((hour - 6.0) / 24.0 * Math.PI * 2) * 9.0 +
                sin(hour / 3.0) * 3.5
            val sleepDip = if (hour < 6.5) -6.0 else 0.0
            val bpm = (base + sleepDip).toInt().coerceIn(52, 128)
            out.add(HeartRatePoint(minute, bpm))
        }
        return out
    }

    fun latestBpm(): Int = 72

    fun restingBpm(): Int = 58

    fun maxBpm(): Int = 128

    fun avgBpm(): Int = 76

    fun steps(): Int = 6240

    fun stepsGoal(): Int = 8000

    fun lastNight(): SleepNight = SleepNight(
        dateLabel = "9月10日 周三",
        bedTime = "23:41",
        wakeTime = "06:53",
        totalMinutes = 432,
        score = 82,
        shares = listOf(
            SleepStageShare(SleepStage.AWAKE, 12),
            SleepStageShare(SleepStage.REM, 52),
            SleepStageShare(SleepStage.LIGHT, 272),
            SleepStageShare(SleepStage.DEEP, 108),
        ),
    )

    /** 近 7 晚总时长（分钟），最后一个是昨晚。 */
    fun sleepWeek(): List<Float> = listOf(398f, 441f, 372f, 455f, 410f, 386f, 432f)

    fun sleepWeekLabels(): List<String> = listOf("四", "五", "六", "日", "一", "二", "三")

    fun bandStatus(): BandStatus = BandStatus(
        name = "小米手环5",
        mac = "AA:BB:CC:DD:EE:FF",
        connected = true,
        batteryPercent = 47,
        firmware = "1.0.2.46",
        authKeyConfigured = true,
    )

    fun notifications(): List<BandNotification> = listOf(
        BandNotification("微信", "张伟", "晚上那个方案我改了一版，你方便的时候看下", "14:32", true),
        BandNotification("短信", "10086", "您本月流量已使用 80%", "13:07", true),
        BandNotification("邮件", "GitHub", "[shouhuan] CI passed on main", "11:48", true),
        BandNotification("日程", "日历", "16:00 项目周会 · 会议室 A", "10:15", false),
    )

    fun appRules(): List<AppRule> = listOf(
        AppRule("com.tencent.mm", "微信", true),
        AppRule("com.android.mms", "短信", true),
        AppRule("com.android.dialer", "来电", true),
        AppRule("com.android.calendar", "日历", true),
        AppRule("com.tencent.mobileqq", "QQ", false),
        AppRule("com.taobao.taobao", "淘宝", false),
        AppRule("com.zhihu.android", "知乎", false),
    )
}
