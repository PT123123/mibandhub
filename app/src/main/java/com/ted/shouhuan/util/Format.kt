package com.ted.shouhuan.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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

