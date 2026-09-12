@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ted.shouhuan.ui.device

import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.HealthConnectSleepSource
import com.ted.shouhuan.data.Pairing
import com.ted.shouhuan.proto.BandSettings
import com.ted.shouhuan.ui.components.DragReorderList
import com.ted.shouhuan.ui.components.FilterChipRow
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.StatusDot
import com.ted.shouhuan.ui.components.SwitchSettingRow
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.util.minuteOfDayToClock
import kotlinx.coroutines.launch

/** 健康连接提供方（Android 13 是独立 App；14+ 内置于系统设置）。 */
private const val HC_PROVIDER_PACKAGE = "com.android.healthconnect.controller"

/** 外部睡眠数据源 App：小米运动健康。 */
private const val MI_FITNESS_PACKAGE = "com.mi.health"

/**
 * 设备页：配对信息 + 手动连接。
 *
 * 这一页的数据全部来自本地存储与真实连接状态 —— 没有任何演示值。
 * 读数拿不到就显示「—」，不编一个好看的数字出来：设备页一旦有个假的电量，
 * 后面用户就没法判断哪个数字是能信的。
 */
@Composable
fun DeviceScreen(vm: DeviceViewModel, onPair: () -> Unit) {
    val mac by vm.mac.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val authKey by vm.authKey.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val sleepSync by vm.sleepSync.collectAsStateWithLifecycle()
    val autoConnect by vm.autoConnect.collectAsStateWithLifecycle()
    val forwardNotifications by vm.forwardNotifications.collectAsStateWithLifecycle()

    // ---- 手环设置 ----
    val wearLeft by vm.wearLeft.collectAsStateWithLifecycle()
    val liftWake by vm.liftWake.collectAsStateWithLifecycle()
    val swipeUnlock by vm.swipeUnlock.collectAsStateWithLifecycle()
    val disconnectAlert by vm.disconnectAlert.collectAsStateWithLifecycle()
    val dndMode by vm.dndMode.collectAsStateWithLifecycle()
    val dndStart by vm.dndStart.collectAsStateWithLifecycle()
    val dndEnd by vm.dndEnd.collectAsStateWithLifecycle()
    val nightMode by vm.nightMode.collectAsStateWithLifecycle()
    val nightStart by vm.nightStart.collectAsStateWithLifecycle()
    val nightEnd by vm.nightEnd.collectAsStateWithLifecycle()
    val menuOrder by vm.menuOrder.collectAsStateWithLifecycle()
    val shortcutOrder by vm.shortcutOrder.collectAsStateWithLifecycle()
    val remindOnConnect by vm.remindOnConnect.collectAsStateWithLifecycle()
    val remindLowBattery by vm.remindLowBattery.collectAsStateWithLifecycle()
    val remindLowBatteryPct by vm.remindLowBatteryPct.collectAsStateWithLifecycle()
    val remindFullyCharged by vm.remindFullyCharged.collectAsStateWithLifecycle()
    val settingsStatus by vm.settingsStatus.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var confirmForget by remember { mutableStateOf(false) }

    // 时间选择弹层：记「哪项设置 + 开始/结束」，弹窗共用一个
    var editingTime by remember { mutableStateOf<TimeEdit?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> vm.onPermissionResult(granted) },
    )
    val connect = {
        // API < 31 没有 BLUETOOTH_CONNECT 这个运行时权限，hasBluetoothPermission() 恒为 true，
        // 所以走不到 launch 那条分支。
        if (vm.hasBluetoothPermission()) vm.connect() else permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    // ---- 健康连接导入：先查可用性，缺权限就拉系统授权框，齐了才真正去读 ----
    val context = LocalContext.current
    val hcSleepPermission = remember { HealthPermission.getReadPermission(SleepSessionRecord::class) }
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (hcSleepPermission in granted) vm.importSleepFromHealthConnect()
    }
    val importFromHealthConnect: () -> Unit = {
        scope.launch {
            when (val availability = vm.healthConnect.availability()) {
                is HealthConnectSleepSource.Availability.Ready -> {
                    val missing = vm.healthConnect.missingPermissions()
                    if (missing.isEmpty()) {
                        vm.importSleepFromHealthConnect()
                    } else {
                        hcPermissionLauncher.launch(missing)
                    }
                }
                is HealthConnectSleepSource.Availability.Unavailable ->
                    Toast.makeText(context, availability.reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openHealthConnect() {
        // Android 14+ 走系统设置里的健康连接页；13 的独立 App 个别 ROM（本机 MIUI）
        // 不响应 androidx.health.ACTION_HEALTH_CONNECT_SETTINGS，逐级兜底到显式组件
        //（本机实测 MigrationActivity 可直接拉起，引导完成后就是它的主页）。
        val settings = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = when {
            settings.resolveActivity(context.packageManager) != null -> settings
            else -> context.packageManager.getLaunchIntentForPackage(HC_PROVIDER_PACKAGE)
                ?: Intent()
                    .setClassName(HC_PROVIDER_PACKAGE, "$HC_PROVIDER_PACKAGE.migration.MigrationActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(target) }
            .onFailure {
                Toast.makeText(context, "打不开健康连接 App（$HC_PROVIDER_PACKAGE）", Toast.LENGTH_LONG).show()
            }
    }

    fun openMiFitness() {
        val intent = context.packageManager.getLaunchIntentForPackage(MI_FITNESS_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "没装小米运动健康（$MI_FITNESS_PACKAGE）", Toast.LENGTH_LONG).show()
        }
    }

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

        // ---- 设备卡 ----
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
                        !paired -> onPair()
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
                                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
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

        // ---- 连接设置 ----
        SectionCard(title = "连接", accent = MaterialTheme.colorScheme.primary) {
            ToggleRow(
                title = "开机自动连接",
                subtitle = "打开应用时自动连上已配对手环",
                checked = autoConnect,
                onCheckedChange = { vm.setAutoConnect(it) },
            )
            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            ToggleRow(
                title = "转发手机通知",
                subtitle = "把手机收到的通知推到手环（需要通知使用权）",
                checked = forwardNotifications,
                onCheckedChange = { vm.setForwardNotifications(it) },
            )
            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            Text(
                "连接会一直保持（通知栏和首页实时显示手环状态），点上面的「断开连接」才断开。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 数据同步：拉活动明细 → 解析睡眠 → 落本地 ----
        SectionCard(title = "数据同步", accent = StepBlue) {
            Text(
                "从手环拉取全部保存的活动与睡眠明细（手环只留最近 15~30 天，同步后即从手环清除），解析出每晚分期写进本地。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.syncSleep() },
                enabled = paired && sleepSync !is SleepSyncPhase.Syncing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (sleepSync is SleepSyncPhase.Syncing) "同步中…" else "同步手环数据")
            }
            Spacer(Modifier.height(8.dp))
            when (val phase = sleepSync) {
                is SleepSyncPhase.Syncing -> {
                    Text(
                        "接收中 ${phase.received} / ${phase.expected} 字节" +
                            if (phase.expected <= 0) "（等待手环应答…）" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = StepBlue,
                    )
                }
                is SleepSyncPhase.Done -> Text(
                    "完成：解析出 ${phase.nights} 晚睡眠（${phase.sampleMinutes} 分钟样本）",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mint,
                )
                is SleepSyncPhase.Failed -> Text(
                    "失败：${phase.message}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseRed,
                )
                SleepSyncPhase.Idle -> Text(
                    "尚未同步",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            // ---- 外部数据源：健康连接（小米运动健康会往里同步）----
            Text(
                "手环数据被清掉/不在身边时，可从「健康连接」补睡眠历史：\n" +
                    "① 首次打开健康连接 App 要先完成它的初始引导；\n" +
                    "② 健康连接 → 应用权限 → 小米运动健康 → 全部允许（写入含睡眠）；\n" +
                    "③ 国内版小米运动健康暂无「同步到健康连接」开关，写入靠它的后台同步，导入为 0 晚通常就是还没写进来。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ::openHealthConnect) {
                    Text("打开健康连接", color = StepBlue)
                }
                TextButton(onClick = ::openMiFitness) {
                    Text("打开小米运动健康", color = StepBlue)
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = importFromHealthConnect,
                enabled = sleepSync !is SleepSyncPhase.Syncing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("从健康连接导入")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 手环设置：连接后整套下发，当场改动当场推 ----
        SectionCard(title = "手环设置", accent = Mint) {
            settingsStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = Mint,
                )
                Spacer(Modifier.height(10.dp))
            }

            Text("佩戴手", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            FilterChipRow(
                options = listOf("左手", "右手"),
                selectedIndex = if (wearLeft) 0 else 1,
                accent = Mint,
                onSelect = { vm.setWearLocation(it == 0) },
            )
            Spacer(Modifier.height(12.dp))
            SwitchSettingRow(
                title = "抬腕亮屏",
                subtitle = "抬手亮屏，放下熄灭",
                checked = liftWake,
                onCheckedChange = { vm.setLiftWake(it) },
                accent = Mint,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            SwitchSettingRow(
                title = "滑动解锁",
                subtitle = "开启后点亮手环要上滑解锁，防误触",
                checked = swipeUnlock,
                onCheckedChange = { vm.setSwipeUnlock(it) },
                accent = Mint,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            SwitchSettingRow(
                title = "断开提醒",
                subtitle = "手环与手机断开蓝牙时，手环自己振动提醒（手环侧功能，断开后才生效）",
                checked = disconnectAlert,
                onCheckedChange = { vm.setDisconnectAlert(it) },
                accent = Mint,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )

            Text("勿扰模式", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            FilterChipRow(
                options = listOf("关闭", "定时", "自动"),
                selectedIndex = dndModeIndex(dndMode),
                accent = Mint,
                onSelect = { index ->
                    val mode = dndModeOfIndex(index)
                    vm.setDndSetting(mode, dndStart, dndEnd)
                },
            )
            if (dndMode == "scheduled") {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickChip(
                        "开始 ${minuteOfDayToClock(dndStart)}",
                        Modifier.weight(1f),
                    ) { editingTime = TimeEdit("dnd", "start") }
                    TimePickChip(
                        "结束 ${minuteOfDayToClock(dndEnd)}",
                        Modifier.weight(1f),
                    ) { editingTime = TimeEdit("dnd", "end") }
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("夜间模式", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            FilterChipRow(
                options = listOf("关闭", "定时", "日落自动"),
                selectedIndex = nightModeIndex(nightMode),
                accent = Mint,
                onSelect = { index ->
                    val mode = nightModeOfIndex(index)
                    vm.setNightSetting(mode, nightStart, nightEnd)
                },
            )
            if (nightMode == "scheduled") {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickChip(
                        "开始 ${minuteOfDayToClock(nightStart)}",
                        Modifier.weight(1f),
                    ) { editingTime = TimeEdit("night", "start") }
                    TimePickChip(
                        "结束 ${minuteOfDayToClock(nightEnd)}",
                        Modifier.weight(1f),
                    ) { editingTime = TimeEdit("night", "end") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "这些是手环本机的设置：连接成功后自动整套下发，改了当场生效；" +
                    "手环上改的不会反向同步回来。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 菜单顺序 / 快捷方式：长按拖拽排序 ----
        SectionCard(title = "菜单顺序", accent = StepBlue) {
            Text(
                "手环上划菜单的显示顺序。长按拖动排序，「表盘」固定在第一位，最多 16 项。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            DragReorderList(
                items = menuOrder,
                onMove = { from, to -> vm.moveMenuItem(from, to) },
            ) { item ->
                ReorderRow(label = item.label, onRemove = { vm.removeMenuItem(item) })
            }
            Spacer(Modifier.height(12.dp))
            AddItemChips(
                all = BandSettings.Item.entries.filter { it !in menuOrder },
                accent = StepBlue,
                onAdd = { vm.addMenuItem(it) },
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "快捷方式", accent = NotifyAmber) {
            Text(
                "表盘界面左右滑显示的快捷卡片。长按拖动排序，点 × 移除。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            DragReorderList(
                items = shortcutOrder,
                onMove = { from, to -> vm.moveShortcutItem(from, to) },
            ) { item ->
                ReorderRow(label = item.label, onRemove = { vm.removeShortcutItem(item) })
            }
            Spacer(Modifier.height(12.dp))
            AddItemChips(
                all = BandSettings.Item.entries.filter { it !in shortcutOrder },
                accent = NotifyAmber,
                onAdd = { vm.addShortcutItem(it) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 手机提醒：手机这边的状态转成手环通知 ----
        SectionCard(title = "手机提醒", accent = PulseRed) {
            Text(
                "手机这边发生的事，转成一条手环通知。手环不在连接范围内时不会补发。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SwitchSettingRow(
                title = "连接提醒",
                subtitle = "手环管家连上手环时，手环上提示一声",
                checked = remindOnConnect,
                onCheckedChange = { vm.setRemindOnConnect(it) },
                accent = PulseRed,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            SwitchSettingRow(
                title = "低电量提醒",
                subtitle = "手机电量掉到阈值以下时提醒（充电中不触发）",
                checked = remindLowBattery,
                onCheckedChange = { vm.setRemindLowBattery(it, remindLowBatteryPct) },
                accent = PulseRed,
            )
            if (remindLowBattery) {
                Spacer(Modifier.height(6.dp))
                val thresholds = listOf(10, 15, 20, 30)
                FilterChipRow(
                    options = thresholds.map { "$it%" },
                    selectedIndex = thresholds.indexOf(remindLowBatteryPct),
                    accent = PulseRed,
                    onSelect = { index -> vm.setRemindLowBattery(true, thresholds[index]) },
                )
            }
            HorizontalDivider(
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            SwitchSettingRow(
                title = "充满提醒",
                subtitle = "手机充满电时在手环上提醒一声",
                checked = remindFullyCharged,
                onCheckedChange = { vm.setRemindFullyCharged(it) },
                accent = PulseRed,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 配对 ----
        SectionCard(title = "配对", accent = PulseRed) {
            Text(
                if (paired) {
                    "换了新手机，或者密钥填错了，都从这里改。密钥绑的是小米账号 + 手环，" +
                        "换机后原样填回来就行，不必重新取。"
                } else {
                    "填入手环的 MAC 与 AuthKey 即可开始使用。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPair,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (paired) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (paired) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                ),
            ) {
                Text(if (paired) "重新配对" else "填 MAC 与 AuthKey")
            }

            if (paired) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { confirmForget = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PulseRed.copy(alpha = 0.14f),
                        contentColor = PulseRed,
                    ),
                ) {
                    Text("忘记此设备")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("忘记这台手环？") },
            text = {
                Text(
                    "会删掉本机存的 MAC 和 AuthKey。手环本身不受影响，测量记录也会保留；" +
                        "下次要用得重新配对。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        scope.launch { vm.forgetDevice() }
                    },
                ) {
                    Text("忘记", color = PulseRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("取消") }
            },
        )
    }

    // ---- 手环设置的时间选择弹窗（勿扰 / 夜间模式共用） ----
    editingTime?.let { edit ->
        val initialMinute = when {
            edit.setting == "dnd" && edit.edge == "start" -> dndStart
            edit.setting == "dnd" -> dndEnd
            edit.edge == "start" -> nightStart
            else -> nightEnd
        }
        val pickerState = rememberTimePickerState(
            initialHour = initialMinute / 60,
            initialMinute = initialMinute % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { editingTime = null },
            title = {
                Text(
                    (if (edit.setting == "dnd") "勿扰" else "夜间模式") +
                        if (edit.edge == "start") "开始时间" else "结束时间",
                )
            },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val next = pickerState.hour * 60 + pickerState.minute
                    if (edit.setting == "dnd") {
                        vm.setDndSetting(
                            "scheduled",
                            if (edit.edge == "start") next else dndStart,
                            if (edit.edge == "start") dndEnd else next,
                        )
                    } else {
                        vm.setNightSetting(
                            "scheduled",
                            if (edit.edge == "start") next else nightStart,
                            if (edit.edge == "start") nightEnd else next,
                        )
                    }
                    editingTime = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTime = null }) { Text("取消") }
            },
        )
    }
}

/** 时间选择弹层的目标：哪项设置（勿扰 / 夜间模式）+ 开始还是结束。 */
private data class TimeEdit(val setting: String, val edge: String)

private fun dndModeIndex(mode: String): Int = when (mode) {
    "scheduled" -> 1
    "automatic" -> 2
    else -> 0
}

private fun dndModeOfIndex(index: Int): String = when (index) {
    1 -> "scheduled"
    2 -> "automatic"
    else -> "off"
}

private fun nightModeIndex(mode: String): Int = when (mode) {
    "scheduled" -> 1
    "sunset" -> 2
    else -> 0
}

private fun nightModeOfIndex(index: Int): String = when (index) {
    1 -> "scheduled"
    2 -> "sunset"
    else -> "off"
}

/** 连接状态 → 给人看的一句话。 */
private fun linkLabel(state: ConnectionState): String = when (state) {
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

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** 时间选择入口的小按钮（勿扰 / 夜间模式的起止时刻）。 */
@Composable
private fun TimePickChip(
    label: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = Mint,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = accent,
        )
    }
}

/**
 * 排序列表的一行：拖动手柄 + 名称 + 移除。
 * 注意保持等高 —— DragReorderList 按第一行实测高度换算拖拽位置。
 */
@Composable
private fun ReorderRow(label: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = "长按拖动排序",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「添加项目」标题 + 横向滚动的候选 chips（点一下加入排序列表）。 */
@Composable
private fun AddItemChips(
    all: List<BandSettings.Item>,
    accent: androidx.compose.ui.graphics.Color,
    onAdd: (BandSettings.Item) -> Unit,
) {
    if (all.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "添加项目",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
    Spacer(Modifier.height(8.dp))
    FilterChipRow(
        options = all.map { it.label },
        selectedIndex = -1, // 候选项没有选中态
        accent = accent,
        onSelect = { index -> onAdd(all[index]) },
    )
}
