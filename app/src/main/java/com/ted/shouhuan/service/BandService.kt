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
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.proto.BandSettings
import com.ted.shouhuan.util.formatDuration
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

/**
 * 前台服务：一条常驻的状态通知 + START_STICKY。
 *
 * 国产 ROM 和各版本 Android 对后台进程下手都很狠 —— 应用退到后台不久就会被
 * 冻结或杀掉，手环的 GATT 长连接随之断掉。把它抬成前台服务是唯一可靠的办法。
 *
 * 这条通知本身也是手环状态的展示位：设备名 + 在线状态做标题，
 * 电量 / 今日步数 / 最近一晚睡眠拼正文，随数据流实时刷新。
 * 数据来自进程级单例（[BandSessionProvider] 的连接状态与电量/步数）和
 * 本地存储（[BandPrefs] 的睡眠历史）—— 服务不负责连接本身，连接还是
 * 由心率/表盘那些流程按需发起；这里只忠实反映它们留下的最新状态。
 */
class BandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { BandPrefs(this) }
    private val session by lazy { BandSessionProvider.get(this) }

    /** 每分钟跳一格：驱动睡眠日期越过零点、电量定时重读这类「没有流变化」的刷新。 */
    private val tick = MutableStateFlow(0)

    /** onStartCommand 会被反复调用（开屏、开机广播……），状态订阅只能挂一份。 */
    private var watching = false

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
        startAsForeground()
        if (!watching) {
            watching = true
            watchState()
        }
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
    // 手环设置整套下发 + 手环提醒
    // ------------------------------------------------------------------

    /** 手环设置快照 —— 从 DataStore 一次读全，推送过程中用同一份，免得中途改出半套。 */
    private data class BandSettingsSnapshot(
        val wearLeft: Boolean,
        val liftWake: Boolean,
        val swipeUnlock: Boolean,
        val disconnectAlert: Boolean,
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
            runCatching { session.sendNotification(BAND_APP_NAME, title, body) }
                .onFailure { Log.w(TAG, "手环提醒发送失败：$title", it) }
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
        val batteryLabel = battery?.let { "电量 $it%" } ?: "电量 --"
        val stepsLabel = steps?.let { "今日 $it 步" } ?: "步数 --"
        val sleepLabel = sleep
            ?.let { "睡眠 ${formatDuration(it.totalMinutes)}（${it.score} 分）" }
            ?: "暂无睡眠记录"

        return NotificationCompat.Builder(this, ShouhuanApp.CHANNEL_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_stat_band)
            .setContentTitle("$deviceName · $stateLabel")
            .setContentText("$batteryLabel · $stepsLabel · $sleepLabel")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$batteryLabel · $stepsLabel · $sleepLabel"),
            )
            .setContentIntent(openAppIntent())
            .addAction(0, "停止运行", stopIntent())
            .setOngoing(true)
            // 同 id 反复 notify 刷新内容，别每次都响一声/弹横幅
            .setOnlyAlertOnce(true)
            .build()
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
        const val NOTIFICATION_ID = 1

        /** 统一的拉起入口：Activity 开屏和开机广播都走这里。 */
        fun start(context: Context) {
            val intent = Intent(context, BandService::class.java)
            context.startForegroundService(intent)
        }
    }
}
