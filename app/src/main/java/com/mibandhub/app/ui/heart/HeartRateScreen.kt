package com.mibandhub.app.ui.heart

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
import androidx.compose.material.icons.rounded.FlashlightOn
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mibandhub.app.data.HeartMeasureRecord
import com.mibandhub.app.data.HeartRateSource
import com.mibandhub.app.service.MeasurePhase
import com.mibandhub.app.ui.components.CollapsibleSection
import com.mibandhub.app.ui.components.FilterChipRow
import com.mibandhub.app.ui.components.KeyValueRow
import com.mibandhub.app.ui.components.MetricTile
import com.mibandhub.app.ui.components.NoticeBanner
import com.mibandhub.app.ui.components.SectionCard
import com.mibandhub.app.ui.components.Sparkline
import com.mibandhub.app.ui.components.StatusDot
import com.mibandhub.app.ui.theme.Mint
import com.mibandhub.app.ui.theme.NotifyAmber
import com.mibandhub.app.ui.theme.PulseRed
import com.mibandhub.app.ui.theme.StepBlue
import com.mibandhub.app.util.epochDayOf
import com.mibandhub.app.util.formatClockTime
import com.mibandhub.app.util.formatDateTime
import com.mibandhub.app.util.formatEpochDayShort
import com.mibandhub.app.util.formatSeconds
import kotlin.math.roundToInt

/** 记录筛选范围：天数，-1 = 全部。 */
private val RECORD_RANGES = listOf(7, 30, 90, -1)
private val RECORD_RANGE_LABELS = listOf("近7天", "近30天", "近90天", "全部")

/** 历史 / 统计的来源筛选，和上面的时间范围叠加生效。 */
private val SOURCE_FILTERS = listOf("全部来源", "手环", "手机闪光灯")

/**
 * 心率页：真正的测量流程都交给 [HeartRateViewModel]（手环）和
 * [PpgMeasureViewModel]（手机闪光灯），这里只负责「把状态画出来」+「把点击转成动作」。
 *
 * 两条来源的测量记录在 [HeartRateViewModel.records] 里合并成一条时间线，
 * 顶部大数字、趋势、统计、历史都吃同一份数据；每条记录带来源徽标，
 * 也可以按来源筛选 —— 合并不等于把出处抹掉。
 */
