package com.ted.shouhuan.debug

import android.content.Context
import android.util.Log
import com.ted.shouhuan.ble.BandConnection
import com.ted.shouhuan.ble.Gatt
import com.ted.shouhuan.proto.Auth
import com.ted.shouhuan.proto.AuthEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.zip.CRC32

/**
 * 表盘下发实验台（**仅 debug 构建**，release 里不存在）。
 *
 * 要回答的问题只有一个：**这台 Mi Band 5 认不认第三方推过去的表盘包？**
 *
 * 上游没有现成的可抄 —— Gadgetbridge 对 Mi Band 5 压根不支持表盘管理
 * （`MiBand5Coordinator` 继承的 `supportsAppsManagement` 默认 false）。
 * 但它的**固件/资源传输通道是实现过的**：`MiBand5Support extends MiBand4Support`，
 * `createUpdateFirmwareOperation()` 返回 `UpdateFirmwareOperationNew`，表盘包应该也走这条路。
 * 所以本实验台就是照那套字节去试，而不是凭空猜。
 *
 * 流程（`UpdateFirmwareOperationNew`，全部走华米私有特征）：
 * ```
 *                    1531 = 控制   1532 = 数据
 * 手机 → 1531: 01 <type> <size u32le> <crc32 u32le>      ① 报元数据
 * 手环 →      : 10 01 01                                  ② 收下了
 * 手机 → 1531: 03 01                                      ③ 开始推
 * 手机 → 1532: <数据包 × N>（每 100 包插一条 00 到 1531）  ④ 推数据
 * 手机 → 1531: 00                                         ⑤ 推完了
 * 手环 →      : 10 03 01                                  ⑥ 数据齐了
 * 手机 → 1531: 04                                         ⑦ 请校验
 * 手环 →      : 10 04 01                                  ⑧ 成功
 * ```
 * ⚠️ ⑥ 的时机是**推断**出来的：上游代码是「先一口气把 `03 01` + 全部数据 + `00` 排进队列，
 * 再等回复」，所以 `10 03 01` 只可能在数据收完之后到。本实验台**每一步的真实回应都原样
 * 打进 logcat**，顺序跟这个不一样也看得出来。
 *
 * 用法（在 PC 上）：
 * ```
 * # 1. 把表盘包推到应用自己的外部目录（这个路径应用能直接读，不用任何存储权限）
 * adb push data.zip /sdcard/Android/data/com.ted.shouhuan/files/
 *
 * # 2. 只发元数据 —— 最安全的一步，看手环认不认这个 type
 * adb shell am start -n com.ted.shouhuan/.debug.WatchFaceLabActivity \
 *     --es mac AA:BB:CC:DD:EE:FF --es key 0123… --es stage init \
 *     --es file /sdcard/Android/data/com.ted.shouhuan/files/data.zip
 *
 * # 3. 整包推下去
 * adb shell am start -n com.ted.shouhuan/.debug.WatchFaceLabActivity \
 *     --es mac AA:BB:CC:DD:EE:FF --es key 0123… --es stage push \
 *     --es file /sdcard/Android/data/com.ted.shouhuan/files/data.zip
 * adb logcat -s WatchFaceLab
 * ```
 * 可调参数：`--ei type 8`（固件类型，默认 `HuamiFirmwareType.WATCHFACE`）、
 * `--ei packet 20`（单次 GATT 写多少字节，默认 20 —— 我们的连接层没协商 MTU，
 * 默认 MTU 23，可用载荷正好 20）。
 *
 * 密钥只从 intent extra 传，不写进源码。
 */
object WatchFaceLab {

    const val TAG = "WatchFaceLab"

    // ---------------- 华米的固件/资源传输通道 ----------------
    /** 控制特征：所有命令和回复都走这里。 */
    val CHAR_FW_CONTROL: UUID = UUID.fromString("00001531-0000-3512-2118-0009af100700")

    /** 数据特征：只用来灌文件内容。 */
    val CHAR_FW_DATA: UUID = UUID.fromString("00001532-0000-3512-2118-0009af100700")

    // ---------------- 协议常量（对齐 HuamiService）----------------
    private const val RESPONSE = 0x10
    private const val SUCCESS = 0x01
    private const val CMD_FIRMWARE_INIT = 0x01
    private const val CMD_FIRMWARE_START_DATA = 0x03
    private const val CMD_FIRMWARE_CHECKSUM = 0x04
    private const val CMD_FIRMWARE_UPDATE_SYNC = 0x00

    /** `HuamiFirmwareType.WATCHFACE`。 */
    const val TYPE_WATCHFACE = 0x08

    /** 每发多少包插一条同步命令（上游就是 100）。 */
    private const val SYNC_EVERY_PACKETS = 100

    private const val AUTH_TIMEOUT_MS = 8_000L

    /** 每条回复的等待上限。 */
    private const val NOTIFY_TIMEOUT_MS = 20_000L

