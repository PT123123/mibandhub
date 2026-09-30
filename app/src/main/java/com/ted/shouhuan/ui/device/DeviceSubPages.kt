@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ted.shouhuan.ui.device

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.BatterySample
import com.ted.shouhuan.data.HealthConnectSleepSource
import com.ted.shouhuan.proto.BandSettings
import com.ted.shouhuan.service.SleepSyncPhase
import com.ted.shouhuan.ui.components.ConfirmHost
import com.ted.shouhuan.ui.components.DragReorderList
import com.ted.shouhuan.ui.components.FilterChipRow
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.SubPage
import com.ted.shouhuan.ui.components.SwitchSettingRow
import com.ted.shouhuan.ui.components.rememberConfirm
import com.ted.shouhuan.ui.components.rememberStatusHint
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.util.formatDateTime
import com.ted.shouhuan.util.formatDuration
import com.ted.shouhuan.util.minuteOfDayToClock
import com.ted.shouhuan.widget.LAUNCHER_SHORTCUT_PERMISSION
import com.ted.shouhuan.widget.MIUI_HOME_SHORTCUT_PERMISSION
import com.ted.shouhuan.widget.SleepDetailWidgetProvider
import com.ted.shouhuan.widget.SleepHeartWidgetProvider
import com.ted.shouhuan.widget.SleepWidgetProvider
import com.ted.shouhuan.widget.hasShortcutPermission
import com.ted.shouhuan.widget.isMiui
import com.ted.shouhuan.widget.openAppSettings
import com.ted.shouhuan.widget.pinWidgetToHome
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 健康连接提供方（Android 13 是独立 App；14+ 内置于系统设置）。 */
private const val HC_PROVIDER_PACKAGE = "com.android.healthconnect.controller"

/** 电量记录卡片最多列几条（全量仍在本地，永久保存）。 */
private const val HISTORY_ROWS = 15

/** 外部睡眠数据源 App：小米运动健康。 */
private const val MI_FITNESS_PACKAGE = "com.mi.health"

/** 时间选择弹层的目标：哪项设置（勿扰 / 夜间模式）+ 开始还是结束。 */
private data class TimeEdit(val setting: String, val edge: String)

// ---------------------------------------------------------------------------
// 连接
// ---------------------------------------------------------------------------

/** 连接设置子页面：开机自动连接（开关要再确认一次）+ 转发手机通知。 */
@Composable
fun DeviceConnectPage(vm: DeviceViewModel, onBack: () -> Unit) {
    val autoConnect by vm.autoConnect.collectAsStateWithLifecycle()
    val forwardNotifications by vm.forwardNotifications.collectAsStateWithLifecycle()
    val confirm = rememberConfirm()
    val status = rememberStatusHint()

    SubPage("连接", onBack, status = status.value, statusAccent = MaterialTheme.colorScheme.primary) {
        SectionCard(title = "连接", accent = MaterialTheme.colorScheme.primary) {
            ToggleRow(
                title = "开机自动连接",
                subtitle = "打开应用时自动连上已配对手环",
                checked = autoConnect,
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启开机自动连接？" else "关闭开机自动连接？",
                        message = if (target) {
                            "打开应用时会自动连上已配对手环，通知、心率这些都不用手动点连接。"
                        } else {
                            "关闭后每次都要手动点「连接手环」，通知转发也要等连上才会走。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                    ) {
                        vm.setAutoConnect(target)
                        status.show(if (target) "已开启开机自动连接" else "已关闭开机自动连接")
                    }
                },
            )
            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            ToggleRow(
                title = "转发手机通知",
                subtitle = "把手机收到的通知推到手环（需要通知使用权）",
                checked = forwardNotifications,
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启转发手机通知？" else "关闭转发手机通知？",
                        message = if (target) {
                            "手机收到的通知会推到手环上（还需要「通知使用权限」）。"
                        } else {
                            "关闭后手机通知不再推到手环，通知 tab 里配的规则一并不生效。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                    ) {
                        vm.setForwardNotifications(target)
                        status.show(if (target) "已开启转发手机通知" else "已关闭转发手机通知")
                    }
                },
            )
            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            Text(
                "连接会一直保持（通知栏和首页实时显示手环状态），到设备页点「断开连接」才断开。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    ConfirmHost(confirm)
}

// ---------------------------------------------------------------------------
// 电量记录
// ---------------------------------------------------------------------------

/** 电量记录子页面：最新读数 + 掉电速度 + 逐条时间点。 */
@Composable
fun DeviceBatteryPage(vm: DeviceViewModel, onBack: () -> Unit) {
    val batteryHistory by vm.batteryHistory.collectAsStateWithLifecycle()

    SubPage("电量记录", onBack) {
        SectionCard(title = "电量记录", accent = Mint) {
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
    }
}

// ---------------------------------------------------------------------------
// 数据同步
// ---------------------------------------------------------------------------

/** 数据同步子页面：睡眠监测开关（开/关都要再确认一次）+ 手环同步 + 健康连接导入。 */
@Composable
fun DeviceSyncPage(vm: DeviceViewModel, onBack: () -> Unit) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val sleepSync by vm.sleepSync.collectAsStateWithLifecycle()
    val sleepMonitoring by vm.sleepMonitoring.collectAsStateWithLifecycle()
    val confirm = rememberConfirm()
    val status = rememberStatusHint()
    val scope = rememberCoroutineScope()

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

    // 小米运动健康是否安装：装了就得提醒「睡眠会被它抢先同步清掉」。
    val miHealthInstalled = remember {
        context.packageManager.getLaunchIntentForPackage(MI_FITNESS_PACKAGE) != null
    }

    SubPage("数据同步", onBack, status = status.value, statusAccent = StepBlue) {
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启睡眠监测？" else "关闭睡眠监测？",
                        message = if (target) {
                            "开启后，打开应用会自动拉取最近几晚睡眠数据。"
                        } else {
                            "关闭后应用不再自动拉取睡眠，要拉得手动点下面的「同步手环数据」。" +
                                "手环本身仍然在记录睡眠。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = StepBlue,
                    ) {
                        vm.setSleepMonitoring(target)
                        status.show(if (target) "已开启睡眠监测" else "已关闭睡眠监测")
                    }
                },
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
    }

    ConfirmHost(confirm)
}

