package com.ted.shouhuan.proto

import android.content.Context
import android.util.Log
import com.ted.shouhuan.ble.BandConnection
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.ble.Gatt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.util.UUID

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

        /** 等元数据应答 —— 手环对这个基本是秒回。 */
        const val INIT_TIMEOUT_MS = 20_000L

        /** 等校验结果 —— 手环要算完整个文件的校验和，文件越大越慢。 */
        const val CHECKSUM_TIMEOUT_MS = 90_000L

        /**
         * 数据包之间的最小间隔：6 ms ≈ 166 包/秒。
         *
         * 这个数是实测出来的。手环的接收缓冲有限，而每秒能灌多少取决于当时协商的
         * BLE 连接间隔 —— 全速写大约 300 包/秒，会在 4000 包上下被手环打回；
         * 同一条链路按 ~170 包/秒 匀速推就能一路跑完。多花 20 秒换一次成功率，
         * 比传到 40% 崩掉重来划算。
         */
        const val PACKET_INTERVAL_NANOS = 6_000_000L

        /** 单个数据包写失败后的重试次数。 */
        const val PACKET_WRITE_ATTEMPTS = 4

        /** 重试的退避步长（第 n 次等 n × 这个值）。 */
        const val PACKET_RETRY_BASE_MS = 120L

        /** 每这么多包回调一次进度（≈3 秒一次）。 */
        const val PROGRESS_EVERY_PACKETS = 500

        // ---- 活动数据同步（syncActivity）----
        // 近 7 天 ≈ 8 万字节 ≈ 4000 包，限速推大约 25~40 秒；给足余量。
        private const val SYNC_TOTAL_TIMEOUT_MS = 300_000L

        /** 这么久没有新包就算卡死（传输途中偶尔停顿几秒是正常的，别太敏感）。 */
        private const val SYNC_IDLE_TIMEOUT_MS = 30_000L

        /** 每次等包的超时 —— 也是「停顿了多久」的计时粒度。 */
        private const val SYNC_POLL_MS = 1_000L

        /** 进度日志粒度（样本字节数）。 */
        private const val SAMPLE_LOG_EVERY_BYTES = 8_000
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

    /**
     * 实时步数（当天累计）。认证连接后由手环主动推送（见 [enableRealtimeSteps]）；
     * 掉线后保留最后一次的值 —— 和 [battery] 一个待遇，通知栏显示「最后已知」。
     */
    private val _steps = MutableStateFlow<Int?>(null)
    val steps: StateFlow<Int?> = _steps

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs

    /**
     * 固件/表盘通道（1531）推上来的原始回复。
     *
     * 用 Channel 而不是 Flow：表盘下发是「发一条命令、等一条回复」的严格一问一答，
     * 中途没人订阅时也不能把消息丢掉（Flow 的 replay=0 就会丢）。
     */
    private val firmwareIn = Channel<ByteArray>(Channel.UNLIMITED)

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
        enableRealtimeSteps()
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

                    Gatt.CHAR_REALTIME_STEPS_HUAMI, Gatt.CHAR_REALTIME_STEPS -> {
                        parseSteps(msg.value)?.let { _steps.value = it }
                    }

                    // 认证是一来一回的字节对话，被拒时没原文根本分不清
                    // 是「key 不对」还是「解析错位」。
                    Gatt.CHAR_AUTH -> log(
                        "认证上报 <- " + msg.value.joinToString(" ") { "%02x".format(it) },
                    )

                    // 表盘下发期间手环的应答 —— 逐条排队，等 [installWatchFace] 来取。
                    WatchFace.CHAR_CONTROL -> firmwareIn.trySend(msg.value)
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

    // ------------------------------------------------------------------
    // 文字通知下发（MB3/4/5 走 chunked 通道，编码细节见 [Notify]）
    // ------------------------------------------------------------------

    /**
     * 往手环发一条文字通知。要求会话已认证（没有就先走 connectAndAuthenticate）。
     *
     * 每个分块的写入结果都打日志 —— 通知链路的字节序列是照 Gadgetbridge 抄的，
     * 还没在我们自己的真机流程里验证过，出了问题原始日志是唯一的排查起点。
     *
     * @return 全部分块写入成功为 true；任一分块失败立即中止（手环不会显示半条）。
     */
    suspend fun sendNotification(appName: String, title: String, body: String): Boolean {
        if (!_authenticated.value) {
            log("发送通知失败：会话未认证")
            return false
        }
        if (!connection.hasCharacteristic(Gatt.CHAR_CHUNKED)) {
            log("发送通知失败：手环没有 chunked 通道（00000020）")
            return false
        }
        val chunks = Notify.chunk(Notify.buildPacket(appName, title, body))
        log("发送通知 -> $appName / $title，payload 分 ${chunks.size} 包")
        for ((index, chunk) in chunks.withIndex()) {
            if (!connection.write(Gatt.CHAR_CHUNKED, chunk)) {
                log("通知第 ${index + 1}/${chunks.size} 包写入失败，中止")
                return false
            }
        }
        log("通知发送完成（${chunks.size} 包全部写入）")
        return true
    }

    /** 读一次电量。 */
    suspend fun refreshBattery() {
        if (!connection.hasCharacteristic(Gatt.CHAR_BATTERY_INFO)) return
        val raw = connection.read(Gatt.CHAR_BATTERY_INFO) ?: return
        parseBattery(raw)?.let { _battery.value = it }
    }

    // ------------------------------------------------------------------
    // 手环本机设置（字节构造见 [BandSettings]）
    //
    // 这些设置写完即生效、手环自己持久化，但没有读取接口 —— 手环上现在是
    // 什么状态只有手环知道。所以应用侧存一份偏好，连接成功后整套下发
    // （见 BandService），用户当场改的当场也推一条。
    // ------------------------------------------------------------------

    private suspend fun requireAuthenticated() {
        check(_authenticated.value) { "会话未认证，先连接手环" }
    }

    /** 写配置特征（00000003）—— 开关类设置都走这里。 */
    private suspend fun writeConfiguration(data: ByteArray): Boolean {
        requireAuthenticated()
        if (!connection.hasCharacteristic(Gatt.CHAR_CONFIGURATION)) {
            log("设置下发失败：没有配置特征（00000003）")
            return false
        }
        val ok = connection.write(Gatt.CHAR_CONFIGURATION, data)
        log("设置 ${BandSettings.hex(data)} -> ${if (ok) "已下发" else "下发失败"}")
        return ok
    }

    /** 菜单/快捷方式顺序：payload 常超过单包上限，走 chunked 通道（类型号 2）。 */
    private suspend fun applyDisplayItems(items: List<BandSettings.Item>, shortcuts: Boolean): Boolean {
        requireAuthenticated()
        if (!connection.hasCharacteristic(Gatt.CHAR_CHUNKED)) {
            log("设置下发失败：没有 chunked 通道（00000020）")
            return false
        }
        val data = BandSettings.displayItemsCommand(items, shortcuts)
        val chunks = Notify.chunk(data, type = BandSettings.CHUNKED_TYPE_DISPLAY_ITEMS)
        log(
            "${if (shortcuts) "快捷方式" else "菜单"}顺序下发：${items.size} 项，" +
                "payload ${data.size} 字节分 ${chunks.size} 包",
        )
        for ((index, chunk) in chunks.withIndex()) {
            if (!connection.write(Gatt.CHAR_CHUNKED, chunk)) {
                log("设置第 ${index + 1}/${chunks.size} 包写入失败，中止")
                return false
            }
        }
        return true
    }

    /** 手环上划菜单的显示顺序。 */
    suspend fun applyMenuOrder(items: List<BandSettings.Item>): Boolean =
        applyDisplayItems(items, shortcuts = false)

    /** 表盘左右滑的快捷方式。 */
    suspend fun applyShortcutOrder(items: List<BandSettings.Item>): Boolean =
        applyDisplayItems(items, shortcuts = true)

    /**
     * 佩戴手。GB 写之前先在用户设置特征（00000008）上开一次 notify ——
     * 手环会在这条特征上回执，照做但收完就关掉。
     */
    suspend fun applyWearLocation(left: Boolean): Boolean {
        requireAuthenticated()
        if (!connection.hasCharacteristic(Gatt.CHAR_USER_SETTINGS)) {
            log("设置下发失败：没有用户设置特征（00000008）")
            return false
        }
        connection.enableNotify(Gatt.CHAR_USER_SETTINGS)
        val ok = connection.write(Gatt.CHAR_USER_SETTINGS, BandSettings.wearLocationCommand(left))
        connection.enableNotify(Gatt.CHAR_USER_SETTINGS, enable = false)
        log("佩戴手（${if (left) "左" else "右"}）-> ${if (ok) "已下发" else "下发失败"}")
        return ok
    }

    /** 抬腕亮屏。 */
    suspend fun applyDisplayOnLiftWrist(enabled: Boolean): Boolean =
        writeConfiguration(BandSettings.displayOnLiftWristCommand(enabled))

    /** 滑动解锁（锁屏后需上滑解锁）。 */
    suspend fun applySwipeUnlock(enabled: Boolean): Boolean =
        writeConfiguration(BandSettings.swipeUnlockCommand(enabled))

    /** 断开提醒：手环与手机断开蓝牙时手环自己振动。 */
    suspend fun applyDisconnectAlert(enabled: Boolean): Boolean =
        writeConfiguration(BandSettings.disconnectAlertCommand(enabled))

    /** 勿扰模式。 */
    suspend fun applyDnd(
        mode: BandSettings.DndMode,
        startMinute: Int,
        endMinute: Int,
    ): Boolean = writeConfiguration(BandSettings.dndCommand(mode, startMinute, endMinute))

    /** 夜间模式。 */
    suspend fun applyNightMode(
        mode: BandSettings.NightMode,
        startMinute: Int,
        endMinute: Int,
    ): Boolean = writeConfiguration(BandSettings.nightModeCommand(mode, startMinute, endMinute))

    // ------------------------------------------------------------------
    // 活动数据同步（含睡眠）—— 字节层见 ActivitySync
    // ------------------------------------------------------------------

    /** 同步进行到哪了：received/expected 是样本字节数。 */
    data class ActivitySyncProgress(val receivedBytes: Int, val expectedBytes: Int)

    data class ActivitySyncResult(
        val sampleMinutes: Int,
        val since: ZonedDateTime,
        val nights: List<com.ted.shouhuan.data.SleepNightRecord>,
    )

    /**
     * 从手环拉 [sinceDays] 天的活动明细并归并成睡眠夜。
     *
     * 字节序列照 Gadgetbridge 的 AbstractFetchOperation 抄的，**还没在真机上
     * 全流程验证过** —— 每一步都把原始字节打进日志，跑挂了靠日志定位：
     *
     * ```
     * ① 00000004 写 [0x01,0x01,时间8字节]     → 元数据应答 [0x10,0x01,状态,长度,起点]
     * ② 00000004 写 [0x02]                    → 00000005 开始推样本（序号+8字节/分钟）
     * ③ 00000004 收 [0x10,0x02,0x01]（数据齐） → 解析 + 归并成夜
     * ④ 00000004 写 [0x03] ack                → 手环收到才清本地，收不到就重复推
     * ```
     */
    suspend fun syncActivity(
        sinceDays: Int = 7,
        onProgress: (ActivitySyncProgress) -> Unit = {},
    ): ActivitySyncResult = coroutineScope {
        check(_authenticated.value) { "会话未认证，先连接手环" }
        val hasFetch = connection.hasCharacteristic(Gatt.CHAR_ACTIVITY_FETCH)
        val hasData = connection.hasCharacteristic(Gatt.CHAR_ACTIVITY_SAMPLES)
        if (!hasFetch || !hasData) {
            throw IOException("手环没有活动数据特征（00000004=${hasFetch}，00000005=$hasData）")
        }
        if (!connection.enableNotify(Gatt.CHAR_ACTIVITY_FETCH)) {
            throw IOException("订阅活动元数据通道（00000004）失败")
        }

        val since = ZonedDateTime.now().minusDays(sinceDays.toLong())
        val request = ActivitySync.startRequest(since)
        log("① 取数请求 -> ${hexOf(request)}")
        if (!connection.write(Gatt.CHAR_ACTIVITY_FETCH, request)) {
            throw IOException("写取数请求失败（00000004 拒绝写入）")
        }

        var startInfo: FetchStartInfo? = null
        val buffer = ByteArrayOutputStream(128 * 1024)
        var lastCounter = -1

        suspend fun processAndAck(): ActivitySyncResult {
            val info = startInfo ?: throw IOException("没有起始应答就想处理数据？不可能走到这")
            val bytes = buffer.toByteArray()
            if (bytes.size % ActivitySync.SAMPLE_SIZE != 0) {
                log("⚠ 样本字节数不是 8 的倍数（${bytes.size}），丢弃尾巴继续")
            }
            val samples = ActivitySync.parseSamples(bytes, info.start)
            val nights = ActivitySync.nightsFromSamples(samples)
            log("⑤ 样本解析：${samples.size} 分钟（${info.start} 起），归并出 ${nights.size} 夜")
            // ack 写失败不影响结果 —— 顶多手环下次把同样的数据再推一遍
            runCatching {
                if (!connection.write(Gatt.CHAR_ACTIVITY_FETCH, byteArrayOf(ActivitySync.CMD_ACK.toByte()))) {
                    log("⚠ 写 ack 失败（数据已到手，不影响本次结果）")
                } else {
                    log("⑥ 已 ack，等手环确认")
                }
            }
            return ActivitySyncResult(
                sampleMinutes = samples.size,
                since = since,
                nights = nights,
            )
        }

        val deadline = System.currentTimeMillis() + SYNC_TOTAL_TIMEOUT_MS
        var idleStart = System.currentTimeMillis()
        var result: ActivitySyncResult? = null

        while (result == null) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException("同步超时（${SYNC_TOTAL_TIMEOUT_MS / 1000} 秒），样本收到 ${buffer.size()} 字节")
            }
            val msg = withTimeoutOrNull(SYNC_POLL_MS) {
                connection.incoming.first {
                    it.characteristic == Gatt.CHAR_ACTIVITY_FETCH ||
                        it.characteristic == Gatt.CHAR_ACTIVITY_SAMPLES
                }
            }
            if (msg == null) {
                if (System.currentTimeMillis() - idleStart > SYNC_IDLE_TIMEOUT_MS) {
                    throw IOException(
                        "同步卡住了（${SYNC_IDLE_TIMEOUT_MS / 1000} 秒没有新数据），" +
                            "样本已收 ${buffer.size()} 字节",
                    )
                }
                continue
            }
            idleStart = System.currentTimeMillis()

            when (msg.characteristic) {
                Gatt.CHAR_ACTIVITY_FETCH -> {
                    val value = msg.value
                    log("元数据 <- ${hexOf(value)}")
                    if (value.size < 3) throw IOException("活动元数据太短（${value.size} 字节）")
                    if ((value[0].toInt() and 0xff) != ActivitySync.RESPONSE) {
                        throw IOException("活动元数据不是应答（0x%02x）".format(value[0].toInt()))
                    }
                    when (value[1].toInt() and 0xff) {
                        ActivitySync.CMD_START_DATE -> {
                            if ((value[2].toInt() and 0xff) != ActivitySync.SUCCESS) {
                                throw IOException(
                                    "取数请求被拒（status=0x%02x）".format(value[2].toInt()),
                                )
                            }
                            startInfo = ActivitySync.parseStartResponse(value)
                                ?: throw IOException("起始应答解析失败：${hexOf(value)}")
                            if (startInfo.expectedBytes == 0) {
                                log("② 手环说这个时间之后没有新数据")
                                result = processAndAck()
                            } else {
                                log(
                                    "② 手环有 ${startInfo.expectedBytes} 字节样本，起点 ${startInfo.start}",
                                )
                                if (!connection.enableNotify(Gatt.CHAR_ACTIVITY_SAMPLES)) {
                                    throw IOException("订阅样本通道（00000005）失败")
                                }
                                buffer.reset()
                                lastCounter = -1
                                if (!connection.write(Gatt.CHAR_ACTIVITY_FETCH, byteArrayOf(ActivitySync.CMD_FETCH.toByte()))) {
                                    throw IOException("写取数指令（0x02）失败")
                                }
                                log("③ 已下发 0x02，等样本从 00000005 推上来")
                            }
                        }

                        ActivitySync.CMD_FETCH -> {
                            if ((value[2].toInt() and 0xff) != ActivitySync.SUCCESS) {
                                throw IOException("样本传输失败（status=0x%02x）".format(value[2].toInt()))
                            }
                            log("④ 手环报告数据齐了：收到 ${buffer.size()} 字节")
                            result = processAndAck()
                        }

                        ActivitySync.CMD_ACK -> {
                            log("⑦ 手环确认 ack，同步收尾")
                        }

                        else -> log("（忽略未识别的元数据命令 0x%02x）".format(value[1].toInt()))
                    }
                }

                Gatt.CHAR_ACTIVITY_SAMPLES -> {
                    val value = msg.value
                    if (value.isEmpty()) continue
                    val counter = value[0].toInt() and 0xff
                    val expectedCounter = (lastCounter + 1) and 0xff
                    if (lastCounter >= 0 && counter != expectedCounter) {
                        throw IOException(
                            "样本包序号跳变：上一包 $lastCounter，这一包 $counter —— 数据不连续，重试同步",
                        )
                    }
                    lastCounter = counter
                    buffer.write(value, 1, value.size - 1)
                    val info = startInfo
                    if (info != null && buffer.size() >= info.expectedBytes) {
                        // 样本收齐 —— 「数据齐」元数据有时贴着最后一包就来，
                        // 不主动处理，等 00000004 的完成标记（下一轮循环会收到）
                        onProgress(ActivitySyncProgress(buffer.size(), info.expectedBytes))
                    } else if (buffer.size() % (SAMPLE_LOG_EVERY_BYTES) < 20) {
                        log("   样本进度 ${buffer.size()} 字节（包 $counter）")
                        info?.let { onProgress(ActivitySyncProgress(buffer.size(), it.expectedBytes)) }
                    }
                }

                else -> {} // 不会走到：上面 first{} 已过滤
            }
        }

        result!!
    }

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it) }

    /**
     * 订阅实时步数。
     *
     * Gadgetbridge 的做法（onEnableRealtimeSteps）：不用写任何控制点指令，
     * 先 read 一次（手环收到读请求就把当前值推上来），再挂 notify 接住后续推送。
     * MB3+ 走华米 7 号特征，老 MiBand 的 ff06 留作兼容。
     */
    private suspend fun enableRealtimeSteps() {
        val uuid = when {
            connection.hasCharacteristic(Gatt.CHAR_REALTIME_STEPS_HUAMI) -> Gatt.CHAR_REALTIME_STEPS_HUAMI
            connection.hasCharacteristic(Gatt.CHAR_REALTIME_STEPS) -> Gatt.CHAR_REALTIME_STEPS
            else -> {
                log("没有实时步数特征，跳过订阅")
                return
            }
        }
        if (!connection.enableNotify(uuid)) {
            log("订阅实时步数失败")
            return
        }
        val raw = connection.read(uuid) ?: return
        parseSteps(raw)?.let { _steps.value = it }
    }

    // ------------------------------------------------------------------
    // 表盘下发（实验性 —— 协议细节见 WatchFace 的类注释）
    // ------------------------------------------------------------------

    /**
     * 把一份表盘包下发到手环。
     *
     * 这里只负责「发一条、等一条」的编排；每一步的真实回应都用 [log] 原样打出来 ——
     * 这条链路还有没确认的环节，出问题时没有原始字节根本查不下去。
     *
     * @param onProgress 每 100 包回调一次（不包在 suspend 里也没关系，调用方自己切线程）；
     *                   太密反而拖慢下发。
     */
    suspend fun installWatchFace(
        payload: ByteArray,
        onProgress: (WatchFaceProgress) -> Unit = {},
    ): WatchFaceOutcome = coroutineScope {
        if (payload.isEmpty()) {
            return@coroutineScope WatchFaceOutcome.Failed(
                title = "表盘包是空的",
                detail = "读出来 0 字节，没什么可下发的。",
            )
        }
        if (payload.size > WatchFace.MAX_PAYLOAD_BYTES) {
            return@coroutineScope WatchFaceOutcome.Failed(
                title = "表盘包太大",
                detail = "文件 ${payload.size} 字节，上限 ${WatchFace.MAX_PAYLOAD_BYTES} 字节。",
                hint = "确认选的是手环表盘包（官方自定义表盘约 240 KB），不是别的压缩文件。",
            )
        }
        if (!connection.hasCharacteristic(WatchFace.CHAR_CONTROL)) {
            return@coroutineScope WatchFaceOutcome.Failed(
                title = "这个手环没有固件传输通道",
                detail = "连接上找不到 1531 这条特征，表盘下发走不通。",
                hint = "这条通道是华米设备才有的；换型号可能就不支持。",
            )
        }
        if (!connection.enableNotify(WatchFace.CHAR_CONTROL)) {
            return@coroutineScope WatchFaceOutcome.Failed(
                title = "订阅固件通道失败",
                detail = "没能打开 1531 的通知，手环的应答收不到。",
                hint = "断开重连一次再试。",
            )
        }

        // 排空上一次残留的应答 —— 别把旧消息当成这次的回执。
        while (firmwareIn.tryReceive().isSuccess) { /* discard */ }

        // ---- ⓪ 选表盘槽（仅 UIHH 容器）----
        // 照 Gadgetbridge：表盘上传前要先往配置特征写「39 00 00 ff ff ff <表盘ID>」，
        // 手环才知道收到的包归哪个表盘槽。缺了这一步，数据推得再完整，
        // 收尾校验也会被拒（实测回 10 20 08 / 10 20 00，表盘不换）。
        if (WatchFace.isUihh(payload)) {
            if (!connection.hasCharacteristic(WatchFace.CHAR_CONFIG)) {
                return@coroutineScope WatchFaceOutcome.Failed(
                    title = "这个手环没有表盘配置通道",
                    detail = "连接上找不到 00000003 这条配置特征，没法选表盘槽。",
                    hint = "这条特征华米设备才有；把协议日志发出来一起看看。",
                )
            }
            val slot = WatchFace.slotSelectPacket(payload)
            log("⓪ 选表盘槽 -> ${WatchFace.hex(slot)}")
            if (!connection.write(WatchFace.CHAR_CONFIG, slot)) {
                return@coroutineScope WatchFaceOutcome.Failed(
                    title = "写表盘槽选择命令失败",
                    detail = "配置特征（00000003）拒绝写入。",
                    hint = "手环可能已经断开了，重连后再试。",
                )
            }
        }

        val crc = WatchFace.crc32Of(payload)
        val totalPackets = WatchFace.packetCount(payload.size)
        val startedAt = System.currentTimeMillis()
        log(
            "表盘下发开始：${payload.size} 字节 / $totalPackets 包，CRC32 ${WatchFace.hex32(crc)}",
        )

        // ---- ① 元数据 ----
        val init = WatchFace.initPacket(payload)
        log("① 报元数据 -> ${WatchFace.hex(init)}")
        if (!connection.write(WatchFace.CHAR_CONTROL, init)) {
            return@coroutineScope WatchFaceOutcome.Failed(
                title = "写元数据失败",
                detail = "1531 特征拒绝写入。",
                hint = "手环可能已经断开了，重连后再试。",
            )
        }

        // ---- ② 手环收下了吗 ----
        // 上游实现里 ⓪ 的槽选择命令不会有应答，但万一某版固件回了，
        // 别把它误判成「元数据被拒」—— 认不出的 Ack 先记日志、继续等真正的 10 01 01。
        var initAcked = false
        var attempts = 0
        while (!initAcked && attempts < 3) {
            attempts++
            when (val resp = awaitFirmware(INIT_TIMEOUT_MS)) {
                null -> return@coroutineScope WatchFaceOutcome.Failed(
                    title = "手环没有回应元数据",
                    detail = "${INIT_TIMEOUT_MS / 1000} 秒内 1531 上没有任何回复。",
                    hint = "手环是不是还连在「小米运动健康」上？它同一时间只服务一个 App。",
                )

                is WatchFace.Response.Ack -> {
                    log("② <- ${WatchFace.hex(resp.raw)}")
                    if (resp.command == WatchFace.CMD_INIT && resp.ok) {
                        log("   ✓ 手环收下了元数据")
                        initAcked = true
                    } else if (resp.ok) {
                        // 槽选择之类的旁路应答 —— 不是拒绝，继续等元数据的回执。
                        log("   （0x%02x 不是元数据应答，继续等）".format(resp.command))
                    } else {
                        return@coroutineScope WatchFaceOutcome.Failed(
                            title = "手环拒了这份表盘包",
                            detail = "元数据没被接受，手环回的是 ${WatchFace.hex(resp.raw)}。",
                            hint = "包体格式可能不被这台固件认。",
                        )
                    }
                }

                is WatchFace.Response.Other -> return@coroutineScope WatchFaceOutcome.Failed(
                    title = "手环回了个看不懂的东西",
                    detail = "元数据之后收到 ${WatchFace.hex(resp.raw)}，不像标准的应答格式。",
                )
            }
        }
        if (!initAcked) {
            return@coroutineScope WatchFaceOutcome.Failed(
                title = "手环没有回应元数据",
                detail = "等了几轮也没等到 10 01 01。",
                hint = "把协议日志发出来一起看看。",
            )
        }

        // ---- ③ 开始推 ----
        val start = WatchFace.startPacket()
        log("③ 开始推 -> ${WatchFace.hex(start)}")
        if (!connection.write(WatchFace.CHAR_CONTROL, start)) {
            return@coroutineScope WatchFaceOutcome.Failed("写开始命令失败", "1531 特征拒绝写入。")
        }

        // ---- ④ 灌数据 ----
        //
        // 这里**主动限速**（每包至少间隔 PACKET_INTERVAL_NANOS），不是闲的：
        // 手环的接收缓冲有限，全速灌会在中途被整包打回。实测数据见常量注释。
        var sent = 0
        var nextSlot = System.nanoTime()
        while (sent < totalPackets) {
            if (!writePacket(WatchFace.CHAR_DATA, WatchFace.packetAt(payload, sent))) {
                return@coroutineScope WatchFaceOutcome.Failed(
                    title = "数据传到第 ${sent + 1} 包失败了",
                    detail = "数据通道（1532）在 $sent/$totalPackets 处连续 $PACKET_WRITE_ATTEMPTS 次拒绝写入。",
                    hint = "手环多半是中途断开了，或者接收缓冲被打满。重连后再试；" +
                        "手环没收到完整文件就不会生效，现有表盘不受影响。",
                )
            }
            sent++

            if (sent % WatchFace.SYNC_EVERY_PACKETS == 0) {
                // 上游也是每 100 包插一条同步命令，给手环一个「刷缓冲」的机会。
                connection.write(WatchFace.CHAR_CONTROL, WatchFace.syncPacket())
            }
            // 进度约每 3 秒报一次（500 包）—— 每 100 包就报会让界面一直重组。
            if (sent % PROGRESS_EVERY_PACKETS == 0 || sent == totalPackets) {
                onProgress(
                    WatchFaceProgress(
                        sentPackets = sent,
                        totalPackets = totalPackets,
                        sentBytes = minOf(sent * WatchFace.PACKET_SIZE, payload.size),
                        totalBytes = payload.size,
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                    ),
                )
            }

            // 按「时间片」限速：落后了就立刻发下一包，超前了就等到该发的时刻。
            // 这样不会因为 delay 的毫秒精度把总时长拖得更长。
            nextSlot += PACKET_INTERVAL_NANOS
            val waitMs = (nextSlot - System.nanoTime()) / 1_000_000
            if (waitMs > 0) delay(waitMs)
        }
        log("④ 数据推完：$sent 包 / ${payload.size} 字节")

        // ---- ⑤ 收尾 ----
        val finish = WatchFace.syncPacket()
        log("⑤ 推完 -> ${WatchFace.hex(finish)}")
        connection.write(WatchFace.CHAR_CONTROL, finish)

        // ---- ⑥⑦⑧ 逐条处理应答 ----
        // 正常是 10 03 01（数据齐了）→ 我们回 04 → 10 04 01（校验通过）。
        // 实测里最后收到的是 10 20 08 —— 所以这里不预设顺序，来一条处理一条。
        var lastRaw: String? = null
        repeat(6) {
            val resp = awaitFirmware(CHECKSUM_TIMEOUT_MS) ?: return@coroutineScope WatchFaceOutcome.Failed(
                title = "等手环的结果超时",
                detail = "${CHECKSUM_TIMEOUT_MS / 1000} 秒内 1531 上没有新回复。",
                hint = "手环要算完整个文件的校验和，大文件慢一些；也可以直接看手环屏幕。",
            )
            val raw = WatchFace.hex(resp.raw)
            lastRaw = raw
            log("<- $raw")

            when (resp) {
                is WatchFace.Response.Ack -> when (resp.command) {
                    WatchFace.CMD_DATA_COMPLETE -> {
                        log("⑥ ✓ 手环说数据齐了，请求校验（带 CRC16）")
                        val checksum = WatchFace.checksumPacket(payload)
                        if (!connection.write(WatchFace.CHAR_CONTROL, checksum)) {
                            return@coroutineScope WatchFaceOutcome.Failed(
                                "写校验命令失败",
                                "1531 特征拒绝写入。",
                            )
                        }
                        log("⑦ 请校验 -> ${WatchFace.hex(checksum)}")
                    }

                    WatchFace.CMD_CHECKSUM -> {
                        if (resp.ok) {
                            log("⑧ ★ 校验通过 —— 手环接受了这个表盘包")
                            return@coroutineScope WatchFaceOutcome.Success
                        }
                        // 走到校验这一步说明数据是全的，只是结果不是 0x01。
                        log("⑧ ✗ 校验结果不是成功（status=0x%02x）".format(resp.status))
                        return@coroutineScope WatchFaceOutcome.Unconfirmed(raw)
                    }

                    else -> log("   （0x%02x 这条命令我们没定义，继续等）".format(resp.command))
                }

                is WatchFace.Response.Other -> log("   （认不出的形态，继续等）")
            }
        }
        return@coroutineScope WatchFaceOutcome.Unconfirmed(lastRaw ?: "(没收到任何回复)")
    }

    /**
     * 写一个数据包，失败就退避重试。
     *
     * BLE 写入偶发失败是常态（手环端缓冲满、连接事件被别的通知挤占）。
     * 一失败就整个放弃，等于前面那 70 秒白跑 —— 所以给它几次机会。
     */
    private suspend fun writePacket(characteristic: UUID, data: ByteArray): Boolean {
        repeat(PACKET_WRITE_ATTEMPTS) { attempt ->
            if (connection.write(characteristic, data)) return true
            delay(PACKET_RETRY_BASE_MS * (attempt + 1))
        }
        return false
    }

    /** 等一条固件通道的回复；超时返回 null。 */
    private suspend fun awaitFirmware(timeoutMs: Long): WatchFace.Response? =
        withTimeoutOrNull(timeoutMs) { firmwareIn.receive() }?.let { WatchFace.parse(it) }

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

    /**
     * 实时步数的字节布局照 Gadgetbridge 的 handleRealtimeSteps 抄的：
     * 13 字节包，步数在 bytes[1..2]（uint16 小端）。其余长度一律视为未识别 ——
     * 原样打进日志，等真机对过抓包再扩。
     */
    private fun parseSteps(value: ByteArray): Int? {
        val steps = when (value.size) {
            13 -> (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            2 -> (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
            else -> null
        }?.takeIf { it <= 500_000 }
        if (steps == null) {
            log("未识别的步数上报 <- " + value.joinToString(" ") { "%02x".format(it) })
        }
        return steps
    }
}
