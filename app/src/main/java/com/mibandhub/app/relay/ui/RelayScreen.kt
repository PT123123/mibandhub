package com.mibandhub.app.relay.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mibandhub.app.relay.RelayViewModel
import com.mibandhub.app.relay.model.RelayLogLevel
import com.mibandhub.app.relay.model.RelayMode
import com.mibandhub.app.relay.model.RelaySyncStatus
import com.mibandhub.app.ui.components.CollapsibleSection
import com.mibandhub.app.ui.components.FilterChipRow
import com.mibandhub.app.ui.components.KeyValueRow
import com.mibandhub.app.ui.components.NoticeBanner
import com.mibandhub.app.ui.components.SectionCard
import com.mibandhub.app.ui.components.StatusDot
import com.mibandhub.app.ui.components.SwitchSettingRow
import com.mibandhub.app.ui.theme.NotifyAmber
import com.mibandhub.app.ui.theme.StepBlue
import com.mibandhub.app.util.formatClockTime

/**
 * 数据中转页面：发现节点 / 手动+自动同步 / 服务端设置 / 实时日志。
 *
 * 默认走「局域网中转」—— 冷启动即组播宣告并开放 /info、/snapshot，供电脑端
 * health-motion 自动发现与拉取；「模拟中转」只在手机内环路跑、不触网，供调试用。
 * 组播不通可在地址栏手填 IP:18765，协议细节见 README「数据中转」一节。
 */
