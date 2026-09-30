package com.ted.shouhuan.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.Pairing
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.NavRow
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.StatusDot
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.StepBlue

/**
 * 设备 tab 的子页面路由。目录页和 AppRoot 认同一份字符串。
 */
object DeviceRoutes {
    const val CONNECT = "device-connect"
    const val BATTERY = "device-battery"
    const val SYNC = "device-sync"
    const val BAND = "device-band"
    const val MENU = "device-menu"
    const val SHORTCUT = "device-shortcuts"
    const val REMIND = "device-remind"
    const val WIDGET = "device-widget"
    const val PAIR = "device-pair"

    /** 配对表单页（填 MAC / AuthKey 的那一页）。 */
    const val PAIRING_FORM = "pairing"
}

/**
 * 设备页的目录页。
 *
 * 一屏只剩下「这台手环现在什么状态 + 能点进哪一页」：手环设置、菜单顺序、
 * 快捷方式、手机提醒这些原本铺在同一页的内容，现在都要多点一下才进得去。
 * 改开关、删条目这类动作全部挪进了子页面，目录上滑一下不会再误触。
 */
@Composable
fun DeviceScreen(vm: DeviceViewModel, onNavigate: (String) -> Unit) {
    val mac by vm.mac.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val authKey by vm.authKey.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val autoConnect by vm.autoConnect.collectAsStateWithLifecycle()
    val forwardNotifications by vm.forwardNotifications.collectAsStateWithLifecycle()
    val sleepMonitoring by vm.sleepMonitoring.collectAsStateWithLifecycle()
    val batteryHistory by vm.batteryHistory.collectAsStateWithLifecycle()
    val menuOrder by vm.menuOrder.collectAsStateWithLifecycle()
    val shortcutOrder by vm.shortcutOrder.collectAsStateWithLifecycle()
    val remindOnConnect by vm.remindOnConnect.collectAsStateWithLifecycle()
    val remindLowBattery by vm.remindLowBattery.collectAsStateWithLifecycle()
    val remindFullyCharged by vm.remindFullyCharged.collectAsStateWithLifecycle()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = { granted -> vm.onPermissionResult(granted) },
    )
    val connect = {
        // API < 31 没有 BLUETOOTH_CONNECT 这个运行时权限，hasBluetoothPermission() 恒为 true，
        // 所以走不到 launch 那条分支。
        if (vm.hasBluetoothPermission()) {
            vm.connect()
        } else {
            permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    val remindersOn = listOf(remindOnConnect, remindLowBattery, remindFullyCharged).count { it }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("设备", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        if (!paired) {
            NoticeBanner(
                title = "还没有配对手环",
                tone = NotifyAmber,
                detail = "本地没存设备 MAC 和 AuthKey，心率页和表盘页都连不上手环。",
                hint = "用仓库里的 just fetch 能从官方 App 日志里直接读出密钥。",
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- 设备卡：状态与连接动作留在目录上，设置一律点进去改 ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BandGlyph()
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name ?: "未配对手环",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connected)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            linkLabel(connection),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(6.dp))

            KeyValueRow("MAC 地址", mac ?: "未配置")
            KeyValueRow(
                "配对密钥",
                Pairing.maskAuthKey(authKey),
                valueColor = if (paired) Mint else PulseRed,
            )
            KeyValueRow(
                "电量",
                // 只在真的连上时显示：断着的时候手环没上报，留着上一次的数字会骗人。
                if (connected) battery?.let { "$it%" } ?: "读取中…" else "—",
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    when {
                        !paired -> onNavigate(DeviceRoutes.PAIRING_FORM)
                        connected -> vm.disconnect()
                        else -> connect()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = if (connected) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                },
            ) {
                Text(
                    when {
                        !paired -> "去配对"
                        connected -> "断开连接"
                        else -> "连接手环"
                    },
                )
            }
        }

        // ---- 连接失败：说清出了什么事 + 怎么办 ----
        val current = error
        if (current != null) {
            Spacer(Modifier.height(12.dp))
            NoticeBanner(
                title = current.title,
                tone = PulseRed,
                detail = current.detail,
                hint = current.hint,
                action = if (current.canGrantPermission) {
                    {
                        TextButton(
                            onClick = {
                                permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                            },
                        ) {
                            Text("去授权", color = PulseRed)
                        }
                    }
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "下面每一项都要点进去才改得到 —— 目录上只显示当前状态。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // ---- 连接 ----
        NavRow(
            title = "连接",
            subtitle = "开机自动连接${if (autoConnect) "已开启" else "已关闭"} · " +
                "转发手机通知${if (forwardNotifications) "已开启" else "已关闭"}",
            accent = MaterialTheme.colorScheme.primary,
            onClick = { onNavigate(DeviceRoutes.CONNECT) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 电量记录 ----
        NavRow(
            title = "电量记录",
            subtitle = "每条读数 + 最近 7 天掉电速度，永久保存",
            badge = batteryHistory.lastOrNull()?.let { "最新 ${it.percent}%" } ?: "暂无",
            accent = Mint,
            onClick = { onNavigate(DeviceRoutes.BATTERY) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 数据同步 ----
        NavRow(
            title = "数据同步",
            subtitle = "睡眠监测${if (sleepMonitoring) "已开启" else "已关闭"} · 从手环拉活动与睡眠明细",
            accent = StepBlue,
            onClick = { onNavigate(DeviceRoutes.SYNC) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 手环设置 ----
        NavRow(
            title = "手环设置",
            subtitle = "佩戴手 / 抬腕亮屏 / 勿扰 / 夜间模式，连上后整套下发",
            accent = Mint,
            onClick = { onNavigate(DeviceRoutes.BAND) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 菜单顺序 ----
        NavRow(
            title = "菜单顺序",
            subtitle = "手环上划菜单的显示顺序，长按拖动排序",
            badge = "${menuOrder.size} 项",
            accent = StepBlue,
            onClick = { onNavigate(DeviceRoutes.MENU) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 快捷方式 ----
        NavRow(
            title = "快捷方式",
            subtitle = "表盘左右滑显示的快捷卡片，长按拖动排序",
            badge = "${shortcutOrder.size} 项",
            accent = NotifyAmber,
            onClick = { onNavigate(DeviceRoutes.SHORTCUT) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 手机提醒 ----
        NavRow(
            title = "手机提醒",
            subtitle = "连接 / 低电量 / 充满 —— 手机这边的事转成手环通知",
            badge = "$remindersOn/3 开",
            accent = PulseRed,
            onClick = { onNavigate(DeviceRoutes.REMIND) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 桌面控件 ----
        NavRow(
            title = "桌面控件",
            subtitle = "把电量 / 睡眠 / 心率三个控件钉到桌面",
            accent = Mint,
            onClick = { onNavigate(DeviceRoutes.WIDGET) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 配对 ----
        NavRow(
            title = "配对",
            subtitle = if (paired) {
                "已配对 · ${Pairing.maskAuthKey(authKey)}"
            } else {
                "未配对 · 填入 MAC 与 AuthKey"
            },
            accent = PulseRed,
            onClick = { onNavigate(DeviceRoutes.PAIR) },
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** 连接状态 → 给人看的一句话。 */
internal fun linkLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected,
    is ConnectionState.Authenticated,
    -> "已连接"

    is ConnectionState.Connecting,
    is ConnectionState.Discovering,
    -> "正在连接…"

    is ConnectionState.Failed -> "连接失败"

    ConnectionState.Disconnected -> "未连接"
}

/** 一个简单的手环图标（纯 Compose 画，不引图标资源）。 */
@Composable
private fun BandGlyph() {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 16.dp, height = 24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 8.dp, height = 12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
