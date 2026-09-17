package com.ted.shouhuan.service

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.AppRule
import com.ted.shouhuan.data.BandNotification
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.proto.BandSession
import com.ted.shouhuan.proto.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知转发的来源：手机上的通知在这里被读到，过滤后推到手环。
 *
 * 流程：
 *   1. 跳过自己、系统噪音（常驻通知/前台服务通知）
 *   2. 读用户偏好：总开关、勿扰、应用白名单、关键词、去重、振动档位
 *   3. 确保手环连着（没连就尝试自动连接）
 *   4. 调用协议层 sendNotification 下发
 *   5. 记入「最近推送」
 *
 * 线程模型：
 *   onNotificationPosted 在系统 Binder 线程回调，不能阻塞。所有数据操作
 *   （读 DataStore、连蓝牙、发通知）都丢到进程级 scope 里异步执行。
 */
class BandNotificationListener : NotificationListenerService() {

    private val TAG = "BandNotifyListener"

    private lateinit var prefs: BandPrefs
    private lateinit var session: BandSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 去重表：key = "包名|标题"，value = 上次转发时间戳（毫秒）。
     * 进程活着就不丢；进程重启后清零，算不了「历史重复」—— 能接受。
     */
    private val dedupeTable = ConcurrentHashMap<String, Long>()

    /** 正在跑的连接尝试：同一个时刻只发一波，后到的通知等它收尾。 */
    @Volatile
    private var connectingForNotify = false

    override fun onCreate() {
        super.onCreate()
        prefs = BandPrefs(this)
        session = BandSessionProvider.get(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkg = sbn.packageName
        val app = applicationContext

        // 1. 过滤自己 —— 我们自己的前台服务通知、测试通知都别转发
        if (pkg == app.packageName) {
            Log.d(TAG, "跳过自己的通知：$pkg")
            return
        }

        // 2. 过滤系统噪音：常驻通知、前台服务通知、正在进行的事件
        val notification = sbn.notification
        val flags = notification?.flags ?: 0
        if ((flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0
        ) {
            Log.d(TAG, "跳过常驻/前台服务通知：$pkg")
            return
        }

        // 3. 跳过系统 UI 包名（MIUI/HyperOS 自己的通知一般是装饰性的）
        if (isNoisePackage(pkg)) {
            Log.d(TAG, "跳过系统噪音包：$pkg")
            return
        }

        scope.launch {
            try {
                handleNotification(sbn)
            } catch (e: Exception) {
                Log.w(TAG, "转发通知时出错", e)
            }
        }
    }

    /**
     * 一条通知的完整处理链路（在 scope 里跑，可以挂起）。
     */
    private suspend fun handleNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val notification = sbn.notification

        // ---- 读偏好（一次性读齐，避免多轮 DataStore 访问）----
        val forwardEnabled = prefs.forwardNotifications.first()
        if (!forwardEnabled) {
            Log.d(TAG, "总开关已关，跳过转发")
            return
        }

        // ---- 仅锁屏时转发 ----
        // 开着时，亮屏使用手机期间的通知不打扰（人正在看手机，手环再震一遍是噪音）。
        if (prefs.notifyOnlyLocked.first() && !isDeviceLocked()) {
            Log.d(TAG, "仅锁屏转发已开但手机未锁屏，跳过：$pkg")
            return
        }

        // ---- 勿扰时段 ----
        val dndEnabled = prefs.dndEnabled.first()
        if (dndEnabled) {
            val dndStart = prefs.dndStart.first()
            val dndEnd = prefs.dndEnd.first()
            val nowMinute = LocalTime.now().toSecondOfDay() / 60
            if (inDndWindow(nowMinute, dndStart, dndEnd)) {
                Log.d(TAG, "勿扰时段内，跳过转发")
                return
            }
        }

        // ---- 应用白名单：默认拒绝，只有「在名单里且用户明确允许」才转发 ----
        // 不在名单里 → 不转发（用户没加过的应用不替他决定转发）
        // 在名单里但被禁用 → 不转发
        // 在名单里且启用 → 转发
        val rules = prefs.appRules.first()
        val rule = rules.firstOrNull { it.packageName == pkg }
        if (rule == null) {
            Log.d(TAG, "包 $pkg 不在转发白名单，跳过")
            return
        }
        if (!rule.enabled) {
            Log.d(TAG, "包 $pkg 已被用户禁用，跳过")
            return
        }

        // ---- 提取通知内容 ----
        val extras = notification?.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            .ifBlank { notification?.tickerText?.toString().orEmpty() }
        val body = extractBody(notification, extras)

        if (title.isBlank() && body.isBlank()) {
            Log.d(TAG, "通知标题和正文都是空的，跳过")
            return
        }

        // ---- 关键词过滤 ----
        val keywordList = prefs.keywords.first()
        if (keywordList.isNotEmpty()) {
            val blacklist = prefs.keywordBlacklist.first()
            val hit = keywordList.any { kw ->
                title.contains(kw, ignoreCase = true) || body.contains(kw, ignoreCase = true)
            }
            if (blacklist && hit) {
                Log.d(TAG, "命中关键词黑名单，跳过")
                return
            }
            if (!blacklist && !hit) {
                Log.d(TAG, "未命中关键词白名单，跳过")
                return
            }
        }

        // ---- 去重 ----
        val dedupeEnabled = prefs.dedupeEnabled.first()
        if (dedupeEnabled) {
            val dedupeSecs = prefs.dedupeSeconds.first().coerceAtLeast(1)
            val key = "$pkg|$title"
            val now = System.currentTimeMillis()
            val last = dedupeTable[key]
            if (last != null && (now - last) < dedupeSecs * 1000L) {
                Log.d(TAG, "去重：$title 在 ${dedupeSecs}s 内已转发过")
                return
            }
            dedupeTable[key] = now
        }

        // ---- 组装要推的内容（应用名 + 标题 + 正文，全量照发）----
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        } catch (_: Exception) {
            pkg
        }

        // ---- 应用级「详细内容」开关：该应用关掉了就只推标题，正文不上手环 ----
        val effectiveBody = if (rule != null && !rule.showDetail) "" else body

        // ---- 振动档位 → 告警类别（手环按类别存的振动模式来震）----
        val alertCategory = Notify.alertCategoryFor(prefs.notifyVibration.first())

        // ---- 确保手环连着 ----
        val session = BandSessionProvider.get(this@BandNotificationListener)
        if (session.connectionState.value != ConnectionState.Authenticated) {
            if (!ensureConnected()) return
        }

        // ---- 发送 ----
        val sent = runCatching {
            session.sendNotification(appLabel, title.ifBlank { "(无标题)" }, effectiveBody, alertCategory)
        }.onFailure { Log.w(TAG, "发送通知到手环失败", it) }.getOrDefault(false)

        val timeLabel = "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute)
        prefs.recordNotification(
            BandNotification(
                appName = appLabel,
                title = title.ifBlank { "(无标题)" },
                body = effectiveBody,
                timeLabel = timeLabel,
                forwarded = sent,
            ),
        )

        Log.d(TAG, "通知${if (sent) "已转发" else "转发失败"}：$appLabel / ${title.take(40)}" +
            if (rule != null && !rule.showDetail) "（该应用仅标题）" else "")
    }