    /** 等校验结果的上限 —— 手环要算完整个文件，得给宽一点。 */
    private const val CHECKSUM_TIMEOUT_MS = 90_000L

    /**
     * 跑一次。
     *
     * @param stage `init` = 只发元数据；`push` = 完整下发。
     * @return 是否走完（`init` 阶段以「手环回了 `10 01 01`」为成功）。
     */
    suspend fun run(
        context: Context,
        mac: String,
        keyHex: String,
        file: File,
        typeValue: Int,
        packetSize: Int,
        stage: String,
    ): Boolean = coroutineScope {
        Log.i(TAG, "════ ════ ════ 表盘下发实验 ════ ════ ════")
        Log.i(TAG, "文件 = ${file.absolutePath}")
        Log.i(TAG, "stage=$stage  type=0x%02x  packet=%d".format(typeValue, packetSize))

        val resolved = resolveFile(context, file)
        if (resolved == null) {
            Log.e(TAG, "✗ 读不到文件。先 adb push 到 ${appExtDir(context)}/")
            return@coroutineScope false
        }
        Log.i(TAG, "实际读到 = ${resolved.absolutePath}")
        val bytes = resolved.readBytes()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        val packets = (bytes.size + packetSize - 1) / packetSize
        Log.i(TAG, "大小 = ${bytes.size} 字节，CRC32 = 0x%s，数据包 = $packets × $packetSize B"
            .format(Integer.toHexString(crc).padStart(8, '0')))

        val key = Auth.parseKey(keyHex)
        val conn = BandConnection(context)
        val authIn = Channel<ByteArray>(Channel.UNLIMITED)
        val fwIn = Channel<ByteArray>(Channel.UNLIMITED)
        val pumps = listOf(
            launchPump(this, conn, Gatt.CHAR_AUTH, authIn),
            launchPump(this, conn, CHAR_FW_CONTROL, fwIn),
        )

        try {
            if (!conn.connect(mac)) {
                Log.e(TAG, "✗ 连接失败：${conn.state.value}")
                return@coroutineScope false
            }
            Log.i(TAG, "✓ 已连接")
            Log.i(TAG, "   认证特征存在 = ${conn.hasCharacteristic(Gatt.CHAR_AUTH)}")
            Log.i(TAG, "   固件控制特征存在 = ${conn.hasCharacteristic(CHAR_FW_CONTROL)}")
            Log.i(TAG, "   固件数据特征存在 = ${conn.hasCharacteristic(CHAR_FW_DATA)}")

            if (!conn.enableNotify(Gatt.CHAR_AUTH)) {
                Log.e(TAG, "✗ 订阅认证特征失败")
                return@coroutineScope false
            }
            if (!authenticate(conn, key, authIn)) return@coroutineScope false

            if (!conn.enableNotify(CHAR_FW_CONTROL)) {
                Log.e(TAG, "✗ 订阅固件控制特征失败 —— 收不到任何回复，后面没法走")
                return@coroutineScope false
            }
            Log.i(TAG, "✓ 已订阅固件控制特征")

            // ---- ① 报元数据 ----
            val init = byteArrayOf(CMD_FIRMWARE_INIT.toByte(), typeValue.toByte()) +
                u32(bytes.size) + u32(crc)
            Log.i(TAG, "① -> ${hex(init)}")
            if (!conn.write(CHAR_FW_CONTROL, init)) {
                Log.e(TAG, "✗ 元数据写入失败（连特征都没找到？）")
                return@coroutineScope false
            }

            // ---- ② 手环收下了吗 ----
            val initResp = await(fwIn, NOTIFY_TIMEOUT_MS) ?: run {
                Log.e(TAG, "✗ 手环没有回应元数据（${NOTIFY_TIMEOUT_MS / 1000}s）")
                Log.e(TAG, "   这条通道可能就是不通，或者 type 该换一个值")
                return@coroutineScope false
            }
            Log.i(TAG, "② <- ${hex(initResp)}")
            if (!isSuccess(initResp, CMD_FIRMWARE_INIT)) {
                Log.e(TAG, "✗ 手环拒了元数据（期望 10 01 01）。把上面这行字节记下来")
                return@coroutineScope false
            }
            Log.i(TAG, "   ✓ 手环收下了元数据")

            if (stage != "push") {
                Log.i(TAG, "stage=init 到此为止 —— 通道是通的，元数据被接受")
                return@coroutineScope true
            }

            // ---- ③ 开始推 ----
            val start = byteArrayOf(CMD_FIRMWARE_START_DATA.toByte(), 1)
            Log.i(TAG, "③ -> ${hex(start)}")
            if (!conn.write(CHAR_FW_CONTROL, start)) {
                Log.e(TAG, "✗ 开始命令写入失败")
                return@coroutineScope false
            }

            // ---- ④ 灌数据 ----
            val startedAt = System.currentTimeMillis()
            var sent = 0
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + packetSize, bytes.size)
                if (!conn.write(CHAR_FW_DATA, bytes.copyOfRange(offset, end))) {
                    Log.e(TAG, "✗ 第 $sent 包写入失败（offset=$offset）")
                    return@coroutineScope false
                }
                offset = end
                sent++
                if (sent % SYNC_EVERY_PACKETS == 0) {
                    conn.write(CHAR_FW_CONTROL, byteArrayOf(CMD_FIRMWARE_UPDATE_SYNC.toByte()))
                    val sec = (System.currentTimeMillis() - startedAt) / 1000.0
                    Log.i(TAG, "   已发 $sent/$packets 包（$offset/${bytes.size} 字节，${"%.1f".format(sec)}s）")
                }
            }
            Log.i(TAG, "④ 数据推完：$sent 包 / ${bytes.size} 字节")

