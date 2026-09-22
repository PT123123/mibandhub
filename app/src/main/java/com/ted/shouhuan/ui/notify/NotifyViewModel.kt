package com.ted.shouhuan.ui.notify

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.AppRule
import com.ted.shouhuan.data.BandNotification
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.DemoData
import com.ted.shouhuan.data.InstalledApp
import com.ted.shouhuan.service.BandNotificationListener
import com.ted.shouhuan.service.BandSessionProvider
import com.ted.shouhuan.service.HeartMeasureController
import com.ted.shouhuan.util.minuteOfDayToClock
import java.text.Collator
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 测试通知发到哪一步了。任何一步都可能失败，失败要给出「为什么 + 怎么办」。 */
sealed interface TestSendState {
    data object Idle : TestSendState

    /** 正在连接/认证手环。 */
    data object Connecting : TestSendState

    /** 已连接，正在往 chunked 通道写通知。 */
    data object Sending : TestSendState

    data object Success : TestSendState

    data class Failure(
        val title: String,
        val detail: String,
        val hint: String? = null,
    ) : TestSendState
}

/**
 * 通知页的状态：所有设置都以本地存储为唯一来源，改开关 = 落盘，
 * 重启后原样还在。转发协议接通时读的也是这一份配置。
 */
class NotifyViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** 连接 + 认证的总兜底（与心率测量同值：底层每步还有 15 秒超时）。 */
        const val CONNECT_TIMEOUT_MS = 35_000L
    }

    private val prefs = BandPrefs(app)
    private val session = BandSessionProvider.get(app)

    val forwardEnabled: StateFlow<Boolean> =
        prefs.forwardNotifications.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val dndEnabled: StateFlow<Boolean> =
        prefs.dndEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val dndStart: StateFlow<Int> = prefs.dndStart.stateIn(viewModelScope, SharingStarted.Eagerly, 22 * 60)
    val dndEnd: StateFlow<Int> = prefs.dndEnd.stateIn(viewModelScope, SharingStarted.Eagerly, 7 * 60 + 30)

    val keywordBlacklist: StateFlow<Boolean> =
        prefs.keywordBlacklist.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val keywords: StateFlow<List<String>> =
        prefs.keywords.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 应用名单模式：true = 黑名单（名单内不转发），false = 白名单（名单内才转发）。 */
    val appFilterBlacklist: StateFlow<Boolean> =
        prefs.appFilterBlacklist.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dedupeEnabled: StateFlow<Boolean> =
        prefs.dedupeEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dedupeSeconds: StateFlow<Int> =
        prefs.dedupeSeconds.stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    val onlyLocked: StateFlow<Boolean> =
        prefs.notifyOnlyLocked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val vibration: StateFlow<String> =
        prefs.notifyVibration.stateIn(viewModelScope, SharingStarted.Eagerly, "standard")

    val appRules: StateFlow<List<AppRule>> =
        prefs.appRules.stateIn(viewModelScope, SharingStarted.Eagerly, DemoData.appRules())

    /**
     * 手机上能选的转发应用（「添加应用」列表）。
     *
     * 不在构造时读 —— 读一遍要遍历包管理器，只有用户真打开选择器时才值得花这份钱。
     * 读完缓在内存，同一个进程里不重复读。
     */
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private var appsLoading = false

    /** 读一遍手机上的应用：只取「能启动的」（有桌面图标的那种），自己排除掉。 */
    fun loadInstalledApps(force: Boolean = false) {
        if (appsLoading) return
        if (!force && _installedApps.value.isNotEmpty()) return
        appsLoading = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val list = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0)
                    .mapNotNull { it.activityInfo?.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != app.packageName }
                    .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                    .sortedWith(compareBy(Collator.getInstance(Locale.CHINA)) { it.label })
            }
            _installedApps.value = list
            appsLoading = false
        }
    }

    /**
     * 最近推送：真实推到手环的通知记录（持久化在 [BandPrefs]）。除了测试通知，
     * 手环提醒（低电量/充满/连接）也会记进来。无记录时为空 —— 不放演示条目。
     */
    val recent: StateFlow<List<BandNotification>> =
        prefs.recentNotifications.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 测试通知的进行状态（连接 → 发送 → 结果）。 */
    private val _testSend = MutableStateFlow<TestSendState>(TestSendState.Idle)
    val testSend: StateFlow<TestSendState> = _testSend.asStateFlow()

    /** 通知监听权限是否已授予（读 Settings.Secure 的 enabled_notification_listeners）。 */
    private val _notifyPermission = MutableStateFlow(checkNotifyPermission(getApplication()))
    val notifyPermission: StateFlow<Boolean> = _notifyPermission.asStateFlow()

    /** 从系统设置页返回后调一次，刷新权限状态。 */
    fun refreshNotifyPermission() {
        _notifyPermission.value = checkNotifyPermission(getApplication())
    }

    /** 生成跳转到系统通知监听设置页的 Intent（不同 ROM 入口不同，这里用通用入口 + 兜底）。 */
    fun notifyListenerSettingsIntent(): Intent {
        val app = getApplication<Application>()
        val general = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (general.resolveActivity(app.packageManager) != null) {
            general
        } else {
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun checkNotifyPermission(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull() ?: return false
        if (raw.isBlank()) return false
        val our = ComponentName(context, BandNotificationListener::class.java).flattenToString()
        return raw.split(':').any { it.trim() == our }
    }

    fun setForward(enabled: Boolean) = launch { prefs.setForwardNotifications(enabled) }

    fun setDnd(enabled: Boolean) = launch { prefs.setDndEnabled(enabled) }

    fun setDndWindow(startMinute: Int, endMinute: Int) =
        launch { prefs.setDndWindow(startMinute, endMinute) }

    fun setKeywordMode(blacklist: Boolean) = launch { prefs.setKeywordBlacklist(blacklist) }

    /** 切应用名单模式（白名单 / 黑名单）。名单内容不变，只是语义反过来。 */
    fun setAppFilterMode(blacklist: Boolean) = launch { prefs.setAppFilterBlacklist(blacklist) }

    fun addKeyword(keyword: String) = launch {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@launch
        val current = prefs.keywords.first()
        if (current.none { it.equals(trimmed, ignoreCase = true) }) {
            prefs.setKeywords(current + trimmed)
        }
    }

    fun removeKeyword(keyword: String) = launch {
        prefs.setKeywords(prefs.keywords.first().filterNot { it == keyword })
    }

    fun setDedupe(enabled: Boolean, seconds: Int) = launch { prefs.setDedupe(enabled, seconds) }

    fun setOnlyLocked(enabled: Boolean) = launch { prefs.setNotifyOnlyLocked(enabled) }

    fun setVibration(pattern: String) = launch { prefs.setNotifyVibration(pattern) }

    fun setAppEnabled(packageName: String, enabled: Boolean) = launch {
        prefs.setAppRules(
            prefs.appRules.first().map {
                if (it.packageName == packageName) it.copy(enabled = enabled) else it
            },
        )
    }

    /** 单个应用的「详细内容」开关：关掉后该应用的通知只推标题，正文不上手环。 */
    fun setAppShowDetail(packageName: String, showDetail: Boolean) = launch {
        prefs.setAppRules(
            prefs.appRules.first().map {
                if (it.packageName == packageName) it.copy(showDetail = showDetail) else it
            },
        )
    }

    /** 把手机上的某个应用加进白名单（加进来默认允许转发）。 */
    fun addAppRule(packageName: String, appName: String) = launch {
        val current = prefs.appRules.first()
        if (current.any { it.packageName == packageName }) return@launch
        prefs.setAppRules(current + AppRule(packageName, appName, enabled = true))
    }

    /** 从白名单里移除某个应用 —— 不再关心它的通知，也不占列表位置。 */
    fun removeAppRule(packageName: String) = launch {
        prefs.setAppRules(prefs.appRules.first().filterNot { it.packageName == packageName })
    }

    /**
     * 「最近推送」条目对应的包名：新记录自带 packageName；老记录只有应用名，
     * 按已加载的安装应用列表按名反查（查不到返回 null，界面就不显示快捷按钮）。
     */
    fun resolvePackageName(item: BandNotification): String? {
        if (item.packageName.isNotBlank()) return item.packageName
        if (item.appName.isBlank()) return null
        return installedApps.value.firstOrNull { it.label == item.appName }?.packageName
    }

    /** 勿扰时段展示成 "22:00 – 07:30"。 */
    fun dndWindowLabel(startMinute: Int, endMinute: Int): String =
        "${minuteOfDayToClock(startMinute)} – ${minuteOfDayToClock(endMinute)}"

    /** 蓝牙运行时权限给没给（Android 12 以下恒为 true）。 */
    fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 权限被拒：把失败原因摆到界面上，不让用户干等。 */
    fun onBluetoothPermissionDenied() {
        _testSend.value = TestSendState.Failure(
            title = "没有蓝牙权限",
            detail = "发通知到手环要先连上它 —— 你拒绝了「附近的设备」权限。",
            hint = "到「系统设置 → 应用 → 手环管家 → 权限」里打开，或重新点一次允许。",
        )
    }

    /**
     * 发一条测试通知到手环。
     *
     * 流程：前置检查（配对 / 权限 / 蓝牙 / 没在测心率）→ 需要时连接认证
     * （复用进程级共享会话，发完保持连接不断开）→ chunked 通道分块写入。
     *
     * 成功 = 所有分块都被手环接受；手环屏幕应立刻弹出这条通知并按它的设置振动。
     */
    fun sendTestNotification() {
        val current = _testSend.value
        if (current == TestSendState.Connecting || current == TestSendState.Sending) return
        viewModelScope.launch { runTestSend() }
    }

    private suspend fun runTestSend() {
        val app = getApplication<Application>()

        // ---- 前置检查，每一步失败都给可操作的原因 ----
        val mac = prefs.mac.first()
        val key = prefs.authKey.first()
        if (mac.isNullOrBlank() || key.isNullOrBlank()) {
            _testSend.value = TestSendState.Failure(
                title = "还没配对手环",
                detail = "本地没有设备 MAC 和 AuthKey，不知道要连哪一台。",
                hint = "先到「设备」页完成配对。",
            )
            return
        }
        if (!hasBluetoothPermission()) {
            _testSend.value = TestSendState.Failure(
                title = "缺少蓝牙权限",
                detail = "Android 12 及以上连接手环需要 BLUETOOTH_CONNECT 权限。",
                hint = "重新点一次「发送测试通知」，在系统弹窗里允许「附近的设备」。",
            )
            return
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _testSend.value = TestSendState.Failure(
                title = "蓝牙没开",
                detail = "蓝牙适配器不可用或处于关闭状态。",
                hint = "下拉通知栏打开蓝牙后重试。",
            )
            return
        }
        if (HeartMeasureController.get(app).busy) {
            _testSend.value = TestSendState.Failure(
                title = "正在测心率",
                detail = "测试通知和心率测量共用同一条手环连接，先等测量结束。",
                hint = "回心率页看一下，测量跑完再发。",
            )
            return
        }

        // ---- 连接认证（会话已就绪就跳过）----
        if (!session.authenticated.value) {
            _testSend.value = TestSendState.Connecting
            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                session.connectAndAuthenticate(mac, key)
            } ?: false
            if (!ok) {
                _testSend.value = TestSendState.Failure(
                    title = "连接手环失败",
                    detail = (session.connectionState.value as? ConnectionState.Failed)?.reason
                        ?: "认证没有走完 —— 手环没回应，或者 AuthKey 不匹配。",
                    hint = "① 手环是不是还连在「小米运动健康」上？② 把手环贴近手机再试。",
                )
                return
            }
        }

        // ---- 发送 ----
        _testSend.value = TestSendState.Sending
        val now = LocalDateTime.ofInstant(java.time.Instant.now(), ZoneId.systemDefault())
        val sent = session.sendNotification(
            appName = "手环管家",
            title = "测试通知",
            body = "如果你在手环上看到这条，说明通知通道是通的。发送于 " +
                "%02d:%02d".format(now.hour, now.minute),
        )
        if (sent) {
            _testSend.value = TestSendState.Success
        } else {
            _testSend.value = TestSendState.Failure(
                title = "手环没有接受通知",
                detail = "chunked 通道（00000020）有分块写入失败。协议日志（logcat -s BandSession）里有具体是第几包。",
                hint = "确认手环在附近且已连接（心率页能看到连接状态），然后重试。",
            )
        }
        rememberTestInRecent(forwarded = sent)
    }

    /** 测试结果也记进「最近推送」，成功/失败一目了然。 */
    private suspend fun rememberTestInRecent(forwarded: Boolean) {
        val now = LocalDateTime.ofInstant(java.time.Instant.now(), ZoneId.systemDefault())
        prefs.recordNotification(
            BandNotification(
                appName = "手环管家",
                title = "测试通知",
                body = if (forwarded) {
                    "已发到手环。这是一条测试通知，用于验证通知通道。"
                } else {
                    "发送失败（写入被拒）。这是一条测试通知。"
                },
                timeLabel = "%02d:%02d".format(now.hour, now.minute),
                forwarded = forwarded,
            ),
        )
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
