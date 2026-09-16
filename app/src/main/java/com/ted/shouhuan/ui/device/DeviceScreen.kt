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
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.ted.shouhuan.widget.SleepDetailWidgetProvider
import com.ted.shouhuan.widget.SleepHeartWidgetProvider
import com.ted.shouhuan.widget.SleepWidgetProvider
import com.ted.shouhuan.widget.isMiui
import com.ted.shouhuan.widget.openAppSettings
import com.ted.shouhuan.widget.pinWidgetToHome
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
import com.ted.shouhuan.data.BatterySample
import com.ted.shouhuan.data.HealthConnectSleepSource
import com.ted.shouhuan.data.Pairing
import com.ted.shouhuan.proto.BandSettings
import com.ted.shouhuan.ui.components.CollapsibleSection
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
import com.ted.shouhuan.util.formatDateTime
import com.ted.shouhuan.util.formatDuration
import com.ted.shouhuan.util.minuteOfDayToClock
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 健康连接提供方（Android 13 是独立 App；14+ 内置于系统设置）。 */
private const val HC_PROVIDER_PACKAGE = "com.android.healthconnect.controller"

/** 电量记录卡片最多列几条（全量仍在本地，永久保存）。 */
private const val HISTORY_ROWS = 15

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
    val sleepMonitoring by vm.sleepMonitoring.collectAsStateWithLifecycle()
    val liftWake by vm.liftWake.collectAsStateWithLifecycle()
    val swipeUnlock by vm.swipeUnlock.collectAsStateWithLifecycle()
    val disconnectAlert by vm.disconnectAlert.collectAsStateWithLifecycle()
    val autoHeartRate by vm.autoHeartRate.collectAsStateWithLifecycle()
    val autoHeartRateInterval by vm.autoHeartRateInterval.collectAsStateWithLifecycle()
    val batteryHistory by vm.batteryHistory.collectAsStateWithLifecycle()
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

    // 小米运动健康是否安装：装了就得提醒「睡眠会被它抢先同步清掉」。装没装不随页面存活变，
    // 记住了即可。
    val miHealthInstalled = remember {
        context.packageManager.getLaunchIntentForPackage(MI_FITNESS_PACKAGE) != null
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

        // ---- 桌面控件：主动添加到桌面，绕开长按图标菜单的控件列表缓存 ----
        Spacer(Modifier.height(12.dp))
        WidgetAddCard()

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

        // ---- 电量记录：默认收起 —— 明细只有点开时才排版，最新读数贴在标题上 ----
        CollapsibleSection(
            title = "电量记录",
            accent = Mint,
            badge = batteryHistory.lastOrNull()?.let { "最新 ${it.percent}%" } ?: "暂无",
            initiallyExpanded = false,
        ) {
            val latest = batteryHistory.lastOrNull()
            if (latest == null) {
                Text(
                    "连上手环后，电量每变 1% 记一条时间点。攒上几天，这里就能算出" +
                        "「多久掉 1%、满电能撑多久」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValueRow("最新读数", "${latest.percent}%", valueColor = Mint)
                KeyValueRow("读到时间", formatDateTime(latest.atMillis))
                batteryDrainSummary(batteryHistory)?.let { summary ->
                    Spacer(Modifier.height(2.dp))
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = Mint)
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(Modifier.height(4.dp))
                // 新的在上。回升（充电）的那条显式标出来，免得被当成耗电。
                batteryHistory.takeLast(HISTORY_ROWS).reversed().forEachIndexed { index, sample ->
                    val previous = batteryHistory.getOrNull(batteryHistory.size - index - 2)
                    val charging = previous != null && sample.percent > previous.percent
                    KeyValueRow(
                        formatDateTime(sample.atMillis),
                        if (charging) "${sample.percent}%（充回）" else "${sample.percent}%",
                        valueColor = if (charging) NotifyAmber else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (batteryHistory.size > HISTORY_ROWS) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "上面是最近 $HISTORY_ROWS 条。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "共 ${batteryHistory.size} 条，永久保存、不清理（同一次读数不重复记）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 数据同步：拉活动明细 → 解析睡眠 → 落本地 ----
        SectionCard(title = "数据同步", accent = StepBlue) {
            Text(
                "从手环拉取活动与睡眠明细，解析出每晚分期写进本地（手环只留最近 15~30 天）。" +
                    "同步只读不删 —— 数据留在手环上，重复推送会按日期自动去重。" +
                    "打开 app 时也会自动拉近 7 天，这里的按钮拉手环保留的全部。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (miHealthInstalled) {
                Spacer(Modifier.height(12.dp))
                NoticeBanner(
                    title = "检测到「小米运动健康」已安装",
                    tone = NotifyAmber,
                    detail = "它会在后台抢先同步手环并把睡眠数据清掉 —— 你一觉刚睡完它先拉走了，这里就再也拿不到那晚数据（睡眠会显示 0 晚）。",
                    hint = "用本应用同步睡眠时，关掉小米运动健康的「后台自动同步」/自启动，或者只在需要时才打开它。",
                )
            }
            Spacer(Modifier.height(12.dp))
            SwitchSettingRow(
                title = "睡眠监测",
                subtitle = "打开后，打开应用会自动拉最近几晚睡眠；手环睡眠本身是常开的，关掉只会停掉应用自动拉取，手动同步照常可用。",
                checked = sleepMonitoring,
                onCheckedChange = { vm.setSleepMonitoring(it) },
                accent = StepBlue,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
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

        // ---- 手环设置：连接后整套下发，当场改动当场推。默认收起，要用再展开 ----
        CollapsibleSection(
            title = "手环设置",
            accent = Mint,
            initiallyExpanded = false,
        ) {
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
            SwitchSettingRow(
                title = "自动心率检测",
                subtitle = "官方「检测模式」里的自动心率检测：开着时手环按下面的「检测频率」" +
                    "全天定时探测心率（默认关）。关掉最省手环电，代价是手环不再产生" +
                    "连续心率分钟数据，桌面控件的心率曲线会空。",
                checked = autoHeartRate,
                onCheckedChange = { vm.setAutoHeartRate(it) },
                accent = Mint,
            )
            if (autoHeartRate) {
                Spacer(Modifier.height(6.dp))
                Text("检测频率", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                FilterChipRow(
                    options = vm.autoHeartRateIntervals.map { "$it 分钟" },
                    selectedIndex = vm.autoHeartRateIntervals.indexOf(autoHeartRateInterval)
                        .coerceAtLeast(0),
                    accent = Mint,
                    onSelect = { vm.setAutoHeartRateInterval(vm.autoHeartRateIntervals[it]) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "档位越小越费手环电：1 分钟接近连续测量，30 分钟是官方标称 15 天续航的" +
                        "测试条件（默认）。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

        // ---- 菜单顺序 / 快捷方式：长按拖拽排序。列表长且不常动，默认收起 ----
        CollapsibleSection(
            title = "菜单顺序",
            accent = StepBlue,
            initiallyExpanded = false,
        ) {
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

        CollapsibleSection(
            title = "快捷方式",
            accent = NotifyAmber,
            initiallyExpanded = false,
        ) {
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

        // ---- 手机提醒：手机这边的状态转成手环通知。默认收起 ----
        CollapsibleSection(
            title = "手机提醒",
            accent = PulseRed,
            initiallyExpanded = false,
        ) {
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

/**
 * 「多久耗多少电」的粗算。
 *
 * 只统计最近 7 天里**电量下降**的那些相邻两点，把掉的百分点和花掉的时间加起来
 * 求平均 —— 充电段（电量回升）直接跳过，否则会把平均速度冲淡成一个没意义的数。
 * 数据不够（不到两个点、或者这 7 天里根本没掉过电）就返回 null，界面不显示。
 */
private fun batteryDrainSummary(
    samples: List<BatterySample>,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (samples.size < 2) return null
    val since = nowMillis - 7L * 24 * 60 * 60 * 1000
    val window = samples.filter { it.atMillis >= since }
    var dropped = 0
    var elapsedMs = 0L
    window.zipWithNext { a, b ->
        if (b.percent < a.percent) {
            dropped += a.percent - b.percent
            elapsedMs += b.atMillis - a.atMillis
        }
    }
    if (dropped <= 0 || elapsedMs <= 0) return null
    val minutesPerPercent = elapsedMs / 60_000.0 / dropped
    val perPercent = formatDuration(minutesPerPercent.roundToInt().coerceAtLeast(1))
    val days = minutesPerPercent * 100 / 1440.0
    return "最近 7 天平均：每 $perPercent 掉 1%，满电约可用 %.1f 天".format(days)
}

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

/**
 * 桌面控件添加入口：三个控件各一行「添加到桌面」，走系统 requestPinAppWidget 直接钉到桌面。
 *
 * 为什么不能只靠长按图标菜单：长按菜单里的控件预览只给接了小米小部件体系的 App 留位
 * （第三方 App 侧载装的历史上就没有这一格），桌面自己那份小部件列表又按包名缓存。
 * 所以：主控件按小米小部件规范配置（长按图标菜单能收录）+ 静态快捷方式给一条
 * 「添加桌面控件」，再加这里的固定按钮，三条路至少有一条能走通。
 */
@Composable
private fun WidgetAddCard() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        Text("桌面控件", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "点「添加到桌面」由系统弹位置选择；长按应用图标，菜单里也有「添加桌面控件」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        WidgetAddRow("昨晚睡眠（2×2，可拉大）", SleepDetailWidgetProvider::class.java, context)
        WidgetAddRow("睡眠与心率", SleepHeartWidgetProvider::class.java, context)
        WidgetAddRow("睡眠时长", SleepWidgetProvider::class.java, context)
        Spacer(Modifier.height(4.dp))
        if (isMiui()) {
            Text(
                "小米/红米点了没反应？到桌面双指捏合（或长按空白处）→ 添加小部件 → 找「手环管家」，" +
                    "没有就滑到最底部的「安卓小组件」；并确认 应用信息 → 权限管理 → 其他权限 " +
                    "里的「桌面快捷方式」是打开的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openAppSettings(context) }) {
                Text("打开应用信息")
            }
        } else {
            Text(
                "点了没弹位置选择界面？去桌面长按空白处 → 小部件 → 找到「手环管家」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WidgetAddRow(
    label: String,
    provider: Class<out AppWidgetProvider>,
    context: Context,
) {
    OutlinedButton(
        onClick = { pinWidgetToHome(context, provider) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("$label — 添加到桌面")
    }
}
