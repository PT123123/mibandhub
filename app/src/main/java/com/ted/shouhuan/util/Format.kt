package com.ted.shouhuan.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** "432" -> "7 小时 12 分"；不足一小时只显示分钟。 */
fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h <= 0) "$m 分" else "$h 小时 $m 分"
}

/** "432" -> "7h12m"，图表底下这种空间紧张的地方用。 */
fun formatDurationShort(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h <= 0) "${m}m" else "${h}h${m}m"
}

/**
 * "270" -> "4.5h"；睡眠/清醒这种主打数字用小数小时更紧凑。
 *
 * 整小时不显小数（"7h"）；不足 0.1h 的零头进位成 "0.1h"（避免 1 分钟显示成 "0.0h"）。
 * 小数点用 Locale.US，别让某些区域设置把 "." 渲染成 ","。
 */
fun formatHours(minutes: Int): String {
    val hours = minutes / 60.0
    var rounded = Math.round(hours * 10.0) / 10.0
    if (minutes > 0 && rounded < 0.1) rounded = 0.1
    return if (rounded == rounded.toInt().toDouble()) {
        "${rounded.toInt()}h"
    } else {
        String.format(Locale.US, "%.1f", rounded) + "h"
    }
}

/** "23:41" 风格时钟，从「当天第几分钟」换算。 */
fun minuteOfDayToClock(minuteOfDay: Int): String {
    val normalized = ((minuteOfDay % 1440) + 1440) % 1440
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

/** epoch 毫秒 → "20:41:07"（本地时区）。测量记录里要看精确到秒的时刻。 */
fun formatClockTime(epochMillis: Long): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
    return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
}

/** epoch 毫秒 → "09-11 20:41:07"（本地时区）。测量明细里要看到日期和秒。 */
fun formatDateTime(epochMillis: Long): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
    return "%02d-%02d %02d:%02d:%02d".format(
        t.monthValue,
        t.dayOfMonth,
        t.hour,
        t.minute,
        t.second,
    )
}

/** 耗时秒数 → "8 秒" / "1 分 12 秒"。 */
fun formatSeconds(seconds: Int): String {
    if (seconds < 60) return "$seconds 秒"
    return "${seconds / 60} 分 ${seconds % 60} 秒"
}

/** epochDay → "9月10日 周三"。睡眠页的主卡标题用。 */
fun formatEpochDay(epochDay: Long): String {
    val d = LocalDate.ofEpochDay(epochDay)
    return "${d.monthValue}月${d.dayOfMonth}日 ${WEEKDAY_NAMES[d.dayOfWeek.value - 1]}"
}

/** epochDay → "09-10 周三"。列表行里空间紧张的地方用。 */
fun formatEpochDayShort(epochDay: Long): String {
    val d = LocalDate.ofEpochDay(epochDay)
    return "%02d-%02d %s".format(d.monthValue, d.dayOfMonth, WEEKDAY_NAMES[d.dayOfWeek.value - 1])
}

/** epoch 毫秒 → 醒来/测量那天的 epochDay（本地时区）。历史记录按天分组用。 */
fun epochDayOf(epochMillis: Long): Long =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        .toLocalDate().toEpochDay()

/** epochDay → "周六"。导出 CSV 等需要单独星期列的地方用。 */
fun weekdayOf(epochDay: Long): String = WEEKDAY_NAMES[LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1]

