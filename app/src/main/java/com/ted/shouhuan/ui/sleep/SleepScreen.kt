@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ted.shouhuan.ui.sleep

import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.data.SleepStage
import com.ted.shouhuan.data.SleepStageShare
import com.ted.shouhuan.service.SleepSyncPhase
import com.ted.shouhuan.ui.components.CollapsibleSection
import com.ted.shouhuan.ui.components.FilterChipRow
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.MetricTile
import com.ted.shouhuan.ui.components.MiniBarChart
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.SleepStageBar
import com.ted.shouhuan.ui.components.SleepStageSegment
import com.ted.shouhuan.ui.components.displayColor
import com.ted.shouhuan.ui.components.label
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.SleepIndigo
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.util.formatDurationShort
import com.ted.shouhuan.util.formatHours
import com.ted.shouhuan.util.formatEpochDay
import com.ted.shouhuan.util.formatEpochDayShort
import com.ted.shouhuan.util.minuteOfDayToClock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/** 范围筛选：天数，-1 = 全部。 */
private val RANGE_OPTIONS = listOf(7, 14, 30, 90, -1)
private val RANGE_LABELS = listOf("近7天", "近14天", "近30天", "近90天", "全部")

/** 达标线：单晚总睡眠 ≥ 7 小时。 */
private const val GOOD_NIGHT_MINUTES = 7 * 60