// ---------------------------------------------------------------------------
// 手环设置
// ---------------------------------------------------------------------------

/** 手环设置子页面：连接后整套下发，当场改动当场推。 */
@Composable
fun DeviceBandSettingsPage(vm: DeviceViewModel, onBack: () -> Unit) {
    val wearLeft by vm.wearLeft.collectAsStateWithLifecycle()
    val liftWake by vm.liftWake.collectAsStateWithLifecycle()
    val swipeUnlock by vm.swipeUnlock.collectAsStateWithLifecycle()
    val disconnectAlert by vm.disconnectAlert.collectAsStateWithLifecycle()
    val autoHeartRate by vm.autoHeartRate.collectAsStateWithLifecycle()
    val autoHeartRateInterval by vm.autoHeartRateInterval.collectAsStateWithLifecycle()
    val dndMode by vm.dndMode.collectAsStateWithLifecycle()
    val dndStart by vm.dndStart.collectAsStateWithLifecycle()
    val dndEnd by vm.dndEnd.collectAsStateWithLifecycle()
    val nightMode by vm.nightMode.collectAsStateWithLifecycle()
    val nightStart by vm.nightStart.collectAsStateWithLifecycle()
    val nightEnd by vm.nightEnd.collectAsStateWithLifecycle()
    val settingsStatus by vm.settingsStatus.collectAsStateWithLifecycle()
    val confirm = rememberConfirm()

    // 时间选择弹层：记「哪项设置 + 开始/结束」，弹窗共用一个
    var editingTime by remember { mutableStateOf<TimeEdit?>(null) }

    // 下发状态是 VM 里的全局最后一句，进页面先清掉 —— 别把别的页写的那句带到这页来
    LaunchedEffect(Unit) { vm.clearSettingsStatus() }

    SubPage("手环设置", onBack, status = settingsStatus, statusAccent = Mint) {
        SectionCard(title = "手环设置", accent = Mint) {
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启抬腕亮屏？" else "关闭抬腕亮屏？",
                        message = if (target) "抬手就亮屏，放下自动熄灭。" else "抬手不再亮屏，要看时间得点一下屏幕。",
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = Mint,
                    ) { vm.setLiftWake(target) }
                },
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启滑动解锁？" else "关闭滑动解锁？",
                        message = if (target) {
                            "点亮手环后要上滑一下才能进表盘，防放在口袋里误触。"
                        } else {
                            "点亮即可操作，不用上滑 —— 口袋里更容易误触。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = Mint,
                    ) { vm.setSwipeUnlock(target) }
                },
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启断开提醒？" else "关闭断开提醒？",
                        message = if (target) {
                            "手环和手机断开蓝牙时，手环自己振动提醒（断开后才生效）。"
                        } else {
                            "断开蓝牙时手环不再振动提醒。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = Mint,
                    ) { vm.setDisconnectAlert(target) }
                },
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启自动心率检测？" else "关闭自动心率检测？",
                        message = if (target) {
                            "手环按下面的「检测频率」全天定时探测心率。档位越小越费电，" +
                                "关掉最省电但手环不再产生连续心率数据。"
                        } else {
                            "手环不再自动测心率：最省电，代价是桌面控件的心率曲线会空。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = Mint,
                    ) { vm.setAutoHeartRate(target) }
                },
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

    ConfirmHost(confirm)
}

