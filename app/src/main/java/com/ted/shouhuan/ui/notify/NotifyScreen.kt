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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.BandNotification
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

private val VIBRATIONS = listOf("standard", "short", "double")
private val VIBRATION_LABELS = listOf("标准", "短促", "双震")

@Composable
fun NotifyScreen(vm: NotifyViewModel) {
    val forwardEnabled by vm.forwardEnabled.collectAsStateWithLifecycle()
    val dndEnabled by vm.dndEnabled.collectAsStateWithLifecycle()
    val dndStart by vm.dndStart.collectAsStateWithLifecycle()
    val dndEnd by vm.dndEnd.collectAsStateWithLifecycle()
    val keywordBlacklist by vm.keywordBlacklist.collectAsStateWithLifecycle()
    val keywords by vm.keywords.collectAsStateWithLifecycle()
    val dedupeEnabled by vm.dedupeEnabled.collectAsStateWithLifecycle()
    val dedupeSeconds by vm.dedupeSeconds.collectAsStateWithLifecycle()
    val showAppName by vm.showAppName.collectAsStateWithLifecycle()
    val includeBody by vm.includeBody.collectAsStateWithLifecycle()
    val vibration by vm.vibration.collectAsStateWithLifecycle()
    val appRules by vm.appRules.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val testSend by vm.testSend.collectAsStateWithLifecycle()

    // 弹层状态：勿扰时间选择 / 关键词新增
    var editingDndEdge by remember { mutableStateOf<String?>(null) }
    var showKeywordDialog by remember { mutableStateOf(false) }

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

        // ---- 通知内容与振动 ----
        SectionCard(title = "通知内容与振动", accent = NotifyAmber) {
            SwitchSettingRow(
                title = "附带应用名",
                subtitle = "手环上先显示「微信」再显示内容",
                checked = showAppName,
                onCheckedChange = { vm.setNotifyContent(it, includeBody) },
                accent = NotifyAmber,
            )
            SwitchSettingRow(
                title = "包含正文",
                subtitle = "关闭后只发标题，手环一屏能读完",
                checked = includeBody,
                onCheckedChange = { vm.setNotifyContent(showAppName, it) },
                accent = NotifyAmber,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "振动模式",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            FilterChipRow(
                options = VIBRATION_LABELS,
                selectedIndex = VIBRATIONS.indexOf(vibration).coerceAtLeast(0),
                accent = NotifyAmber,
                onSelect = { vm.setVibration(VIBRATIONS[it]) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 应用白名单 ----
        SectionCard(title = "允许转发的应用", accent = NotifyAmber) {
            appRules.forEachIndexed { index, rule ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                }
                SwitchSettingRow(
                    title = rule.appName,
                    checked = rule.enabled,
                    onCheckedChange = { vm.setAppEnabled(rule.packageName, it) },
                    enabled = forwardEnabled,
                    accent = NotifyAmber,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 最近推送 ----
        RecentCard(recent = recent)

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
private fun RecentCard(recent: List<BandNotification>) {
    SectionCard(title = "最近推送", accent = NotifyAmber) {
        recent.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(14.dp))
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
                            "未推送（被规则拦下）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
