package com.ted.shouhuan.ui.watchface

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.proto.BandSession
import com.ted.shouhuan.proto.WatchFace
import com.ted.shouhuan.proto.WatchFaceOutcome
import com.ted.shouhuan.proto.WatchFaceProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 选中的表盘包（只说「是什么」，字节另行持有 —— 别让 StateFlow 里躺着 240 KB）。 */
data class WatchFaceFileInfo(
    val name: String,
    val sizeBytes: Int,
    val crc32: Int,
)

/** 一次下发走到哪一步了。 */
sealed interface WatchFacePhase {
    data object Idle : WatchFacePhase

    data class Running(val progress: WatchFaceProgress) : WatchFacePhase

    /** 手环明确回了「校验通过」。 */
    data object Success : WatchFacePhase

    /** 包全收下了，但手环的收尾回复不符合预期 —— 生效没生效不确定。 */
    data class Unconfirmed(val lastResponse: String) : WatchFacePhase

    data class Failure(
        val title: String,
        val detail: String,
        val hint: String? = null,
        /** 能靠重新授权解决 —— 界面据此多给一个「去授权」按钮。 */
        val canGrantPermission: Boolean = false,
    ) : WatchFacePhase
}

/**
 * 表盘页的状态机。
 *
 * 这条链路是**实验性**的：协议只实测到「数据被完整接收」，
 * 最后一步（手环是否真的应用了表盘）还没确认。所以状态里专门有
 * [WatchFacePhase.Unconfirmed] 这一档，不硬拗成成功或失败 ——
 * 界面照实说明「包送达了、生效没确认」，用户才不会一头雾水。
 */
