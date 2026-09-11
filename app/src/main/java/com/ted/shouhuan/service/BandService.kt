package com.ted.shouhuan.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 与手环保持长连接的前台服务。
 *
 * 目前只是骨架 —— 等 BLE 层接进来后，这里负责 startForeground（连接期间不能被杀）、
 * 托管连接、以及在系统回收后按 START_STICKY 拉起。
 */
class BandService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
