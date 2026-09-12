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
        // 名字叫「手环状态」而不是「保活」—— 这条通知现在展示的是手环信息本身
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEP_ALIVE, "手环状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻通知实时显示手环连接、电量、步数与睡眠，同时防止系统清理后台"
                setShowBadge(false)
            },
        )

        // 测试通知走 DEFAULT：它是用来验证「通知能正常弹出来」的，就该有响声和横幅
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TEST, "测试通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "「测试通知」功能发出的通知走这个渠道"
            },
        )
    }

    companion object {
        const val CHANNEL_KEEP_ALIVE = "keep_alive"
        const val CHANNEL_TEST = "test"
    }
}
