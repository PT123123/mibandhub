package com.ted.shouhuan.util

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
