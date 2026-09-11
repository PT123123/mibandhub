package com.ted.shouhuan.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 无界面调试入口（**仅 debug 构建**）。用 adb 拉起，跑完自动退出。
 *
 * ```
 * adb shell am start -n com.ted.shouhuan/.debug.ActivityLabActivity \
 *     --es mac AA:BB:CC:DD:EE:FF --es key 0123... --ei days 2
 * adb logcat -s ActivityLab
 * ```
 *
 * `--ei days N`：从几天前开始要（默认 1 天）。
 */
class ActivityLabActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mac = intent.getStringExtra("mac")
        val key = intent.getStringExtra("key")
        val days = intent.getIntExtra("days", 1)

        if (mac.isNullOrBlank() || key.isNullOrBlank()) {
            Log.e(ActivityLab.TAG, "缺少参数；需要 --es mac / --es key")
            finish()
            return
        }
        if (!hasBluetoothPermission()) {
            Log.e(ActivityLab.TAG, "缺少 BLUETOOTH_CONNECT 运行时权限，先在 App 里授权")
            finish()
            return
        }

        lifecycleScope.launch {
            val ok = try {
                ActivityLab.run(applicationContext, mac, key, days)
            } catch (t: Throwable) {
                Log.e(ActivityLab.TAG, "✗ 抛异常：${t.javaClass.simpleName}: ${t.message}", t)
                false
            }
            Log.i(ActivityLab.TAG, "════ 结论 days=$days → ${if (ok) "PASS ✓" else "FAIL ✗"} ════")
            finish()
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
