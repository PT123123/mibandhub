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

    fun steps(): Int = 6240

    fun stepsGoal(): Int = 8000

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

    /**
     * 出厂默认的转发应用名单（白名单模式用）：美团、微信、短信、来电、日历。
     * 用户只要动过名单（哪怕存成空），就完全照用户存的来（见 BandPrefs.appRules）。
     */
    fun appRules(): List<AppRule> = listOf(
        AppRule("com.sankuai.meituan", "美团", true),
        AppRule("com.tencent.mm", "微信", true),
        AppRule("com.android.mms", "短信", true),
        AppRule("com.android.dialer", "来电", true),
        AppRule("com.android.calendar", "日历", true),
    )

    /**
     * 出厂默认的关键词**黑名单**。
     *
     * 系统 / ROM 自己会发「短信正在运行，点按即可了解详情或停止应用」这类提示卡 ——
     * 它不是用户关心的消息，但包名往往是应用自己的（短信、音乐），躲不过系统包过滤，
     * 只能靠关键词兜住。这里只放最典型的几条；用户只要动过关键词列表（哪怕删空），
     * 就完全照用户存的来（见 BandPrefs.keywordBlacklistRules）。
     */
    fun defaultKeywordBlacklist(): List<KeywordRule> = listOf(
        KeywordRule("正在运行"),
        KeywordRule("点按即可了解详情或停止应用"),
        KeywordRule("点按即可停止"),
    )

    /**
     * 出厂默认的敏感信息识别规则：内置类型全部启用、不限应用。
     * 总开关默认关，所以这份默认值只有在用户打开「敏感信息过滤」后才起作用。
     */
    fun defaultSensitiveRules(): List<SensitiveRule> =
        SensitiveKind.entries.map { SensitiveRule(kind = it) }
}
