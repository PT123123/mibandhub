package com.ted.shouhuan.debug

import android.content.Context
import android.util.Log
import com.ted.shouhuan.ble.BandConnection
import com.ted.shouhuan.ble.Gatt
import com.ted.shouhuan.proto.Auth
import com.ted.shouhuan.proto.AuthEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.UUID

/**
 * 活动数据（含睡眠）拉取实验台 —— **仅 debug 构建**，release 里不存在。
 *
 * 要回答的问题：**这台 Mi Band 5 能不能把记录好的活动数据交给我们？**
 *
 * 协议照 Gadgetbridge 的 `AbstractFetchOperation` / `FetchActivityOperation` 写。
 * 华米的活动数据走两条私有特征，一问一答：
 *
 * ```
 *                     00000004 = 控制（写命令 + 回元数据）
 *                     00000005 = 数据（只推采样）
 *
 * 手机 → 04: 01 01 <年 u16le> <月> <日> <时> <分> 00 <时区>   ① 从某时刻起，要活动数据
 * 手环 → 04: 10 01 01 <字节数 u32le> <起始时间 8B>              ② 有这么多要传
 * 手机 → 04: 02                                               ③ 开始传
 * 手机 →        （同时订阅 05）
 * 手环 → 05: <计数器 1B> <采样数据 …>                           ④ 一批批推
 * 手环 → 04: 10 02 01 <crc32 u32le>                            ⑤ 传完了
 * 手机 → 04: 03                                               ⑥ 收到了（手环才会删）
 * ```
 *
 * ⚠️ ⑥ 很重要：不回这条 ACK，数据会一直留着手环上、下次还会重复推。
 * 所以实验台**默认都会回 ACK**（哪怕只是想看一眼数据）。
 *
 * 用法（在 PC 上）：
 * ```
 * adb shell am start -n com.ted.shouhuan/.debug.ActivityLabActivity \
 *     --es mac AA:BB:CC:DD:EE:FF --es key 0123… --ei days 2
 * adb logcat -s ActivityLab
 * ```
 *
 * `--ei days N` 表示「从 N 天前的那一分钟开始要」。不传默认 1 天。
 * 密钥只从 intent extra 传，不写进源码。
 */
object ActivityLab {

    const val TAG = "ActivityLab"

    /** 控制特征：命令与元数据。 */
    val CHAR_CONTROL: UUID = UUID.fromString("00000004-0000-3512-2118-0009af100700")

    /** 数据特征：只推采样。 */
    val CHAR_DATA: UUID = UUID.fromString("00000005-0000-3512-2118-0009af100700")

    private const val RESPONSE = 0x10
    private const val SUCCESS = 0x01

    private const val CMD_START_DATE = 0x01
    private const val CMD_FETCH_DATA = 0x02
    private const val CMD_ACK = 0x03

    /** `HuamiFetchDataType.ACTIVITY`。 */
    private const val FETCH_TYPE_ACTIVITY = 0x01

    /**
     * 每个采样多少字节 —— **实测就是 8**。
     *
     * 这条是拿真机数据验出来的，不是照抄：手环在「有多少要传」的应答里
     * 给的数字（`10 01 01 <n> …`）是**采样数**，实测 n=2738，
     * 而收下来的字节数 21904 ÷ 8 = 2738，一个不差。
     *
     * 有意思的是 Gadgetbridge 对此**用的是 4**（`mActivitySampleSize` 的默认值，
     * 只有 Amazfit GTR/GTS 那几款设成 8）—— 也就是说它按 4 字节切 Mi Band 5 的数据。
     * 我们以实测为准。
     *
     * 布局（对齐 `HuamiExtendedActivitySample`）：
     * `kind, intensity, steps, heartRate, unknown1, sleep, deepSleep, remSleep`
     */
    private const val SAMPLE_SIZE = 8

    private const val AUTH_TIMEOUT_MS = 8_000L

    /** 等「有多少数据要传」的应答。 */
    private const val META_TIMEOUT_MS = 30_000L

    /** 等整批数据传完 —— 数据量随天数涨，给宽一点。 */
    private const val DATA_TIMEOUT_MS = 120_000L

