package com.ted.shouhuan.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 无界面调试入口（**仅 debug 构建**）。用 adb 拉起，跑完自动退出。
 *
 * ```
 * adb shell am start -n com.ted.shouhuan/.debug.WatchFaceLabActivity \
 *     --es mac AA:BB:CC:DD:EE:FF --es key 0123... \
 *     --es file /sdcard/Android/data/com.ted.shouhuan/files/data.zip \
 *     --es stage init          # init = 只报元数据；push = 完整下发
 * adb logcat -s WatchFaceLab
 * ```
 *
 * 可选：`--ei type 8`（固件类型，默认 WATCHFACE）、`--ei packet 20`（单次 GATT 写字节数）。
 */
class WatchFaceLabActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mac = intent.getStringExtra("mac")
        val key = intent.getStringExtra("key")
        val path = intent.getStringExtra("file")
        val stage = intent.getStringExtra("stage") ?: "init"
        val type = intent.getIntExtra("type", WatchFaceLab.TYPE_WATCHFACE)
        val packet = intent.getIntExtra("packet", 20)

        if (mac.isNullOrBlank() || key.isNullOrBlank() || path.isNullOrBlank()) {
            Log.e(WatchFaceLab.TAG, "缺少参数；需要 --es mac / --es key / --es file")
            finish()
            return
        }
        if (packet <= 0) {
            Log.e(WatchFaceLab.TAG, "packet 必须为正数，收到 $packet")
            finish()
            return
        }
        if (!hasBluetoothPermission()) {
            Log.e(WatchFaceLab.TAG, "缺少 BLUETOOTH_CONNECT 运行时权限，先在 App 里授权")
            finish()
            return
        }

        lifecycleScope.launch {
            val ok = try {
                WatchFaceLab.run(
                    context = applicationContext,
                    mac = mac,
                    keyHex = key,
                    file = File(path),
                    typeValue = type,
                    packetSize = packet,
                    stage = stage,
                )
            } catch (t: Throwable) {
                Log.e(WatchFaceLab.TAG, "✗ 抛异常：${t.javaClass.simpleName}: ${t.message}", t)
                false
            }
            Log.i(
                WatchFaceLab.TAG,
                "════ 结论 stage=$stage type=0x%02x → %s ════".format(
                    type,
                    if (ok) "PASS ✓" else "FAIL ✗",
                ),
            )
            finish()
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
