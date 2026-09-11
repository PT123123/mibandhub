package com.ted.shouhuan.ui.heart

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.MeasureResult
import com.ted.shouhuan.proto.BandSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 一次测量走到哪一步了。 */
sealed interface MeasurePhase {
    /** 没在测 —— 显示上一次的值，或者「--」。 */
    data object Idle : MeasurePhase

    /** 正在建 GATT 连接。 */
    data object Connecting : MeasurePhase

    /** 连上了，正在跑认证。 */
    data object Authenticating : MeasurePhase

    /** 指令已下发，等手环上报读数。 */
    data object Measuring : MeasurePhase

    data class Success(val result: MeasureResult) : MeasurePhase

    data class Failure(val error: MeasureError) : MeasurePhase
}

/**
 * 一条给用户看的失败原因。
 *
 * 三样都得有，用户才不会卡在「正在测量…」上干等：
 *   - [title] 一句话说清出了什么事
 *   - [detail] 底层到底报了什么（能贴原始原因就贴，方便排查）
 *   - [hint] 接下来该干什么
 */
data class MeasureError(
    val title: String,
    val detail: String,
    val hint: String? = null,
    /** 这个错误能靠重新授权解决 —— 界面据此多给一个「去授权」按钮。 */
    val canGrantPermission: Boolean = false,
)

/** 等待读数时，「现在能得出什么结论」。 */
private sealed interface Reading {
    data object Waiting : Reading
    data class Ok(val bpm: Int) : Reading
    data object Lost : Reading
}

/**
 * 心率页的状态机。
 *
 * 界面只管画 [phase] / [elapsedSec] / [lastResult] / [history]，
 * 所有「连不上、认证不过、超时、中途掉线」的判断都在这里收敛成一条
 * [MeasureError]，不让 UI 去猜。
 */