    /**
     * 确保手环已认证连接。没连就试着连一次 —— 只在有配对信息时动手。
     * 35 秒超时（和手动测试同值），超时或失败都返回 false，通知不丢，下次再来。
     */
    private suspend fun ensureConnected(): Boolean {
        // 已连着就不用再连
        if (session.connectionState.value == ConnectionState.Authenticated) return true
        // 已经在连了（前一个通知触发的），等它 —— 10 秒内没好就放弃
        if (connectingForNotify) {
            delay(10_000)
            return session.connectionState.value == ConnectionState.Authenticated
        }

        val mac = prefs.mac.first()
        val key = prefs.authKey.first()
        if (mac.isNullOrBlank() || key.isNullOrBlank()) {
            Log.d(TAG, "没有配对信息，无法自动连接")
            return false
        }

        connectingForNotify = true
        try {
            Log.d(TAG, "通知触发自动连接：$mac")
            val ok = withTimeoutOrNull(35_000L) {
                session.connectAndAuthenticate(mac, key)
            } ?: false
            Log.d(TAG, "通知触发连接${if (ok) "成功" else "失败"}")
            return ok
        } finally {
            connectingForNotify = false
        }
    }

    // ------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------

    /**
     * 从 Notification 里提取正文。
     * Android 13 以后 Notification.Builder 内部藏了 EXTRA_TEXT_LINES（多行），
     * 比 EXTRA_TEXT 更完整 —— 优先取它。
     */
    private fun extractBody(
        notification: android.app.Notification,
        extras: Bundle,
    ): String {
        val textLines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            return textLines.joinToString("\n") { it.toString() }
        }
        // BigTextStyle：可能藏在 extras 里
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        if (!bigText.isNullOrBlank()) return bigText
        // 普通 EXTRA_TEXT
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) return text
        // InboxStyle
        val inboxLines = extras.getCharSequenceArray("android.inboxTextLines")
        if (!inboxLines.isNullOrEmpty()) {
            return inboxLines.joinToString("\n") { it.toString() }
        }
        return ""
    }

    /**
     * 当前分钟是否落在勿扰窗口内。
     * 支持跨零点（start=22:00, end=07:30 → 22:00–24:00 和 00:00–07:30 都算）。
     */
    private fun inDndWindow(nowMinute: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            nowMinute in start..end
        } else {
            nowMinute >= start || nowMinute <= end
        }
    }

    /**
     * 手机是否处于锁屏状态（Keyguard 挡着：锁屏页 / PIN / 指纹解锁界面都算）。
     * 纯内存查询，开销可以忽略。拿不到 KeyguardManager 时按「未锁屏」处理 ——
     * 宁可少转发，不误转发。
     */
    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    /**
     * 一些系统 UI / 桌面包名的通知大多是装饰性的，不值得转发。
     * 这里只列最常见的几类 —— 其他系统包走 appRules 白名单过滤。
     */
    private fun isNoisePackage(pkg: String): Boolean {
        val noisePrefixes = listOf(
            "com.miui.home",        // MIUI 桌面
            "com.mi.android.globallauncher",
            "com.android.systemui", // SystemUI
        )
        return noisePrefixes.any { pkg.startsWith(it) }
    }

    override fun onListenerConnected() {
        Log.d(TAG, "通知监听服务已连接")
        super.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "通知监听服务已断开")
        super.onListenerDisconnected()
    }
}
