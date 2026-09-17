package com.ted.shouhuan.proto

import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.data.SleepStage
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 一分钟的活动样本 —— MB4/MB5 的 8 字节扩展格式
 * （字节切片对齐 Gadgetbridge `FetchActivityOperation.createExtendedSample`）。
 */
data class MinuteSample(
    val time: LocalDateTime,

    /** 样本类型：[KIND_SLEEP] = 睡眠分钟（仅华米新一代固件用），其余是活动 / 未佩戴 / 充电。 */
    val kind: Int,
    val intensity: Int,
    val steps: Int,
    val heartRate: Int,

    /** 浅睡强度（byte5，本身只有 7 位，0 = 这一分钟没有睡眠强度）。 */
    val sleepLevel: Int,

    /** 深睡强度原始值（byte6）—— **最高位 0x80 是标志位**，见 [ActivitySync.NO_SLEEP_STAGE]。 */
    val deepRaw: Int,

    /** REM 强度原始值（byte7），同 [deepRaw]。 */
    val remRaw: Int,
) {
    val deepLevel: Int get() = deepRaw and 0x7f
    val remLevel: Int get() = remRaw and 0x7f

    /** 手环在这一分钟给了睡眠分期数据（= 手环认为「这一分钟在睡」），见 [ActivitySync.isSleepMinute]。 */
    val hasSleepStage: Boolean get() = deepRaw != ActivitySync.NO_SLEEP_STAGE ||
        remRaw != ActivitySync.NO_SLEEP_STAGE
}

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
 *   ⑤ 收到「传输完成」元数据 → ⑥ 解析样本、归并成夜
 *
 * 睡眠判据见 [isSleepMinute]（**不是 kind == 0x78**，那是新一代固件的格式）。
 *
 * [CMD_ACK]（0x03，GB 用它告诉手环「这批我拿走了」）我们**从不发**：同步不清手环数据，
 * 手环下次会把同一窗口重复推一遍，入库端按时间幂等去重。详见 syncActivity 的注释。
 */
object ActivitySync {

    const val CMD_START_DATE = 0x01
    const val CMD_FETCH = 0x02

    /** 手环收到才删已传数据 —— 协议里认识这个命令，但本项目不发（数据保留在手环上）。 */
    const val CMD_ACK = 0x03
    const val RESPONSE = 0x10
    const val SUCCESS = 0x01

    /** 取数类型：0x01 = 活动明细（含睡眠分期）。 */
    const val FETCH_TYPE_ACTIVITY = 0x01

    /** 每条样本的字节数与时间步长（1 分钟）。 */
    const val SAMPLE_SIZE = 8

    /**
     * 深睡 / REM 强度字段的「这一分钟没有分期数据」哨兵值。
     * 实测 MB5 上这两个字节**恒 ≥ 0x80**（3216 分钟原始字节里一个例外都没有），
     * 所以最高位是标志位、低 7 位是强度。
     */
    const val NO_SLEEP_STAGE = 0x80

    /**
     * 华米**新一代**（Zepp OS，GTS/GTR 2+）的「睡眠分钟」类型号
     * —— GB `HuamiExtendedSampleProvider.TYPE_SLEEP`（120）。
     *
     * ⚠️ **Mi Band 5 不会给这个值**。别拿它判 MB5 的睡眠，见 [isSleepMinute]。
     */
    const val KIND_SLEEP = 0x78

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
     * ② 起始应答：[0x10, 0x01, 状态, 采样数 uint32 LE, 时间 8 字节]（共 15 字节；
     * 个别固件在尾部多一个 0x00，共 16 字节）。
     *
     * 那个 uint32 实测是**采样条数**不是字节数 —— 真机两次分毫不差地对上
     * （2738 × 8 = 21904、8568 × 8 = 68544）。这里换算成字节，调用方统一按字节记账。
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
        return FetchStartInfo(expectedBytes = expected * SAMPLE_SIZE, start = parseTimeBytes(value, 7))
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
     * `[kind, 强度, 步数, 心率, 未知, 浅睡强度, 深睡强度, REM 强度]`。
     *
     * 注意后三个字节**不是 0/1 旗标**：真机数据里清醒分钟是 `00 80 80` 这种组合 ——
     * 浅睡强度 0、深睡/REM 都是 0x80（[NO_SLEEP_STAGE] = 这一分钟没有分期数据）。
     * 睡着时这两个字段换成真强度（低 7 位）。0x80 是标志位而不是「真」，强度要从
     * `& 0x7f` 里取 —— 当初当成旗标解析，把整周清醒全判成了深睡+REM。
     * 分期阈值见 [stageOf]，睡眠判据见 [isSleepMinute]。
     */
    fun parseSamples(bytes: ByteArray, start: LocalDateTime): List<MinuteSample> {
        val out = ArrayList<MinuteSample>(bytes.size / SAMPLE_SIZE)
        var t = start
        var i = 0
        while (i + SAMPLE_SIZE <= bytes.size) {
            out.add(
                MinuteSample(
                    time = t,
                    kind = bytes[i].toInt() and 0xff,
                    intensity = bytes[i + 1].toInt() and 0xff,
                    steps = bytes[i + 2].toInt() and 0xff,
                    heartRate = bytes[i + 3].toInt() and 0xff,
                    sleepLevel = bytes[i + 5].toInt() and 0x7f,
                    deepRaw = bytes[i + 6].toInt() and 0xff,
                    remRaw = bytes[i + 7].toInt() and 0xff,
                ),
            )
            t = t.plusMinutes(1)
            i += SAMPLE_SIZE
        }
        return out
    }