@SuppressLint("MissingPermission")
class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /**
         * 连接 + 认证的总兜底。底层每一步自己还有 15 秒超时，
         * 这里是最后一道保险，保证界面不会永远停在「正在连接手环…」。
         */
        const val CONNECT_TIMEOUT_MS = 35_000L

        /** 单次测量等读数的最长时间 —— 手环一般 10~30 秒出结果。 */
        const val MEASURE_TIMEOUT_MS = 40_000L
    }

    private val prefs = BandPrefs(app)
    private val session = BandSession(app, viewModelScope)

    private val _phase = MutableStateFlow<MeasurePhase>(MeasurePhase.Idle)
    val phase: StateFlow<MeasurePhase> = _phase.asStateFlow()

    /** 已经等了多久（秒）—— 界面显示成「正在测量… 12s」，让用户知道程序还活着。 */
    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec.asStateFlow()

    /**
     * 测量记录，新的在前。
     *
     * 直接以本地存储为唯一来源，而不是在内存里另存一份：这样「界面上看到的」
     * 和「重启后还在的」天然是同一份，不会出现两边对不上。
     */
    val history: StateFlow<List<MeasureResult>> = prefs.measureHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 最近一次测量 —— 就是记录里的第一条，重启后依然在。 */
    val lastResult: StateFlow<MeasureResult?> = history
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 最近一次测到的值。切到别的页面再回来还在。 */
    val lastBpm: StateFlow<Int?> = history
        .map { it.firstOrNull()?.bpm }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 设置里是否已经配好手环（MAC + AuthKey 都在）。 */
    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    /** 底层连接状态，界面拿它显示「已连接 / 未连接」。 */
    val connectionState: StateFlow<ConnectionState> = session.connectionState

    private var running: Job? = null

    init {
        viewModelScope.launch {
            combine(prefs.mac, prefs.authKey) { mac, key ->
                !mac.isNullOrBlank() && !key.isNullOrBlank()
            }.collect { _configured.value = it }
        }
    }

    // ------------------------------------------------------------------
    // 对外动作
    // ------------------------------------------------------------------

    /** 点「测量一次 / 再测一次 / 重试」。 */
    fun startMeasure() {
        if (running?.isActive == true) return // 连点两下不叠两个流程
        _phase.value = MeasurePhase.Connecting
        _elapsedSec.value = 0
        running = viewModelScope.launch { runMeasure() }
    }

    /** 点「取消 / 停止测量」。 */
    fun cancelMeasure() {
        running?.cancel()
        running = null
        _elapsedSec.value = 0
        _phase.value = MeasurePhase.Idle
    }

    /** 界面申请运行时权限后回填结果。 */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            startMeasure()
            return
        }
        _phase.value = MeasurePhase.Failure(
            MeasureError(
                title = "没有蓝牙权限，测不了",
                detail = "你拒绝了「附近的设备」权限，系统不允许应用连接手环。",
                hint = "到「系统设置 → 应用 → 手环管家 → 权限」里手动打开，或者点重试再允许一次。",
                canGrantPermission = true,
            ),
        )
    }

    /** 当前有没有连手环所需的运行时权限（Android 12 以下恒为 true）。 */
    fun hasBluetoothPermission(): Boolean = checkBluetoothPermission(getApplication())

    override fun onCleared() {
        running?.cancel()
        session.disconnect()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 主线：前置检查 → 连接认证 → 下发指令 → 等读数
    // ------------------------------------------------------------------

    private suspend fun runMeasure() {
        // ---- 1. 前置条件：没配对 / 没权限 / 蓝牙没开，都在这儿拦掉 ----
        preflight(getApplication())?.let {
            fail(it)
            return
        }

        // 从这一刻起界面上的秒数开始走：用户能看出程序在动，而不是卡死了。
        // 同时也拿它当耗时基准 —— 跟界面上那个秒数同源，不会对不上。
        val startedAtMillis = System.currentTimeMillis()
        val ticker = startTicker()
        try {
            // ---- 2. 连上并认证（已经认证过就跳过）----
            if (!session.authenticated.value) {
                val mac = prefs.mac.first().orEmpty()
                val key = prefs.authKey.first().orEmpty()
                if (!ensureAuthenticated(mac, key)) {
                    fail(connectError())
                    return
                }
            }

            // ---- 3. 下发单次测量指令 ----
            _phase.value = MeasurePhase.Measuring
            session.clearHeartRate() // 不清的话会拿上一轮的旧读数当结果

            if (!session.measureOnce()) {
                fail(
                    MeasureError(
                        title = "手环没有接受测量指令",
                        detail = "往心率控制点（0x2A39）写指令失败，或者手环压根没暴露这个特征。",
                        hint = "确认手环没连在「小米运动健康」上，然后重试。",
                    ),
                )
                return
            }

            // ---- 4. 等读数：拿到值 / 掉线 / 超时，三选一 ----
            val reading = withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
                readingFlow().first { it !is Reading.Waiting }
            }

            when (reading) {
                is Reading.Ok -> {
                    val finishedAt = System.currentTimeMillis()
                    val result = MeasureResult(
                        bpm = reading.bpm,
                        finishedAtMillis = finishedAt,
                        durationSec = ((finishedAt - startedAtMillis) / 1000).toInt(),
                    )
                    // 先落盘再改状态：记录是唯一来源，写进去之后 lastResult / lastBpm
                    // 会自己跟着更新，界面和存储不会各说各话。
                    prefs.recordMeasure(result)
                    _phase.value = MeasurePhase.Success(result)
                }

                Reading.Lost -> fail(
                    MeasureError(
                        title = "测量途中连接断开了",
                        detail = (connectionState.value as? ConnectionState.Failed)?.reason
                            ?: "手环侧主动断开了 GATT 连接。",
                        hint = "常见原因是手环被别的 App 抢走，或者离手机太远。拉近距离后重试。",
                    ),
                )

                else -> fail(
                    MeasureError(
                        title = "测量超时（${MEASURE_TIMEOUT_MS / 1000} 秒没有读数）",
                        detail = "指令写成功了，但心率测量特征（0x2A37）一直没有上报任何有效值。",
                        hint = "把手环戴紧、贴合手腕，保持静止几秒后重试。",
                    ),
                )
            }
        } finally {
            ticker.cancel()
            _elapsedSec.value = 0
        }
    }

    /**
     * 返回 null 表示前置条件都满足；否则返回该显示给用户的错误。
     *
     * 顺序有讲究：先看配没配、再看权限、最后看蓝牙开关 —— 越靠前的越根本。
     */
    private suspend fun preflight(app: Application): MeasureError? {
        val mac = prefs.mac.first()
        val key = prefs.authKey.first()
        if (mac.isNullOrBlank() || key.isNullOrBlank()) {
            return MeasureError(
                title = "还没配对手环",
                detail = if (mac.isNullOrBlank()) {
                    "本地没存设备 MAC 和 AuthKey，不知道要连哪一台。"
                } else {
                    "有 MAC（$mac）但缺 AuthKey —— 没有它手环会在认证阶段直接断连。"
                },
                hint = "先到「设备」页完成配对；AuthKey 可以用仓库里的 just fetch 从官方 App 日志里直接读。",
            )
        }

        if (!checkBluetoothPermission(app)) {
            return MeasureError(
                title = "缺少蓝牙权限",
                detail = "Android 12 及以上要连手环，必须有 BLUETOOTH_CONNECT 这个运行时权限。",
                hint = "点下面的按钮，在系统弹窗里允许「附近的设备」。",
                canGrantPermission = true,
            )
        }

        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            return MeasureError(
                title = "这台设备没有蓝牙适配器",
                detail = "系统报告 BLUETOOTH_SERVICE 不可用。",
                hint = "换一台手机，或检查是不是在模拟器里跑。",
            )
        }
        if (!adapter.isEnabled) {
            return MeasureError(
                title = "蓝牙没开",
                detail = "蓝牙适配器处于关闭状态，连不上手环。",
                hint = "下拉通知栏打开蓝牙，然后点重试。",
            )
        }
        return null
    }

    /**
     * 连接 + 认证。
     *
     * 期间顺带把底层连接状态翻译成界面文案（正在连接 → 正在认证），
     * 用户能看出到底卡在哪一步，而不是干等一个「正在测量…」。
     */
    private suspend fun ensureAuthenticated(mac: String, key: String): Boolean {
        val watcher = viewModelScope.launch {
            session.connectionState.collect { state ->
                // 服务发现完成（ConnectionState.Connected）= GATT 已经通了，下一步才是认证
                if (state is ConnectionState.Connected && _phase.value == MeasurePhase.Connecting) {
                    _phase.value = MeasurePhase.Authenticating
                }
            }
        }
        return try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                session.connectAndAuthenticate(mac, key)
            } ?: false
        } finally {
            watcher.cancel()
        }
    }

    /** 把底层报的原因翻译成给用户看的话。 */
    private fun connectError(): MeasureError {
        val reason = (connectionState.value as? ConnectionState.Failed)?.reason
            ?: "认证没有走完 —— 手环没回应，或者 AuthKey 不匹配。"
        return MeasureError(
            title = "连接手环失败",
            detail = reason,
            hint = "① 手环是不是还连在「小米运动健康」上？它同一时间只能连一个 App；" +
                "② 把手环贴近手机；③ 确认 AuthKey 确实是这个 MAC 对应的那一台。",
        )
    }

    /**
     * 两条流合并：谁先给出结论用谁。
     *
     * `bpm > 0` 这层判断现在由 [com.ted.shouhuan.proto.HeartRateParser] 保证
     * （它把 0 直接滤成 null），这里留一道是防止以后换数据源时漏掉。
     */
    private fun readingFlow(): Flow<Reading> = combine(
        session.heartRate,
        session.connectionState,
    ) { bpm, conn ->
        when {
            bpm != null && bpm > 0 -> Reading.Ok(bpm)
            conn is ConnectionState.Disconnected || conn is ConnectionState.Failed -> Reading.Lost
            else -> Reading.Waiting
        }
    }

    /** 每秒 +1，只为让界面上的秒数在动。 */
    private fun startTicker(): Job = viewModelScope.launch {
        while (isActive) {
            delay(1_000)
            _elapsedSec.value += 1
        }
    }

    private fun fail(error: MeasureError) {
        _elapsedSec.value = 0
        _phase.value = MeasurePhase.Failure(error)
    }

    private fun checkBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
