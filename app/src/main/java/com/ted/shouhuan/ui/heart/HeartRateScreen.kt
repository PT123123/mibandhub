package com.ted.shouhuan.ui.heart

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.MeasureResult
import com.ted.shouhuan.service.MeasureError
import com.ted.shouhuan.service.MeasurePhase
import com.ted.shouhuan.ui.components.CollapsibleSection
import com.ted.shouhuan.ui.components.FilterChipRow
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.MetricTile
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.Sparkline
import com.ted.shouhuan.ui.components.StatusDot
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.util.epochDayOf
import com.ted.shouhuan.util.formatClockTime
import com.ted.shouhuan.util.formatDateTime
import com.ted.shouhuan.util.formatEpochDayShort
import com.ted.shouhuan.util.formatSeconds
import kotlin.math.roundToInt

/** 记录筛选范围：天数，-1 = 全部。 */
private val RECORD_RANGES = listOf(7, 30, 90, -1)
private val RECORD_RANGE_LABELS = listOf("近7天", "近30天", "近90天", "全部")

/**
 * 心率页：真正的测量流程都交给 [HeartRateViewModel]，
 * 这里只负责「把状态画出来」+「把点击转成动作」。
 *
 * 一次测量可能停在好几个地方 —— 没配对、没权限、蓝牙没开、连不上、认证不过、
 * 指令写不进、超时、中途掉线 —— 每一种都会变成界面上的具体提示，
 * 不会只留一个转圈的「正在测量…」。
 */
