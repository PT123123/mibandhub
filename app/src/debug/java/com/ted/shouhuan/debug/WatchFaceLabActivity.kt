package com.ted.shouhuan.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ted.shouhuan.proto.BandSession
import com.ted.shouhuan.proto.WatchFaceOutcome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 无界面调试入口（**仅 debug 构建**）。用 adb 拉起，跑完自动退出。
 *
 * ```
 * adb shell am start -n com.ted.shouhuan/.debug.WatchFaceLabActivity \
 *     --es mac AA:BB:CC:DD:EE:FF --es key 0123... \
 *     --es file /sdcard/Android/data/com.ted.shouhuan/files/data.zip \
 *     --es stage init          # init = 只报元数据；push = 完整下发；real = 走生产代码
 * adb logcat -s WatchFaceLab
 * ```
 *
 * `stage=real` 会调应用真正用的那条路（`BandSession.installWatchFace`），
 * 用来验证「实验台能通 ≠ 应用能通」这件事。
 *
 * 可选：`--ei type 8`（固件类型，默认 WATCHFACE）、`--ei packet 20`（单次 GATT 写字节数）。
 */
class WatchFaceLabActivity : ComponentActivity() {

    private companion object {
        /** `stage=real`：走生产代码那条路（`BandSession` + `installWatchFace`）。 */
        const val STAGE_REAL = "real"
    }

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
                if (stage == STAGE_REAL) {
                    // 走生产代码（BandSession 认证 + installWatchFace）。
                    // 实验台自己拼的字节能通，不代表应用里那条路也能通 ——
                    // 心率那次就是靠这条入口才发现生产路径和实验台不一致的。
                    runProductionPath(applicationContext, mac, key, File(path))
                } else {
                    WatchFaceLab.run(
                        context = applicationContext,
                        mac = mac,
                        keyHex = key,
                        file = File(path),
                        typeValue = type,
                        packetSize = packet,
                        stage = stage,
                    )
                }
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

    /**
     * 跑**生产那条路**：`BandSession` 连接 → 认证 → `installWatchFace`。
     *
     * 实验台自己拼的字节能通，不代表应用里那条路也能通（心率那次就是靠这条
     * 入口才发现两条路的字节其实不一样）。所以专门留一个只走生产代码的 stage。
     */
    private suspend fun runProductionPath(
        context: Context,
        mac: String,
        key: String,
        file: File,
    ): Boolean = coroutineScope {
        val resolved = WatchFaceLab.resolveFile(context, file) ?: run {
            Log.e(WatchFaceLab.TAG, "✗ 读不到文件：${file.absolutePath}")
            return@coroutineScope false
        }
        val bytes = resolved.readBytes()
        Log.i(WatchFaceLab.TAG, "生产路径：${resolved.name}，${bytes.size} 字节")

        val session = BandSession(context, this)
        val logJob = launch {
            session.logs.collect { Log.i(WatchFaceLab.TAG, "  · $it") }
        }
        try {
            if (!session.connectAndAuthenticate(mac, key)) {
                Log.e(WatchFaceLab.TAG, "✗ 连接 / 认证失败")
                return@coroutineScope false
            }
            Log.i(WatchFaceLab.TAG, "✓ 认证通过，开始下发")

            val outcome = session.installWatchFace(bytes) { p ->
                if (p.sentPackets % 2000 == 0 || p.sentPackets == p.totalPackets) {
                    Log.i(
                        WatchFaceLab.TAG,
                        "   进度 ${p.sentPackets}/${p.totalPackets}（${p.percent}%）",
                    )
                }
            }
            when (outcome) {
                WatchFaceOutcome.Success -> {
                    Log.i(WatchFaceLab.TAG, "★ 生产路径结论：PASS —— 手环回「校验通过」")
                    true
                }

                is WatchFaceOutcome.Unconfirmed -> {
                    Log.w(
                        WatchFaceLab.TAG,
                        "◐ 生产路径结论：包送完了但未确认，最后回应 ${outcome.lastResponse}",
                    )
                    false
                }

                is WatchFaceOutcome.Failed -> {
                    Log.e(
                        WatchFaceLab.TAG,
                        "✗ 生产路径结论：${outcome.title} —— ${outcome.detail}",
                    )
                    false
                }
            }
        } finally {
            logJob.cancel()
            session.disconnect()
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
