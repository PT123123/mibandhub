package com.ted.shouhuan.debug

import android.content.Context
import android.util.Log
import com.ted.shouhuan.ble.BandConnection
import com.ted.shouhuan.ble.Gatt
import com.ted.shouhuan.proto.Auth
import com.ted.shouhuan.proto.BandSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 认证实验台（**仅 debug 构建**，release 里不存在）。
 *
 * 目的：把 Mi Band 5 认证握手里几个「说不清该是多少」的字节拆成独立变量，
 * 用 adb 一次跑一个组合，把**写出去和收回来的每一条真实字节**打到 logcat。
 *
 * 拆出来的四个变量：
 *   1. sendKey    —— 要不要先发第 ① 步 `01 <af> <key>`
 *   2. authFlags  —— 第 2 个字节。**这台固件只认 0x00**，Gadgetbridge 用的 0x08 会被回
 *                    `10 83 07` 拒掉最后一步
 *   3. cryptFlags —— 命令字节的高位。0x80 时随机数请求是 `82 <af> 02 01 00`
 *   4. randomReq  —— 随机数请求的形态：`<cf|02> <af> 02 01 00`（带尾巴，GB 风格）
 *                    还是 `<02> <af>` 两字节（mebeats 风格）
 *
 * 用法（在 PC 上）：
 * ```
 * adb shell am start -n com.ted.shouhuan/.debug.AuthLabActivity \
 *     --es variant cb --es mac AA:BB:CC:DD:EE:FF --es key 0123...
 * adb logcat -s AuthLab
 * ```
 *
 * `variant=real` 是个特例：不拼字节，直接跑生产路径 `BandSession`（连接 → 认证 → 实时心率），
 * 用来验证应用真正走的那条路。
 *
 * 密钥刻意不写进源码，只从 intent extra 传入。
 */
object AuthLab {

    const val TAG = "AuthLab"

    private const val AUTH_TIMEOUT_MS = 8_000L

    /** 跑生产路径时等心率读数的上限。 */
    private const val HR_WAIT_MS = 45_000L

    /** 随机数请求的两种形态。 */
    enum class RandomReq {
        /** `<cryptFlags|0x02> <authFlags> 0x02 0x01 0x00>` —— Gadgetbridge 风格。 */
        CRYPT_TAIL,

        /** `<0x02> <authFlags>` —— mebeats 风格，两字节。 */
        SHORT,
    }

    /**
     * 一个认证组合。
     *
     * @param sendKey 是否先发 `01 <authFlags> <key>`
     * @param authFlags 第 2 个字节
     * @param cryptFlags 0x80 或 0x00
     * @param randomReq 随机数请求形态
     */
    data class Variant(
        val id: String,
        val sendKey: Boolean,
        val authFlags: Int,
        val cryptFlags: Int,
        val randomReq: RandomReq,
        val note: String,
    )

    val VARIANTS: List<Variant> = listOf(
        Variant("ours", true, 0x08, 0x80, RandomReq.CRYPT_TAIL, "旧基线：三步 + authFlags=0x08"),
        Variant("gb", false, 0x08, 0x80, RandomReq.CRYPT_TAIL, "Gadgetbridge 原样（authFlags=0x08）"),
        Variant("cb", false, 0x00, 0x80, RandomReq.CRYPT_TAIL, "Codeberg #6007 解法（已采纳为正式流程）"),
        Variant("af0", true, 0x00, 0x80, RandomReq.CRYPT_TAIL, "旧基线只把 authFlags 改成 0x00"),
        Variant("mebeats", true, 0x00, 0x00, RandomReq.SHORT, "mebeats 原样（authFlags/cryptFlags 全 0）"),
    )

    /** `real` 不是字节组合，而是直接跑生产路径。 */
    const val REAL = "real"

    fun find(id: String): Variant? = VARIANTS.firstOrNull { it.id == id }

    fun variantIds(): String = (VARIANTS.map { it.id } + REAL).joinToString(", ")

