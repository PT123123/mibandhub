package com.ted.shouhuan.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Discovering : ConnectionState

    /** 已连上且服务就绪，但还没认证。 */
    data object Connected : ConnectionState
    data object Authenticated : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

data class Incoming(val characteristic: UUID, val value: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Incoming && characteristic == other.characteristic && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * characteristic.hashCode() + value.contentHashCode()
}

/**
 * 一条 GATT 连接的薄封装。
 *
 * 两个必须守住的规则（否则会出现「写进去了但手环没反应」）：
 *   1. **GATT 操作必须串行** —— 上一个 write 的回调没回来之前不能发下一个，
 *      所以所有读写都过同一把锁。
 *   2. **API 33 前后回调签名不同** —— `onCharacteristicChanged` 有 2 参和 3 参两个版本，
 *      写特征的新 API 还会直接返回状态码。两套都要覆盖。
 *
 * 连接方式：按 MAC 直连，不做扫描 —— 已配对设备不需要广播发现，
 * 也顺带省掉了「扫描要定位权限」的麻烦。
 */
@SuppressLint("MissingPermission")
class BandConnection(private val context: Context) {

    private companion object {
        const val TAG = "BandConnection"
        const val TIMEOUT_MS = 15_000L
    }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 128)
    val incoming: SharedFlow<Incoming> = _incoming

    /**
     * 活动数据（00000004 元数据 / 00000005 样本）的专用队列。
     *
     * 同步时样本包以约 90 包/秒的速率涌入（实测 MTU 244、241 字节/包、286 包 ≈ 3 秒），
     * 不能走 [incoming]：SharedFlow 的 tryEmit 缓冲满了会静默丢包，消费者每次
     * `first {}` 的订阅间隙也会漏包 —— 丢一条，包序号就断，整轮同步作废。
     * Channel(UNLIMITED) 在蓝牙回调线程 trySend，只进不出也丢不了，
     * 由同步循环按自己的节奏取（见 BandSession.syncActivity）。
     */
    private val activityIncoming = Channel<Incoming>(Channel.UNLIMITED)

    /** 所有 GATT 操作共用一把锁，保证串行。 */
    private val gattMutex = Mutex()

    private var pendingConnect: CompletableDeferred<Boolean>? = null
    private var pendingServices: CompletableDeferred<Boolean>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingRead: CompletableDeferred<ByteArray?>? = null
    private var pendingDescriptor: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "连接状态变化 status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnectionState.Discovering
                    pendingConnect?.complete(true)
                    if (!g.discoverServices()) {
                        _state.value = ConnectionState.Failed("无法启动服务发现")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                        ConnectionState.Disconnected
                    } else {
                        ConnectionState.Failed("连接断开（GATT 状态 $status）")
                    }
                    pendingConnect?.complete(false)
                    pendingServices?.complete(false)
                    pendingWrite?.complete(false)
                    pendingRead?.complete(null)
                    pendingDescriptor?.complete(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, "服务发现完成 status=$status 服务数=${g.services.size}")
            if (ok) _state.value = ConnectionState.Connected
            pendingServices?.complete(ok)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // 也在这里打：writeCharacteristic() 同步返回 SUCCESS 只代表「请求已受理」，
            // 真正写没写成要看这个回调的 status（133=GATT_ERROR、3=无写权限、
            // 8=需要认证/加密、6=特征不支持该操作）。
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "写入回调失败 status=$status 特征=${c.uuid}")
            }
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "读取回调失败 status=$status 特征=${c.uuid}")
            }
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "描述符写入回调失败 status=$status 描述符=${d.uuid}")
            }
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        /** Android 12 及以下的回调。 */
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            emit(c.uuid, c.value ?: ByteArray(0))
        }

        /** Android 13+ 的回调。 */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emit(c.uuid, value)
        }
    }

    /** 活动数据的取包入口 —— 同步循环从这里 receive，语义见 [activityIncoming]。 */
    fun activityQueue(): Channel<Incoming> = activityIncoming

    private fun emit(uuid: UUID, value: ByteArray) {
        val msg = Incoming(uuid, value)
        if (uuid == Gatt.CHAR_ACTIVITY_FETCH || uuid == Gatt.CHAR_ACTIVITY_SAMPLES) {
            activityIncoming.trySend(msg)
        } else {
            _incoming.tryEmit(msg)
        }
    }

    /** 按 MAC 连接并完成服务发现。 */
    suspend fun connect(mac: String): Boolean {
        val a = adapter
        if (a == null) {
            _state.value = ConnectionState.Failed("本机没有蓝牙适配器")
            return false
        }
        if (!a.isEnabled) {
            _state.value = ConnectionState.Failed("蓝牙未开启")
            return false
        }

        close()

        val connectDeferred = CompletableDeferred<Boolean>()
        val servicesDeferred = CompletableDeferred<Boolean>()
        pendingConnect = connectDeferred
        pendingServices = servicesDeferred
        _state.value = ConnectionState.Connecting

        val device = try {
            a.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            _state.value = ConnectionState.Failed("MAC 地址不合法：$mac")
            return false
        }

        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        val connected = withTimeoutOrNull(TIMEOUT_MS) { connectDeferred.await() } ?: false
        if (!connected) {
            if (_state.value !is ConnectionState.Failed) {
                _state.value = ConnectionState.Failed("连接超时（手环不在附近或已被其它 App 占用？）")
            }
            return false
        }

        val discovered = withTimeoutOrNull(TIMEOUT_MS) { servicesDeferred.await() } ?: false
        if (!discovered) {
            _state.value = ConnectionState.Failed("服务发现超时")
            return false
        }
        return true
    }

    /** 写一个特征值。 */
    suspend fun write(characteristic: UUID, data: ByteArray): Boolean = gattMutex.withLock {
        val g = gatt ?: return@withLock false
        val c = findCharacteristic(g, characteristic) ?: run {
            Log.w(TAG, "找不到特征 $characteristic")
            return@withLock false
        }

        // writeType 必须跟着特征声明的能力走：只声明了「无应答写」的特征，
        // 用 WRITE_TYPE_DEFAULT 会被协议栈直接拒掉。两个都声明时先试带应答的，
        // 失败再退到无应答 —— 海米设备在这件事上的容忍度不太一致。
        val props = c.properties
        val candidates = buildList {
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                add(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                add(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            }
        }.ifEmpty { listOf(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }

        for ((index, writeType) in candidates.withIndex()) {
            if (tryWrite(g, c, data, writeType)) {
                Log.d(
                    TAG,
                    "写入 $characteristic 成功（writeType=$writeType properties=0x" +
                        "${Integer.toHexString(props)} 字节数=${data.size}）",
                )
                return@withLock true
            }
            Log.w(
                TAG,
                "写 $characteristic 失败（writeType=$writeType properties=0x" +
                    "${Integer.toHexString(props)} 字节数=${data.size}）" +
                    if (index < candidates.lastIndex) {
                        "，换另一种 writeType 重试"
                    } else {
                        "，没有可再试的 writeType"
                    },
            )
        }
        false
    }

    /**
     * 发起一次写并等回调。
     *
     * 调用方必须已持有 [gattMutex]（GATT 操作要串行）。
     * `writeCharacteristic()` 返回 SUCCESS 只代表「请求被受理」，
     * 真正写没写成要看 `onCharacteristicWrite` 的 status —— 所以失败日志那边也有一份。
     */
    private suspend fun tryWrite(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingWrite = deferred

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = g.writeCharacteristic(c, data, writeType)
            if (code != BluetoothStatusCodes.SUCCESS) {
                Log.w(TAG, "writeCharacteristic 被拒：code=$code 特征=${c.uuid} writeType=$writeType")
            }
            code == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = writeType
                c.value = data
                g.writeCharacteristic(c)
            }
        }

        if (!started) {
            pendingWrite = null
            return false
        }
        return withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: false
    }

    /** 读一个特征值。 */
    suspend fun read(characteristic: UUID): ByteArray? = gattMutex.withLock {
        val g = gatt ?: return@withLock null
        val c = findCharacteristic(g, characteristic) ?: return@withLock null

        val deferred = CompletableDeferred<ByteArray?>()
        pendingRead = deferred

        // 注意：readCharacteristic 一直返回 Boolean —— Android 13 只把
        // writeCharacteristic / writeDescriptor 改成返回状态码，read 没改。
        // 所以这里不能和 BluetoothStatusCodes.SUCCESS 比较（那是 Int）。
        @Suppress("DEPRECATION")
        val started = g.readCharacteristic(c)

        if (!started) {
            pendingRead = null
            return@withLock null
        }
        withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
    }

    /** 开启/关闭某个特征的 notify（会一并写 CCCD 描述符）。 */
    suspend fun enableNotify(characteristic: UUID, enable: Boolean = true): Boolean =
        gattMutex.withLock {
            val g = gatt ?: return@withLock false
            val c = findCharacteristic(g, characteristic) ?: run {
                Log.w(TAG, "找不到特征 $characteristic")
                return@withLock false
            }

            if (!g.setCharacteristicNotification(c, enable)) {
                Log.w(TAG, "setCharacteristicNotification 失败：$characteristic")
                return@withLock false
            }

            val cccd = c.getDescriptor(Gatt.DESC_CCCD) ?: run {
                // 少数特征没有 CCCD，但 setCharacteristicNotification 已经生效
                Log.d(TAG, "$characteristic 没有 CCCD 描述符，跳过")
                return@withLock true
            }

            val deferred = CompletableDeferred<Boolean>()
            pendingDescriptor = deferred
            val value = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    g.writeDescriptor(cccd)
                }
            }

            if (!started) {
                pendingDescriptor = null
                return@withLock false
            }
            withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: false
        }

    /** 当前连接上是否存在某个特征 —— 用来判断手环支持哪些能力。 */
    fun hasCharacteristic(characteristic: UUID): Boolean =
        gatt?.let { findCharacteristic(it, characteristic) } != null

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        if (_state.value !is ConnectionState.Failed) {
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun findCharacteristic(
        g: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattCharacteristic? {
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }
}
