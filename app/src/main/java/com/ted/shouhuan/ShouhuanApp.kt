package com.ted.shouhuan

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ShouhuanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
    }

    companion object {
        /** v2：v1 渠道可能带着系统记住的旧提示设置，换了 ID 才能强制静音。 */
        const val CHANNEL_KEEP_ALIVE = "keep_alive_v2"

        /** v1 渠道 ID，创建 v2 后删掉。 */
        private const val OLD_KEEP_ALIVE_CHANNEL = "keep_alive"
        const val CHANNEL_TEST = "test"
    }
}
