package com.ted.shouhuan.proto

import com.ted.shouhuan.data.SleepNightRecord
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** 一分钟的活动样本 —— MB4/MB5 的 8 字节扩展格式。 */
data class MinuteSample(
    val time: LocalDateTime,
    val rawKind: Int,
    val intensity: Int,
    val steps: Int,
    val heartRate: Int,
    val isLight: Boolean,
    val isDeep: Boolean,
    val isRem: Boolean,
)

/** 手环对「取数请求」的应答：有多少字节、从什么时候开始。 */
data class FetchStartInfo(
    val expectedBytes: Int,
    val start: LocalDateTime,
)

/**
 * 活动数据同步的字节层（含睡眠）。
 *
 * 取值来源：Gadgetbridge 的 AbstractFetchOperation / FetchActivityOperation /
 * MiBand5Support，真机 Mi Band 5。不走 chunked 通道：
 *   - 00000004：写取数指令，收元数据应答（长度、起点、完成标记）
 *   - 00000005：推样本，每包首字节是递增序号，其余是 8 字节一条的分钟样本
 *
 * 流程（[com.ted.shouhuan.proto.BandSession.syncActivity] 编排）：
 *   ① 写 [startRequest]     → ② 应答 [parseStartResponse]
 *   ③ 写 0x02 取数          → ④ 00000005 收样本到 expectedBytes
 *   ⑤ 收到「传输完成」元数据 → ⑥ 解析样本、归并成夜 → ⑦ 写 0x03 ack（手环据此清掉本地）
 */
object ActivitySync {

    const val CMD_START_DATE = 0x01
    const val CMD_FETCH = 0x02
    const val CMD_ACK = 0x03
    const val RESPONSE = 0x10
    const val SUCCESS = 0x01

    /** 取数类型：0x01 = 活动明细（含睡眠分期）。 */
    const val FETCH_TYPE_ACTIVITY = 0x01

    /** 每条样本的字节数与时间步长（1 分钟）。 */
    const val SAMPLE_SIZE = 8

    /**
     * 时间字节（8 字节）：[年 LO, 年 HI, 月, 日, 时, 分, 秒, 时区]。
     * 年小端；时区按 15 分钟一档（含夏令时偏移）—— GB calendarToRawBytes 同款。
     */
    fun timeBytes(at: ZonedDateTime): ByteArray {
        val tzQuarter = at.offset.totalSeconds / (15 * 60)
        return byteArrayOf(
            (at.year and 0xff).toByte(),
            ((at.year shr 8) and 0xff).toByte(),
            at.monthValue.toByte(),
            at.dayOfMonth.toByte(),
            at.hour.toByte(),
            at.minute.toByte(),
            at.second.toByte(),
            tzQuarter.toByte(),
        )
    }

    /** ① 取数请求：[0x01, 类型, 时间 8 字节]，含义是「把这个时间之后的数据给我」。 */
    fun startRequest(since: ZonedDateTime, type: Int = FETCH_TYPE_ACTIVITY): ByteArray =
        byteArrayOf(CMD_START_DATE.toByte(), type.toByte()) + timeBytes(since)

    /**
     * ② 起始应答：[0x10, 0x01, 状态, 长度 uint32 LE, 时间 8 字节]（共 15 字节；
     * 个别固件在尾部多一个 0x00，共 16 字节）。长度是「接下来的样本字节数」。
     */
    fun parseStartResponse(value: ByteArray): FetchStartInfo? {
        if (value.size < 15) return null
        if ((value[0].toInt() and 0xff) != RESPONSE) return null
        if ((value[1].toInt() and 0xff) != CMD_START_DATE) return null
        if ((value[2].toInt() and 0xff) != SUCCESS) return null
        val expected = (value[3].toInt() and 0xff) or
            ((value[4].toInt() and 0xff) shl 8) or
            ((value[5].toInt() and 0xff) shl 16) or
            ((value[6].toInt() and 0xff) shl 24)
        return FetchStartInfo(expectedBytes = expected, start = parseTimeBytes(value, 7))
    }

    /** 时间字节 → 本地时间。手环时钟和手机对过时（连接时同步），按本地时间解读。 */
    private fun parseTimeBytes(value: ByteArray, offset: Int): LocalDateTime =
        LocalDateTime.of(
            (value[offset].toInt() and 0xff) or ((value[offset + 1].toInt() and 0xff) shl 8),
            (value[offset + 2].toInt() and 0xff).coerceIn(1, 12),
            (value[offset + 3].toInt() and 0xff).coerceIn(1, 31),
            (value[offset + 4].toInt() and 0xff).coerceIn(0, 23),
            (value[offset + 5].toInt() and 0xff).coerceIn(0, 59),
            (value[offset + 6].toInt() and 0xff).coerceIn(0, 59),
        )