@Composable
fun HeartRateScreen(vm: HeartRateViewModel) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedSec.collectAsStateWithLifecycle()
    val lastBpm by vm.lastBpm.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val lastRecord by vm.lastRecord.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()

    // 测量方式 / 范围筛选 / 展开状态：纯界面状态
    var modeIndex by remember { mutableStateOf(0) } // 0 = 手环, 1 = 手机闪光灯
    var rangeIndex by remember { mutableStateOf(0) }
    var sourceIndex by remember { mutableStateOf(0) }
    var expandedRecordAt by remember { mutableStateOf<Long?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showPpgOverlay by remember { mutableStateOf(false) }
    val ppgVm: PpgMeasureViewModel = viewModel()

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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Text("心率", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // ---- 测量方式：两种来源共用下面的大数字和历史 ----
            FilterChipRow(
                options = listOf("手环测量", "手机闪光灯"),
                selectedIndex = modeIndex,
                accent = if (modeIndex == 0) PulseRed else Mint,
                onSelect = { modeIndex = it },
            )
            Spacer(Modifier.height(12.dp))

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
                        statusLabel(phase, elapsed, lastRecord?.finishedAtMillis),
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
                // 大数字是「最近一次测量」，不管哪条路测的 —— 来源必须写清楚
                lastRecord?.let { record ->
                    Spacer(Modifier.height(8.dp))
                    SourceBadge(record.source)
                }

                if (modeIndex == 0) {
                    Spacer(Modifier.height(16.dp))
                    if (!configured) {
                        NoticeBanner(
                            title = "还没有配对手环",
                            tone = NotifyAmber,
                            detail = "本地没有设备 MAC 和 AuthKey，测心率时连不上任何手环。",
                            hint = "先到「设备」页完成配对。AuthKey 可以用 just fetch 从官方 App 的日志里直接读出来。",
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    if (error != null) {
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
                        Spacer(Modifier.height(12.dp))
                    }
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
                } else {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.FlashlightOn,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "用摄像头 + 闪光灯做光学测量",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "不需要手环。手指盖住摄像头和闪光灯，保持不动约 20 秒即可出结果；" +
                            "读数会记入下面的历史，并标注来源。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val lastPpg = records.firstOrNull { it.source == HeartRateSource.PHONE_PPG }
                    if (lastPpg != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "上次闪光灯测量：${lastPpg.bpm} BPM · ${formatClockTime(lastPpg.finishedAtMillis)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = StepBlue,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { showPpgOverlay = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Mint,
                            contentColor = Color(0xFF08120E),
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("开始闪光灯测量")
                    }
                }
            }

            // ---- 上次测量的明细：什么时候测的、等了多久、哪来的 ----
            lastRecord?.let { record ->
                Spacer(Modifier.height(12.dp))
                SectionCard(title = "测量明细", accent = PulseRed) {
                    KeyValueRow("读数", "${record.bpm} BPM", valueColor = PulseRed)
                    KeyValueRow("来源", record.source.label, valueColor = sourceColor(record.source))
                    KeyValueRow("测量时刻", formatDateTime(record.finishedAtMillis))
                    KeyValueRow("耗时", formatSeconds(record.durationSec))
                    record.quality?.let {
                        KeyValueRow("信号质量", "$it / 100", valueColor = Mint)
                    }
                }
            }

            val rangeDays = RECORD_RANGES[rangeIndex]
            val filtered = remember(records, rangeDays, sourceIndex) {
                val cutoff = System.currentTimeMillis() - rangeDays * 86_400_000L
                records.filter { record ->
                    val inRange = rangeDays < 0 || record.finishedAtMillis >= cutoff
                    val inSource = when (sourceIndex) {
                        1 -> record.source == HeartRateSource.BAND
                        2 -> record.source == HeartRateSource.PHONE_PPG
                        else -> true
                    }
                    inRange && inSource
                }
            }
            // 「与上一次的差值」相对完整历史算，不随筛选缩水。
            val deltaByTimestamp = remember(records) {
                records.mapIndexed { index, record ->
                    val previous = if (index > 0) records.getOrNull(index - 1) else null
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
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "最早一次",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LegendDot(PulseRed)
                            Spacer(Modifier.width(4.dp))
                            Text("手环", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            LegendDot(StepBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("手机闪光灯", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "共 ${trend.size} 次 · 最新在右 · 逐条来源见下方记录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    if (records.isNotEmpty()) {
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
                Spacer(Modifier.height(8.dp))
                FilterChipRow(
                    options = SOURCE_FILTERS,
                    selectedIndex = sourceIndex,
                    accent = StepBlue,
                    onSelect = {
                        sourceIndex = it
                        expandedRecordAt = null
                    },
                )
                Spacer(Modifier.height(10.dp))

                if (filtered.isEmpty()) {
                    Text(
                        "这个筛选下还没有测量记录 —— 换一个范围，或者先测一次。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val dayGroups = remember(filtered) {
                        filtered.groupBy { epochDayOf(it.finishedAtMillis) }
                    }
                    dayGroups.forEach { (epochDay, dayRecords) ->
                        val dayAvg = dayRecords.map { it.bpm }.average().roundToInt()
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
                                "${dayRecords.size} 次 · 均 $dayAvg",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        dayRecords.forEachIndexed { index, record ->
                            RecordRow(
                                record = record,
                                delta = deltaByTimestamp[record.finishedAtMillis],
                                expanded = expandedRecordAt == record.finishedAtMillis,
                                showDivider = index < dayRecords.lastIndex,
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

        if (showPpgOverlay) {
            PpgMeasureOverlay(vm = ppgVm, onClose = { showPpgOverlay = false })
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部测量记录？") },
            text = {
                Text("共 ${records.size} 条（含手环与手机闪光灯两种来源），清空后无法恢复。设备配对信息不受影响。")
            },
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

/** 一条测量记录：收起时时间 + 来源徽标 + 读数，展开后把上下文全摆出来。 */
@Composable
private fun RecordRow(
    record: HeartMeasureRecord,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    formatClockTime(record.finishedAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                SourceBadge(record.source)
            }
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
                KeyValueRow("来源", record.source.label, valueColor = sourceColor(record.source))
                KeyValueRow("测量时刻", formatDateTime(record.finishedAtMillis))
                KeyValueRow("耗时", formatSeconds(record.durationSec))
                record.quality?.let { KeyValueRow("信号质量", "$it / 100", valueColor = Mint) }
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

/** 每条记录都带的来源标记 —— 合并展示时这是最关键的一眼信息。 */
@Composable
private fun SourceBadge(source: HeartRateSource) {
    val color = sourceColor(source)
    Text(
        source.label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(7.dp).background(color, CircleShape))
}

private fun sourceColor(source: HeartRateSource): Color = when (source) {
    HeartRateSource.BAND -> PulseRed
    HeartRateSource.PHONE_PPG -> StepBlue
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

private fun connectionLabel(state: com.mibandhub.app.ble.ConnectionState): String = when (state) {
    is com.mibandhub.app.ble.ConnectionState.Connected,
    is com.mibandhub.app.ble.ConnectionState.Authenticated,
    -> "已连接"

    is com.mibandhub.app.ble.ConnectionState.Connecting,
    is com.mibandhub.app.ble.ConnectionState.Discovering,
    -> "正在连接"

    is com.mibandhub.app.ble.ConnectionState.Failed -> "连接失败"

    com.mibandhub.app.ble.ConnectionState.Disconnected -> "未连接"
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
