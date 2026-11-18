package com.mibandhub.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import com.mibandhub.app.link.LinkManager
import com.mibandhub.app.service.BandNotificationListener
import com.mibandhub.app.service.NotifyPermissionReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ShouhuanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        NotifyPermissionReminder.markProcessStart()
        // 主从转发：进程一起来就初始化 —— 角色是主机要立刻起接收服务，
        // 是从机要立刻开始监听宣告，不能等用户打开界面才恢复。
        LinkManager.get(this)
        rebindListenerIfNeeded()
    }

    /**
     * 「通知使用权」授权了、系统却没把监听服务绑起来时，请系统重绑一次。
     *
     * 国产 ROM 的常见坑：授权记录是真的，监听服务就是不被创建 —— 症状是从机
     * 一条通知都收不到、也就一条都转发不出去，而界面上看不出哪儿不对（实测
     * 一台从机的 dumpsys "Live notification listeners" 里根本没有我们，只有
     * "Allowed / Has user set"）。系统给了 requestRebind 这个显式请求接口，
     * 用上它就不用靠用户去设置里反复开关。
     */
    private fun rebindListenerIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val component = ComponentName(this, BandNotificationListener::class.java)
        scope.launch {
            // 冷启动时系统可能正在绑，给它一点时间；还没绑上就请求重绑。
            // 两次都失败就别再刷了 —— 反复请求对某些 ROM 是骚扰，剩下交给
            // 通知栏那条「监听服务没被绑定」的提醒引导用户去设置里处理。
            delay(3_000)
            if (NotifyPermissionReminder.isListenerBound()) return@launch
            runCatching { NotificationListenerService.requestRebind(component) }
            delay(5_000)
            if (!NotifyPermissionReminder.isListenerBound()) {
                runCatching { NotificationListenerService.requestRebind(component) }
            }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // 常驻状态通知走 LOW：不该响铃、不该出现在状态栏最上方。
        // 渠道一旦创建，系统就记住它的重要性/声音 —— 早期版本用默认渠道设置建过
        // 同名渠道的话，状态通知随连接刷新时就会响一声。所以换新的渠道 ID，
        // 显式关掉声音/振动/指示灯，再把旧渠道删掉，保证静音从「装上」就成立。
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEP_ALIVE, "手环状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻通知实时显示手环连接、电量、步数与睡眠，同时防止系统清理后台"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            },
        )
        manager.deleteNotificationChannel(OLD_KEEP_ALIVE_CHANNEL)

        // 测试通知走 DEFAULT：它是用来验证「通知能正常弹出来」的，就该有响声和横幅
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TEST, "测试通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "「测试通知」功能发出的通知走这个渠道"
            },
        )

        // 权限提醒走 DEFAULT：缺「通知使用权」时挂一条常驻提醒，点一下跳系统设置。
        // 同一时刻最多一条，sync 靠 ID 覆盖，不会越积越多。
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PERMISSION, "权限提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "缺少「通知使用权」等关键权限时的常驻提醒，点击直达系统设置"
            },
        )
    }

    companion object {
        /** 进程级作用域：监听重绑这种「过一会儿再看结果」的事用它兜着。 */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** v2：v1 渠道可能带着系统记住的旧提示设置，换了 ID 才能强制静音。 */
        const val CHANNEL_KEEP_ALIVE = "keep_alive_v2"

        /** v1 渠道 ID，创建 v2 后删掉。 */
        private const val OLD_KEEP_ALIVE_CHANNEL = "keep_alive"
        const val CHANNEL_TEST = "test"

        /** 权限提醒渠道：缺「通知使用权」时的常驻通知。 */
        const val CHANNEL_PERMISSION = "permission_reminder"
    }
}
