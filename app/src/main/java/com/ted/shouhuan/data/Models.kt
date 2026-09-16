package com.ted.shouhuan.data

/** 睡眠分期。手环活动数据里的 rawKind 映射到这四个状态。 */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

/** 心率采样点。minuteOfDay 是当天的第几分钟，便于直接铺在时间轴上。 */
data class HeartRatePoint(val minuteOfDay: Int, val bpm: Int)

/**
 * 一条分钟级心率样本，[BandPrefs] 持久化（桌面控件的心率曲线就吃这份数据）。
 *
 * 来源是手环活动同步：分钟样本里 heartRate > 0 的那些分钟。epochMillis 用
 * 本地时区换算，和睡眠夜的 epochDay 口径一致 —— 手环时钟连上时同步过。
 */
data class HeartRateSample(
    /** epoch 毫秒。 */
    val atMillis: Long,
    val bpm: Int,
)

data class SleepStageShare(val stage: SleepStage, val minutes: Int)

/**
 * 一晚睡眠的可持久化形态（[BandPrefs] 存储，不设条数上限）。
 *
 * epochDay 是醒来那天的 LocalDate.toEpochDay()；bed/wake 是「当天第几分钟」，
 * 入睡那侧跨零点属正常（如 23:41），展示时直接按钟点格式化。
 * totalMinutes 只含深睡+浅睡+REM，清醒分钟单独记 —— 和主流手环 App 的口径一致。
 */
data class SleepNightRecord(
    val epochDay: Long,
    val totalMinutes: Int,
    val score: Int,
    val bedMinutes: Int,
    val wakeMinutes: Int,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val remMinutes: Int,
    val awakeMinutes: Int,
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
data class AppRule(
    val packageName: String,
    val appName: String,
    val enabled: Boolean,
    /** 是否把通知正文一起推到手环；false = 只推应用名 + 标题。 */
    val showDetail: Boolean = true,
)

/** 手机上装的应用 —— 「添加应用」列表里可选的那一份。 */
data class InstalledApp(val packageName: String, val label: String)

/**
 * 一次手环电量读数。
 *
 * 只记「变了的时候」：手环电量按 1% 跳，同一个数字重复记没有信息量，还白灌
 * DataStore。有了时间点 + 电量，相邻两点的差就能算出「多久耗多少电」。
 */
data class BatterySample(val atMillis: Long, val percent: Int)

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

