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
 *   1. 跳过自己；常驻/前台服务通知默认拦下（「转发常驻通知」开关打开才放行）
 *   2. 读用户偏好：总开关、仅锁屏、勿扰、应用白名单、关键词、去重、振动档位
 *   3. 确保手环连着（没连就尝试自动连接）
 *   4. 调用协议层 sendNotification 下发
 *   5. 记入「最近推送」（系统提示、名单拦下、勿扰、常驻等丢弃的也记，
 *      标 forwarded=false 并带上丢弃原因，用户能在列表里看到每条死在哪一关）
 *
 * 线程模型：
 *   onNotificationPosted 在系统 Binder 线程回调，不能阻塞。所有数据操作
 *   （读 DataStore、连蓝牙、发通知）都丢到进程级 scope 里异步执行。
 */
class BandNotificationListener : NotificationListenerService() {

    private val TAG = "BandNotifyListener"

    private companion object {
        /** 同一条丢弃（包名+标题+原因相同）记录进「最近推送」的最小间隔。 */
        const val DROP_RECORD_THROTTLE_MS = 60_000L
    }

    private lateinit var prefs: BandPrefs
    private lateinit var session: BandSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 去重表：key = "包名|标题"，value = 上次转发时间戳（毫秒）。
     * 进程活着就不丢；进程重启后清零，算不了「历史重复」—— 能接受。
     */
    private val dedupeTable = ConcurrentHashMap<String, Long>()

    /**
     * 丢弃记录的节流表：key = "包名|标题|原因"，value = 上次记录时间戳（毫秒）。
     * 常驻通知每几秒更新一次就会回调一次 onNotificationPosted，不节流会把
     * 「最近推送」刷爆 —— 同一条丢弃 60 秒内只记一次。只约束丢弃记录，
     * 不影响正常转发（那边有自己的去重）。
     */
    private val dropRecordAt = ConcurrentHashMap<String, Long>()

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

        // 2. 其余一律交给 handleNotification：常驻/前台服务通知在那里默认拦下并记录原因
        //    （「转发常驻通知」开关打开才放行），系统提示/噪音包记入「最近推送」但不转发，
        //    这样它们都能在最近推送里看到、一键加白/黑名单。

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

        // ---- 提取通知内容（记录和转发都要用，先提出来）----
        val extras = notification?.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            .ifBlank { notification?.tickerText?.toString().orEmpty() }
        val body = extractBody(notification, extras)

        if (title.isBlank() && body.isBlank()) {
            Log.d(TAG, "通知标题和正文都是空的，跳过")
            return
        }

        // ---- 系统提示 / 系统 UI 噪音：记入「最近推送」但不转发 ----
        // 这类通知不是应用发出的（如「哪个 APP 正在使用通知栏」的系统提示），
        // 默认不上手环，但要在最近推送里可见、可一键加白/黑名单。
        if (pkg.isEmpty() || pkg.startsWith("android") || isNoisePackage(pkg)) {
            Log.d(TAG, "系统提示/噪音包，只记录不转发：$pkg")
            recordDropped(pkg, title, body, "系统提示/噪音包")
            return
        }

        // ---- 读偏好（一次性读齐，避免多轮 DataStore 访问）----
        val forwardEnabled = prefs.forwardNotifications.first()
        if (!forwardEnabled) {
            Log.d(TAG, "总开关已关，跳过转发")
            return
        }

        // ---- 常驻/前台服务通知 ----
        // 默认不转发（音乐播放器、下载进度这类常驻卡是噪音）；「转发常驻通知」
        // 打开后继续走白名单等其余过滤。美团骑手位置这类通知此前就死在这一关
        // 且一条记录不留，用户无从排查 —— 拦下时必须记入「最近推送」带原因。
        val flags = notification?.flags ?: 0
        if ((flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0
        ) {
            if (!prefs.forwardOngoing.first()) {
                Log.d(TAG, "常驻/前台服务通知且未开转发开关，跳过：$pkg")
                recordDropped(pkg, title, body, "常驻/前台服务通知")
                return
            }
        }

        // ---- 仅锁屏时转发 ----
        // 开着时，亮屏使用手机期间的通知不打扰（人正在看手机，手环再震一遍是噪音）。
        if (prefs.notifyOnlyLocked.first() && !isDeviceLocked()) {
            Log.d(TAG, "仅锁屏转发已开但手机未锁屏，跳过：$pkg")
            recordDropped(pkg, title, body, "手机未锁屏（仅锁屏转发已开）")
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
                recordDropped(pkg, title, body, "勿扰时段内")
                return
            }
        }

        // ---- 应用名单过滤：白名单 / 黑名单两种模式 ----
        // 白名单模式（默认）：不在名单里 → 不转发（用户没加过的应用不替他决定转发）；
        //                     在名单里但被禁用 → 不转发；在名单里且启用 → 转发。
        // 黑名单模式：名单内且启用 → 不转发，其余应用一律转发。
        // 被拦下的也记入「最近推送」（forwarded=false），方便在列表里发现并加名单。
        val rules = prefs.appRules.first()
        val rule = rules.firstOrNull { it.packageName == pkg }
        if (prefs.appFilterBlacklist.first()) {
            if (rule != null && rule.enabled) {
                Log.d(TAG, "包 $pkg 命中转发黑名单，跳过")
                recordDropped(pkg, title, body, "命中转发黑名单")
                return
            }
        } else {
            if (rule == null) {
                Log.d(TAG, "包 $pkg 不在转发白名单，跳过")
                recordDropped(pkg, title, body, "不在转发白名单")
                return
            }
            if (!rule.enabled) {
                Log.d(TAG, "包 $pkg 已被用户禁用，跳过")
                recordDropped(pkg, title, body, "应用在名单中被禁用")
                return
            }
        }

