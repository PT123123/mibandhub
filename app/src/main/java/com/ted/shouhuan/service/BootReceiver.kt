package com.ted.shouhuan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ted.shouhuan.widget.WidgetRenderer

/**
 * 开机自启：系统启动完成后把前台保活服务拉起来。
 *
 * ACTION_BOOT_COMPLETED 是受保护广播，只有系统发得出来，exported=true 是安全的。
 * 注意：用户在系统设置里「强行停止」过的应用收不到这条广播 —— 那是 Android 的
 * stopped-state 语义，绕不过去，只能等用户下次打开应用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BandService.start(context)
            // 桌面控件也顺手刷一遍 —— 控件的 30 分钟周期更新开机后还要等很久才轮到
            WidgetRenderer.refreshAsync(this, context)
        }
    }
}