@Composable
fun HeartRateScreen(vm: HeartRateViewModel) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedSec.collectAsStateWithLifecycle()
    val lastBpm by vm.lastBpm.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    // 范围筛选 + 展开状态：切范围、点开某条记录都是纯界面状态
    var rangeIndex by remember { mutableStateOf(0) }
    var expandedRecordAt by remember { mutableStateOf<Long?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> vm.onPermissionResult(granted) },
    )
    val requestPermission = {
        // API < 31 没有这个运行时权限，hasBluetoothPermission() 会直接返回 true，
        // 所以这条分支实际走不到。
        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    val busy = isBusy(phase)
    val error = (phase as? MeasurePhase.Failure)?.error

    val onMainAction: () -> Unit = {
        when {
            busy -> vm.cancelMeasure()
            vm.hasBluetoothPermission() -> vm.startMeasure()
            else -> requestPermission()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("心率", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // ---- 当前值 + 测量 ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            PulseRed.copy(alpha = 0.12f),
                        ),
                    ),
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    PulsingDot(PulseRed)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    statusLabel(phase, elapsed, lastResult?.finishedAtMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    displayValue(phase, lastBpm),
                    style = MaterialTheme.typography.displayLarge,
                    color = PulseRed,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "BPM",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseRed,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            // ---- 还没配对：一进来就提醒，别等用户点了才说 ----
            if (!configured) {
                Spacer(Modifier.height(16.dp))
                NoticeBanner(
                    title = "还没有配对手环",
                    tone = NotifyAmber,
                    detail = "本地没有设备 MAC 和 AuthKey，测心率时连不上任何手环。",
                    hint = "先到「设备」页完成配对。AuthKey 可以用 just fetch 从官方 App 的日志里直接读出来。",
                )
            }

            // ---- 失败：把原因和下一步都摆出来 ----
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                NoticeBanner(
                    title = error.title,
                    tone = PulseRed,
                    detail = error.detail,
                    hint = error.hint,
                    action = if (error.canGrantPermission) {
                        {
                            TextButton(onClick = requestPermission) {
                                Text("去授权", color = PulseRed)
                            }
                        }
                    } else {
                        null
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onMainAction,
                colors = if (busy) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = PulseRed,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(buttonLabel(phase, lastBpm != null))
            }

            // ---- 连接状态：让「连上没有」随时看得见 ----
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(connectionLabel(connection) == "已连接")
                Spacer(Modifier.width(6.dp))
                Text(
                    connectionLabel(connection),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 上次测量的明细：什么时候测的、等了多久 ----
        // 大数字只给一个「多少 BPM」，剩下的上下文放这儿，省得用户回头猜。
        lastResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "测量明细", accent = PulseRed) {
                KeyValueRow("读数", "${result.bpm} BPM", valueColor = PulseRed)
                KeyValueRow("测量时刻", formatDateTime(result.finishedAtMillis))
                KeyValueRow("耗时", formatSeconds(result.durationSec))
            }
        }

        val rangeDays = RECORD_RANGES[rangeIndex]
        val filtered = remember(history, rangeDays) {
            val cutoff = System.currentTimeMillis() - rangeDays * 86_400_000L
            if (rangeDays < 0) history else history.filter { it.finishedAtMillis >= cutoff }
        }
        // 「与上一次的差值」相对完整历史算，不随范围筛选缩水。
        // history 新的在前：一条记录的「上一次」是它时间上更早的那次 = 列表里更靠前的邻居；
        // 最新的那条没有「上一次」，显示为「首次记录」。
        val deltaByTimestamp = remember(history) {
            history.mapIndexed { index, record ->
                val previous = if (index > 0) history.getOrNull(index - 1) else null
                record.finishedAtMillis to previous?.let { record.bpm - it.bpm }
            }.toMap()
        }

        // ---- 读数趋势（真实记录，按测量次序从旧到新）：默认收起，点开再看 ----
        Spacer(Modifier.height(12.dp))
        CollapsibleSection(
            title = "读数趋势",
            accent = PulseRed,
            badge = if (filtered.size >= 2) "${filtered.size} 次" else null,
            initiallyExpanded = false,
        ) {
            val trend = remember(filtered) { filtered.map { it.bpm.toFloat() }.reversed() }
            if (trend.size >= 2) {
                Sparkline(
                    values = trend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    color = PulseRed,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "最早一次",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "共 ${trend.size} 次 · 最新在右",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "测满 2 次后，这里会出现读数走势。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 统计 + 区间分布（真实记录）：合并成一卡，默认收起 ----
        Spacer(Modifier.height(12.dp))
        CollapsibleSection(
            title = "读数统计与分布",
            accent = StepBlue,
            initiallyExpanded = false,
        ) {
            if (filtered.isEmpty()) {
                Text(
                    "该范围内还没有测量记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val bpms = filtered.map { it.bpm }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricTile("${bpms.average().roundToInt()}", "BPM", "平均", accent = StepBlue)
                    MetricTile("${bpms.min()}", "BPM", "最低", accent = StepBlue)
                    MetricTile(
                        "${bpms.max()}",
                        "BPM",
                        "最高",
                        accent = PulseRed,
                        valueColor = PulseRed,
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(Modifier.height(12.dp))
                ZoneRow("过缓", "< 60", bpms.count { it < 60 }.toFloat() / bpms.size,
                    MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                ZoneRow("正常", "60 – 100", bpms.count { it in 60..100 }.toFloat() / bpms.size,
                    MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                ZoneRow("偏高", "> 100", bpms.count { it > 100 }.toFloat() / bpms.size, PulseRed)
            }
        }

        // ---- 全部测量记录（可收缩大项）：默认收起，每一条都能展开看细节 ----
        Spacer(Modifier.height(12.dp))
        CollapsibleSection(
            title = "全部测量记录",
            accent = StepBlue,
            badge = "${filtered.size} 条",
            initiallyExpanded = false,
            headerTrailing = {
                if (history.isNotEmpty()) {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        ) {
            FilterChipRow(
                options = RECORD_RANGE_LABELS,
                selectedIndex = rangeIndex,
                accent = PulseRed,
                onSelect = {
                    rangeIndex = it
                    expandedRecordAt = null
                },
            )
            Spacer(Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                Text(
                    "还没有测量记录 —— 先测一次，这里会把每一条都留下来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val dayGroups = remember(filtered) {
                    filtered.groupBy { epochDayOf(it.finishedAtMillis) }
                }
                dayGroups.forEach { (epochDay, records) ->
                    val dayAvg = records.map { it.bpm }.average().roundToInt()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            formatEpochDayShort(epochDay),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${records.size} 次 · 均 $dayAvg",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    records.forEachIndexed { index, record ->
                        RecordRow(
                            record = record,
                            delta = deltaByTimestamp[record.finishedAtMillis],
                            expanded = expandedRecordAt == record.finishedAtMillis,
                            showDivider = index < records.lastIndex,
                            onToggle = {
                                expandedRecordAt =
                                    if (expandedRecordAt == record.finishedAtMillis) {
                                        null
                                    } else {
                                        record.finishedAtMillis
                                    }
                            },
                        )
                    }
                    HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部测量记录？") },
            text = { Text("共 ${history.size} 条，清空后无法恢复。设备配对信息不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    showClearConfirm = false
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 一条测量记录：收起时只有时间 + 读数，展开后把这一测的上下文全摆出来。 */
@Composable
private fun RecordRow(
    record: MeasureResult,
    delta: Int?,
    expanded: Boolean,
    showDivider: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggle),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatClockTime(record.finishedAtMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${record.bpm}",
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseRed,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "BPM",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseRed,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = 8.dp)) {
                KeyValueRow("测量时刻", formatDateTime(record.finishedAtMillis))
                KeyValueRow("耗时", formatSeconds(record.durationSec))
                KeyValueRow(
                    "与上一次",
                    delta?.let { if (it >= 0) "+$it BPM" else "$it BPM" } ?: "首次记录",
                    valueColor = when {
                        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        delta > 0 -> PulseRed
                        else -> StepBlue
                    },
                )
            }
        }
        if (showDivider && !expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        }
    }
}

// ---------------------------------------------------------------------------
// 状态 → 文案
// ---------------------------------------------------------------------------

private fun isBusy(phase: MeasurePhase): Boolean = when (phase) {
    MeasurePhase.Connecting, MeasurePhase.Authenticating, MeasurePhase.Measuring -> true
    else -> false
}

/** 大数字该显示什么。测量中一律「--」，免得拿着上次的旧读数骗人。 */
private fun displayValue(phase: MeasurePhase, lastBpm: Int?): String = when (phase) {
    is MeasurePhase.Success -> phase.result.bpm.toString()
    MeasurePhase.Connecting, MeasurePhase.Authenticating, MeasurePhase.Measuring -> "--"
    is MeasurePhase.Failure, MeasurePhase.Idle -> lastBpm?.toString() ?: "--"
}

/** 顶部那行小字。带上秒数，用户才知道程序在动。 */
private fun statusLabel(
    phase: MeasurePhase,
    elapsedSec: Int,
    lastFinishedAtMillis: Long?,
): String {
    val base = when (phase) {
        MeasurePhase.Connecting -> "正在连接手环…"
        MeasurePhase.Authenticating -> "正在认证手环…"
        MeasurePhase.Measuring -> "正在测量…"
        is MeasurePhase.Failure -> "测量未完成"
        // 没在测的时候大数字是上一次的读数 —— 写「当前心率」会让人以为是实时的
        else -> lastFinishedAtMillis
            ?.let { "上次测量 ${formatClockTime(it)}" }
            ?: "当前心率"
    }
    return if (isBusy(phase) && elapsedSec > 0) "$base ${elapsedSec}s" else base
}

private fun buttonLabel(phase: MeasurePhase, hasValue: Boolean): String = when (phase) {
    MeasurePhase.Connecting, MeasurePhase.Authenticating -> "取消"
    MeasurePhase.Measuring -> "停止测量"
    is MeasurePhase.Failure -> "重试"
    is MeasurePhase.Success -> "再测一次"
    MeasurePhase.Idle -> if (hasValue) "再测一次" else "测量一次"
}

private fun connectionLabel(state: com.ted.shouhuan.ble.ConnectionState): String = when (state) {
    is com.ted.shouhuan.ble.ConnectionState.Connected,
    is com.ted.shouhuan.ble.ConnectionState.Authenticated,
    -> "已连接"

    is com.ted.shouhuan.ble.ConnectionState.Connecting,
    is com.ted.shouhuan.ble.ConnectionState.Discovering,
    -> "正在连接"

    is com.ted.shouhuan.ble.ConnectionState.Failed -> "连接失败"

    com.ted.shouhuan.ble.ConnectionState.Disconnected -> "未连接"
}

/** 测量中的呼吸小圆点 —— 静默的等待最让人怀疑程序卡死。 */
@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "hr-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hr-pulse-alpha",
    )
    Box(
        Modifier
            .size(7.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun ZoneRow(label: String, range: String, fraction: Float, color: Color) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                range,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.18f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}
