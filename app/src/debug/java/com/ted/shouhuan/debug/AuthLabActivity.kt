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
 * adb shell am start -n com.ted.shouhuan/.debug.AuthLabActivity \
 *     --es variant cb --es mac AA:BB:CC:DD:EE:FF --es key 0123...
 * adb logcat -s AuthLab
 * ```
 *
 * 不带 `variant` 时会打出所有可选组合。
 */
class AuthLabActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val variantId = intent.getStringExtra("variant")
        if (variantId == null) {
            Log.i(AuthLab.TAG, "可选组合：${AuthLab.variantIds()}")
            AuthLab.VARIANTS.forEach { Log.i(AuthLab.TAG, "  ${it.id}  ${it.note}") }
            finish()
            return
        }

        val variant = AuthLab.find(variantId)
        val isReal = variantId == AuthLab.REAL
        if (variant == null && !isReal) {
            Log.e(AuthLab.TAG, "未知 variant=$variantId；可选：${AuthLab.variantIds()}")
            finish()
            return
        }

        val mac = intent.getStringExtra("mac")
        val key = intent.getStringExtra("key")
        if (mac.isNullOrBlank() || key.isNullOrBlank()) {
            Log.e(AuthLab.TAG, "缺少 --es mac / --es key")
            finish()
            return
        }

        if (!hasBluetoothPermission()) {
            Log.e(AuthLab.TAG, "缺少 BLUETOOTH_CONNECT 运行时权限，先在 App 里授权")
            finish()
            return
        }

        lifecycleScope.launch {
            val ok = try {
                if (isReal) {
                    // 跑生产路径：BandSession 连接 → 认证 → 实时心率
                    AuthLab.runReal(applicationContext, mac, key)
                } else {
                    AuthLab.run(applicationContext, mac, key, variant!!)
                }
            } catch (t: Throwable) {
                Log.e(AuthLab.TAG, "✗ 抛异常：${t.javaClass.simpleName}: ${t.message}", t)
                false
            }
            Log.i(AuthLab.TAG, "════ 结论 variant=$variantId → ${if (ok) "PASS ✓" else "FAIL ✗"} ════")
            finish()
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
