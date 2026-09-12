package com.ted.shouhuan.ui.sleep

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.data.SleepStage
import com.ted.shouhuan.data.SleepStageShare
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
import com.ted.shouhuan.ui.theme.SleepIndigo
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.util.formatDuration
import com.ted.shouhuan.util.formatDurationShort
import com.ted.shouhuan.util.formatEpochDay
import com.ted.shouhuan.util.formatEpochDayShort
import com.ted.shouhuan.util.minuteOfDayToClock
import java.time.LocalDate
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

    var rangeIndex by remember { mutableStateOf(0) }
    // null = 最新一晚；点了柱子之后才是具体某天
    var selectedEpochDay by remember { mutableStateOf<Long?>(null) }

    // ---- 导出：小数据进剪贴板，大数据走系统文件选择器 ----
    var exportHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                exportHint = if (vm.exportToFile(uri)) {
                    "已写入文件（${vm.nightCount()} 晚）"
                } else {
                    "写入文件失败，换个位置再试一次"
                }
            }
        }
    }
    val onExport: () -> Unit = {
        if (vm.exportToClipboard()) {
            exportHint = "已复制 ${vm.nightCount()} 晚到剪贴板"
        } else {
            // 剪贴板放不下 —— 让用户挑个位置写成 CSV 文件
            exportLauncher.launch(vm.suggestedFileName())
        }
    }

    val latest = nights.firstOrNull()
    val selected = selectedEpochDay?.let { day -> nights.firstOrNull { it.epochDay == day } } ?: latest

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("睡眠", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

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

        // ---- 分期明细 ----
        SectionCard(title = "分期明细", accent = SleepIndigo) {
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
            exportHint = exportHint,
            onExport = onExport,
        )

        Spacer(Modifier.height(24.dp))
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
                    formatDuration(night.totalMinutes),
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

        KeyValueRow("入睡", minuteOfDayToClock(night.bedMinutes))
        KeyValueRow("醒来", minuteOfDayToClock(night.wakeMinutes))
        KeyValueRow("夜间清醒", formatDurationShort(night.awakeMinutes))
    }
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
    exportHint: String?,
    onExport: () -> Unit,
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
        headerTrailing = {
            TextButton(onClick = onExport) {
                Text("导出 CSV", color = SleepIndigo)
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
        if (exportHint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                exportHint,
                style = MaterialTheme.typography.labelMedium,
                color = SleepIndigo,
            )
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
