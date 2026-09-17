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

    /** 启动所需的运行时权限（通知 + 蓝牙/FGS），齐了就顺手拉起保活前台服务。
     *  拒绝就静默：保活通知不弹、前台服务不启，界面与其余功能照常可用。 */
    private val startupPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            BandService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensurePermissionsAndStartService()

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

    /**
     * 启动前把运行门槛权限一次要齐，齐了立刻拉起前台保活服务：
     *  - Android 13+ 常驻通知也要运行时权限；不给的话服务照样跑，只是通知收进抽屉。
     *  - Android 14+ 的 connectedDevice 型前台服务必须先授予
     *    FOREGROUND_SERVICE_CONNECTED_DEVICE（与 BLUETOOTH_CONNECT 同属「附近的设备」
     *    权限组，一起要、一次弹窗授权）。缺它直接启动会在 startForeground 抛
     *    SecurityException 崩进程 —— 这正是最近一次更新在 Android 14/15 上崩的原因。
     */
    private fun ensurePermissionsAndStartService() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !BandService.hasConnectedDevicePermission(this)
        ) {
            missing += Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing += Manifest.permission.BLUETOOTH_CONNECT
            }
        }
        if (missing.isEmpty()) {
            BandService.start(this)
        } else {
            startupPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}
