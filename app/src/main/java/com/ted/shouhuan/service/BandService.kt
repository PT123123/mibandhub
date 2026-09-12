package com.ted.shouhuan.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ted.shouhuan.MainActivity
import com.ted.shouhuan.R
import com.ted.shouhuan.ShouhuanApp
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

    override fun onBind(intent: Intent?): IBinder? = null

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
        const val ACTION_STOP = "com.ted.shouhuan.action.STOP_KEEP_ALIVE"
        const val NOTIFICATION_ID = 1

        /** 统一的拉起入口：Activity 开屏和开机广播都走这里。 */
        fun start(context: Context) {
            val intent = Intent(context, BandService::class.java)
            context.startForegroundService(intent)
        }
    }
}