    /**
     * 直接跑生产路径：`BandSession` 连接 → 认证 → 开实时心率 → 等一个读数。
     *
     * 和上面的字节组合是两回事 —— 这条跑的是**应用 UI 真正调用的代码**，
     * 改完认证逻辑后用它做端到端验证，不用手动点界面。
     */
    suspend fun runReal(context: Context, mac: String, authKey: String): Boolean = coroutineScope {
        Log.i(TAG, "════ ════ 生产路径（BandSession）════ ════ ════")
        Log.i(TAG, "mac=$mac")

        val session = BandSession(context, this)
        val logPump = launch {
            session.logs.collect { Log.i(TAG, "  [session] $it") }
        }

        try {
            val authed = try {
                session.connectAndAuthenticate(mac, authKey)
            } catch (t: Throwable) {
                Log.e(TAG, "✗ connectAndAuthenticate 抛异常：${t.javaClass.simpleName}: ${t.message}", t)
                false
            }
            Log.i(TAG, "认证结果 = $authed")
            if (!authed) return@coroutineScope false

            session.clearHeartRate()
            if (!session.startRealtimeHeartRate()) {
                Log.e(TAG, "✗ 启动实时心率失败")
                return@coroutineScope false
            }

            // 手环开测后的前几帧常常是 0（还没采到脉搏），所以不能只看第一条 ——
            // 收满一段时间、把所有读数都列出来，才分得清「没数据」和「数据就是 0」。
            val readings = mutableListOf<Int>()
            withTimeoutOrNull(HR_WAIT_MS) {
                session.heartRate.filterNotNull().collect { readings += it }
            }
            Log.i(TAG, "共收到 ${readings.size} 帧心率：[${readings.joinToString()}]")
            val best = readings.maxOrNull()
            if (best == null) {
                Log.e(TAG, "✗ ${HR_WAIT_MS / 1000}s 内一帧心率都没收到")
            } else if (best <= 0) {
                Log.e(TAG, "✗ 收到 ${readings.size} 帧但全是 0 —— 手环没采到脉搏（戴上手腕再试）")
            } else {
                Log.i(TAG, "★ 心率最大值 $best bpm ✓")
            }
            session.stopRealtimeHeartRate()
            return@coroutineScope best != null && best > 0
        } finally {
            logPump.cancel()
            session.disconnect()
        }
    }