@Composable
fun SleepScreen(vm: SleepViewModel) {
    val nights by vm.nights.collectAsStateWithLifecycle()
    val sleepSync by vm.sleepSync.collectAsStateWithLifecycle()

    // 进页面自动拉一次睡眠（SleepSyncManager 内部带去抖，反复切 tab 不折腾手环）
    LaunchedEffect(Unit) { vm.syncOnEnter() }
    val syncing = sleepSync is SleepSyncPhase.Syncing

    var rangeIndex by remember { mutableStateOf(0) }
    // null = 最新一晚；点了柱子之后才是具体某天
    var selectedEpochDay by remember { mutableStateOf<Long?>(null) }

    // ---- 导出：走系统「保存文件」对话框，反馈用 Toast（不受折叠区影响）----
    // 之前小数据直接进剪贴板、提示条塞在折叠区深处，点完看起来「毫无反应」；
    // 现在「导出」一律落成文件，选择器打不开的 ROM 退回剪贴板兜底
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = vm.exportToFile(uri)
                Toast.makeText(
                    context,
                    if (ok) "已导出 ${vm.nightCount()} 晚睡眠记录" else "写入文件失败，换个位置再试一次",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val onExport: () -> Unit = {
        try {
            exportLauncher.launch(vm.suggestedFileName())
        } catch (e: Exception) {
            // 个别 ROM 没有 SAF 文档选择器 —— 退回剪贴板，至少数据出得去
            val msg = if (vm.exportToClipboard()) {
                "系统文件选择器不可用，已复制 ${vm.nightCount()} 晚到剪贴板"
            } else {
                "导出失败：文件选择器打不开，剪贴板也放不下"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ---- 导入：和导出一一对应，走系统「打开文件」对话框，选回刚导出的 CSV ----
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val res = vm.importFromFile(uri)
                Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val onImport: () -> Unit = {
        try {
            importLauncher.launch(arrayOf("text/csv", "*/*"))
        } catch (e: Exception) {
            Toast.makeText(context, "系统文件选择器不可用，无法导入", Toast.LENGTH_LONG).show()
        }
    }

    val latest = nights.firstOrNull()
    val selected = selectedEpochDay?.let { day -> nights.firstOrNull { it.epochDay == day } } ?: latest

    // 顶部下拉 = 触发一次手动同步（总是真的跑），同步期间转圈
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = { vm.syncNow() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Text("睡眠", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // ---- 同步手环数据：和设备页的按钮同一套流程与状态（SleepSyncManager）----
            SyncCard(sleepSync = sleepSync, onSync = { vm.syncNow() })
            Spacer(Modifier.height(12.dp))

        if (selected == null) {
            NoticeBanner(
                title = "还没有睡眠数据",
                tone = SleepIndigo,
                detail = "连上手环同步一次后，这里会出现第一晚的记录。",
            )
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        // ---- 选中夜晚（默认最新一晚，点趋势图可切换）----
        NightDetailCard(night = selected, isLatest = selected.epochDay == latest?.epochDay)

        Spacer(Modifier.height(12.dp))

        // ---- 分期明细：默认收起，主卡的分期条已经能看个大概 ----
        CollapsibleSection(
            title = "分期明细",
            accent = SleepIndigo,
            initiallyExpanded = false,
        ) {
            selected.stages().sortedByDescending { it.minutes }.forEachIndexed { index, share ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(10.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(share.stage.displayColor()),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(share.stage.label(), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        formatDurationShort(share.minutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 趋势：范围内每一晚都是一根可点的柱子 ----
        TrendCard(
            nights = nights,
            rangeIndex = rangeIndex,
            selectedEpochDay = selected?.epochDay,
            onRangeChange = {
                rangeIndex = it
                // 切了范围后原选中日可能不在范围内，落回「最新一晚」
                selectedEpochDay = null
            },
            onBarTap = { night -> selectedEpochDay = night.epochDay },
        )

        Spacer(Modifier.height(12.dp))

        // ---- 详细数据：统计 + 每晚明细，可收缩 ----
        DetailedDataSection(
            nights = nights,
            rangeIndex = rangeIndex,
            onExport = onExport,
            onImport = onImport,
        )

        Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 同步手环数据（与设备页「同步手环数据」共用 SleepSyncManager 的流程和状态）
// ---------------------------------------------------------------------------

@Composable
private fun SyncCard(sleepSync: SleepSyncPhase, onSync: () -> Unit) {
    OutlinedButton(
        onClick = onSync,
        enabled = sleepSync !is SleepSyncPhase.Syncing,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(if (sleepSync is SleepSyncPhase.Syncing) "同步中…" else "同步手环数据")
    }
    when (val phase = sleepSync) {
        is SleepSyncPhase.Syncing -> Text(
            "接收中 ${phase.received} / ${phase.expected} 字节" +
                if (phase.expected <= 0) "（等待手环应答…）" else "",
            style = MaterialTheme.typography.labelMedium,
            color = StepBlue,
        )

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

        SleepSyncPhase.Idle -> {}
    }
}

// ---------------------------------------------------------------------------
// 选中夜晚的主卡
// ---------------------------------------------------------------------------

@Composable
private fun NightDetailCard(night: SleepNightRecord, isLatest: Boolean) {
    SectionCard(title = formatEpochDay(night.epochDay), accent = SleepIndigo) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    formatHours(night.totalMinutes),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (!isLatest) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在看历史记录 · 非最新一晚",
                        style = MaterialTheme.typography.labelMedium,
                        color = SleepIndigo,
                    )
                }
            }
            ScorePill(night.score)
        }

        Spacer(Modifier.height(16.dp))
        SleepStageBar(
            segments = night.stages().map { SleepStageSegment(it.stage, it.minutes) },
            modifier = Modifier.fillMaxWidth(),
            height = 16.dp,
        )
        Spacer(Modifier.height(16.dp))

        // 夜间睡眠基本都跨天（入睡 23:41、醒来次日 08:00），入睡和醒来各标日期，
        // 一眼能分清是哪两天；已醒与桌面控件同口径 = 现在 − 该夜起床时间。
        KeyValueRow(
            "入睡",
            "${formatEpochDay(bedDayEpoch(night))} ${minuteOfDayToClock(night.bedMinutes)}",
        )
        KeyValueRow(
            "醒来",
            "${formatEpochDay(night.epochDay)} ${minuteOfDayToClock(night.wakeMinutes)}",
        )
        KeyValueRow("已醒", formatDurationShort(sinceWakeMinutes(night)))
    }
}

/** 入睡那天：醒来那天的第几分钟还没到起床分钟数 → 跨到前一天（23:41 入睡、08:00 醒来）。 */
private fun bedDayEpoch(night: SleepNightRecord): Long =
    if (night.bedMinutes > night.wakeMinutes) night.epochDay - 1 else night.epochDay

/** 从该夜起床时刻到现在过了多久（分钟）。epochDay 是醒来那天。 */
private fun sinceWakeMinutes(night: SleepNightRecord): Int {
    val wake = LocalDate.ofEpochDay(night.epochDay)
        .atStartOfDay()
        .plusMinutes(night.wakeMinutes.toLong())
    return Duration.between(wake, LocalDateTime.now()).toMinutes().coerceAtLeast(0).toInt()
}

@Composable
private fun ScorePill(score: Int) {
    val color = when {
        score >= 85 -> SleepIndigo
        score >= 70 -> StepBlue
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$score",
            style = MaterialTheme.typography.titleLarge,
            color = color,
        )
        Text(
            "睡眠得分",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 趋势卡：范围筛选 + 可点柱状图
// ---------------------------------------------------------------------------

@Composable
private fun TrendCard(
    nights: List<SleepNightRecord>,
    rangeIndex: Int,
    selectedEpochDay: Long?,
    onRangeChange: (Int) -> Unit,
    onBarTap: (SleepNightRecord) -> Unit,
) {
    val days = RANGE_OPTIONS[rangeIndex]
    val todayEpoch = remember { LocalDate.now().toEpochDay() }
    val rangeNights = remember(nights, days, todayEpoch) {
        nights.filter { days < 0 || it.epochDay >= todayEpoch - (days - 1) }
    }
    // 柱子从左到右按时间正序，最新一晚在最右
    val ascending = remember(rangeNights) { rangeNights.sortedBy { it.epochDay } }
    val selectedIndex = ascending.indexOfFirst { it.epochDay == selectedEpochDay }

    SectionCard(title = "趋势 · ${RANGE_LABELS[rangeIndex]}", accent = StepBlue) {
        Text(
            "点柱子可以查看那一晚",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FilterChipRow(
            options = RANGE_LABELS,
            selectedIndex = rangeIndex,
            accent = SleepIndigo,
            onSelect = onRangeChange,
        )
        Spacer(Modifier.height(14.dp))

        MiniBarChart(
            values = ascending.map { it.totalMinutes.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            color = SleepIndigo.copy(alpha = 0.45f),
            selectedIndex = selectedIndex,
            selectedColor = SleepIndigo,
            onBarTap = { index -> onBarTap(ascending[index]) },
        )
        Spacer(Modifier.height(10.dp))
        ChartLabels(ascending = ascending, selectedIndex = selectedIndex)

        Spacer(Modifier.height(12.dp))
        val avg = rangeNights.map { it.totalMinutes }.average().takeIf { rangeNights.isNotEmpty() }
        KeyValueRow(
            "平均时长",
            avg?.let { formatDurationShort(it.roundToInt()) } ?: "--",
            valueColor = SleepIndigo,
        )
    }
}

/** 柱子少时全部标日期，多时只标首尾 + 选中项。 */
@Composable
private fun ChartLabels(ascending: List<SleepNightRecord>, selectedIndex: Int) {
    fun shortLabel(epochDay: Long): String {
        val d = LocalDate.ofEpochDay(epochDay)
        return "${d.monthValue}/${d.dayOfMonth}"
    }
    if (ascending.size <= 14) {
        Row(Modifier.fillMaxWidth()) {
            ascending.forEachIndexed { index, night ->
                Text(
                    shortLabel(night.epochDay),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == selectedIndex) {
                        SleepIndigo
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                shortLabel(ascending.first().epochDay),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedIndex >= 0) {
                Text(
                    shortLabel(ascending[selectedIndex].epochDay),
                    style = MaterialTheme.typography.labelMedium,
                    color = SleepIndigo,
                )
            }
            Text(
                shortLabel(ascending.last().epochDay),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 详细数据（可收缩大项）：区间统计 + 每晚明细
// ---------------------------------------------------------------------------

@Composable
private fun DetailedDataSection(
    nights: List<SleepNightRecord>,
    rangeIndex: Int,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val days = RANGE_OPTIONS[rangeIndex]
    val todayEpoch = remember { LocalDate.now().toEpochDay() }
    val rangeNights = remember(nights, days, todayEpoch) {
        nights.filter { days < 0 || it.epochDay >= todayEpoch - (days - 1) }
    }
    // 每晚明细里展开哪一行（-1 = 全部收起）
    var expandedEpochDay by remember { mutableStateOf<Long?>(null) }

    CollapsibleSection(
        title = "详细数据",
        accent = SleepIndigo,
        badge = "${rangeNights.size} 晚",
        initiallyExpanded = false, // 统计 + 每晚明细都收进来，进页面先看主卡和趋势
        headerTrailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onImport) {
                    Text("导入 CSV", color = SleepIndigo)
                }
                TextButton(onClick = onExport) {
                    Text("导出 CSV", color = SleepIndigo)
                }
            }
        },
    ) {
        val stats = remember(rangeNights) { SleepRangeStats.of(rangeNights) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricTile(
                stats.avgTotal?.let { formatDurationShort(it) } ?: "--",
                null,
                "平均时长",
                accent = SleepIndigo,
            )
            MetricTile(
                stats.avgScore?.toString() ?: "--",
                null,
                "平均得分",
                accent = StepBlue,
            )
            MetricTile(
                stats.deepPercent?.let { "$it%" } ?: "--",
                null,
                "深睡占比",
                accent = SleepIndigo,
                valueColor = SleepIndigo,
            )
        }

        Spacer(Modifier.height(12.dp))
        KeyValueRow("快速眼动占比", stats.remPercent?.let { "$it%" } ?: "--")
        KeyValueRow("清醒时间占比", stats.awakePercent?.let { "$it%" } ?: "--")
        KeyValueRow(
            "达标（≥7小时）",
            stats.goodNights?.let { "${it.first} / ${it.second} 晚" } ?: "--",
        )
        KeyValueRow(
            "平均入睡",
            stats.avgBedClock?.let { "$it（±${stats.bedVariationMin ?: 0} 分）" } ?: "--",
        )
        KeyValueRow(
            "最长 / 最短",
            stats.longest?.let { l ->
                "${formatDurationShort(l.totalMinutes)} / " +
                    (stats.shortest?.let { formatDurationShort(it.totalMinutes) } ?: "--")
            } ?: "--",
        )
        KeyValueRow("记录总晚数", "${rangeNights.size} 晚")
        rangeNights.lastOrNull()?.let {
            KeyValueRow("最早记录", formatEpochDay(it.epochDay))
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        Spacer(Modifier.height(14.dp))

        Text(
            "每晚明细（点开看分期构成）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        rangeNights.forEachIndexed { index, night ->
            val previous = rangeNights.getOrNull(index - 1) // 更新一晚
            NightRow(
                night = night,
                previous = previous,
                expanded = expandedEpochDay == night.epochDay,
                onToggle = {
                    expandedEpochDay =
                        if (expandedEpochDay == night.epochDay) null else night.epochDay
                },
            )
            if (index < rangeNights.lastIndex) {
                HorizontalDivider(
                    Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun NightRow(
    night: SleepNightRecord,
    previous: SleepNightRecord?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatEpochDayShort(night.epochDay),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDurationShort(night.totalMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${night.score}",
                    style = MaterialTheme.typography.titleMedium,
                    color = SleepIndigo,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .rotate(if (expanded) 180f else 0f),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 10.dp)) {
                SleepStageBar(
                    segments = night.stages().map { SleepStageSegment(it.stage, it.minutes) },
                    modifier = Modifier.fillMaxWidth(),
                    height = 10.dp,
                )
                Spacer(Modifier.height(10.dp))
                night.stages().forEach { share ->
                    KeyValueRow(
                        share.stage.label(),
                        "${formatDurationShort(share.minutes)}（${percent(share.minutes, night.totalMinutes)}%）",
                    )
                }
                KeyValueRow("入睡 / 醒来", "${minuteOfDayToClock(night.bedMinutes)} — ${minuteOfDayToClock(night.wakeMinutes)}")
                KeyValueRow("睡眠得分", "${night.score}")
                // 只有真的紧挨着前一晚时才比，中间缺了几天就比得没意义了
                if (previous != null && previous.epochDay == night.epochDay + 1) {
                    val delta = night.totalMinutes - previous.totalMinutes
                    KeyValueRow(
                        "比前一晚",
                        if (delta >= 0) "+${formatDurationShort(delta)}" else "-${formatDurationShort(-delta)}",
                        valueColor = if (delta >= 0) StepBlue else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 统计计算
// ---------------------------------------------------------------------------

private fun percent(part: Int, total: Int): Int =
    if (total <= 0) 0 else (part * 100f / total).roundToInt()

/** 一晚的四段分期，清醒排在最前（和主卡的分期条顺序一致）。 */
private fun SleepNightRecord.stages(): List<SleepStageShare> = listOf(
    SleepStageShare(SleepStage.AWAKE, awakeMinutes),
    SleepStageShare(SleepStage.REM, remMinutes),
    SleepStageShare(SleepStage.LIGHT, lightMinutes),
    SleepStageShare(SleepStage.DEEP, deepMinutes),
)

/**
 * 一个范围（近 N 天 / 全部）的聚合统计。
 *
 * 入睡时刻的均值和波动要处理跨零点：直接对 23:41 和 00:10 取平均会得到荒谬的
 * 「中午 11:55」，所以统一折算成「距前一天 18:00 过了多少分钟」再算。
 */
private data class SleepRangeStats(
    val avgTotal: Int?,
    val avgScore: Int?,
    val deepPercent: Int?,
    val remPercent: Int?,
    val awakePercent: Int?,
    val goodNights: Pair<Int, Int>?,
    val avgBedClock: String?,
    val bedVariationMin: Int?,
    val longest: SleepNightRecord?,
    val shortest: SleepNightRecord?,
) {
    companion object {
        /** 折算基准：前一天 18:00。 */
        private const val BED_ANCHOR = 18 * 60

        fun of(nights: List<SleepNightRecord>): SleepRangeStats {
            if (nights.isEmpty()) return SleepRangeStats(null, null, null, null, null, null, null, null, null, null)

            val totals = nights.map { it.totalMinutes }
            val deep = nights.sumOf { it.deepMinutes }
            val rem = nights.sumOf { it.remMinutes }
            val awake = nights.sumOf { it.awakeMinutes }
            val grandTotal = nights.sumOf { it.totalMinutes }

            val bedShifts = nights.map { ((it.bedMinutes - BED_ANCHOR) + 1440) % 1440 }
            val avgShift = bedShifts.average()
            val stdShift = sqrt(bedShifts.sumOf { (it - avgShift) * (it - avgShift) / bedShifts.size })
            val avgBed = ((avgShift + BED_ANCHOR).roundToInt() % 1440 + 1440) % 1440

            return SleepRangeStats(
                avgTotal = totals.average().roundToInt(),
                avgScore = nights.map { it.score }.average().roundToInt(),
                deepPercent = percent(deep, grandTotal),
                remPercent = percent(rem, grandTotal),
                awakePercent = percent(awake, grandTotal),
                goodNights = nights.count { it.totalMinutes >= GOOD_NIGHT_MINUTES } to nights.size,
                avgBedClock = minuteOfDayToClock(avgBed),
                bedVariationMin = stdShift.roundToInt(),
                longest = nights.maxByOrNull { it.totalMinutes },
                shortest = nights.minByOrNull { it.totalMinutes },
            )
        }
    }
}
