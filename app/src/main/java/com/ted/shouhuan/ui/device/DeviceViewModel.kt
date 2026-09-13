package com.ted.shouhuan.ui.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.HealthConnectSleepSource
import com.ted.shouhuan.proto.BandSettings
import com.ted.shouhuan.proto.FULL_HISTORY_DAYS
import com.ted.shouhuan.service.ActivityDataImport
import com.ted.shouhuan.service.BandSessionProvider
import com.ted.shouhuan.service.HeartMeasureController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * 一条给用户看的连接失败原因。
 *
 * 和心率页的 [com.ted.shouhuan.ui.heart.MeasureError] 是同一个形状（出了什么事 / 为什么 /
 * 怎么办），故意不共用一个类：两页的失败原因将来会各自长出自己的字段，
 * 硬绑在一起只会互相牵制。
 */
data class DeviceError(
    val title: String,
    val detail: String,
    val hint: String? = null,
    /** 能靠重新授权解决 —— 界面据此多给一个「去授权」按钮。 */
    val canGrantPermission: Boolean = false,
)

/** 睡眠同步走到哪一步了。 */
sealed interface SleepSyncPhase {
    data object Idle : SleepSyncPhase

    /** received/expected 是样本字节数（手环全量约几十万字节）。 */
    data class Syncing(val received: Int, val expected: Int) : SleepSyncPhase

    data class Done(val nights: Int, val sampleMinutes: Int) : SleepSyncPhase

    data class Failed(val message: String) : SleepSyncPhase
}

/**
 * 已存下来的配对信息，用来给配对页做初值。
 *
 * 单独包一层是为了区分「还没从本地存储读出来」（null）和「读出来了但是空的」——
 * 两者都表现为空字符串的话，表单就没法既填上旧值、又不被尚未加载的空值覆盖。
 */
data class PairingSeed(
    val mac: String,
    val name: String,
    val authKey: String,
)

/**
 * 设备页的状态。
 *
 * 这一页管两件事：
 *   1. **配对信息**（名称 / MAC / AuthKey）—— 读写全在 [BandPrefs]，界面只是搬运工；
 *   2. **手动连一次**（连接 → 认证 → 读电量），成功与否都给一句能照做的失败原因。
 *
 * 连接走**进程级共享会话**（[BandSessionProvider]）：这里点「连接」，通知栏的
 * 状态卡和首页跟着一起变 —— 它们看的就是同一条会话。连上之后也不主动断：
 * 常驻通知本来就要显示在线状态，而且心率页再测量时可以免重连直接用。
 * 「断开」只在用户明确点按钮时发生。
 */
