package com.ted.shouhuan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ted.shouhuan.service.BandService
import com.ted.shouhuan.ui.AppRoot
import com.ted.shouhuan.ui.theme.ShouhuanTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝就静默：保活通知不弹，服务照样跑 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureNotificationPermission()
        // 前台保活：应用一开就拉起，配合开机广播覆盖冷启动之外的场景
        BandService.start(this)

        setContent {
            ShouhuanTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台切回也算「打开应用」：让服务再跑一遍自动拉取的判断
        // （服务里带 10 分钟去抖，反复进出不会连番轰炸手环）。
        BandService.notifyAppOpen(this)
    }

    /** Android 13+ 常驻通知也要运行时权限；不给的话服务照样跑，只是通知收进抽屉。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