            // ---- ⑤ 收尾 ----
            Log.i(TAG, "⑤ -> ${hex(byteArrayOf(CMD_FIRMWARE_UPDATE_SYNC.toByte()))}")
            conn.write(CHAR_FW_CONTROL, byteArrayOf(CMD_FIRMWARE_UPDATE_SYNC.toByte()))

            // ---- ⑥⑦⑧ 逐条处理回应 ----
            // 正常应该是 10 03 01（数据齐了）→ 我们回 04 → 10 04 01（校验通过）。
            // 万一顺序不一样，这里把每一条都打出来再判断，不至于卡死。
            repeat(6) {
                val resp = await(fwIn, CHECKSUM_TIMEOUT_MS) ?: run {
                    Log.e(TAG, "✗ 等回复超时（${CHECKSUM_TIMEOUT_MS / 1000}s）")
                    return@coroutineScope false
                }
                Log.i(TAG, "<- ${hex(resp)}")
                when (resp.getOrElse(1) { 0 }.toInt() and 0xff) {
                    CMD_FIRMWARE_START_DATA -> {
                        Log.i(TAG, "⑥ ✓ 手环说数据齐了，请求校验")
                        Log.i(TAG, "⑦ -> ${hex(byteArrayOf(CMD_FIRMWARE_CHECKSUM.toByte()))}")
                        conn.write(CHAR_FW_CONTROL, byteArrayOf(CMD_FIRMWARE_CHECKSUM.toByte()))
                    }

                    CMD_FIRMWARE_CHECKSUM -> {
                        if (isSuccess(resp, CMD_FIRMWARE_CHECKSUM)) {
                            Log.i(TAG, "⑧ ★ 校验通过 —— 手环接受了这个表盘包 ✓")
                            return@coroutineScope true
                        }
                        Log.e(TAG, "✗ 校验没过（手环回的是 0x%02x）".format(resp.getOrElse(2) { 0 }))
                        return@coroutineScope false
                    }

                    else -> Log.w(TAG, "   （没见过的回应，继续等）")
                }
            }
            Log.e(TAG, "✗ 回应处理轮次用尽")
            return@coroutineScope false
        } finally {
            pumps.forEach { it.cancel() }
            conn.close()
        }
    }

    // ------------------------------------------------------------------
    // 认证：走的还是 Auth 里那套真实协议，只是不经过 BandSession
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

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun launchPump(
        scope: CoroutineScope,
        conn: BandConnection,
        characteristic: UUID,
        sink: Channel<ByteArray>,
    ) = scope.launch {
        conn.incoming.collect {
            if (it.characteristic == characteristic) sink.send(it.value)
        }
    }

    private suspend fun await(channel: Channel<ByteArray>, ms: Long): ByteArray? =
        withTimeoutOrNull(ms) { channel.receive() }

    /** 应用自己的外部目录 —— adb push 到这里，应用不用任何存储权限就能读。 */
    private fun appExtDir(context: Context): File? = context.getExternalFilesDir(null)

    /**
     * 把命令行给的路径变成真能打开的文件。
     *
     * 字面路径 `/sdcard/Android/data/<包名>/files/x.zip` 在 Android 11+ 上**打不开**：
     * `/sdcard` 是 FUSE 视图，应用直连自己那个目录的绝对路径会被拒，
     * 但用 `getExternalFilesDir()` 拿到的同一份文件是通的。所以打不开就按文件名回落。
     *
     * 公开出来是给 `stage=real` 用的 —— 那条路要跑生产代码（BandSession），
     * 但同样得先把文件路径解析对。
     */
    fun resolveFile(context: Context, given: File): File? {
        if (given.isFile) return given
        val dir = appExtDir(context) ?: return null
        return File(dir, given.name).takeIf { it.isFile }
    }

    /** 是不是 `10 <cmd> 01` 这种成功回复。 */
    private fun isSuccess(value: ByteArray, command: Int): Boolean =
        value.size >= 3 &&
            (value[0].toInt() and 0xff) == RESPONSE &&
            (value[1].toInt() and 0xff) == command &&
            (value[2].toInt() and 0xff) == SUCCESS

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}
