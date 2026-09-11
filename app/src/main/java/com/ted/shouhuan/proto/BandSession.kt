package com.ted.shouhuan.proto

import android.content.Context
import android.util.Log
import com.ted.shouhuan.ble.BandConnection
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.ble.Gatt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次「手环会话」：连接 → 认证 → 可用。
 *
 * 这是协议层的门面，界面只跟它打交道，不直接碰 GATT。
 */
class BandSession(
    context: Context,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "BandSession"
        const val AUTH_TIMEOUT_MS = 10_000L
    }

    private val connection = BandConnection(context)

    /** 底层 GATT 连接状态。 */
    val connectionState: StateFlow<ConnectionState> = connection.state

    private val _authenticated = MutableStateFlow(false)
    val authenticated: StateFlow<Boolean> = _authenticated

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs

    private var collector: Job? = null

    fun log(message: String) {
        Log.d(TAG, message)
        _logs.tryEmit(message)
    }

    /** 连接 + 认证。成功后手环即可收发指令。 */
    suspend fun connectAndAuthenticate(mac: String, authKey: String): Boolean {
        log("正在连接 $mac …")
        if (!connection.connect(mac)) {
            val state = connectionState.value
            log("连接失败：${(state as? ConnectionState.Failed)?.reason ?: state}")
            return false
        }
        log("服务发现完成，共 ${if (connection.hasCharacteristic(Gatt.CHAR_AUTH)) "能" else "不能"}找到认证特征")

        // 先订阅事件流，再发指令 —— 否则早期上报会丢
        startCollector()

        if (!authenticate(Auth.parseKey(authKey))) {
            return false
        }

        _authenticated.value = true
        connection.enableNotify(Gatt.CHAR_HR_MEASUREMENT)
        refreshBattery()
        return true
    }

    /**
     * 两步认证（字节序列与 Gadgetbridge 的 `InitOperation` 一致）：请求随机数 → 回加密结果。
     * 每一步都只认「针对上一步的回复」，避免手环连发多条时把消息错配。
     *
     * 曾经试过在它前面补一步「先交密钥」（对齐抓到的官方 App 流程），但那救不回
     * `authFlags=0x08` —— 这台手环拒的是 authFlags 本身，不是缺了哪一步。
     * 完整实测矩阵见 [Auth] 的类注释。
     */
    private suspend fun authenticate(key: ByteArray): Boolean {
        if (!connection.enableNotify(Gatt.CHAR_AUTH)) {
            log("订阅认证特征失败")
            return false
        }

        val request = Auth.requestAuthNumberPacket()
        log("① 请求随机数：${request.joinToString(" ") { "%02x".format(it) }}")
        if (!connection.write(Gatt.CHAR_AUTH, request)) {
            log("写入认证请求失败")
            return false
        }

        repeat(6) {
            val incoming = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                connection.incoming.first { it.characteristic == Gatt.CHAR_AUTH }
            }
            if (incoming == null) {
                log("认证超时（手环没有回应）")
                return false
            }

            when (val event = Auth.classify(incoming.value)) {
                is AuthEvent.Challenge -> {
                    val packet = Auth.encryptedPacket(event.value, key)
                    log(
                        "② 回加密结果：密文 " +
                            packet.drop(2).joinToString(" ") { "%02x".format(it) },
                    )
                    connection.write(Gatt.CHAR_AUTH, packet)
                }

                AuthEvent.Authenticated -> {
                    log("认证通过")
                    return true
                }

                // 正常流程不会走到这里（我们不先交密钥）。真收到了说明手环在就
                // authFlags 提意见，按被拒处理更安全。
                AuthEvent.KeyAccepted -> log("收到密钥应答，但当前流程不该出现这一步")

                AuthEvent.KeyRejected -> {
                    log(
                        "认证被拒（手环回 " +
                            incoming.value.joinToString(" ") { "%02x".format(it) } + "）",
                    )
                    return false
                }

                AuthEvent.Failed -> {
                    log(
                        "认证失败（手环回 " +
                            incoming.value.joinToString(" ") { "%02x".format(it) } + "）",
                    )
                    return false
                }

                AuthEvent.Unknown -> {
                    log("收到未识别的认证消息，跳过")
                }
            }
        }

        log("认证轮次用尽，未通过")
        return false
    }

    /** 订阅手环主动上报的数据。 */
    private fun startCollector() {
        collector?.cancel()
        collector = scope.launch {
            connection.incoming.collect { msg ->
                when (msg.characteristic) {
                    Gatt.CHAR_HR_MEASUREMENT -> {
                        HeartRateParser.parse(msg.value)?.let { _heartRate.value = it }
                    }

                    Gatt.CHAR_BATTERY_INFO -> {
                        parseBattery(msg.value)?.let { _battery.value = it }
                    }

                    // 认证是一来一回的字节对话，被拒时没原文根本分不清
                    // 是「key 不对」还是「解析错位」。
                    Gatt.CHAR_AUTH -> log(
                        "认证上报 <- " + msg.value.joinToString(" ") { "%02x".format(it) },
                    )
                }
            }
        }
    }

    /** 开始实时心率（连续测量）。 */
    suspend fun startRealtimeHeartRate(): Boolean {
        if (!connection.enableNotify(Gatt.CHAR_HR_MEASUREMENT)) {
            log("订阅心率特征失败")
            return false
        }
        Commands.HR_START_REALTIME_SEQUENCE.forEach {
            connection.write(Gatt.CHAR_HR_CONTROL_POINT, it)
        }
        log("已开始实时心率")
        return true
    }

    /** 停止实时心率。 */
    suspend fun stopRealtimeHeartRate(): Boolean {
        val ok = connection.write(Gatt.CHAR_HR_CONTROL_POINT, Commands.HR_STOP_CONTINUOUS)
        log(if (ok) "已停止实时心率" else "停止心率失败")
        return ok
    }

    /** 单次测量（省电，测完自动停）。 */
    suspend fun measureOnce(): Boolean {
        if (!connection.enableNotify(Gatt.CHAR_HR_MEASUREMENT)) {
            log("订阅心率特征失败")
            return false
        }
        Commands.HR_MEASURE_ONCE_SEQUENCE.forEach {
            connection.write(Gatt.CHAR_HR_CONTROL_POINT, it)
        }
        log("已触发单次测量")
        return true
    }

    /**
     * 抹掉上一次的读数。
     *
     * 每次开始测量前必须调用：`heartRate` 是 StateFlow，上一轮的值会一直留在里面，
     * 不清理的话界面会把旧读数当成新结果，或者等待逻辑立刻就"满足条件"返回。
     */
    fun clearHeartRate() {
        _heartRate.value = null
    }

    /** 读一次电量。 */
    suspend fun refreshBattery() {
        if (!connection.hasCharacteristic(Gatt.CHAR_BATTERY_INFO)) return
        val raw = connection.read(Gatt.CHAR_BATTERY_INFO) ?: return
        parseBattery(raw)?.let { _battery.value = it }
    }

    fun disconnect() {
        collector?.cancel()
        collector = null
        connection.close()
        _heartRate.value = null
        _authenticated.value = false
    }

    /**
     * 华米电量特征的格式：`[状态, 电量百分比, ...]`，取 byte[1]。
     * 若落在 0..100 之外视为无效，宁可显示「--」也不要报个假数字。
     */
    private fun parseBattery(value: ByteArray): Int? {
        if (value.size < 2) return null
        val percent = value[1].toInt() and 0xFF
        return percent.takeIf { it in 0..100 }
    }
}