@SuppressLint("MissingPermission")
class DeviceViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** 连接 + 认证的总兜底（底层每一步自己还有 15 秒超时）。 */
        const val CONNECT_TIMEOUT_MS = 35_000L

        const val TAG = "DeviceViewModel"

        /** 菜单/快捷方式的项数上限 —— GB 的规则：主菜单超过 16 项会被手环截断。 */
        const val MAX_ITEMS = 16
    }

    private val prefs = BandPrefs(app)
    private val session = BandSessionProvider.get(app)

    // ---- 配对信息（本地存储是唯一来源，界面不另存一份）----
    val mac: StateFlow<String?> =
        prefs.mac.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val name: StateFlow<String?> =
        prefs.name.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val authKey: StateFlow<String?> =
        prefs.authKey.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val autoConnect: StateFlow<Boolean> =
        prefs.autoConnect.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val forwardNotifications: StateFlow<Boolean> =
        prefs.forwardNotifications.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** MAC 和 AuthKey 都齐了才算配好 —— 只有一个的话连不上，界面得按「没配好」提示。 */
    val paired: StateFlow<Boolean> =
        combine(prefs.mac, prefs.authKey) { mac, key ->
            !mac.isNullOrBlank() && !key.isNullOrBlank()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 配对页的初值。本地存储读出来之前是 null，读出来之后就不再变。 */
    val pairingSeed: StateFlow<PairingSeed?> =
        combine(prefs.mac, prefs.name, prefs.authKey) { mac, name, key ->
            PairingSeed(mac.orEmpty(), name.orEmpty(), key.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---- 连接状态 ----
    val connectionState: StateFlow<ConnectionState> = session.connectionState

    val authenticated: StateFlow<Boolean> = session.authenticated

    /** 最近一次读到的电量。断开后保留旧值只是为了不闪 —— 界面只在已连接时才显示它。 */
    val battery: StateFlow<Int?> = session.battery

    val connected: StateFlow<Boolean> = session.connectionState
        .map { it is ConnectionState.Connected || it is ConnectionState.Authenticated }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _error = MutableStateFlow<DeviceError?>(null)
    val error: StateFlow<DeviceError?> = _error.asStateFlow()

    private val _sleepSync = MutableStateFlow<SleepSyncPhase>(SleepSyncPhase.Idle)
    val sleepSync: StateFlow<SleepSyncPhase> = _sleepSync.asStateFlow()

    private var linking: Job? = null
    private var syncing: Job? = null

    // ------------------------------------------------------------------
    // 对外动作
    // ------------------------------------------------------------------

    /** 点「连接手环」。 */
    fun connect() {
        if (linking?.isActive == true) return // 连点两下不叠两个流程
        _error.value = null
        linking = viewModelScope.launch {
            val mac = prefs.mac.first()
            val key = prefs.authKey.first()
            if (mac.isNullOrBlank() || key.isNullOrBlank()) {
                _error.value = notPairedError()
                return@launch
            }
            preflight()?.let {
                _error.value = it
                return@launch
            }
            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                session.connectAndAuthenticate(mac, key)
            } ?: false
            if (!ok) {
                _error.value = linkError()
                return@launch
            }
            // 电量由 connectAndAuthenticate 顺序读一次，结果走 session.battery 上来。
        }
    }

    /** 点「断开连接」。只由用户明确触发 —— 离开页面不断开（通知栏要如实显示在线状态）。 */
    fun disconnect() {
        linking?.cancel()
        linking = null
        session.disconnect()
    }

    /** 界面申请运行时权限后回填结果。 */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            connect()
            return
        }
        _error.value = DeviceError(
            title = "没有蓝牙权限，连不上手环",
            detail = "你拒绝了「附近的设备」权限，系统不允许应用连接手环。",
            hint = "到「系统设置 → 应用 → 手环管家 → 权限」里手动打开，或者点重试再允许一次。",
            canGrantPermission = true,
        )
    }

    fun hasBluetoothPermission(): Boolean = checkBluetoothPermission(getApplication())

    /** 存下配对信息（已规范化过）。存完顺手清掉上一次的报错 —— 换了新信息，旧结论不作数了。 */
    suspend fun savePairing(mac: String, name: String, authKey: String) {
        prefs.saveDevice(mac, name, authKey)
        _error.value = null
    }

    /** 忘记设备：断开连接并抹掉本地的 MAC / 名称 / AuthKey。 */
    suspend fun forgetDevice() {
        disconnect()
        prefs.forgetDevice()
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoConnect(enabled) }
    }

    fun setForwardNotifications(enabled: Boolean) {
        viewModelScope.launch { prefs.setForwardNotifications(enabled) }
    }

    /** 关掉失败提示（用户看过、点了知道了）。 */
    fun dismissError() {
        _error.value = null
    }

    /**
     * 从手环同步全部可拉的活动明细，解析成睡眠夜写进本地（设备页的「同步手环数据」按钮）。
     *
     * 同步不清手环数据（从不 ack，手环收到 ack 才删），所以同一窗口会重复推，
     * 入库按醒来日期/样本时刻幂等去重；起点给十年前 = 手环保留多少拉多少，
     * 比 app 打开时的自动拉取（近 7 天，见 BandService）拉得更远也更久。
     * 没连着就先走一遍连接流程；测心率进行中不让动（共用一条 GATT，互相干扰）。
     */
    fun syncSleep() {
        if (syncing?.isActive == true) return
        val controller = HeartMeasureController.get(getApplication())
        if (controller.busy) {
            _sleepSync.value = SleepSyncPhase.Failed("心率测量进行中，等它跑完再同步")
            return
        }
        _sleepSync.value = SleepSyncPhase.Syncing(0, 0)
        syncing = viewModelScope.launch {
            try {
                if (!session.authenticated.value) {
                    val mac = prefs.mac.first()
                    val key = prefs.authKey.first()
                    if (mac.isNullOrBlank() || key.isNullOrBlank()) {
                        _sleepSync.value = SleepSyncPhase.Failed("还没配对手环")
                        return@launch
                    }
                    preflight()?.let {
                        _sleepSync.value = SleepSyncPhase.Failed("${it.title}。${it.detail}")
                        return@launch
                    }
                    val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        session.connectAndAuthenticate(mac, key)
                    } ?: false
                    if (!ok) {
                        _sleepSync.value = SleepSyncPhase.Failed("连接失败，先在上方手动连一次看原因")
                        return@launch
                    }
                }
                val result = session.syncActivity(sinceDays = FULL_HISTORY_DAYS) { p ->
                    _sleepSync.value = SleepSyncPhase.Syncing(p.receivedBytes, p.expectedBytes)
                }
                ActivityDataImport.import(getApplication(), prefs, result.nights, result.samples)
                _sleepSync.value = SleepSyncPhase.Done(result.nights.size, result.sampleMinutes)
            } catch (e: Exception) {
                _sleepSync.value = SleepSyncPhase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 健康连接外部数据源 —— 界面先查可用性/权限，再拉这里。 */
    val healthConnect = HealthConnectSleepSource(getApplication())

    /**
     * 从健康连接导睡眠（小米运动健康等 App 同步进去的数据）。
     * 与手环同步共用 [_sleepSync] 状态和统一的落库入口（[ActivityDataImport]）。
     */
    fun importSleepFromHealthConnect() {
        if (syncing?.isActive == true) return
        _sleepSync.value = SleepSyncPhase.Syncing(0, 0)
        syncing = viewModelScope.launch {
            try {
                val nights = healthConnect.readNights(
                    ZonedDateTime.now().minusDays(FULL_HISTORY_DAYS.toLong()),
                )
                ActivityDataImport.import(getApplication(), prefs, nights)
                _sleepSync.value =
                    SleepSyncPhase.Done(nights.size, nights.sumOf { it.totalMinutes })
            } catch (e: Exception) {
                _sleepSync.value =
                    SleepSyncPhase.Failed("健康连接导入失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 手环本机设置（存储 + 当场下发；连接后整套推送在 BandService）
    // ------------------------------------------------------------------

    val wearLeft: StateFlow<Boolean> =
        prefs.wearLeft.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val liftWake: StateFlow<Boolean> =
        prefs.liftWake.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val swipeUnlock: StateFlow<Boolean> =
        prefs.swipeUnlock.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val disconnectAlert: StateFlow<Boolean> =
        prefs.disconnectAlert.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dndMode: StateFlow<String> =
        prefs.dndMode.stateIn(viewModelScope, SharingStarted.Eagerly, "off")

    val dndStart: StateFlow<Int> =
        prefs.dndStartMinute.stateIn(viewModelScope, SharingStarted.Eagerly, 22 * 60)

    val dndEnd: StateFlow<Int> =
        prefs.dndEndMinute.stateIn(viewModelScope, SharingStarted.Eagerly, 7 * 60)

    val nightMode: StateFlow<String> =
        prefs.nightMode.stateIn(viewModelScope, SharingStarted.Eagerly, "off")

    val nightStart: StateFlow<Int> =
        prefs.nightStartMinute.stateIn(viewModelScope, SharingStarted.Eagerly, 22 * 60)

    val nightEnd: StateFlow<Int> =
        prefs.nightEndMinute.stateIn(viewModelScope, SharingStarted.Eagerly, 7 * 60)

    /** 主菜单顺序。没存过时回落到 MB5 出厂默认。 */
    val menuOrder: StateFlow<List<BandSettings.Item>> =
        prefs.menuOrder.map { keys -> itemsOf(keys, BandSettings.Item.DEFAULT_MENU) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BandSettings.Item.DEFAULT_MENU)

    /** 快捷方式顺序。 */
    val shortcutOrder: StateFlow<List<BandSettings.Item>> =
        prefs.shortcutOrder.map { keys -> itemsOf(keys, BandSettings.Item.DEFAULT_SHORTCUTS) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BandSettings.Item.DEFAULT_SHORTCUTS)

    val remindOnConnect: StateFlow<Boolean> =
        prefs.remindOnConnect.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val remindLowBattery: StateFlow<Boolean> =
        prefs.remindLowBattery.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val remindLowBatteryPct: StateFlow<Int> =
        prefs.remindLowBatteryPct.stateIn(viewModelScope, SharingStarted.Eagerly, 15)

    val remindFullyCharged: StateFlow<Boolean> =
        prefs.remindFullyCharged.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 最近一次设置动作的结果，给界面显示一行反馈。 */
    private val _settingsStatus = MutableStateFlow<String?>(null)
    val settingsStatus: StateFlow<String?> = _settingsStatus.asStateFlow()

    fun clearSettingsStatus() {
        _settingsStatus.value = null
    }

    fun setWearLocation(left: Boolean) = applySetting("佩戴手") {
        prefs.setWearLeft(left)
        if (isLinked()) session.applyWearLocation(left)
    }

    fun setLiftWake(enabled: Boolean) = applySetting("抬腕亮屏") {
        prefs.setLiftWake(enabled)
        if (isLinked()) session.applyDisplayOnLiftWrist(enabled)
    }

    fun setSwipeUnlock(enabled: Boolean) = applySetting("滑动解锁") {
        prefs.setSwipeUnlock(enabled)
        if (isLinked()) session.applySwipeUnlock(enabled)
    }

    fun setDisconnectAlert(enabled: Boolean) = applySetting("断开提醒") {
        prefs.setDisconnectAlert(enabled)
        if (isLinked()) session.applyDisconnectAlert(enabled)
    }

    fun setDndSetting(mode: String, startMinute: Int, endMinute: Int) = applySetting("勿扰模式") {
        prefs.setDndSetting(mode, startMinute, endMinute)
        if (isLinked()) session.applyDnd(dndModeOf(mode), startMinute, endMinute)
    }

    fun setNightSetting(mode: String, startMinute: Int, endMinute: Int) = applySetting("夜间模式") {
        prefs.setNightSetting(mode, startMinute, endMinute)
        if (isLinked()) session.applyNightMode(nightModeOf(mode), startMinute, endMinute)
    }

    /** 拖拽排序回调：换位 → 落盘 → 下发整套顺序。 */
    fun moveMenuItem(from: Int, to: Int) {
        val next = menuOrder.value.toMutableList().apply { add(to, removeAt(from)) }
        applySetting("菜单顺序") {
            prefs.setMenuOrder(next.map { it.key })
            if (isLinked()) session.applyMenuOrder(next)
        }
    }

    fun moveShortcutItem(from: Int, to: Int) {
        val next = shortcutOrder.value.toMutableList().apply { add(to, removeAt(from)) }
        applySetting("快捷方式") {
            prefs.setShortcutOrder(next.map { it.key })
            if (isLinked()) session.applyShortcutOrder(next)
        }
    }

    fun addMenuItem(item: BandSettings.Item) {
        val current = menuOrder.value
        if (item in current || current.size >= MAX_ITEMS) return
        val next = current + item
        applySetting("菜单顺序") {
            prefs.setMenuOrder(next.map { it.key })
            if (isLinked()) session.applyMenuOrder(next)
        }
    }

    fun addShortcutItem(item: BandSettings.Item) {
        val current = shortcutOrder.value
        if (item in current || current.size >= MAX_ITEMS) return
        val next = current + item
        applySetting("快捷方式") {
            prefs.setShortcutOrder(next.map { it.key })
            if (isLinked()) session.applyShortcutOrder(next)
        }
    }

    fun removeMenuItem(item: BandSettings.Item) {
        if (item !in menuOrder.value) return
        val next = menuOrder.value - item
        applySetting("菜单顺序") {
            prefs.setMenuOrder(next.map { it.key })
            if (isLinked()) session.applyMenuOrder(next)
        }
    }

    fun removeShortcutItem(item: BandSettings.Item) {
        if (item !in shortcutOrder.value) return
        val next = shortcutOrder.value - item
        applySetting("快捷方式") {
            prefs.setShortcutOrder(next.map { it.key })
            if (isLinked()) session.applyShortcutOrder(next)
        }
    }

    fun setRemindOnConnect(enabled: Boolean) {
        viewModelScope.launch { prefs.setRemindOnConnect(enabled) }
    }

    fun setRemindLowBattery(enabled: Boolean, thresholdPct: Int) {
        viewModelScope.launch { prefs.setRemindLowBattery(enabled, thresholdPct) }
    }

    fun setRemindFullyCharged(enabled: Boolean) {
        viewModelScope.launch { prefs.setRemindFullyCharged(enabled) }
    }

    // ---- 手环设置的内部工具 ----

    private fun isLinked(): Boolean = session.authenticated.value

    private fun dndModeOf(raw: String): BandSettings.DndMode = when (raw) {
        "scheduled" -> BandSettings.DndMode.SCHEDULED
        "automatic" -> BandSettings.DndMode.AUTOMATIC
        else -> BandSettings.DndMode.OFF
    }

    private fun nightModeOf(raw: String): BandSettings.NightMode = when (raw) {
        "scheduled" -> BandSettings.NightMode.SCHEDULED
        "sunset" -> BandSettings.NightMode.SUNSET
        else -> BandSettings.NightMode.OFF
    }

    private fun itemsOf(keys: List<String>?, defaults: List<BandSettings.Item>): List<BandSettings.Item> =
        keys?.mapNotNull { BandSettings.Item.fromKey(it) }?.takeIf { it.isNotEmpty() } ?: defaults

    /**
     * 「存偏好 → 连着就当场下发」的统一包装。
     * 没连手环不算失败 —— 偏好已落盘，下次连接 BandService 会整套推过去。
     */
    private fun applySetting(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val linked = isLinked()
            try {
                block()
                _settingsStatus.value = if (linked) {
                    "$label 已下发到手环"
                } else {
                    "$label 已保存，连接手环后自动应用"
                }
            } catch (e: Exception) {
                Log.w(TAG, "$label 下发失败", e)
                _settingsStatus.value = "$label 已保存，但下发失败：${e.message}"
            }
        }
    }

    override fun onCleared() {
        // 会话是进程级的（BandSessionProvider），这里绝不能替它断开 ——
        // Activity 被回收不等于用户要断连，通知栏还指着它显示状态。
        linking?.cancel()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 返回 null 表示前置条件都满足。顺序：先看权限、再看适配器、最后看蓝牙开关。 */
    private fun preflight(): DeviceError? {
        val app = getApplication<Application>()

        if (!checkBluetoothPermission(app)) {
            return DeviceError(
                title = "缺少蓝牙权限",
                detail = "Android 12 及以上要连手环，必须有 BLUETOOTH_CONNECT 这个运行时权限。",
                hint = "点下面的按钮，在系统弹窗里允许「附近的设备」。",
                canGrantPermission = true,
            )
        }

        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            return DeviceError(
                title = "这台设备没有蓝牙适配器",
                detail = "系统报告 BLUETOOTH_SERVICE 不可用。",
                hint = "换一台手机，或检查是不是在模拟器里跑。",
            )
        }
        if (!adapter.isEnabled) {
            return DeviceError(
                title = "蓝牙没开",
                detail = "蓝牙适配器处于关闭状态，连不上手环。",
                hint = "下拉通知栏打开蓝牙，然后点重试。",
            )
        }
        return null
    }

    private fun notPairedError(): DeviceError = DeviceError(
        title = "还没配对手环",
        detail = "本地没存设备 MAC 和 AuthKey，不知道要连哪一台。",
        hint = "点下面的「去配对」填进 MAC 与 AuthKey —— 密钥可以用仓库里的 just fetch 取。",
    )

    /** 把底层报的原因翻译成给用户看的话。 */
    private fun linkError(): DeviceError {
        val reason = (connectionState.value as? ConnectionState.Failed)?.reason
            ?: "认证没有走完 —— 手环没回应，或者 AuthKey 不匹配。"
        return DeviceError(
            title = "连接手环失败",
            detail = reason,
            hint = "① 手环是不是还连在「小米运动健康」上？它同一时间只服务一个 App；" +
                "② 把手环贴近手机；③ 确认 AuthKey 确实是这个 MAC 对应的那一台。",
        )
    }

    private fun checkBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
