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

        @Volatile
        private var instance: CallBridge? = null

        fun get(context: Context): CallBridge =
            instance ?: synchronized(this) {
                instance ?: CallBridge(context.applicationContext).also { instance = it }
            }
    }

    private enum class Kind { PHONE, THIRD_PARTY }

    private data class ActiveCall(
        val kind: Kind,
        val label: String,
        val notificationKey: String? = null,
        val rejectIntent: PendingIntent? = null,
        val ringing: Boolean = true,
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

    // 静音前保存的系统音量/铃声模式
    private var muted = false
    private var muteJob: Job? = null
    private var savedRingVolume = -1
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
            showAlert(ActiveCall(Kind.PHONE, label, ringing = true))
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
                label = label,
                notificationKey = notificationKey,
                rejectIntent = findRejectIntent(notification),
                ringing = true,
            ),
        )
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
        if (current.kind == Kind.THIRD_PARTY || !phoneRinging) {
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
        val ok = when (call.kind) {
            Kind.PHONE -> if (call.ringing) endPhoneCall() else false
            Kind.THIRD_PARTY -> sendRejectIntent(call.rejectIntent)
        }
        Log.d(TAG, "手环拒接 -> ${call.label}：${if (ok) "成功" else "失败"}")
        clearCall()
    }

    private suspend fun muteActiveCall() {
        if (active == null) {
            Log.d(TAG, "手环静音：当前没有进行中的来电")
            return
        }
        if (!prefs.callMuteEnabled.first()) {
            Log.d(TAG, "手环静音已关闭，忽略")
            return
        }
        muteRinger()
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
        if (previous != null && previous.kind == call.kind &&
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

    private fun clearCall() {
        alertJob?.cancel()
        alertJob = null
        val had = active
        active = null
        restoreAudio()
        if (had != null) {
            scope.launch { session.stopIncomingCall() }
        }
    }

    // ------------------------------------------------------------------
    // 静音 / 恢复
    // ------------------------------------------------------------------

    private fun muteRinger() {
        if (muted) return
        val am = audio ?: return
        savedRingerMode = am.ringerMode
        savedRingVolume = am.getStreamVolume(AudioManager.STREAM_RING)

        runCatching { am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) }
            .onFailure { Log.w(TAG, "静音铃声流失败", it) }
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
        }
        // 不静音媒体流：QQ 接通后可能走媒体/通话流，误静音会让对方声音消失，
        // 等真实机型验证了 QQ 铃声走哪条流再考虑加。

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
        }.onFailure { Log.w(TAG, "恢复手机音量失败", it) }
        savedRingVolume = -1
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