    /**
     * ④ 样本缓冲 → 每分钟一条。8 字节布局：
     * [rawKind, 强度, 步数, 心率, 未知, 浅睡?, 深睡?, REM?]，后三个是 0/1 标志。
     */
    fun parseSamples(bytes: ByteArray, start: LocalDateTime): List<MinuteSample> {
        val out = ArrayList<MinuteSample>(bytes.size / SAMPLE_SIZE)
        var t = start
        var i = 0
        while (i + SAMPLE_SIZE <= bytes.size) {
            out.add(
                MinuteSample(
                    time = t,
                    rawKind = bytes[i].toInt() and 0xff,
                    intensity = bytes[i + 1].toInt() and 0xff,
                    steps = bytes[i + 2].toInt() and 0xff,
                    heartRate = bytes[i + 3].toInt() and 0xff,
                    isLight = bytes[i + 5].toInt() != 0,
                    isDeep = bytes[i + 6].toInt() != 0,
                    isRem = bytes[i + 7].toInt() != 0,
                ),
            )
            t = t.plusMinutes(1)
            i += SAMPLE_SIZE
        }
        return out
    }

    /** 分期判定优先级（GB 同款）：REM > 深睡 > 浅睡，三旗皆 0 = 清醒/活动。 */
    private fun isSleep(m: MinuteSample) = m.isLight || m.isDeep || m.isRem

    /**
     * 把分钟样本归并成夜。
     *
     * 相邻的睡眠分钟聚成一觉，中间允许 ≤60 分钟的清醒间隙（夜里翻身/上厕所）；
     * 超过 60 分钟就算另一觉 —— 午睡和夜觉天然分开。不足 30 分钟的碎片当噪声丢掉。
     */
    fun nightsFromSamples(samples: List<MinuteSample>): List<SleepNightRecord> {
        val sleepMinutes = samples.filter(::isSleep).distinctBy { it.time }.sortedBy { it.time }
        if (sleepMinutes.isEmpty()) return emptyList()

        val nights = ArrayList<SleepNightRecord>()
        val cluster = ArrayList<MinuteSample>()
        var last: MinuteSample? = null
        for (m in sleepMinutes) {
            val prev = last
            if (prev != null && Duration.between(prev.time, m.time).toMinutes() > MAX_AWAKE_GAP_MINUTES) {
                clusterToNight(cluster)?.let(nights::add)
                cluster.clear()
            }
            cluster.add(m)
            last = m
        }
        clusterToNight(cluster)?.let(nights::add)
        return nights
    }

    private fun clusterToNight(cluster: List<MinuteSample>): SleepNightRecord? {
        if (cluster.size < MIN_NIGHT_MINUTES) return null
        val bed = cluster.first().time
        val wake = cluster.last().time.plusMinutes(1)
        val total = cluster.size
        val deep = cluster.count { it.isDeep }
        val rem = cluster.count { it.isRem }
        val light = (total - deep - rem).coerceAtLeast(0)
        val awake = (Duration.between(bed, wake).toMinutes() - total).coerceAtLeast(0).toInt()
        return SleepNightRecord(
            epochDay = wake.toLocalDate().toEpochDay(),   // 记账口径：醒来那天
            totalMinutes = total,
            score = sleepScore(total, deep, rem),
            bedMinutes = minuteOfDay(bed),
            wakeMinutes = minuteOfDay(wake),
            deepMinutes = deep,
            lightMinutes = light,
            remMinutes = rem,
            awakeMinutes = awake,
        )
    }

    private fun minuteOfDay(t: LocalDateTime): Int = t.hour * 60 + t.minute

    /**
     * 手环不给睡眠评分，这里按时长和深睡/REM 占比粗算一个（0..100）。
     * 自研口径 —— 界面要有个数；等找到官方算法再替换。
     */
    private fun sleepScore(total: Int, deep: Int, rem: Int): Int {
        val durationBonus = when {
            total < 300 -> -10f
            total < 390 -> (total - 300) / 90f * 10f
            total <= 540 -> 10f + (total - 390) / 150f * 8f
            else -> 18f - (total - 540) / 60f * 2f
        }
        val deepShare = if (total > 0) deep.toFloat() / total else 0f
        val remShare = if (total > 0) rem.toFloat() / total else 0f
        return (60f + durationBonus + deepShare * 40f + remShare * 15f).toInt().coerceIn(0, 100)
    }

    private const val MAX_AWAKE_GAP_MINUTES = 60L
    private const val MIN_NIGHT_MINUTES = 30
}