    /**
     * 跑一个字节组合。返回是否认证成功。
     *
     * 全程不吞异常、不猜语义：每一条报文都原样 hex 打出来，失败也照样把实际字节留在日志里。
     */
    suspend fun run(
        context: Context,
        mac: String,
        keyHex: String,
        variant: Variant,
    ): Boolean = coroutineScope {
        val key = Auth.parseKey(keyHex)
        Log.i(TAG, "════ ════ ════ ════ ════ ════ ════ ════ ════")
        Log.i(TAG, "variant=${variant.id}  ${variant.note}")
        Log.i(TAG, "mac=$mac  authFlags=0x%02x cryptFlags=0x%02x sendKey=%s randomReq=%s"
            .format(variant.authFlags, variant.cryptFlags, variant.sendKey, variant.randomReq))

        val conn = BandConnection(context)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val pump = launchPump(this, conn, incoming)

        try {
            if (!conn.connect(mac)) {
                Log.e(TAG, "✗ 连接失败：${conn.state.value}")
                return@coroutineScope false
            }
            Log.i(TAG, "✓ 已连接；认证特征存在=${conn.hasCharacteristic(Gatt.CHAR_AUTH)}")

            if (!conn.enableNotify(Gatt.CHAR_AUTH)) {
                Log.e(TAG, "✗ 订阅认证特征失败")
                return@coroutineScope false
            }
            Log.i(TAG, "✓ 已订阅认证特征")

            // 订阅之后手环可能先主动推一条。先收干净，免得后面把这条当成本步的回应。
            val warmup = drain(incoming, 1_200)
            warmup.forEach { Log.i(TAG, "  （订阅后主动上报）<- ${hex(it)}") }

            // ---- 第 ① 步：交密钥（可选）----
            if (variant.sendKey) {
                val packet = byteArrayOf(0x01, variant.authFlags.toByte()) + key
                Log.i(TAG, "① -> ${hex(packet)}")
                if (!conn.write(Gatt.CHAR_AUTH, packet)) {
                    Log.e(TAG, "✗ 第 ① 步写入失败")
                    return@coroutineScope false
                }
                val resp = await(incoming) ?: run {
                    Log.e(TAG, "✗ 第 ① 步没有回应（超时）")
                    return@coroutineScope false
                }
                Log.i(TAG, "① <- ${hex(resp)}  （命令=0x%02x 状态=0x%02x）"
                    .format(resp.getOrElse(1) { 0 }, resp.getOrElse(2) { 0 }))
            }

            // ---- 第 ② 步：请求随机数 ----
            val request = when (variant.randomReq) {
                RandomReq.CRYPT_TAIL -> byteArrayOf(
                    (variant.cryptFlags or 0x02).toByte(),
                    variant.authFlags.toByte(),
                    0x02, 0x01, 0x00,
                )
                RandomReq.SHORT -> byteArrayOf(
                    0x02,
                    variant.authFlags.toByte(),
                )
            }
            Log.i(TAG, "② -> ${hex(request)}")
            if (!conn.write(Gatt.CHAR_AUTH, request)) {
                Log.e(TAG, "✗ 第 ② 步写入失败")
                return@coroutineScope false
            }
            val challengeResp = await(incoming) ?: run {
                Log.e(TAG, "✗ 第 ② 步没有回应（超时）")
                return@coroutineScope false
            }
            Log.i(TAG, "② <- ${hex(challengeResp)}  （命令=0x%02x 状态=0x%02x）"
                .format(challengeResp.getOrElse(1) { 0 }, challengeResp.getOrElse(2) { 0 }))
            if (challengeResp.size < 19) {
                Log.e(TAG, "✗ 挑战值长度不足（收到 ${challengeResp.size} 字节，需要 ≥19）")
                return@coroutineScope false
            }
            val challenge = challengeResp.copyOfRange(3, 19)
            Log.i(TAG, "   挑战值 ${hex(challenge)}")

            // ---- 第 ③ 步：回加密结果 ----
            val packet3 = byteArrayOf(
                (variant.cryptFlags or 0x03).toByte(),
                variant.authFlags.toByte(),
            ) + Auth.aes(challenge, key)
            Log.i(TAG, "③ -> ${hex(packet3)}")
            if (!conn.write(Gatt.CHAR_AUTH, packet3)) {
                Log.e(TAG, "✗ 第 ③ 步写入失败")
                return@coroutineScope false
            }
            val finalResp = await(incoming) ?: run {
                Log.e(TAG, "✗ 第 ③ 步没有回应（超时）")
                return@coroutineScope false
            }
            val step = finalResp.getOrElse(1) { 0 }.toInt() and 0x0f
            val status = finalResp.getOrElse(2) { 0 }.toInt()
            Log.i(TAG, "③ <- ${hex(finalResp)}  （命令=0x%02x 状态=0x%02x）".format(step, status))
            val ok = step == 0x03 && status == 0x01
            Log.i(TAG, if (ok) "★ 认证通过 ✓" else "★ 认证被拒 ✗")
            return@coroutineScope ok
        } finally {
            pump.cancel()
            conn.close()
        }
    }

    /**
     * 用一个独立的收集协程把认证上报灌进 channel。
     *
     * 不直接对 `incoming` 反复 `first {}`：那样每次都要重新订阅，两条上报挨得近时中间那条会丢。
     */
    private fun launchPump(
        scope: CoroutineScope,
        conn: BandConnection,
        sink: Channel<ByteArray>,
    ) = scope.launch {
        conn.incoming.collect {
            if (it.characteristic == Gatt.CHAR_AUTH) sink.send(it.value)
        }
    }

    /** 等一条认证特征的上报。 */
    private suspend fun await(channel: Channel<ByteArray>): ByteArray? =
        withTimeoutOrNull(AUTH_TIMEOUT_MS) { channel.receive() }

    /** 把 [ms] 毫秒内收到的所有上报都收走。 */
    private suspend fun drain(channel: Channel<ByteArray>, ms: Long): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val deadline = System.currentTimeMillis() + ms
        var remaining = ms
        while (remaining > 0) {
            val next = withTimeoutOrNull(remaining) { channel.receive() } ?: break
            out += next
            remaining = deadline - System.currentTimeMillis()
        }
        return out
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}
