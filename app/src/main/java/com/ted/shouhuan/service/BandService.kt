package com.ted.shouhuan.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ted.shouhuan.MainActivity
import com.ted.shouhuan.R
import com.ted.shouhuan.ShouhuanApp
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.BandNotification
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.proto.BandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 前台服务：一条常驻的状态通知 + START_STICKY。
 *
 * 国产 ROM 和各版本 Android 对后台进程下手都很狠 —— 应用退到后台不久就会被
 * 冻结或杀掉，手环的 GATT 长连接随之断掉。把它抬成前台服务是唯一可靠的办法。
 *
 * 这条通知本身也是手环状态的展示位：设备名 + 在线状态做标题，
 * 电量 / 今日步数 / 最近一晚睡眠拼正文，随数据流实时刷新。
 * 数据来自进程级单例（[BandSessionProvider] 的连接状态与电量/步数）和
 * 本地存储（[BandPrefs] 的睡眠历史）。
 *
 * 服务还承担两个「不用用户操心」的自动化：按偏好自动连接手环（[maybeAutoConnect]），
 * 以及打开 app 时自动拉取活动明细（[maybeAutoSync]，近 7 天，静默执行）。
 */
class BandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { BandPrefs(this) }
    private val session by lazy { BandSessionProvider.get(this) }

    /** 每分钟跳一格：驱动睡眠日期越过零点、电量定时重读这类「没有流变化」的刷新。 */
    private val tick = MutableStateFlow(0)

    /** onStartCommand 会被反复调用（开屏、开机广播……），状态订阅只能挂一份。 */
    private var watching = false

    /** 自动连接进行中 / 上次尝试的时间 —— onStartCommand 反复触发时不叠流程、不刷屏。 */
    private var autoConnecting = false
    private var lastAutoConnectAt = 0L

    /** 上次自动拉取的时间 —— 打开 app 频繁进出时靠它去抖。 */
    private var lastAutoSyncAt = 0L

    /**
     * 「连上就拉」的意图标记：打开 app 时还没连上手环（自动连接在跑），
     * 先把拉取的意图记下，认证通过后由回调接着执行；拉取完成/开跑即清除。
     */
    @Volatile
    private var syncAfterAuth = false

    /** 手环提醒相关的最新配置（广播回调里不能挂起读 DataStore，这里常备一份）。 */
    @Volatile
    private var reminderConfig: ReminderConfig? = null

    /** 提醒的状态锁：低电量提醒发过一次就不再重复，回充/回升后复位。 */
    private var lowBatterySent = false
    private var fullBatterySent = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** 手机电池广播：低电量 / 充满时给手环发提醒。 */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            val cfg = reminderConfig ?: return

            if (cfg.lowBattery) {
                if (!lowBatterySent && !plugged && pct <= cfg.lowBatteryPct) {
                    lowBatterySent = true
                    sendBandReminder("手机电量低", "手机只剩 $pct% 电量，记得充电")
                } else if (lowBatterySent && (plugged || pct > cfg.lowBatteryPct + 5)) {
                    lowBatterySent = false
                }
            }

            if (cfg.fullyCharged) {
                if (!fullBatterySent && plugged && status == BatteryManager.BATTERY_STATUS_FULL) {
                    fullBatterySent = true
                    sendBandReminder("充电完成", "手机已充满电（$pct%）")
                } else if (fullBatterySent && !plugged) {
                    fullBatterySent = false
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // APP_OPEN 是「服务已在跑、通知别动」的轻量触发（Activity onResume 发来）：
        // 再走一遍 startAsForeground 会把常驻通知重置成占位文案，StateFlow 不变更
        // 就不重发，得干等下一个分钟 tick 才恢复。冷启动的第一次 start 不带 action，
        // 负责把前台通知立起来；onCreate 先 start、onResume 后 APP_OPEN，顺序天然安全。
        if (intent?.action != ACTION_APP_OPEN) {
            startAsForeground()
        }
        if (!watching) {
            watching = true
            watchState()
        }
        // 每次被拉起（开应用、开机）都试一把自动连接 —— 打开应用就该看到
        // 状态栏自己在刷新，而不是等用户去设备页点「连接手环」。
        maybeAutoConnect()
        // 打开应用也顺手把数据拉下来：连着就直接拉，还没连上就等这次连接完成。
        maybeAutoSync()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 先用占位文案立刻上屏（FGS 启动有时限，不能等 DataStore），
     * 然后交给 [watchState] 在几百毫秒内替换成真实状态。
     */
    private fun startAsForeground() {
        val notification = buildNotification(
            name = null,
            state = session.connectionState.value,
            battery = session.battery.value,
            steps = session.steps.value,
            sleep = null,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // targetSdk 34+ 必须把 manifest 里声明的类型再传一次，否则直接抛异常
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 订阅状态流刷新通知；顺手做每分钟一次的电量重读。 */
    private fun watchState() {
        scope.launch {
            while (isActive) {
                delay(60_000)
                // 手环连着时重读一次电量，通知栏的数字才不会停在几小时前
                if (session.connectionState.value == ConnectionState.Authenticated) {
                    runCatching { session.refreshBattery() }
                }
                // 没连上就顺手再试一次自动连接 —— 手环回到范围内 / 蓝牙重开后的自愈
                maybeAutoConnect()
                tick.value += 1
            }
        }

        // 手环提醒配置 —— 收敛成一个不可变快照，广播回调直接读
        scope.launch {
            combine(
                prefs.remindOnConnect,
                prefs.remindLowBattery,
                prefs.remindLowBatteryPct,
                prefs.remindFullyCharged,
            ) { onConnect, lowBattery, lowPct, fullyCharged ->
                ReminderConfig(onConnect, lowBattery, lowPct, fullyCharged)
            }.collect { reminderConfig = it }
        }

        // 认证通过（含断线重连）后：整套下发手环设置，再按需发连接提醒。
        // 只认「假 → 真」的跳变，避免重复推送。
        var lastAuthenticated = false
        scope.launch {
            session.authenticated.collect { auth ->
                val was = lastAuthenticated
                lastAuthenticated = auth
                if (auth && !was) {
                    // 手环刚认证完还在收尾（读电量、订阅步数），缓一拍再推
                    delay(1_500)
                    pushBandSettings()
                    if (reminderConfig?.onConnect == true) {
                        sendBandReminder("已连接", "$BAND_APP_NAME 已连上手环")
                    }
                    // 打开 app 时还没连上的场景：这次连接是替「自动拉取」连的，
                    // 认证一过就把数据拉下来。
                    if (syncAfterAuth) {
                        syncAfterAuth = false
                        startAutoSync()
                    }
                }
            }
        }

        // 六路数据嵌两层 combine：内层打包成对，外层再合并 ticker
        val connection = combine(
            session.connectionState,
            session.battery,
            session.steps,
        ) { state, battery, steps -> Triple(state, battery, steps) }
        val context = combine(
            prefs.name,
            prefs.sleepHistory,
        ) { name, sleep -> name to sleep.firstOrNull() }

        scope.launch {
            combine(connection, context, tick) { (state, battery, steps), (name, sleep), _ ->
                buildNotification(name, state, battery, steps, sleep)
            }.collect { notification ->
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    // ------------------------------------------------------------------
    // 自动连接
    // ------------------------------------------------------------------

    /**
     * 按偏好自动连手环。满足全部条件才动手：
     * 开了「自动连接」、配对信息齐、当前没连（也没在连）、不是用户自己断开的、
     * 心率测量没在跑（共用一条 GATT，测量优先）。
     */
    private fun maybeAutoConnect() {
        if (autoConnecting) return
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAt < AUTO_CONNECT_MIN_INTERVAL_MS) return
        val state = session.connectionState.value
        if (state is ConnectionState.Authenticated ||
            state is ConnectionState.Connected ||
            state is ConnectionState.Connecting ||
            state is ConnectionState.Discovering
        ) {
            return
        }
        if (session.userDisconnected) return
        if (HeartMeasureController.get(application).busy) return

        lastAutoConnectAt = now
        autoConnecting = true
        scope.launch {
            try {
                val enabled = prefs.autoConnect.first()
                if (!enabled) return@launch
                val mac = prefs.mac.first()
                val key = prefs.authKey.first()
                if (mac.isNullOrBlank() || key.isNullOrBlank()) {
                    Log.d(TAG, "自动连接跳过：还没配对手环")
                    return@launch
                }
                Log.d(TAG, "自动连接：尝试连 $mac")
                val ok = withTimeoutOrNull(AUTO_CONNECT_TIMEOUT_MS) {
                    session.connectAndAuthenticate(mac, key)
                } ?: false
                Log.d(TAG, if (ok) "自动连接成功" else "自动连接未成功（等下一轮重试或手动连接）")
            } catch (e: Exception) {
                Log.w(TAG, "自动连接异常", e)
            } finally {
                autoConnecting = false
            }
        }
    }

    // ------------------------------------------------------------------
    // 自动拉取活动数据（打开 app 即拉，不等用户去设备页手点）
    // ------------------------------------------------------------------

    /**
     * 打开 app 时的自动拉取判断：连着就直接拉；还没连上但自动连接正在跑，
     * 记下意图等认证回调接手；两边条件都不满足（用户自己断了连接、自动连接
     * 被关掉）就什么都不做 —— 自动拉取从不替用户决定「要连手环」。
     */
    private fun maybeAutoSync() {
        if (session.connectionState.value == ConnectionState.Authenticated) {
            startAutoSync()
        } else if (autoConnecting) {
            syncAfterAuth = true
        }
    }

    /**
     * 实际发起一次自动拉取：近 [AUTO_SYNC_DAYS] 天活动明细 → 统一落库
     * （[ActivityDataImport]）。静默执行 —— 成败都只进日志，不打扰界面；
     * 拉取窗口只给 7 天（实测约 3 秒），比起十年全量更适合每次开 app 都跑。
     *
     * 让路规则：同步已在跑（手动同步或上一轮自动拉取）、心率测量进行中，
     * 都直接跳过；重复触发靠 [lastAutoSyncAt] 的最小间隔去抖。
     */
    private fun startAutoSync() {
        if (session.syncBusy) return
        if (HeartMeasureController.get(application).busy) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncAt < AUTO_SYNC_MIN_INTERVAL_MS) return
        lastAutoSyncAt = now
        scope.launch {
            try {
                // 睡眠监测开关关掉时不做自动拉取（设备页的手动同步照常可用）。
                if (prefs.sleepMonitoring.firstOrNull() == false) {
                    Log.d(TAG, "睡眠监测已关闭，跳过自动拉取")
                    return@launch
                }
                val mac = prefs.mac.first()
                val key = prefs.authKey.first()
                if (mac.isNullOrBlank() || key.isNullOrBlank()) return@launch
                Log.d(TAG, "自动拉取：近 $AUTO_SYNC_DAYS 天活动明细")
                val result = session.syncActivity(sinceDays = AUTO_SYNC_DAYS)
                ActivityDataImport.import(this@BandService, prefs, result.nights, result.samples)
                Log.d(
                    TAG,
                    "自动拉取完成：${result.nights.size} 夜 / ${result.sampleMinutes} 分钟样本",
                )
            } catch (e: Exception) {
                Log.w(TAG, "自动拉取失败（下次打开 app 或手动同步再试）", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // 手环设置整套下发 + 手环提醒
    // ------------------------------------------------------------------

    /** 手环设置快照 —— 从 DataStore 一次读全，推送过程中用同一份，免得中途改出半套。 */
    private data class BandSettingsSnapshot(
        val wearLeft: Boolean,
        val liftWake: Boolean,
        val swipeUnlock: Boolean,
        val disconnectAlert: Boolean,
        val autoHeartRate: Boolean,
        val dndMode: String,
        val dndStart: Int,
        val dndEnd: Int,
        val nightMode: String,
        val nightStart: Int,
        val nightEnd: Int,
        val menuOrder: List<String>?,
        val shortcutOrder: List<String>?,
    )

    private data class ReminderConfig(
        val onConnect: Boolean,
        val lowBattery: Boolean,
        val lowBatteryPct: Int,
        val fullyCharged: Boolean,
    )

    /** 连接成功后把手环设置整套推过去。单项失败不中断 —— 尽量把剩下的推完。 */
    private suspend fun pushBandSettings() {
        val s = try {
            readSettingsSnapshot()
        } catch (e: Exception) {
            Log.w(TAG, "读取手环设置失败，跳过下发", e)
            return
        }
        runCatching {
            session.applyWearLocation(s.wearLeft)
            session.applyDisplayOnLiftWrist(s.liftWake)
            session.applySwipeUnlock(s.swipeUnlock)
            session.applyDisconnectAlert(s.disconnectAlert)
            session.applyAutoHeartRate(s.autoHeartRate)
            session.applyDnd(dndModeOf(s.dndMode), s.dndStart, s.dndEnd)
            session.applyNightMode(nightModeOf(s.nightMode), s.nightStart, s.nightEnd)
            session.applyMenuOrder(itemsOf(s.menuOrder, BandSettings.Item.DEFAULT_MENU))
            session.applyShortcutOrder(itemsOf(s.shortcutOrder, BandSettings.Item.DEFAULT_SHORTCUTS))
        }.onFailure { Log.w(TAG, "手环设置下发中断", it) }
    }

    private suspend fun readSettingsSnapshot(): BandSettingsSnapshot {
        return BandSettingsSnapshot(
            wearLeft = prefs.wearLeft.first(),
            liftWake = prefs.liftWake.first(),
            swipeUnlock = prefs.swipeUnlock.first(),
            disconnectAlert = prefs.disconnectAlert.first(),
            autoHeartRate = prefs.autoHeartRate.first(),
            dndMode = prefs.dndMode.first(),
            dndStart = prefs.dndStartMinute.first(),
            dndEnd = prefs.dndEndMinute.first(),
            nightMode = prefs.nightMode.first(),
            nightStart = prefs.nightStartMinute.first(),
            nightEnd = prefs.nightEndMinute.first(),
            menuOrder = prefs.menuOrder.firstOrNull(),
            shortcutOrder = prefs.shortcutOrder.firstOrNull(),
        )
    }

    private fun itemsOf(
        keys: List<String>?,
        defaults: List<BandSettings.Item>,
    ): List<BandSettings.Item> = keys
        ?.mapNotNull { BandSettings.Item.fromKey(it) }
        ?.takeIf { it.isNotEmpty() }
        ?: defaults

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

    /** 给手环发一条提醒（未连接时静默放弃 —— 手环不在，发了也白发）。 */
    private fun sendBandReminder(title: String, body: String) {
        if (session.connectionState.value != ConnectionState.Authenticated) {
            Log.d(TAG, "手环未连接，跳过提醒：$title")
            return
        }
        scope.launch {
            val sent = runCatching { session.sendNotification(BAND_APP_NAME, title, body) }
                .onFailure { Log.w(TAG, "手环提醒发送失败：$title", it) }
                .getOrDefault(false)
            if (sent) {
                val now = java.time.LocalTime.now()
                prefs.recordNotification(
                    BandNotification(
                        appName = BAND_APP_NAME,
                        title = title,
                        body = body,
                        timeLabel = "%02d:%02d".format(now.hour, now.minute),
                        forwarded = true,
                    ),
                )
                Log.d(TAG, "手环提醒已发送：$title")
            }
        }
    }

    private fun buildNotification(
        name: String?,
        state: ConnectionState,
        battery: Int?,
        steps: Int?,
        sleep: SleepNightRecord?,
    ): Notification {
        val deviceName = name?.takeIf { it.isNotBlank() } ?: "手环管家"
        val stateLabel = when (state) {
            // Authenticated = 认证过的长连接，才是真正「在线」
            ConnectionState.Authenticated -> "已连接"
            ConnectionState.Connected -> "连接中"
            ConnectionState.Connecting, ConnectionState.Discovering -> "连接中"
            ConnectionState.Disconnected -> "未连接"
            is ConnectionState.Failed -> "未连接"
        }
        // 精简正文：「电87 睡7小时12分 走6234步」。
        // 拿不到的那段直接略过（断连时电量/步数留着旧值，只有从未读过才是 null），
        // 三段全空就给个占位符，正文不至于空白。
        val summary = listOfNotNull(
            battery?.let { "电$it" },
            sleep?.let { "睡${compactDuration(it.totalMinutes)}" },
            steps?.let { "走${it}步" },
        ).joinToString(" ").ifEmpty { "—" }

        return NotificationCompat.Builder(this, ShouhuanApp.CHANNEL_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_stat_band)
            .setContentTitle("$deviceName · $stateLabel")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openAppIntent())
            .addAction(0, "停止运行", stopIntent())
            .setOngoing(true)
            // 同 id 反复 notify 刷新内容，别每次都响一声/弹横幅
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 「442」→「7小时22分」，通知栏精简正文用 —— 无空格，整小时不带零头。 */
    private fun compactDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h <= 0 -> "${m}分钟"
            m == 0 -> "${h}小时"
            else -> "${h}小时${m}分"
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, BandService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "BandService"
        private const val BAND_APP_NAME = "手环管家"
        const val ACTION_STOP = "com.ted.shouhuan.action.STOP_KEEP_ALIVE"

        /** 「app 在前台」的轻量触发：只跑自动连接/自动拉取的判断，不动常驻通知。 */
        const val ACTION_APP_OPEN = "com.ted.shouhuan.action.APP_OPEN"
        const val NOTIFICATION_ID = 1

        /** 连接 + 认证的总兜底（底层每一步自己还有 15 秒超时）。 */
        private const val AUTO_CONNECT_TIMEOUT_MS = 35_000L

        /** 两次自动连接尝试的最小间隔：onStartCommand 反复触发时不至于连番轰炸。 */
        private const val AUTO_CONNECT_MIN_INTERVAL_MS = 10_000L

        /** 两次自动拉取的最小间隔：频繁进出 app 也不反复折腾手环。 */
        private const val AUTO_SYNC_MIN_INTERVAL_MS = 10 * 60_000L

        /** 自动拉取的窗口：覆盖最近的睡眠夜，实测约 3 秒传完；全量走设备页手动同步。 */
        private const val AUTO_SYNC_DAYS = 7

        /** 统一的拉起入口：Activity 开屏和开机广播都走这里。 */
        fun start(context: Context) {
            val intent = Intent(context, BandService::class.java)
            context.startForegroundService(intent)
        }

        /** app 已到前台（Activity onResume）。服务已在前台时不会重置常驻通知。 */
        fun notifyAppOpen(context: Context) {
            val intent = Intent(context, BandService::class.java).setAction(ACTION_APP_OPEN)
            context.startForegroundService(intent)
        }
    }
}