    /**
     * 睡眠样本的分期（GB HuamiExtendedSampleProvider.postProcess 的阈值，
     * 上游注释明说这些数是经验值，但和手环屏幕显示基本一致）：
     * REM 强度 > 55 判 REM，其次深睡强度 > 42 判深睡，其余都算浅睡。
     */
    fun stageOf(m: MinuteSample): SleepStage = when {
        m.remLevel > 55 -> SleepStage.REM
        m.deepLevel > 42 -> SleepStage.DEEP
        else -> SleepStage.LIGHT
    }

    /**
     * 这一分钟算不算「睡眠」。
     *
     * **不是** `kind == 0x78`。0x78 是华米新一代（Zepp OS：GTS/GTR 2+）的活动数据格式，
     * Mi Band 5 的 kind 字节从来不给这个值 —— 实测 7 天全量 10075 分钟里一个都没有，
     * 于是睡眠页恒为 0 夜（当时的排查把锅甩给了「官方 App 抢数据」，那是误判，
     * 详见 docs/sleep-sync.md）。
     *
     * MB5 把「这一分钟在睡」标在**分期字段**上：睡着时填 byte6/byte7（深睡/REM 强度），
     * 醒着时写 [NO_SLEEP_STAGE]（0x80，无数据）。所以判据就是「分期字段有没有被填」。
     *
     * 实测校验（2026-09-16，3216 分钟真机原始字节）：
     * ```
     * 判据                       睡眠分钟  夜数   → 各夜心率有值率 / 步数非零 / 深睡占比 / REM 占比
     * kind == 0x78（原实现）          0     0    —— 一个 0x78 都没有
     * 分期字段被填（本实现）          648    4    → 89~100% / ≤1 分钟 / 21~27% / 9~18%
     * 浅睡强度 byte5 != 0            1136    7    → 多出 3 段白天假睡眠（心率 0%、有步数）
     * ```
     * 只有「分期字段被填」这一条同时满足：心率全程有值（手环只在睡眠时每分钟测心率）、
     * 步数为 0、深睡/REM 占比落在真实睡眠区间。
     */
    fun isSleepMinute(m: MinuteSample): Boolean =
        m.kind == KIND_SLEEP || m.hasSleepStage

    /**
     * 把分钟样本归并成夜。
     *
     * 只有 [isSleepMinute] 的分钟算睡眠（清醒/活动分钟那三个强度字节也是
     * 非零的，不能用强度判）。相邻的睡眠分钟聚成一觉，中间允许 ≤60 分钟的清醒
     * 间隙（夜里翻身/上厕所）；超过 60 分钟就算另一觉 —— 午睡和夜觉天然分开。
     * 不足 30 分钟的碎片当噪声丢掉。
     */
    fun nightsFromSamples(samples: List<MinuteSample>): List<SleepNightRecord> {
        val distinct = samples.distinctBy { it.time }.sortedBy { it.time }
        val sleepMinutes = distinct.filter { isSleepMinute(it) }
        if (sleepMinutes.isEmpty()) return emptyList()
        // 全量样本（含清醒分钟）按时间索引，供「已醒」统计睡眠时段内的真实分钟数。
        val samplesByTime = distinct.associateBy { it.time }

        val nights = ArrayList<SleepNightRecord>()
        val cluster = ArrayList<MinuteSample>()
        var last: MinuteSample? = null
        for (m in sleepMinutes) {
            val prev = last
            if (prev != null && Duration.between(prev.time, m.time).toMinutes() > MAX_AWAKE_GAP_MINUTES) {
                clusterToNight(cluster, samplesByTime)?.let(nights::add)
                cluster.clear()
            }
            cluster.add(m)
            last = m
        }
        clusterToNight(cluster, samplesByTime)?.let(nights::add)
        return nights
    }

    private fun clusterToNight(
        cluster: List<MinuteSample>,
        samplesByTime: Map<LocalDateTime, MinuteSample>,
    ): SleepNightRecord? {
        if (cluster.size < MIN_NIGHT_MINUTES) return null
        val bed = cluster.first().time
        val wake = cluster.last().time.plusMinutes(1)
        val total = cluster.size
        val deep = cluster.count { stageOf(it) == SleepStage.DEEP }
        val rem = cluster.count { stageOf(it) == SleepStage.REM }
        val light = (total - deep - rem).coerceAtLeast(0)
        // 清醒 = 睡眠时段里「有样本、但不是睡眠」的分钟：手环每分钟一个样本，
        // 清醒分钟以 `00 80 80` 形态留在流里，数它们就行。
        // 注意不能拿「跨度 - 睡眠分钟」来算 —— 被官方 App 同步删除过的时段在流里
        // 是**真空洞**（没有样本），那不是清醒，算进去会让「已醒」虚高几十分钟。
        val awake = (samplesByTime.keys.count { it >= bed && it < wake } - total)
            .coerceAtLeast(0)
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
     * Health Connect 导入的外部记录也用它，两个来源的分数才有可比性。
     */
    fun sleepScore(total: Int, deep: Int, rem: Int): Int {
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
