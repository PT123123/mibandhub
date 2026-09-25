@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ted.shouhuan.ui.notify

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.AppRule
import com.ted.shouhuan.data.BandNotification
import com.ted.shouhuan.ui.components.CollapsibleSection
import com.ted.shouhuan.ui.components.FilterChipRow
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.SwitchSettingRow
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.util.minuteOfDayToClock

private val DEDUPE_SECONDS = listOf(10, 30, 60, 300)
private val DEDUPE_LABELS = listOf("10秒", "30秒", "1分钟", "5分钟")

private val VIBRATIONS = listOf("standard", "short", "strong")
private val VIBRATION_LABELS = listOf("标准", "短促", "强提醒")

@Composable
fun NotifyScreen(
    vm: NotifyViewModel,
    onOpenRecentNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val forwardEnabled by vm.forwardEnabled.collectAsStateWithLifecycle()
    val notifyPermission by vm.notifyPermission.collectAsStateWithLifecycle()
    val dndEnabled by vm.dndEnabled.collectAsStateWithLifecycle()
    val dndStart by vm.dndStart.collectAsStateWithLifecycle()
    val dndEnd by vm.dndEnd.collectAsStateWithLifecycle()
    val keywordBlacklist by vm.keywordBlacklist.collectAsStateWithLifecycle()
    val keywords by vm.keywords.collectAsStateWithLifecycle()
    val appFilterBlacklist by vm.appFilterBlacklist.collectAsStateWithLifecycle()
    val dedupeEnabled by vm.dedupeEnabled.collectAsStateWithLifecycle()
    val dedupeSeconds by vm.dedupeSeconds.collectAsStateWithLifecycle()
    val onlyLocked by vm.onlyLocked.collectAsStateWithLifecycle()
    val vibration by vm.vibration.collectAsStateWithLifecycle()
    val appRules by vm.appRules.collectAsStateWithLifecycle()
    val installedApps by vm.installedApps.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val testSend by vm.testSend.collectAsStateWithLifecycle()

    // 每次进入页面都刷新一次权限状态（从系统设置授权回来也能看到最新状态）
    LaunchedEffect(Unit) {
        vm.refreshNotifyPermission()
        // 「最近推送」的快捷加名单按钮要对老记录按应用名反查包名，顺手把应用列表备好
        vm.loadInstalledApps()
    }

    // 弹层状态：勿扰时间选择 / 关键词新增 / 添加转发应用
    var editingDndEdge by remember { mutableStateOf<String?>(null) }
    var showKeywordDialog by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.sendTestNotification() else vm.onBluetoothPermissionDenied()
    }
    val sendTest = {
        if (vm.hasBluetoothPermission()) {
            vm.sendTestNotification()
        } else {
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        Unit
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("通知", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // ---- 通知监听权限检查：没授权就高亮提示，这是一切转发的前提 ----
        if (!notifyPermission) {
            NoticeBanner(
                title = "需要开启「通知使用权限」",
                tone = PulseRed,
                detail = "手环管家需要读取手机通知，才能把微信、短信等消息转发到手环。" +
                    "现在没有权限，下面的转发规则都不会生效。",
                action = {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { context.startActivity(vm.notifyListenerSettingsIntent()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PulseRed,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("去系统设置授权")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- 转发总开关 ----
        SectionCard(title = "转发到手机", accent = NotifyAmber) {
            SwitchSettingRow(
                title = "手机通知同步到手环",
                subtitle = if (forwardEnabled) {
                    "已开启 · 命中规则的通知会振动提醒"
                } else {
                    "已关闭"
                },
                checked = forwardEnabled,
                onCheckedChange = vm::setForward,
                accent = NotifyAmber,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 测试通知：真的发一条到手环 ----
        SectionCard(title = "测试", accent = NotifyAmber) {
            Text(
                "连上手环后真实发一条通知过去 —— 手环上应立即弹出「手环管家 · 测试通知」并振动。" +
                    "这是验证整条通知链路（连接 → 认证 → chunked 通道写入）最直接的办法。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val testBusy = testSend == TestSendState.Connecting || testSend == TestSendState.Sending
            Button(
                onClick = sendTest,
                enabled = !testBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NotifyAmber,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = NotifyAmber.copy(alpha = 0.4f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    when (testSend) {
                        TestSendState.Connecting -> "正在连接手环…"
                        TestSendState.Sending -> "正在发送…"
                        else -> "发送测试通知到手环"
                    },
                )
            }
            when (val state = testSend) {
                is TestSendState.Success -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "✓ 已发到手环 —— 看一眼手环屏幕，应该已经弹出来了。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is TestSendState.Failure -> {
                    Spacer(Modifier.height(10.dp))
                    NoticeBanner(
                        title = state.title,
                        tone = PulseRed,
                        detail = state.detail,
                        hint = state.hint,
                    )
                }

                else -> {}
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 勿扰时段 ----
        SectionCard(title = "勿扰时段", accent = NotifyAmber) {
            SwitchSettingRow(
                title = "该时段内不转发",
                subtitle = "支持跨零点，例如 23:00 – 07:30",
                checked = dndEnabled,
                onCheckedChange = vm::setDnd,
                accent = NotifyAmber,
            )
            Spacer(Modifier.height(4.dp))
            KeyValueRow(
                "当前时段",
                vm.dndWindowLabel(dndStart, dndEnd),
                valueColor = if (dndEnabled) NotifyAmber else MaterialTheme.colorScheme.onSurface,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TimePickButton("开始 ${minuteOfDayToClock(dndStart)}", Modifier.weight(1f)) {
                    editingDndEdge = "start"
                }
                TimePickButton("结束 ${minuteOfDayToClock(dndEnd)}", Modifier.weight(1f)) {
                    editingDndEdge = "end"
                }
            }
            if (!forwardEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "转发总开关关闭时，勿扰时段不生效。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            SwitchSettingRow(
                title = "仅锁屏时转发",
                subtitle = "亮屏使用手机期间的通知不推到手环，锁屏后才转发",
                checked = onlyLocked,
                onCheckedChange = vm::setOnlyLocked,
                accent = NotifyAmber,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 关键词过滤 ----
        SectionCard(title = "关键词过滤", accent = NotifyAmber) {
            Text(
                if (keywordBlacklist) {
                    "黑名单模式：标题或正文命中关键词的通知不转发。"
                } else {
                    "白名单模式：只有标题或正文命中关键词的通知才转发。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FilterChipRow(
                options = listOf("白名单（命中才转发）", "黑名单（命中不转发）"),
                selectedIndex = if (keywordBlacklist) 1 else 0,
                accent = NotifyAmber,
                onSelect = { vm.setKeywordMode(it == 1) },
            )
            Spacer(Modifier.height(12.dp))

            if (keywords.isEmpty()) {
                Text(
                    "还没有关键词。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                keywords.forEach { keyword ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(keyword, style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "删除 $keyword",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { vm.removeKeyword(keyword) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { showKeywordDialog = true }) {
                Text("+ 添加关键词", color = NotifyAmber)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 防重复 ----
        SectionCard(title = "防重复转发", accent = NotifyAmber) {
            SwitchSettingRow(
                title = "同一通知短时间内只转发一次",
                subtitle = "应用连续弹出的重复通知（抢红包、验证码重发）不再连震",
                checked = dedupeEnabled,
                onCheckedChange = { vm.setDedupe(it, dedupeSeconds) },
                accent = NotifyAmber,
            )
            if (dedupeEnabled) {
                Spacer(Modifier.height(8.dp))
                FilterChipRow(
                    options = DEDUPE_LABELS,
                    selectedIndex = DEDUPE_SECONDS.indexOf(dedupeSeconds).coerceAtLeast(0),
                    accent = NotifyAmber,
                    onSelect = { vm.setDedupe(true, DEDUPE_SECONDS[it]) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 振动模式 ----
        SectionCard(title = "振动", accent = NotifyAmber) {
            Text(
                "转发通知到手环时的振动方式。协议没法直接下发「强度/时长」，" +
                    "所以每一档走手环的一个告警类别，手环按该类别存的振动模式来震 —— " +
                    "在官方 App 的「振动模式」里可以给对应类别单独调节奏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FilterChipRow(
                options = VIBRATION_LABELS,
                selectedIndex = VIBRATIONS.indexOf(vibration).coerceAtLeast(0),
                accent = NotifyAmber,
                onSelect = { vm.setVibration(VIBRATIONS[it]) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 应用名单（白名单 / 黑名单两种模式）----
        SectionCard(
            title = if (appFilterBlacklist) "不转发的应用（黑名单）" else "允许转发的应用（白名单）",
            accent = NotifyAmber,
        ) {
            Text(
                if (appFilterBlacklist) {
                    "黑名单模式：名单内的应用不转发，其余应用的通知都推到手环。"
                } else {
                    "白名单模式：只有名单内的应用才转发，其余应用一律不打扰。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FilterChipRow(
                options = listOf("白名单（名单内才转发）", "黑名单（名单内不转发）"),
                selectedIndex = if (appFilterBlacklist) 1 else 0,
                accent = NotifyAmber,
                onSelect = { vm.setAppFilterMode(it == 1) },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "手机上的应用都能加进来：下面这份只是出厂参考名单，点「添加应用」" +
                    "从已安装的应用里随便挑。每个应用还可以单独点「含正文 / 仅标题」" +
                    "决定它的通知要不要带上正文。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (appRules.isEmpty()) {
                Text(
                    "还没有选任何应用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            appRules.forEachIndexed { index, rule ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            rule.appName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (forwardEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            },
                        )
                        Text(
                            rule.packageName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 单应用的「详细内容」开关：点一下在 含正文 / 仅标题 之间切换
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (rule.showDetail) NotifyAmber.copy(alpha = 0.12f) else Color.Transparent,
                            )
                            .clickable(enabled = forwardEnabled) {
                                vm.setAppShowDetail(rule.packageName, !rule.showDetail)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (rule.showDetail) "含正文" else "仅标题",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (rule.showDetail) {
                                NotifyAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = { vm.removeAppRule(rule.packageName) },
                        enabled = forwardEnabled,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "移除 ${rule.appName}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { vm.setAppEnabled(rule.packageName, it) },
                        enabled = forwardEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = NotifyAmber,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    vm.loadInstalledApps()
                    showAppPicker = true
                },
                enabled = forwardEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("添加应用")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 最近推送 ----
        RecentCard(
            recent = recent,
            appRules = appRules,
            blacklistMode = appFilterBlacklist,
            vm = vm,
            onOpenRecentNotifications = onOpenRecentNotifications,
        )

        Spacer(Modifier.height(24.dp))
    }

    // ---- 勿扰时间选择弹窗 ----
    editingDndEdge?.let { edge ->
        val initialMinute = if (edge == "start") dndStart else dndEnd
        val pickerState = rememberTimePickerState(
            initialHour = initialMinute / 60,
            initialMinute = initialMinute % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { editingDndEdge = null },
            title = { Text(if (edge == "start") "勿扰开始时间" else "勿扰结束时间") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val next = pickerState.hour * 60 + pickerState.minute
                    if (edge == "start") vm.setDndWindow(next, dndEnd) else vm.setDndWindow(dndStart, next)
                    editingDndEdge = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingDndEdge = null }) {
                    Text("取消")
                }
            },
        )
    }

    // ---- 添加转发应用：从手机上装的应用里挑 ----
    if (showAppPicker) {
        var query by remember { mutableStateOf("") }
        val added = appRules.map { it.packageName }.toSet()
        val matched = installedApps.filter { app ->
            query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("添加应用") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜应用名或包名") },
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        installedApps.isEmpty() -> Text(
                            "正在读取手机上的应用…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        matched.isEmpty() -> Text(
                            "没有匹配的应用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> Column(
                            Modifier
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            matched.forEach { app ->
                                val already = app.packageName in added
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !already) {
                                            vm.addAppRule(app.packageName, app.label)
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (already) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        Text(
                                            app.packageName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        if (already) "已添加" else "添加",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (already) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            NotifyAmber
                                        },
                                    )
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) { Text("完成") }
            },
        )
    }

    // ---- 关键词新增弹窗 ----
    if (showKeywordDialog) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeywordDialog = false },
            title = { Text("添加关键词") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("例如：验证码、快递") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addKeyword(draft)
                    showKeywordDialog = false
                }) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeywordDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TimePickButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NotifyAmber.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = NotifyAmber,
        )
    }
}

@Composable
private fun RecentCard(
    recent: List<BandNotification>,
    appRules: List<AppRule>,
    blacklistMode: Boolean,
    vm: NotifyViewModel,
    onOpenRecentNotifications: () -> Unit,
) {
    // 默认收起：记录一多整页都被它占满，点开才展开明细
    CollapsibleSection(
        title = "最近推送",
        accent = NotifyAmber,
        badge = "${recent.size} 条",
        initiallyExpanded = false,
    ) {
        if (recent.isEmpty()) {
            Text(
                "还没有推送记录。发送一条「测试通知」，或等手环提醒（低电量/充满/连接）触发后，会在这里显示真实记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CollapsibleSection
        }

        // 完整历史在新页面里：只要有记录就给「查看全部」入口，
        // 保证浏览页（含筛选/搜索/快捷加名单）永远可达，不依赖记录数超过 20。
        TextButton(
            onClick = onOpenRecentNotifications,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "查看全部 ${recent.size} 条 →",
                style = MaterialTheme.typography.labelMedium,
                color = NotifyAmber,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        recent.take(20).forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            // 快捷加名单：应用不在名单里时给一个按钮 —— 白名单模式加白、黑名单模式加黑；
            // 已在名单里则显示状态，两种都保证有可点的入口/反馈。
            val pkg = vm.resolvePackageName(item)
            val rule = pkg?.let { p -> appRules.firstOrNull { it.packageName == p } }
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(38.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (item.forwarded) NotifyAmber else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${item.appName} · ${item.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.timeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.body,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.forwarded) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "未推送",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pkg != null) {
                        if (rule == null) {
                            TextButton(
                                onClick = {
                                    if (blacklistMode) {
                                        vm.addToBlacklist(pkg, item.appName)
                                    } else {
                                        vm.addAppRule(pkg, item.appName)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    if (blacklistMode) "+ 加入黑名单" else "+ 加入白名单",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NotifyAmber,
                                )
                            }
                        } else {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                when {
                                    blacklistMode && rule.enabled -> "已在黑名单 ✓"
                                    rule.enabled -> "已在白名单 ✓"
                                    else -> "已禁用 ✓"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
