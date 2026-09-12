package com.ted.shouhuan.service

import android.content.Context
import com.ted.shouhuan.proto.BandSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级共享的 [BandSession] 单例。
 *
 * 以前会话住在 HeartRateViewModel 里：切 tab 没问题（VM 挂在 Activity 上），
 * 但系统在后台回收 Activity 时 `onCleared()` 会把连接和正在跑的测量一起杀掉 ——
 * 前台服务保住了进程，却保不住 ViewModel。现在把会话和测量编排都提到
 * 进程级（[HeartMeasureController] 同理），只要进程活着它们就活着，
 * 而「进程活着」正是 BandService（前台保活服务）的职责。
 *
 * 作用域也是进程级的，永不取消：这是「一条长连接」的宿主。
 */
object BandSessionProvider {

    @Volatile
    private var session: BandSession? = null

    fun get(context: Context): BandSession = session ?: synchronized(this) {
        session ?: BandSession(
            context.applicationContext,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ).also { session = it }
    }
}
