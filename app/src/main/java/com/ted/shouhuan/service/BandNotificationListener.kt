package com.ted.shouhuan.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知转发的来源：手机上的通知在这里被读到。
 *
 * 目前只是骨架。接入后要做的事：
 *   1. 过滤掉自己、系统噪音、以及用户屏蔽的包
 *   2. 提取标题/正文/应用名，按手环能吃的长度截断
 *   3. 交给协议层推送到手环
 */
class BandNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // TODO: 接入协议层后在此推送
    }
}