// ---------------------------------------------------------------------------
// 菜单顺序 / 快捷方式
// ---------------------------------------------------------------------------

/** 菜单顺序子页面：长按拖动排序，移除要再确认一次。 */
@Composable
fun DeviceMenuPage(vm: DeviceViewModel, onBack: () -> Unit) {
    val menuOrder by vm.menuOrder.collectAsStateWithLifecycle()
    val settingsStatus by vm.settingsStatus.collectAsStateWithLifecycle()
    val confirm = rememberConfirm()

    LaunchedEffect(Unit) { vm.clearSettingsStatus() }

    SubPage("菜单顺序", onBack) {
        settingsStatus?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = StepBlue)
            Spacer(Modifier.height(10.dp))
        }
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
                ReorderRow(label = item.label, onRemove = {
                    confirm.ask(
                        title = "从菜单里移除？",
                        message = "「${item.label}」要从手环菜单里移除。\n" +
                            "移除后手环上划菜单里不再出现这一项（随时能在这里加回来）。",
                        confirmLabel = "移除",
                        accent = PulseRed,
                    ) { vm.removeMenuItem(item) }
                })
            }
            Spacer(Modifier.height(12.dp))
            AddItemChips(
                all = BandSettings.Item.entries.filter { it !in menuOrder },
                accent = StepBlue,
                onAdd = { vm.addMenuItem(it) },
            )
        }
    }

    ConfirmHost(confirm)
}

/** 快捷方式子页面：表盘左右滑的卡片顺序，移除要再确认一次。 */
@Composable
fun DeviceShortcutPage(vm: DeviceViewModel, onBack: () -> Unit) {
    val shortcutOrder by vm.shortcutOrder.collectAsStateWithLifecycle()
    val settingsStatus by vm.settingsStatus.collectAsStateWithLifecycle()
    val confirm = rememberConfirm()

    LaunchedEffect(Unit) { vm.clearSettingsStatus() }

    SubPage("快捷方式", onBack) {
        settingsStatus?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = NotifyAmber)
            Spacer(Modifier.height(10.dp))
        }
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
                ReorderRow(label = item.label, onRemove = {
                    confirm.ask(
                        title = "移除快捷方式？",
                        message = "「${item.label}」要从表盘快捷卡片里移除。\n" +
                            "移除后左右滑看不到它了（随时能在这里加回来）。",
                        confirmLabel = "移除",
                        accent = PulseRed,
                    ) { vm.removeShortcutItem(item) }
                })
            }
            Spacer(Modifier.height(12.dp))
            AddItemChips(
                all = BandSettings.Item.entries.filter { it !in shortcutOrder },
                accent = NotifyAmber,
                onAdd = { vm.addShortcutItem(it) },
            )
        }
    }

    ConfirmHost(confirm)
}

// ---------------------------------------------------------------------------
// 手机提醒 / 桌面控件 / 配对
// ---------------------------------------------------------------------------

/** 手机提醒子页面：手机这边的状态转成手环通知。 */
@Composable
fun DeviceRemindPage(vm: DeviceViewModel, onBack: () -> Unit) {
    val remindOnConnect by vm.remindOnConnect.collectAsStateWithLifecycle()
    val remindLowBattery by vm.remindLowBattery.collectAsStateWithLifecycle()
    val remindLowBatteryPct by vm.remindLowBatteryPct.collectAsStateWithLifecycle()
    val remindFullyCharged by vm.remindFullyCharged.collectAsStateWithLifecycle()
    val confirm = rememberConfirm()
    val status = rememberStatusHint()

    SubPage("手机提醒", onBack, status = status.value, statusAccent = PulseRed) {
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启连接提醒？" else "关闭连接提醒？",
                        message = if (target) {
                            "手环管家连上手环时，手环上提示一声。"
                        } else {
                            "连上手环时不再提示。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = PulseRed,
                    ) {
                        vm.setRemindOnConnect(target)
                        status.show(if (target) "已开启连接提醒" else "已关闭连接提醒")
                    }
                },
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启低电量提醒？" else "关闭低电量提醒？",
                        message = if (target) {
                            "手机电量掉到阈值以下时在手环上提醒，充电中不触发。"
                        } else {
                            "手机电量低时不再提醒。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = PulseRed,
                    ) {
                        vm.setRemindLowBattery(target, remindLowBatteryPct)
                        status.show(if (target) "已开启低电量提醒" else "已关闭低电量提醒")
                    }
                },
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
                onCheckedChange = { target ->
                    confirm.ask(
                        title = if (target) "开启充满提醒？" else "关闭充满提醒？",
                        message = if (target) {
                            "手机充满电时在手环上提醒一声。"
                        } else {
                            "充满电时不再提醒。"
                        },
                        confirmLabel = if (target) "开启" else "关闭",
                        accent = PulseRed,
                    ) {
                        vm.setRemindFullyCharged(target)
                        status.show(if (target) "已开启充满提醒" else "已关闭充满提醒")
                    }
                },
                accent = PulseRed,
            )
        }
    }

    ConfirmHost(confirm)
}

