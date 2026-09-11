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

/**
 * 一次完成的测量结果。
 *
 * 光记一个 BPM 不够用：回头看记录时还想知道「这是什么时候测的、等了多久」。
 * 所以时间戳和耗时一起留下来，界面直接摆出来，不用靠猜。
 *
 * 放在 data 层（而不是心率页）是因为它要被 [BandPrefs] 持久化 —— 换了界面它还得在。
 */
data class MeasureResult(
    val bpm: Int,
    /** 拿到读数的那一刻（epoch 毫秒）。 */
    val finishedAtMillis: Long,
    /** 从点下「测量」到拿到读数花了多久（秒）。 */
    val durationSec: Int,
)

