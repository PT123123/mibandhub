package com.mibandhub.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mibandhub.app.ble.ConnectionState
import com.mibandhub.app.data.BandPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 手机通话 / QQ、微信等第三方通话 → 手环来电页的桥。
 *
 * 手环侧（Mi Band 4/5）：
 *   - 来电页由标准 ANS 的 New Alert（2a46）驱动，类别 Call；
 *   - 按「拒接」→ 设备事件 0x07，按「静音」→ 0x09（见 Gadgetbridge HuamiDeviceEvent）。
 *
 * 手机侧：
 *   - 系统电话：PhoneStateListener 监听 RINGING；拒接走 TelecomManager.endCall()
 *     （ANSWER_PHONE_CALLS），失败再退回反射 ITelephony。
 *   - 第三方通话：通知监听里识别 CATEGORY_CALL / 「语音通话」「视频通话」等；
 *     拒接优先触发对方通知里自带的「拒绝 / 挂断」按钮。
 *   - 静音：直接静音铃声流（第三方通话连媒体流一起静音），通话结束/超时后恢复。
 *
 * 只在主线程跑：PhoneStateListener 回调、通知监听回调都切到这里的 scope，
 * 串行处理，不会出现两条来电同时抢 active 状态。
 */
class CallBridge private constructor(context: Context) {

    companion object {
        private const val TAG = "CallBridge"

        /** 手环设备事件。 */
        private const val EVENT_CALL_REJECT = 0x07
        private const val EVENT_CALL_IGNORE = 0x09
        private const val EVENT_SILENT_MODE = 0x10

        /** 来电页「静音」后，最多保持这么久再自动恢复（防止漏掉通话结束事件）。 */
        private const val MUTE_AUTO_RESTORE_MS = 90_000L

        /** 可操作通知在手环上最多停留这么久 —— 通知不像通话有明确的结束事件。 */
        private const val NOTIFICATION_ALERT_TIMEOUT_MS = 60_000L

        /** 手环来电页最多等这么久，用来等系统来电通知把联系人名字带上来。 */
        private const val CALLER_NAME_WAIT_MS = 450L

        /** 这些包名的通知即使没标 CATEGORY_CALL，也按第三方通话识别。 */
        private val VOIP_PACKAGES = setOf(
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.tencent.mm",
            "com.tencent.wework",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
        )

        private val CALL_HINTS = listOf(
            "语音通话", "视频通话", "通话邀请", "邀请你", "来电", "语音聊天", "视频聊天",
        )

        private val CALL_NEGATIVE = listOf(
            "未接", "已取消", "已结束", "通话时长", "挂断", "已拒绝",
        )

        /** 这些字样说明通话已经被接起，手环不该继续停在来电页。 */
        private val CALL_ONGOING = listOf("通话中", "正在通话", "已接听", "通话时长")

        private val REJECT_ACTIONS = listOf(
            "拒接", "拒绝", "挂断", "结束通话", "挂断通话", "reject", "decline", "hang up", "hangup",
        )

        /**
         * 可操作通知里允许手环「拒接」键触发的动作。
         *
         * 只认应用自己提供的处理类按钮（标记已读/归档/删除/清除/忽略/完成），
         * 不碰「回复」这种需要 RemoteInput 的动作 —— 没输入内容点了也没用。
         */
        private val NOTIFICATION_ACTIONS = REJECT_ACTIONS + listOf(
            "已读", "标记为已读", "标记已读", "归档", "删除", "清除", "忽略", "完成",
            "mark as read", "archive", "delete", "dismiss", "done",
        )

        @Volatile
        private var instance: CallBridge? = null

        fun get(context: Context): CallBridge =
            instance ?: synchronized(this) {
                instance ?: CallBridge(context.applicationContext).also { instance = it }
            }
    }

    private enum class Kind { PHONE, THIRD_PARTY, NOTIFICATION }