    /**
     * 拉一次活动数据并打印结果。
     *
     * @param daysBack 从几天前开始要（分钟精度）。
     * @return 是否成功拿到一批数据。
     */
    suspend fun run(
        context: Context,
        mac: String,
        keyHex: String,
        daysBack: Int,
    ): Boolean = coroutineScope {
        val since = GregorianCalendar().apply {
            add(Calendar.DAY_OF_MONTH, -daysBack)
        }
        Log.i(TAG, "════ ════ 活动数据拉取实验 ════ ════")
        Log.i(TAG, "起始时间 = ${since.time}（$daysBack 天前，分钟精度）")

        val key = Auth.parseKey(keyHex)
        val conn = BandConnection(context)
        val authIn = Channel<ByteArray>(Channel.UNLIMITED)
        val authPump = launch {
            conn.incoming.collect {
                if (it.characteristic == Gatt.CHAR_AUTH) authIn.send(it.value)
            }
        }

        val buffer = ByteArrayOutputStream(64 * 1024)
        /** `10 01 01 <字节数> <起始时间>` —— 手环说这次有多少要传。 */
        val startAck = CompletableDeferred<ByteArray>()
        /** `10 02 01 <crc32>` —— 传完了。 */
        val finished = CompletableDeferred<ByteArray>()
        var lastCounter = -1

        // 原始形态：包长分布 + 头几个包的原文。解析对不上时，只有原文能说明问题。
        val packetLengths = sortedMapOf<Int, Int>()
        val rawHead = mutableListOf<String>()

        // 数据与元数据从 BandConnection 的活动专用队列回来（00000004/00000005 已不再走
        // SharedFlow，见 activityQueue 的注释：同步速率下会丢包、序号跳变）。
        // for 循环持续消费 —— 别对队列反复 first{}，订阅间隙同样会漏包。
        val dataPump = launch {
            for (msg in conn.activityQueue()) {
                when (msg.characteristic) {
                    CHAR_DATA -> {
                        val v = msg.value
                        if (v.isEmpty()) continue
                        packetLengths[v.size] = (packetLengths[v.size] ?: 0) + 1
                        if (rawHead.size < 3) rawHead.add(hex(v))
                        val counter = v[0].toInt() and 0xff
                        val expected = (lastCounter + 1) and 0xff
                        if (counter == expected) {
                            lastCounter = counter
                            buffer.write(v, 1, v.size - 1)
                        } else {
                            // 丢包/重包：上游在这种情况下会把整轮作废，这里先照实记下来。
                            Log.w(TAG, "包计数器不对：期望 $expected，收到 $counter —— 这一轮数据不完整")
                        }
                    }

                    CHAR_CONTROL -> {
                        val v = msg.value
                        if (v.size >= 2 && (v[0].toInt() and 0xff) == RESPONSE) {
                            when (v[1].toInt() and 0xff) {
                                CMD_START_DATE -> startAck.complete(v)
                                CMD_FETCH_DATA -> finished.complete(v)
                                else -> Log.i(TAG, "元数据 <- ${hex(v)}")
                            }
                        } else {
                            Log.i(TAG, "控制 <- ${hex(v)}")
                        }
                    }
                }
            }
        }

        try {
            if (!conn.connect(mac)) {
                Log.e(TAG, "✗ 连接失败：${conn.state.value}")
                return@coroutineScope false
            }
            Log.i(TAG, "✓ 已连接")
            Log.i(TAG, "   控制特征存在 = ${conn.hasCharacteristic(CHAR_CONTROL)}")
            Log.i(TAG, "   数据特征存在 = ${conn.hasCharacteristic(CHAR_DATA)}")

            if (!conn.enableNotify(Gatt.CHAR_AUTH)) {
                Log.e(TAG, "✗ 订阅认证特征失败")
                return@coroutineScope false
            }
            if (!authenticate(conn, key, authIn)) return@coroutineScope false

            if (!conn.enableNotify(CHAR_CONTROL)) {
                Log.e(TAG, "✗ 订阅控制特征失败 —— 收不到元数据，后面没法走")
                return@coroutineScope false
            }

            // ---- ① 从某时刻起要活动数据 ----
            val start = byteArrayOf(CMD_START_DATE.toByte(), FETCH_TYPE_ACTIVITY.toByte()) +
                timeBytes(since)
            Log.i(TAG, "① -> ${hex(start)}")
            if (!conn.write(CHAR_CONTROL, start)) {
                Log.e(TAG, "✗ 起始命令写入失败")
                return@coroutineScope false
            }

            // ---- ② 手环说有多少 ----
            val ack = withTimeoutOrNull(META_TIMEOUT_MS) { startAck.await() }
            if (ack == null) {
                Log.e(TAG, "✗ 等不到手环的回应（${META_TIMEOUT_MS / 1000}s）")
                Log.e(TAG, "   这条通道可能就是不通，或者手环手上没有这段时间的数据")
                return@coroutineScope false
            }
            Log.i(TAG, "② <- ${hex(ack)}")
            if (ack.size < 3 || (ack[2].toInt() and 0xff) != SUCCESS) {
                Log.e(TAG, "✗ 手环拒了这次拉取（期望第三个字节是 01）")
                return@coroutineScope false
            }
            // 这个数是**采样数**，不是字节数 —— 实测 2738 采样 × 8 字节 = 收到的 21904 字节。
            val expectedSamples = u32(ack, 3)
            Log.i(TAG, "   手环说这次有 $expectedSamples 个采样")

            // ---- ③ 开始传（先订阅数据特征，别漏掉开头几包）----
            if (!conn.enableNotify(CHAR_DATA)) {
                Log.e(TAG, "✗ 订阅数据特征失败")
                return@coroutineScope false
            }
            Log.i(TAG, "③ -> 02")
            if (!conn.write(CHAR_CONTROL, byteArrayOf(CMD_FETCH_DATA.toByte()))) {
                Log.e(TAG, "✗ 开始命令写入失败")
                return@coroutineScope false
            }

            // ---- ④ 等数据推完 ----
            // ⚠️ `10 02 04` **不是**「传完了」—— 实测数据是在它之后才开始推的。
            // 真正的完成条件是「收够声明的采样数」，或者推流安静下来。
            val targetBytes = expectedSamples * SAMPLE_SIZE
            val ticker = launch {
                var last = 0
                while (true) {
                    delay(1_000)
                    val got = buffer.size()
                    if (got != last) {
                        last = got
                        Log.i(TAG, "   已收 $got 字节")
                    }
                }
            }
            var lastSize = 0
            var quietSince = System.currentTimeMillis()
            val deadline = System.currentTimeMillis() + DATA_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(500)
                val size = buffer.size()
                if (size != lastSize) {
                    lastSize = size
                    quietSince = System.currentTimeMillis()
                }
                if (targetBytes > 0 && size >= targetBytes) break
                if (System.currentTimeMillis() - quietSince > 3_000) break
            }
            ticker.cancel()

            if (finished.isCompleted) {
                Log.i(TAG, "⑤ <- ${hex(finished.getCompleted())}")
            } else {
                Log.w(TAG, "   没等到 `10 02 xx` 收尾信号")
            }
            Log.i(
                TAG,
                "   收完：${buffer.size()} / $targetBytes 字节" +
                    "（目标 = $expectedSamples 采样 × $SAMPLE_SIZE 字节）",
            )

            // ---- ⑥ 回 ACK（不回的话手环会一直留着、下次重复推）----
            conn.write(CHAR_CONTROL, byteArrayOf(CMD_ACK.toByte()))
            Log.i(TAG, "⑥ -> 03（已回 ACK）")

            // ---- 打印结果 ----
            val bytes = buffer.toByteArray()
            Log.i(TAG, "总数据 ${bytes.size} 字节")
            if (bytes.isEmpty()) {
                Log.w(TAG, "◐ 一字节都没收到 —— 协议对不上，或者手环手上没有这段时间的数据")
                return@coroutineScope false
            }
            // ---- 原始形态：解析对不上时，只有原文能说明问题 ----
            Log.i(TAG, "包长分布：${packetLengths.entries.joinToString { "${it.key}B×${it.value}" }}")
            rawHead.forEachIndexed { i, raw -> Log.i(TAG, "   包#$i = $raw") }
            Log.i(
                TAG,
                "前 64 字节 = " + hex(bytes.copyOfRange(0, minOf(64, bytes.size))),
            )

            val samples = bytes.size / SAMPLE_SIZE
            val remainder = bytes.size % SAMPLE_SIZE
            Log.i(TAG, "采样数 = $samples（每个 $SAMPLE_SIZE 字节，余 $remainder 字节）")
            if (remainder != 0) {
                Log.w(TAG, "⚠ 余数不为 0 —— 采样大小可能不是 $SAMPLE_SIZE，解析会错位")
            }

            // 采样点从「手环给的起始时间」开始、每分钟一条。
            // 这里只打头尾各若干条，够看出数据长什么样。
            val preview = minOf(samples, 25)
            for (i in 0 until preview) {
                Log.i(TAG, "   #$i ${describeSample(bytes, i)}")
            }
            if (samples > preview) {
                Log.i(TAG, "   …")
                for (i in (samples - 5) until samples) {
                    Log.i(TAG, "   #$i ${describeSample(bytes, i)}")
                }
            }

            val kinds = (0 until samples).groupingBy { bytes[it * SAMPLE_SIZE].toInt() and 0xff }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
            Log.i(TAG, "kind 分布：${kinds.joinToString { "0x%02x×%d".format(it.key, it.value) }}")
            Log.i(TAG, "★ 结论：拿到 ${samples} 个采样 ✓")
            true
        } finally {
            authPump.cancel()
            dataPump.cancel()
            conn.close()
        }
    }

    // ------------------------------------------------------------------

    private suspend fun authenticate(
        conn: BandConnection,
        key: ByteArray,
        channel: Channel<ByteArray>,
    ): Boolean {
        if (!conn.write(Gatt.CHAR_AUTH, Auth.requestAuthNumberPacket())) {
            Log.e(TAG, "✗ 认证请求写入失败")
            return false
        }
        repeat(4) {
            val v = withTimeoutOrNull(AUTH_TIMEOUT_MS) { channel.receive() } ?: run {
                Log.e(TAG, "✗ 认证超时（手环没回应）")
                return false
            }
            Log.i(TAG, "   认证 <- ${hex(v)}")
            when (val event = Auth.classify(v)) {
                is AuthEvent.Challenge ->
                    conn.write(Gatt.CHAR_AUTH, Auth.encryptedPacket(event.value, key))

                AuthEvent.Authenticated -> {
                    Log.i(TAG, "   ✓ 认证通过")
                    return true
                }

                else -> Unit
            }
        }
        Log.e(TAG, "✗ 认证没走完")
        return false
    }

    /**
     * 时间字段：`年 u16le, 月, 日, 时, 分, 0, 时区`。
     *
     * 对齐上游 `HuamiSupport.getTimeBytes(cal, MINUTES)` +
     * `BLETypeConversions.shortCalendarToRawBytes`。末尾那个时区是**15 分钟**为单位
     * 的东经偏移（中国是 +8 小时 → 8×4 = 32 = 0x20）。
     */
    private fun timeBytes(calendar: Calendar): ByteArray {
        val year = calendar.get(Calendar.YEAR)
        val offsetQuarterHours =
            (calendar.timeZone.getOffset(calendar.timeInMillis) / (1000 * 60 * 15)).toByte()
        return byteArrayOf(
            (year and 0xff).toByte(),
            ((year ushr 8) and 0xff).toByte(),
            (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DATE).toByte(),
            calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(),
            0x00,
            offsetQuarterHours,
        )
    }

    /** 把第 [index] 个采样打成一行给人看。 */
    private fun describeSample(bytes: ByteArray, index: Int): String {
        val o = index * SAMPLE_SIZE
        return "kind=0x%02x intensity=%d steps=%d hr=%d | unk=%d sleep=%d deep=%d rem=%d".format(
            bytes[o].toInt() and 0xff,
            bytes[o + 1].toInt() and 0xff,
            bytes[o + 2].toInt() and 0xff,
            bytes[o + 3].toInt() and 0xff,
            bytes[o + 4].toInt() and 0xff,
            bytes[o + 5].toInt() and 0xff,
            bytes[o + 6].toInt() and 0xff,
            bytes[o + 7].toInt() and 0xff,
        )
    }

    /** 小端 u32。 */
    private fun u32(value: ByteArray, offset: Int): Int =
        if (value.size < offset + 4) {
            0
        } else {
            (value[offset].toInt() and 0xff) or
                ((value[offset + 1].toInt() and 0xff) shl 8) or
                ((value[offset + 2].toInt() and 0xff) shl 16) or
                ((value[offset + 3].toInt() and 0xff) shl 24)
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}