/** 桌面控件子页面：三个控件各一个「添加到桌面」入口。 */
@Composable
fun DeviceWidgetPage(onBack: () -> Unit) {
    SubPage("桌面控件", onBack) {
        WidgetAddCard()
    }
}

/** 配对子页面：重新配对 / 忘记此设备（忘记要再确认一次）。 */
@Composable
fun DevicePairPage(vm: DeviceViewModel, onPair: () -> Unit, onBack: () -> Unit) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val authKey by vm.authKey.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirm()

    SubPage("配对", onBack) {
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
            if (paired) {
                Spacer(Modifier.height(8.dp))
                KeyValueRow(
                    "当前密钥",
                    com.ted.shouhuan.data.Pairing.maskAuthKey(authKey),
                    valueColor = Mint,
                )
            }
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
                    onClick = {
                        confirm.ask(
                            title = "忘记这台手环？",
                            message = "会删掉本机存的 MAC 和 AuthKey。手环本身不受影响，" +
                                "测量记录也会保留；下次要用得重新配对。",
                            confirmLabel = "忘记",
                            accent = PulseRed,
                        ) {
                            scope.launch { vm.forgetDevice() }
                        }
                    },
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
    }

    ConfirmHost(confirm)
}

// ---------------------------------------------------------------------------
// 小部件与工具
// ---------------------------------------------------------------------------

/** 「多久耗多少电」的粗算：只统计最近 7 天里电量下降的相邻两点，充电段跳过。 */
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
    accent: Color = Mint,
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
    accent: Color,
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
 * 所以：主控件按原生 AppWidget 配置（保证澎湃OS「添加小部件 → 安卓小组件」里找得到）+
 * 静态快捷方式给一条「添加桌面控件」，再加这里的固定按钮，三条路至少有一条能走通。
 *
 * 澎湃OS/MIUI 上点「添加到桌面」前要先拿到「桌面快捷方式」权限（应用信息 → 权限管理 →
 * 其他权限 里的那个开关）：关着时 requestPinAppWidget 不弹位置选择、控件也落不到桌面。
 * 这里先运行时请求两个权限字符串（对应同一个开关），授权后再固定；被拒就提示手动开。
 */
@Composable
private fun WidgetAddCard() {
    val context = LocalContext.current
    var pendingProvider by remember {
        mutableStateOf<Class<out AppWidgetProvider>?>(null)
    }
    val shortcutPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val provider = pendingProvider ?: return@rememberLauncherForActivityResult
        pendingProvider = null
        if (hasShortcutPermission(context)) {
            pinWidgetToHome(context, provider)
        } else {
            Toast.makeText(
                context,
                "「桌面快捷方式」权限没开，没法自动放上桌面：请到 应用信息 → 权限管理 → 其他权限 打开，再试一次。",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    // 点击「添加到桌面」：小米上先确保「桌面快捷方式」权限再钉，别家直接钉。
    fun onAdd(provider: Class<out AppWidgetProvider>) {
        pendingProvider = provider
        if (isMiui() && !hasShortcutPermission(context)) {
            shortcutPermLauncher.launch(
                arrayOf(MIUI_HOME_SHORTCUT_PERMISSION, LAUNCHER_SHORTCUT_PERMISSION),
            )
        } else {
            pinWidgetToHome(context, provider)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        Text(
            "点「添加到桌面」由系统弹位置选择；长按应用图标，菜单里也有「添加桌面控件」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        WidgetAddRow("昨晚睡眠（2×2，可拉大）", context) {
            onAdd(SleepDetailWidgetProvider::class.java)
        }
        WidgetAddRow("睡眠与心率", context) { onAdd(SleepHeartWidgetProvider::class.java) }
        WidgetAddRow("睡眠时长", context) { onAdd(SleepWidgetProvider::class.java) }
        Spacer(Modifier.height(4.dp))
        if (isMiui()) {
            Text(
                "澎湃OS/小米：点「添加到桌面」会先弹「桌面快捷方式」授权，允许后再选位置。" +
                    "桌面找不到控件时，到桌面双指捏合（或长按空白处）→ 添加小部件 → 滑到最底部的" +
                    "「安卓小组件」分类，按「手环管家」分组找三个控件；没有就先在 应用信息 →" +
                    "权限管理 → 其他权限 里把「桌面快捷方式」打开。",
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

/** 点击「添加到桌面」按钮行。 */
@Composable
private fun WidgetAddRow(
    label: String,
    context: Context,
    onAdd: () -> Unit,
) {
    OutlinedButton(
        onClick = onAdd,
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