    /** 来电从哪里来：系统电话、第三方通知、QQ 全屏来电（无障碍）、可操作通知。 */
    private enum class Source { PHONE, NOTIFICATION, ACCESSIBILITY, NOTIFICATION_ACTION }

    private data class ActiveCall(
        val kind: Kind,
        val source: Source,
        val label: String,
        val notificationKey: String? = null,
        val rejectIntent: PendingIntent? = null,
        /** 无障碍来源的拒接动作；返回是否点击成功。 */
        val rejectAction: (() -> Boolean)? = null,
        val ringing: Boolean = true,
        /** 第三方通话是否连媒体流一起静音（QQ 铃声常走媒体流）。 */
        val muteMedia: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val prefs = BandPrefs(appContext)
    private val session = BandSessionProvider.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val telecom = appContext.getSystemService(TelecomManager::class.java)

    @Volatile
    private var active: ActiveCall? = null

    /** 系统来电通知里带的联系人名字（PhoneStateListener 本身不提供号码/名字）。 */
    @Volatile
    private var systemCallTitle: String? = null

    @Volatile
    private var phoneRinging = false

    private var phoneListening = false
    private var eventsJob: Job? = null
    private var alertJob: Job? = null

    /** 可操作通知的自动撤页计时。 */
    private var actionTimeoutJob: Job? = null

    // 静音前保存的系统音量/铃声模式
    private var muted = false
    private var muteJob: Job? = null
    private var savedRingVolume = -1
    private var savedMusicVolume = -1
    private var savedRingerMode = -1

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 幂等：服务/通知监听谁先起来都可以调，重复调用只会补上还没注册的那部分。 */
    fun start() {
        if (eventsJob == null) {
            eventsJob = scope.launch {
                session.callEvents.collect { event ->
                    when (event) {
                        EVENT_CALL_REJECT -> rejectActiveCall()
                        EVENT_CALL_IGNORE -> muteActiveCall()
                        EVENT_SILENT_MODE -> Log.d(TAG, "手环上报手机静音状态，忽略")
                    }
                }
            }
        }
        registerPhoneListenerIfAllowed()
    }

    fun stop() {
        unregisterPhoneListener()
        eventsJob?.cancel()
        eventsJob = null
        alertJob?.cancel()
        alertJob = null
        actionTimeoutJob?.cancel()
        actionTimeoutJob = null
        restoreAudio()
    }

    private fun registerPhoneListenerIfAllowed() {
        if (phoneListening) return
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return
        val tm = appContext.getSystemService(TelephonyManager::class.java) ?: return
        @Suppress("DEPRECATION")
        runCatching { tm.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE) }
            .onSuccess {
                phoneListening = true
                Log.d(TAG, "已开始监听手机通话状态")
            }
            .onFailure { Log.w(TAG, "监听通话状态失败", it) }
    }