@SuppressLint("MissingPermission")
class WatchFaceViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** 连接 + 认证的总兜底（底层每一步自己还有 15 秒超时）。 */
        const val CONNECT_TIMEOUT_MS = 35_000L

        /** 界面上留多少行协议日志。 */
        const val LOG_LIMIT = 200
    }

    private val prefs = BandPrefs(app)
    private val session = BandSession(app, viewModelScope)

    private val _phase = MutableStateFlow<WatchFacePhase>(WatchFacePhase.Idle)
    val phase: StateFlow<WatchFacePhase> = _phase.asStateFlow()

    private val _file = MutableStateFlow<WatchFaceFileInfo?>(null)
    val file: StateFlow<WatchFaceFileInfo?> = _file.asStateFlow()

    /** 协议日志 —— 实验性功能，出问题时这些原始字节就是唯一线索。 */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** 本地有没有存好 MAC + AuthKey。 */
    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = session.connectionState

    /** 表盘包字节 —— 只在内存里，不进 StateFlow（也不落盘）。 */
    private var payload: ByteArray? = null

    private var running: Job? = null

    init {
        viewModelScope.launch {
            session.logs.collect { message ->
                _logs.value = (_logs.value + message).takeLast(LOG_LIMIT)
            }
        }
        viewModelScope.launch {
            combine(prefs.mac, prefs.authKey) { mac, key ->
                !mac.isNullOrBlank() && !key.isNullOrBlank()
            }.collect { _configured.value = it }
        }
    }

    // ------------------------------------------------------------------
    // 对外动作
    // ------------------------------------------------------------------

    /** 用户从系统文件选择器挑了一个包。 */
    fun selectFile(uri: Uri) {
        if (running?.isActive == true) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(app, uri) ?: "watchface.zip"
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("系统返回的文件流打不开")
                    name to bytes
                }
            }
            result.fold(
                onSuccess = { (name, bytes) ->
                    val problem = validate(bytes)
                    if (problem != null) {
                        _phase.value = problem
                        return@fold
                    }
                    payload = bytes
                    _file.value = WatchFaceFileInfo(name, bytes.size, WatchFace.crc32Of(bytes))
                    _phase.value = WatchFacePhase.Idle
                    session.log("已选中：$name（${bytes.size} 字节）")
                },
                onFailure = { t ->
                    _phase.value = WatchFacePhase.Failure(
                        title = "读不了这个文件",
                        detail = t.message ?: t.javaClass.simpleName,
                        hint = "换个文件试试 —— 有些来源的文件不允许被直接读取。",
                    )
                },
            )
        }
    }

    /** 开始下发。 */
    fun start() {
        if (running?.isActive == true) return
        val data = payload
        if (data == null) {
            _phase.value = WatchFacePhase.Failure(
                title = "还没选表盘包",
                detail = "先从文件里挑一个表盘包。",
                hint = "官方 App 会把用过的表盘缓存在手机里，也可以直接挑它。",
            )
            return
        }
        _phase.value = WatchFacePhase.Running(
            WatchFaceProgress(
                sentPackets = 0,
                totalPackets = WatchFace.packetCount(data.size),
                sentBytes = 0,
                totalBytes = data.size,
                elapsedMillis = 0,
            ),
        )
        running = viewModelScope.launch { runInstall(data) }
    }

    /**
     * 取消下发。
     *
     * 中途掐断意味着手环只收到半个文件 —— 它会自己把不完整的包丢掉，
     * 不会把现有表盘弄坏（这也是这个功能敢做得比较早的原因）。
     */
    fun cancel() {
        if (running?.isActive != true) return
        running?.cancel()
        running = null
        session.log("已取消下发（手环收到的是不完整文件，不会生效）")
        _phase.value = WatchFacePhase.Idle
    }

    /** 界面申请运行时权限后回填结果。 */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            start()
            return
        }
        _phase.value = WatchFacePhase.Failure(
            title = "没有蓝牙权限，传不了",
            detail = "你拒绝了「附近的设备」权限，系统不允许应用连接手环。",
            hint = "到「系统设置 → 应用 → 手环管家 → 权限」里手动打开，或者点重试再允许一次。",
            canGrantPermission = true,
        )
    }

    fun hasBluetoothPermission(): Boolean = checkBluetoothPermission(getApplication())

    /** 换一份文件时把上一次的结果清掉。 */
    fun clearResult() {
        if (isBusy(_phase.value)) return
        _phase.value = WatchFacePhase.Idle
    }

    override fun onCleared() {
        running?.cancel()
        session.disconnect()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 主线
    // ------------------------------------------------------------------

    private suspend fun runInstall(data: ByteArray) {
        preflight()?.let {
            _phase.value = it
            return
        }

        // 每次下发都重新连、重新认证：表盘通道是个一次性会话，
        // 复用一条来路不明的旧连接只会让失败原因更难判断。
        if (!session.authenticated.value) {
            val mac = prefs.mac.first().orEmpty()
            val key = prefs.authKey.first().orEmpty()
            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                session.connectAndAuthenticate(mac, key)
            } ?: false
            if (!ok) {
                _phase.value = WatchFacePhase.Failure(
                    title = "连接手环失败",
                    detail = (connectionState.value as? ConnectionState.Failed)?.reason
                        ?: "认证没有走完 —— 手环没回应，或者 AuthKey 不匹配。",
                    hint = "① 手环是不是还连在「小米运动健康」上？它同一时间只服务一个 App；" +
                        "② 把手环贴近手机；③ 确认 AuthKey 是这个 MAC 对应的那一台。",
                )
                return
            }
        }

        val outcome = session.installWatchFace(data) { progress ->
            _phase.value = WatchFacePhase.Running(progress)
        }
        _phase.value = when (outcome) {
            WatchFaceOutcome.Success -> WatchFacePhase.Success

            is WatchFaceOutcome.Unconfirmed -> WatchFacePhase.Unconfirmed(outcome.lastResponse)

            is WatchFaceOutcome.Failed -> WatchFacePhase.Failure(
                title = outcome.title,
                detail = outcome.detail,
                hint = outcome.hint,
            )
        }
    }

    /** 返回 null 表示前置条件都满足。顺序：先看配没配、再看权限、最后看蓝牙开关。 */
    private suspend fun preflight(): WatchFacePhase.Failure? {
        val app = getApplication<Application>()
        val mac = prefs.mac.first()
        val key = prefs.authKey.first()
        if (mac.isNullOrBlank() || key.isNullOrBlank()) {
            return WatchFacePhase.Failure(
                title = "还没配对手环",
                detail = "本地没存设备 MAC 和 AuthKey，不知道要连哪一台。",
                hint = "先到「设备」页完成配对。",
            )
        }

        if (!checkBluetoothPermission(app)) {
            return WatchFacePhase.Failure(
                title = "缺少蓝牙权限",
                detail = "Android 12 及以上要连手环，必须有 BLUETOOTH_CONNECT 这个运行时权限。",
                hint = "点下面的按钮，在系统弹窗里允许「附近的设备」。",
                canGrantPermission = true,
            )
        }

        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            return WatchFacePhase.Failure(
                title = "这台设备没有蓝牙适配器",
                detail = "系统报告 BLUETOOTH_SERVICE 不可用。",
                hint = "换一台手机，或检查是不是在模拟器里跑。",
            )
        }
        if (!adapter.isEnabled) {
            return WatchFacePhase.Failure(
                title = "蓝牙没开",
                detail = "蓝牙适配器处于关闭状态，连不上手环。",
                hint = "下拉通知栏打开蓝牙，然后点重试。",
            )
        }
        return null
    }

    /** 包体本身有没有明显问题。 */
    private fun validate(bytes: ByteArray): WatchFacePhase.Failure? = when {
        bytes.isEmpty() -> WatchFacePhase.Failure(
            title = "文件是空的",
            detail = "读出来 0 字节。",
        )

        bytes.size > WatchFace.MAX_PAYLOAD_BYTES -> WatchFacePhase.Failure(
            title = "表盘包太大",
            detail = "文件 ${bytes.size} 字节，上限 ${WatchFace.MAX_PAYLOAD_BYTES} 字节。",
            hint = "官方自定义表盘约 240 KB —— 确认挑的不是别的压缩包。",
        )

        else -> null
    }

    private fun queryDisplayName(app: Application, uri: Uri): String? = runCatching {
        app.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun checkBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/** 下发中（含连接、认证）—— 界面据此禁用按钮、显示进度。 */
fun isBusy(phase: WatchFacePhase): Boolean = phase is WatchFacePhase.Running