@Composable
fun RelayScreen(vm: RelayViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        // ---- 顶部：标题 + 连接状态 ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("数据中转", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state.status == RelaySyncStatus.SYNCING || state.device != null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            state.status == RelaySyncStatus.SYNCING -> "同步中…"
                            state.device != null -> "${state.device!!.name} · 已就绪"
                            else -> if (config.mode == RelayMode.LAN) "未连接中转" else "模拟模式"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "重新发现", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))

        NoticeBanner(
            title = if (config.mode == RelayMode.MOCK) "当前为模拟中转" else "已切到局域网中转",
            tone = if (config.mode == RelayMode.MOCK) NotifyAmber else StepBlue,
            detail = if (config.mode == RelayMode.MOCK) {
                "数据只在手机内环路跑，不触网。对接电脑上的真实中转后，把下方「模式」切到「局域网中转」并填好地址即可，其余界面不用动。"
            } else {
                "数据将发往电脑端的 health-motion（局域网中转 hub）。手机会自动组播发现桌面，也可在此手动填桌面 IP:18765。"
            },
        )

        Spacer(Modifier.height(12.dp))

        // ---- 中转节点 ----
        SectionCard(title = "中转节点", accent = StepBlue) {
            if (devices.isEmpty()) {
                Text(
                    "未发现节点",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                devices.forEach { device ->
                    val selected = config.selectedDeviceId == null || config.selectedDeviceId == device.id
                    DeviceRow(
                        name = device.name,
                        host = if (device.isMock) "进程内 · 不触网" else "${device.host}:${device.port}",
                        fingerprint = device.fingerprint,
                        isMock = device.isMock,
                        selected = selected,
                        onClick = { vm.selectDevice(if (selected) null else device.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 同步控制 ----
        SectionCard(title = "同步", accent = MaterialTheme.colorScheme.primary) {
            SwitchSettingRow(
                title = "中转总开关",
                subtitle = "关掉后既不自动同步也不手动同步",
                checked = config.enabled,
                onCheckedChange = vm::setEnabled,
            )
            Spacer(Modifier.height(6.dp))
            SwitchSettingRow(
                title = "后台自动同步",
                subtitle = "按下方间隔在后台周期性同步",
                checked = config.autoSync,
                enabled = config.enabled,
                onCheckedChange = vm::setAutoSync,
            )
            Spacer(Modifier.height(12.dp))

            Text("自动同步间隔", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val intervalOptions = listOf("5 分钟", "15 分钟", "30 分钟", "1 小时")
            val intervalValues = listOf(5, 15, 30, 60)
            FilterChipRow(
                options = intervalOptions,
                selectedIndex = intervalValues.indexOf(config.intervalMinutes).coerceAtLeast(0),
                onSelect = { vm.setInterval(intervalValues[it]) },
            )
            Spacer(Modifier.height(14.dp))

            // 立即同步按钮 + 进度
            val syncing = state.status == RelaySyncStatus.SYNCING
            val canSync = config.enabled && state.device != null && !syncing
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (canSync) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            },
                        )
                        .clickable(enabled = canSync) { vm.syncNow() }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Send,
                            contentDescription = null,
                            tint = if (canSync) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (syncing) "同步中…" else "立即同步",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (canSync) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (syncing) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (syncing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(14.dp))
            KeyValueRow("上次同步", state.lastSyncAt?.let { formatClockTime(it) } ?: "尚未同步")
            when (val r = state.lastResult) {
                is com.mibandhub.app.relay.model.RelayPushResult.Success ->
                    KeyValueRow("最近结果", "成功 · ${r.acceptedItems} 条 / ${r.acceptedBytes} 字节")

                is com.mibandhub.app.relay.model.RelayPushResult.Failure ->
                    KeyValueRow("最近结果", "失败：${r.error}", valueColor = MaterialTheme.colorScheme.error)

                null -> {}
            }
            state.lastBatchSummary?.let { KeyValueRow("同步内容", it) }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 本次将同步 ----
        SectionCard(title = "本次将同步", accent = com.mibandhub.app.ui.theme.SleepIndigo) {
            val items = preview?.items
            if (items.isNullOrEmpty()) {
                Text(
                    "暂无数据（先连接手环、同步一些心率/睡眠/通知）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                items.forEach { item ->
                    KeyValueRow(typeLabel(item.type), "${item.count} 条 · ${item.bytes} 字节")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 服务端设置 ----
        SectionCard(title = "服务端设置", accent = NotifyAmber) {
            val modeOptions = listOf("模拟中转", "局域网中转")
            FilterChipRow(
                options = modeOptions,
                selectedIndex = if (config.mode == RelayMode.LAN) 1 else 0,
                accent = NotifyAmber,
                onSelect = { vm.setMode(if (it == 1) RelayMode.LAN else RelayMode.MOCK) },
            )
            Spacer(Modifier.height(12.dp))

            ServerSettingField(
                label = "本机别名",
                value = config.sourceAlias,
                onCommit = vm::setAlias,
            )
            Spacer(Modifier.height(10.dp))

            if (config.mode == RelayMode.LAN) {
                ServerSettingField(
                    label = "中转地址（局域网）",
                    value = config.serverUrl,
                    onCommit = vm::setServerUrl,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "格式如 http://192.168.1.50:18765；手机会向该地址的 /api/health-motion/v1/push 推送手环数据，桌面端默认端口 18765。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 同步日志 ----
        CollapsibleSection(
            title = "同步日志",
            accent = MaterialTheme.colorScheme.primary,
            badge = if (logs.isNotEmpty()) "${logs.size}" else null,
            headerTrailing = {
                if (logs.isNotEmpty()) {
                    TextButton(onClick = vm::clearLogs) { Text("清空", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
        ) {
            if (logs.isEmpty()) {
                Text(
                    "还没有日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height((logs.size.coerceAtMost(8) * 30).dp + 8.dp)) {
                    items(logs.reversed()) { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                formatClockTime(entry.atMillis),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = when (entry.level) {
                                    RelayLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    RelayLogLevel.WARN -> NotifyAmber
                                    RelayLogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceRow(
    name: String,
    host: String,
    fingerprint: String,
    isMock: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(3.dp))
            Text(host, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text("指纹 $fingerprint", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isMock) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(NotifyAmber.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("模拟", style = MaterialTheme.typography.labelMedium, color = NotifyAmber)
            }
        }
    }
}

@Composable
private fun ServerSettingField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(value) { text = value }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onCommit(text.trim()) }) { Text("保存") }
        }
    }
}

private fun typeLabel(t: String): String = when (t) {
    "heart_rate" -> "心率"
    "sleep" -> "睡眠"
    "measure" -> "测量"
    "notification" -> "通知"
    else -> t
}