    private fun unregisterPhoneListener() {
        if (!phoneListening) return
        val tm = appContext.getSystemService(TelephonyManager::class.java)
        @Suppress("DEPRECATION")
        runCatching { tm?.listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
        phoneListening = false
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // 系统电话（PhoneStateListener）
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> onPhoneRinging()
                TelephonyManager.CALL_STATE_OFFHOOK -> onPhoneStoppedRinging()
                TelephonyManager.CALL_STATE_IDLE -> onPhoneStoppedRinging()
            }
        }
    }

    private fun onPhoneRinging() {
        phoneRinging = true
        // 第三方通话正在响铃时，系统电话通常不会同时来；真重叠时以先到的为准。
        if (active?.kind == Kind.THIRD_PARTY) return
        alertJob?.cancel()
        alertJob = scope.launch {
            // 稍等一下，让 InCallUI 的来电通知把联系人名字带上来
            delay(CALLER_NAME_WAIT_MS)
            if (!phoneRinging) return@launch
            val label = systemCallTitle?.takeIf { it.isNotBlank() } ?: "来电"
            showAlert(ActiveCall(Kind.PHONE, Source.PHONE, label, ringing = true))
        }
    }

    private fun onPhoneStoppedRinging() {
        phoneRinging = false
        alertJob?.cancel()
        if (active?.kind == Kind.PHONE) clearCall()
        if (!phoneRinging) systemCallTitle = null
    }

    /** 系统电话拒接：Android 9+ 的 TelecomManager.endCall 优先，失败再试反射。 */
    private suspend fun endPhoneCall(): Boolean {
        if (telecom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val ok = telecom.endCall()
                Log.d(TAG, "TelecomManager.endCall -> $ok")
                if (ok) return true
            } catch (e: SecurityException) {
                Log.w(TAG, "endCall 被拒（缺少 ANSWER_PHONE_CALLS？）", e)
            } catch (e: Exception) {
                Log.w(TAG, "endCall 失败", e)
            }
        }
        val reflected = endCallViaReflection()
        Log.d(TAG, "反射 endCall -> $reflected")
        return reflected
    }

    @Suppress("PrivateApi")
    private fun endCallViaReflection(): Boolean {
        return try {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return false
            val getITelephony = TelephonyManager::class.java
                .getDeclaredMethod("getITelephony")
                .apply { isAccessible = true }
            val service = getITelephony.invoke(tm) ?: return false
            service.javaClass
                .getDeclaredMethod("endCall")
                .apply { isAccessible = true }
                .invoke(service)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "反射结束通话失败（系统限制）", e)
            false
        }
    }

    // ------------------------------------------------------------------
    // 通知入口（由 BandNotificationListener 调用）
    // ------------------------------------------------------------------

    /**
     * 判断一条通知是不是来电，并交给手环。
     *
     * @return true = 已按「来电」处理，调用方不要再当普通消息转发。
     */
    suspend fun consumeNotification(
        packageName: String,
        notificationKey: String,
        title: String,
        body: String,
        notification: Notification,
    ): Boolean {
        val text = "$title\n$body"
        if (isSystemDialerPackage(packageName)) {
            return consumeSystemCallNotification(notificationKey, title, body, notification, text)
        }

        // 不能只看「有没有全屏意图」——闹钟、日历也会用全屏意图。
        // 允许的三类：标了 CATEGORY_CALL；已知通话应用带通话特征；
        // 未知应用但同时有全屏意图和通话字样（覆盖一些没规范打标的 VoIP）。
        val isCallCategory = notification.category == Notification.CATEGORY_CALL
        val isKnownVoip = packageName in VOIP_PACKAGES
        val hasCallHints = CALL_HINTS.any { text.contains(it) }
        val looksLikeCall = isCallCategory ||
            (isKnownVoip && (notification.fullScreenIntent != null || hasRejectAction(notification) || hasCallHints)) ||
            (notification.fullScreenIntent != null && hasCallHints)
        if (!looksLikeCall) return false
        if (CALL_NEGATIVE.any { text.contains(it) }) return false

        if (!prefs.callThirdPartyEnabled.first()) return false
        // 无障碍已经识别到 QQ 全屏来电时，不要再被同一通电话的通知覆盖
        if (active?.source == Source.ACCESSIBILITY) return true

        val appName = resolveAppLabel(packageName)
        val label = buildThirdPartyLabel(appName, title, body)
        val existing = active
        if (existing?.kind == Kind.THIRD_PARTY && existing.notificationKey == notificationKey) {
            // 同一条通知更新：接起后 QQ/微信会继续保留「通话中」通知，这时要撤掉来电页
            if (CALL_ONGOING.any { text.contains(it) }) clearCall()
            return true
        }
        showAlert(
            ActiveCall(
                kind = Kind.THIRD_PARTY,
                source = Source.NOTIFICATION,
                label = label,
                notificationKey = notificationKey,
                rejectIntent = findRejectIntent(notification),
                ringing = true,
            ),
        )
        return true
    }

    /**
     * 可操作通知入口：把指定应用的通知伪装成手环来电页，复用「拒接 / 静音」两个键。
     *
     * 通知卡片本身没有任何回传事件，所以想让手环能按键处理某条通知，只能借来电页。
     *
     * @return true = 已作为可操作通知显示在手环上，调用方不要再发普通通知卡片；
     *         false = 条件不满足（开关 / 应用规则 / 手环连接 / 已有通话），按普通通知继续。
     */
    suspend fun offerActionableNotification(
        packageName: String,
        notificationKey: String,
        appName: String,
        title: String,
        notification: Notification,
    ): Boolean {
        if (!prefs.notifyActionableEnabled.first()) return false
        // 复用的是来电页，来电页总开关关掉时也不该出现
        if (!prefs.callAlertEnabled.first()) return false
        val rule = prefs.appRules.first().firstOrNull { it.packageName == packageName }
        if (rule == null || !rule.actionable) return false

        val current = active
        // 真正的通话优先：已有来电页时，普通通知不抢占
        if (current != null && current.source != Source.NOTIFICATION_ACTION) return false
        if (session.connectionState.value != ConnectionState.Authenticated) return false

        val previous = active
        val label = buildActionableLabel(appName, title)
        if (previous != null && previous.notificationKey == notificationKey &&
            previous.label == label
        ) {
            return true
        }

        val call = ActiveCall(
            kind = Kind.NOTIFICATION,
            source = Source.NOTIFICATION_ACTION,
            label = label,
            notificationKey = notificationKey,
            rejectIntent = findActionIntent(notification),
            ringing = true,
        )
        active = call
        if (!session.sendIncomingCall(label)) {
            // 发送失败：把上一条可操作通知的状态还回去，别把它的按键一起弄丢
            active = previous
            return false
        }
        scheduleActionTimeout(notificationKey)
        Log.d(TAG, "可操作通知 -> $label")
        return true
    }

    private suspend fun consumeSystemCallNotification(
        notificationKey: String,
        title: String,
        body: String,
        notification: Notification,
        text: String,
    ): Boolean {
        val isCall = notification.category == Notification.CATEGORY_CALL ||
            notification.fullScreenIntent != null ||
            hasRejectAction(notification) ||
            CALL_HINTS.any { text.contains(it) }
        if (!isCall) return false
        // 未接来电、通话结束这类不进入来电页，交给普通通知链路
        if (CALL_NEGATIVE.any { text.contains(it) }) return false

        // 已经接起（ColorOS 有时先发通知、PhoneState 后到）
        if (CALL_ONGOING.any { text.contains(it) }) {
            if (active?.kind == Kind.PHONE && !phoneRinging) clearCall()
            return true
        }

        val genericTitles = listOf("来电", "incoming call")
        val caller = title.trim()
            .takeIf { it.isNotBlank() && it.lowercase() !in genericTitles }
            ?: body.trim()
        if (caller.isNotBlank()) systemCallTitle = caller.take(40)

        // 有电话权限：以 PhoneStateListener 的 RINGING 为准，通知只补名字
        if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            if (phoneRinging) {
                val current = active
                if (current != null && current.kind == Kind.PHONE && caller.isNotBlank() &&
                    current.label != caller
                ) {
                    showAlert(current.copy(label = caller, notificationKey = notificationKey))
                }
            } else if (notification.category == Notification.CATEGORY_CALL ||
                notification.fullScreenIntent != null
            ) {
                // 个别 ROM 的 PhoneStateListener 不上报响铃，用 InCallUI 通知兜底。
                // 已经在通话中（例如系统补发的常驻通知）就不要弹出新的来电页。
                val inCall = runCatching { telecom?.isInCall == true }.getOrDefault(false)
                val current = active
                if (!inCall && (current == null || current.kind != Kind.PHONE)) {
                    showAlert(
                        ActiveCall(
                            kind = Kind.PHONE,
                            source = Source.PHONE,
                            label = caller.ifBlank { "来电" },
                            notificationKey = notificationKey,
                            ringing = true,
                        ),
                    )
                }
            }
            return true
        }

        // 没有电话权限时，退化为「系统来电通知也能触发手环来电页」
        val current = active
        if (current != null && current.kind == Kind.PHONE && current.notificationKey == notificationKey) {
            return true
        }
        showAlert(
            ActiveCall(
                kind = Kind.PHONE,
                source = Source.PHONE,
                label = caller.ifBlank { "来电" },
                notificationKey = notificationKey,
                ringing = true,
            ),
        )
        return true
    }

    /** 通知被移除：第三方来电通知消失就结束来电页；系统电话仍以通话状态为准。 */
    fun onNotificationRemoved(notificationKey: String) {
        val current = active ?: return
        if (current.notificationKey != notificationKey) return
        if (current.kind == Kind.THIRD_PARTY || current.source == Source.NOTIFICATION_ACTION ||
            !phoneRinging
        ) {
            clearCall()
        }
    }

    // ------------------------------------------------------------------
    // QQ / 微信全屏来电（无障碍服务调用）
    // ------------------------------------------------------------------

    /**
     * 无障碍服务识别到 QQ 全屏来电页。
     *
     * @param reject 由无障碍服务提供的拒接动作（点击 QQ 界面的「拒绝」）。
     */
    fun notifyAccessibilityCallStarted(label: String, reject: () -> Boolean) {
        scope.launch {
            val current = active
            if (current != null && current.source != Source.NOTIFICATION_ACTION) {
                Log.d(TAG, "已有通话在手环上，忽略新的无障碍来电：$label")
                return@launch
            }
            // 真来电优先：可操作通知页直接让位
            if (current?.source == Source.NOTIFICATION_ACTION) {
                actionTimeoutJob?.cancel()
                actionTimeoutJob = null
                active = null
            }
            if (!prefs.callThirdPartyEnabled.first()) return@launch
            showAlert(
                ActiveCall(
                    kind = Kind.THIRD_PARTY,
                    source = Source.ACCESSIBILITY,
                    label = label,
                    rejectAction = reject,
                    ringing = true,
                    muteMedia = true,
                ),
            )
            Log.d(TAG, "无障碍来电 -> $label")
        }
    }

    /** 来电页内容变化，例如联系人名字后到达。 */
    fun notifyAccessibilityCallUpdated(label: String) {
        scope.launch {
            val current = active ?: return@launch
            if (current.source != Source.ACCESSIBILITY || current.label == label) return@launch
            showAlert(current.copy(label = label))
        }
    }

    /** 来电页关闭：接起、拒接、对方取消，都走这里撤掉手环来电页并恢复音量。 */
    fun notifyAccessibilityCallEnded() {
        if (active?.source == Source.ACCESSIBILITY) {
            Log.d(TAG, "无障碍来电结束")
            clearCall()
        }
    }

    // ------------------------------------------------------------------
    // 手环按键
    // ------------------------------------------------------------------

    private suspend fun rejectActiveCall() {
        val call = active ?: run {
            Log.d(TAG, "手环拒接：当前没有进行中的来电")
            return
        }
        if (!prefs.callRejectEnabled.first()) {
            Log.d(TAG, "手环拒接已关闭，忽略")
            return
        }
        val ok = when (call.source) {
            Source.PHONE -> if (call.ringing) endPhoneCall() else false
            Source.NOTIFICATION -> sendRejectIntent(call.rejectIntent)
            Source.ACCESSIBILITY -> call.rejectAction?.invoke() ?: false
            Source.NOTIFICATION_ACTION -> rejectNotification(call)
        }
        Log.d(TAG, "手环拒接 -> ${call.label}：${if (ok) "成功" else "失败"}")
        clearCall()
    }

    private suspend fun muteActiveCall() {
        val call = active ?: run {
            Log.d(TAG, "手环静音：当前没有进行中的来电")
            return
        }
        if (!prefs.callMuteEnabled.first()) {
            Log.d(TAG, "手环静音已关闭，忽略")
            return
        }
        muteRinger(includeMedia = call.muteMedia)
        // 通知的「静音」= 静音铃声 + 收起手环页；不能走 clearCall() 默认的恢复音量
        if (call.source == Source.NOTIFICATION_ACTION) {
            clearCall(restoreAudio = false)
        }
    }

    /**
     * 可操作通知的「拒接」：优先触发通知自带的处理按钮（已读/归档/删除等），
     * 没有可用按钮或触发失败时，直接把手环对应的通知从手机通知栏清除。
     */
    private fun rejectNotification(call: ActiveCall): Boolean {
        if (sendRejectIntent(call.rejectIntent)) return true
        val key = call.notificationKey ?: return false
        // cancelNotification 是 NotificationListenerService 的实例方法，借监听器实例来清
        return BandNotificationListener.instance?.cancelByKey(key) ?: false
    }

    private fun sendRejectIntent(intent: PendingIntent?): Boolean {
        if (intent == null) {
            Log.d(TAG, "第三方通话通知里没有「拒绝/挂断」按钮，无法自动拒接")
            return false
        }
        return try {
            intent.send()
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "触发第三方拒接按钮失败", e)
            false
        }
    }

    // ------------------------------------------------------------------
    // 手环下发
    // ------------------------------------------------------------------

    private suspend fun showAlert(call: ActiveCall) {
        val previous = active
        if (previous != null && previous.source == Source.NOTIFICATION_ACTION) {
            // 真来电优先：可操作通知页直接让位，发送新来电页本身就是替换
            actionTimeoutJob?.cancel()
            actionTimeoutJob = null
            active = null
        } else if (previous != null && previous.kind == call.kind &&
            previous.label == call.label && previous.notificationKey == call.notificationKey
        ) {
            return
        }
        active = call
        if (!prefs.callAlertEnabled.first()) {
            Log.d(TAG, "来电提醒已关闭，不上手环：${call.label}")
            return
        }
        if (session.connectionState.value != ConnectionState.Authenticated) {
            Log.d(TAG, "手环未连接，跳过来电页：${call.label}")
            return
        }
        session.sendIncomingCall(call.label)
    }

    private fun clearCall(restoreAudio: Boolean = true) {
        alertJob?.cancel()
        alertJob = null
        actionTimeoutJob?.cancel()
        actionTimeoutJob = null
        val had = active
        active = null
        // 通知的「静音」= 先静音铃声再收页，这时不能恢复音量（否则静音立刻被撤掉）
        if (restoreAudio) restoreAudio()
        if (had != null) {
            scope.launch { session.stopIncomingCall() }
        }
    }

    /** 可操作通知最多在手环上停留 [NOTIFICATION_ALERT_TIMEOUT_MS]；同一条通知更新会重置。 */
    private fun scheduleActionTimeout(notificationKey: String) {
        actionTimeoutJob?.cancel()
        actionTimeoutJob = scope.launch {
            delay(NOTIFICATION_ALERT_TIMEOUT_MS)
            val current = active ?: return@launch
            if (current.source == Source.NOTIFICATION_ACTION &&
                current.notificationKey == notificationKey
            ) {
                Log.d(TAG, "可操作通知超时，收起手环页")
                clearCall()
            }
        }
    }

    /**
     * 通知里可以交给手环「拒接」键执行的处理按钮。
     *
     * 需要 RemoteInput 的「回复」不算 —— 没有输入内容点下去只会失败。
     */
    private fun findActionIntent(notification: Notification): PendingIntent? =
        notification.actions.orEmpty().firstOrNull { action ->
            if (!action.remoteInputs.isNullOrEmpty()) return@firstOrNull false
            val label = action.title?.toString().orEmpty().lowercase()
            NOTIFICATION_ACTIONS.any { label.contains(it) }
        }?.actionIntent

    /** 可操作通知在手环上的标题：「应用 · 通知标题」。 */
    private fun buildActionableLabel(appName: String, title: String): String {
        val text = title.trim()
        return if (text.isBlank() || text.equals(appName, ignoreCase = true)) {
            "$appName · 新通知"
        } else {
            "$appName · ${text.take(30)}"
        }
    }

    // ------------------------------------------------------------------
    // 静音 / 恢复
    // ------------------------------------------------------------------

    private fun muteRinger(includeMedia: Boolean) {
        if (muted) return
        val am = audio ?: return
        savedRingerMode = am.ringerMode
        savedRingVolume = am.getStreamVolume(AudioManager.STREAM_RING)
        savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        runCatching { am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) }
            .onFailure { Log.w(TAG, "静音铃声流失败", it) }
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
        }
        // 无障碍能检测到 QQ 来电页关闭（接起/挂断），所以这里可以安全地连媒体流一起静音；
        // 通知来源的第三方通话无法可靠判断接起时机，不静音媒体流。
        if (includeMedia) {
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
                .onFailure { Log.w(TAG, "静音媒体流失败", it) }
        }

        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
                .onFailure {
                    Log.w(TAG, "切换静音模式失败（可能缺少勿扰访问权限），仅静音了铃声流", it)
                }
        }

        muted = true
        muteJob?.cancel()
        muteJob = scope.launch {
            delay(MUTE_AUTO_RESTORE_MS)
            restoreAudio()
        }
        Log.d(TAG, "已静音手机铃声")
    }

    private fun restoreAudio() {
        muteJob?.cancel()
        muteJob = null
        if (!muted) return
        muted = false
        val am = audio ?: return
        runCatching {
            if (savedRingerMode >= 0) am.ringerMode = savedRingerMode
            if (savedRingVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0)
            }
            if (savedMusicVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            }
        }.onFailure { Log.w(TAG, "恢复手机音量失败", it) }
        savedRingVolume = -1
        savedMusicVolume = -1
        savedRingerMode = -1
        Log.d(TAG, "已恢复手机音量")
    }

    // ------------------------------------------------------------------
    // 通知解析辅助
    // ------------------------------------------------------------------

    private fun isSystemDialerPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val defaultDialer = runCatching { telecom?.defaultDialerPackage }.getOrNull()
        if (!defaultDialer.isNullOrBlank() && packageName == defaultDialer) return true
        val p = packageName.lowercase()
        return p.contains("incallui") || p.contains("dialer") ||
            p.endsWith(".phone") || p.contains("telecom")
    }

    private fun hasRejectAction(notification: Notification): Boolean =
        notification.actions.orEmpty().any { action ->
            val actionTitle = action.title?.toString().orEmpty().lowercase()
            REJECT_ACTIONS.any { actionTitle.contains(it) }
        }

    private fun findRejectIntent(notification: Notification): PendingIntent? =
        notification.actions.orEmpty().firstOrNull { action ->
            val actionTitle = action.title?.toString().orEmpty().lowercase()
            REJECT_ACTIONS.any { actionTitle.contains(it) }
        }?.actionIntent

    private fun buildThirdPartyLabel(appName: String, title: String, body: String): String {
        // 有的应用把通知标题直接写成应用名，这时退回正文（通常含联系人名）
        val who = title.trim()
            .takeIf { it.isNotBlank() && !it.equals(appName, ignoreCase = true) }
            ?: body.trim()
        return if (who.isBlank()) {
            "$appName · 通话"
        } else {
            "$appName · ${who.take(24)}"
        }
    }

    private fun resolveAppLabel(packageName: String): String = try {
        appContext.packageManager
            .getApplicationLabel(appContext.packageManager.getApplicationInfo(packageName, 0))
            .toString()
    } catch (_: Exception) {
        packageName
    }
}