        // ---- 内容过滤（关键词 + 敏感信息）：命中不整条丢，只把正文藏起来 ----
        // 用户要求「全部都要保留标题」：命中过滤时标题照推、正文不上手环 ——
        // 手环上还是一次提醒，噪音文字 / 敏感内容不落到手腕上。
        // 判定顺序：关键词黑名单 → 关键词白名单（未命中）→ 敏感信息，先命中先定原因。
        var bodyHiddenReason: String? = null

        // 黑名单：命中就只推标题。每条规则可限定生效应用（packages 空 = 全部应用），
        // 所以先按包名筛出「适用于这个应用的」规则再判命中。
        val blacklistHit = prefs.keywordBlacklistRules.first()
            .firstOrNull { it.appliesTo(pkg) && it.hits(title, body) }
        if (blacklistHit != null) {
            Log.d(TAG, "命中关键词黑名单「${blacklistHit.keyword}」：只推标题")
            bodyHiddenReason = "正文已隐藏（命中关键词黑名单：${blacklistHit.keyword}）"
        }

        // 白名单：只要这个应用有生效的白名单规则，未命中就只推标题；
        // 一条白名单规则都没覆盖到它时不受约束（避免把整个应用误伤掉）。
        if (bodyHiddenReason == null) {
            val whitelistForApp = prefs.keywordWhitelistRules.first().filter { it.appliesTo(pkg) }
            if (whitelistForApp.isNotEmpty() && whitelistForApp.none { it.hits(title, body) }) {
                Log.d(TAG, "未命中关键词白名单：只推标题")
                bodyHiddenReason = "正文已隐藏（未命中关键词白名单）"
            }
        }

        // 敏感信息：内置识别（可逐条开关）+ 自定义敏感词。
        if (bodyHiddenReason == null && prefs.sensitiveEnabled.first()) {
            val sensitiveText = "$title\n$body"
            val sensitiveHit = prefs.sensitiveRules.first()
                .firstOrNull { it.enabled && it.appliesTo(pkg) && it.hits(sensitiveText) }
            if (sensitiveHit != null) {
                Log.d(TAG, "命中敏感信息「${sensitiveHit.label}」：只推标题")
                bodyHiddenReason = "正文已隐藏（命中敏感信息：${sensitiveHit.label}）"
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
        val appLabel = resolveAppLabel(pkg)

        // ---- 正文是否要藏起来：应用级「详细内容」开关关了，或者命中了内容过滤 ----
        val appHidesBody = rule != null && !rule.showDetail
        val effectiveBody = if (appHidesBody || bodyHiddenReason != null) "" else body
        // 藏了正文就在「最近推送」里说明原因（已推送的通知也带原因）
        val hideNote = bodyHiddenReason ?: if (appHidesBody) "正文未推送（该应用只推标题）" else ""

        // ---- 振动档位 → 告警类别（手环按类别存的振动模式来震）----
        val alertCategory = Notify.alertCategoryFor(prefs.notifyVibration.first())

        // ---- 确保手环连着 ----
        val session = BandSessionProvider.get(this@BandNotificationListener)
        if (session.connectionState.value != ConnectionState.Authenticated) {
            if (!ensureConnected()) {
                recordDropped(pkg, title, body, "手环未连接，自动连接失败")
                return
            }
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
                // 命中内容过滤时正文照存 —— 「最近推送」要能看出到底哪句话被藏了；
                // 应用级「只推标题」是隐私设置，正文本来就不进日志。
                body = if (bodyHiddenReason != null) body else effectiveBody,
                timeLabel = timeLabel,
                forwarded = sent,
                packageName = pkg,
                dropReason = hideNote,
            ),
        )

        Log.d(TAG, "通知${if (sent) "已转发" else "转发失败"}：$appLabel / ${title.take(40)}" +
            if (hideNote.isBlank()) "" else "（$hideNote）")
    }

    /**
     * 记一条「未转发」的通知，带丢弃原因（"勿扰时段内"、"不在转发白名单"等）。
     * 让它们在「最近推送」里可见、死在哪一关一目了然，并支持一键加白/黑名单。
     */
    private suspend fun recordNotForwarded(pkg: String, title: String, body: String, reason: String) {
        val timeLabel = "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute)
        prefs.recordNotification(
            BandNotification(
                appName = resolveAppLabel(pkg),
                title = title.ifBlank { "(无标题)" },
                body = body,
                timeLabel = timeLabel,
                forwarded = false,
                packageName = pkg,
                dropReason = reason,
            ),
        )
    }

    /**
     * 记一条被丢弃的通知，60 秒内同一条（同包名 + 标题 + 原因）只记一次。
     * 所有丢弃点统一走这里，常驻通知反复更新也不会把「最近推送」刷爆。
     */
    private suspend fun recordDropped(pkg: String, title: String, body: String, reason: String) {
        val key = "$pkg|$title|$reason"
        val now = System.currentTimeMillis()
        val last = dropRecordAt[key]
        if (last != null && (now - last) < DROP_RECORD_THROTTLE_MS) return
        dropRecordAt[key] = now
        recordNotForwarded(pkg, title, body, reason)
    }

    /**
     * 应用显示名：解析不到就用包名；空包名（系统内置通知）给个可读兜底名。
     */
    private fun resolveAppLabel(pkg: String): String {
        if (pkg.isEmpty()) return "系统通知"
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        } catch (_: Exception) {
            pkg
        }
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
