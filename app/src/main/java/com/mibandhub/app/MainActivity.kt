package com.mibandhub.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mibandhub.app.service.BandService
import com.mibandhub.app.service.NotifyPermissionReminder
import com.mibandhub.app.ui.AppRoot
import com.mibandhub.app.ui.theme.ShouhuanTheme

class MainActivity : ComponentActivity() {

    /** 启动所需的运行时权限（通知 + 蓝牙/FGS），齐了就顺手拉起保活前台服务。
     *  拒绝就静默：保活通知不弹、前台服务不启，界面与其余功能照常可用。 */
    private val startupPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            BandService.start(this)
        }

    /** 桌面控件点进来要落到的 tab；null = 正常启动进首页。
     *  onNewIntent 会更新它，AppRoot 里 LaunchedEffect 监听到就去导航。 */
    private var targetRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensurePermissionsAndStartService()

        val initial = intent.getStringExtra(EXTRA_TARGET_ROUTE)
        setContent {
            ShouhuanTheme {
                AppRoot(initialRoute = initial, externalRoute = targetRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop：应用已在栈顶时点桌面控件走这条，而不是 onCreate。
        intent.getStringExtra(EXTRA_TARGET_ROUTE)?.let { targetRoute = it }
    }

    override fun onResume() {
        super.onResume()
        // 从后台切回也算「打开应用」：让服务再跑一遍自动拉取的判断
        // （服务里带 10 分钟去抖，反复进出不会连番轰炸手环）。
        BandService.notifyAppOpen(this)
        // 从系统设置授权回来也走这里：提醒通知据此撤掉 / 补上。
        NotifyPermissionReminder.sync(this)
    }

    companion object {
        /** 桌面控件 PendingIntent 里的目标 tab 名（AppRoot 的 route，如 "sleep"）。 */
        const val EXTRA_TARGET_ROUTE = "com.mibandhub.app.target_route"

        /**
         * 「接收敏感通知」权限。写成字符串而不是 Manifest.permission 常量：
         * 该常量在部分 compileSdk 上还没有，用字面量保证任何 SDK 都能编译。
         */
        private const val PERMISSION_SENSITIVE_NOTIFICATIONS =
            "android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS"
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
        // Android 15+：通知监听要拿到「敏感通知」权限才能收到来电、验证码这类通知。
        // 缺它不影响普通通知，但用户看到的会是「有些通知偶尔不上手环」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(this, PERMISSION_SENSITIVE_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += PERMISSION_SENSITIVE_NOTIFICATIONS
        }
        if (missing.isEmpty()) {
            BandService.start(this)
        } else {
            startupPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}
