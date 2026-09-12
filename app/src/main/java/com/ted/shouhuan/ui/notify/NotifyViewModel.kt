package com.ted.shouhuan.ui.notify

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.AppRule
import com.ted.shouhuan.data.BandNotification
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.DemoData
import com.ted.shouhuan.service.BandSessionProvider
import com.ted.shouhuan.service.HeartMeasureController
import com.ted.shouhuan.util.minuteOfDayToClock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        prefs.keywordBlacklist.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val keywords: StateFlow<List<String>> =
        prefs.keywords.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val dedupeEnabled: StateFlow<Boolean> =
        prefs.dedupeEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dedupeSeconds: StateFlow<Int> =
        prefs.dedupeSeconds.stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    val showAppName: StateFlow<Boolean> =
        prefs.notifyShowAppName.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val includeBody: StateFlow<Boolean> =
        prefs.notifyIncludeBody.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val vibration: StateFlow<String> =
        prefs.notifyVibration.stateIn(viewModelScope, SharingStarted.Eagerly, "standard")

    val appRules: StateFlow<List<AppRule>> =
        prefs.appRules.stateIn(viewModelScope, SharingStarted.Eagerly, DemoData.appRules())

    /**
     * 最近推送。通知读取服务（NotificationListener）接通后这里换成真实捕获；
     * 现在放演示条目 + 发到手环的测试通知。
     */
    private val _recent = MutableStateFlow(DemoData.notifications())
    val recent: StateFlow<List<BandNotification>> = _recent.asStateFlow()

    /** 测试通知的进行状态（连接 → 发送 → 结果）。 */
    private val _testSend = MutableStateFlow<TestSendState>(TestSendState.Idle)
    val testSend: StateFlow<TestSendState> = _testSend.asStateFlow()

    fun setForward(enabled: Boolean) = launch { prefs.setForwardNotifications(enabled) }

    fun setDnd(enabled: Boolean) = launch { prefs.setDndEnabled(enabled) }

    fun setDndWindow(startMinute: Int, endMinute: Int) =
        launch { prefs.setDndWindow(startMinute, endMinute) }

    fun setKeywordMode(blacklist: Boolean) = launch { prefs.setKeywordBlacklist(blacklist) }

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

    fun setNotifyContent(showAppName: Boolean, includeBody: Boolean) =
        launch { prefs.setNotifyContent(showAppName, includeBody) }

    fun setVibration(pattern: String) = launch { prefs.setNotifyVibration(pattern) }

    fun setAppEnabled(packageName: String, enabled: Boolean) = launch {
        val current = prefs.appRules.first().ifEmpty { DemoData.appRules() }
        prefs.setAppRules(
            current.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it },
        )
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
    private fun rememberTestInRecent(forwarded: Boolean) {
        val now = LocalDateTime.ofInstant(java.time.Instant.now(), ZoneId.systemDefault())
        _recent.value = listOf(
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
        ) + _recent.value
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
