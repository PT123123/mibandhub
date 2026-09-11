package com.ted.shouhuan.data

/** 睡眠分期。手环活动数据里的 rawKind 映射到这四个状态。 */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

/** 心率采样点。minuteOfDay 是当天的第几分钟，便于直接铺在时间轴上。 */
data class HeartRatePoint(val minuteOfDay: Int, val bpm: Int)

data class SleepStageShare(val stage: SleepStage, val minutes: Int)

data class SleepNight(
    val dateLabel: String,
    val bedTime: String,
    val wakeTime: String,
    val totalMinutes: Int,
    val score: Int,
    val shares: List<SleepStageShare>,
)

/** 手环连接与电量状态。 */
data class BandStatus(
    val name: String,
    val mac: String,
    val connected: Boolean,
    val batteryPercent: Int,
    val firmware: String,
    val authKeyConfigured: Boolean,
)

/** 设备上发生的一条通知。 */
data class BandNotification(
    val appName: String,
    val title: String,
    val body: String,
    val timeLabel: String,
    val forwarded: Boolean,
)

/** 可被转发的应用。 */
data class AppRule(val packageName: String, val appName: String, val enabled: Boolean)
