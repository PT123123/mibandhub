package com.ted.shouhuan.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.MetricTile
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.SleepStageBar
import com.ted.shouhuan.ui.components.SleepStageSegment
import com.ted.shouhuan.ui.components.Sparkline
import com.ted.shouhuan.ui.components.StatusDot
import com.ted.shouhuan.ui.components.displayColor
import com.ted.shouhuan.ui.components.label
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.SleepIndigo
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.util.formatClockTime
import com.ted.shouhuan.util.formatDuration
import com.ted.shouhuan.util.minuteOfDayToClock

/**
 * 首页：设备状态、心率、步数、睡眠一屏总览。
 * 全部走 [HomeViewModel] 的共享会话与本地存储 —— 和设备页、心率页、通知栏同源，
 * 不再有「首页一个数、别处另一个数」的两张皮。
 */
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

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
                Text("手环管家", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state.connected)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (state.connected) "${state.deviceName ?: "手环"} · 已连接" else "未连接",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BatteryChip(state.battery)
        }

        Spacer(Modifier.height(18.dp))

        // ---- 实时心率（主视觉）----
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            PulseRed.copy(alpha = 0.10f),
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Column {
                Text(
                    "实时心率",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        state.bpm?.toString() ?: "--",
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
                Spacer(Modifier.height(6.dp))
                Text(
                    state.lastMeasuredAt?.let { "上次测量 · ${formatClockTime(it)}" } ?: "还没有测量记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                // 近几次测量的趋势；只有一两次时画不出趋势，干脆不画
                if (state.bpmTrend.size >= 2) {
                    Sparkline(
                        values = state.bpmTrend.map { it.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        color = PulseRed,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 步数 / 电量 并排 ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(Modifier.weight(1f), title = "今日步数", accent = StepBlue) {
                MetricTile(
                    value = state.steps?.let { "%,d".format(it) } ?: "--",
                    unit = "步",
                    label = "目标 %,d".format(vm.stepsGoal.value),
                    accent = StepBlue,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ProgressBar(
                    progress = (state.steps ?: 0).toFloat() / vm.stepsGoal.value,
                    color = StepBlue,
                )
            }
            SectionCard(Modifier.weight(1f), title = "设备电量", accent = NotifyAmber) {
                val battery = state.battery
                MetricTile(
                    value = battery?.toString() ?: "--",
                    unit = "%",
                    label = when (battery) {
                        null -> "连接手环后可见"
                        else -> if (battery < 20) "该充电了" else "约可用 9 天"
                    },
                    accent = NotifyAmber,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ProgressBar(
                    progress = (battery ?: 0) / 100f,
                    color = if ((battery ?: 100) < 20) PulseRed else NotifyAmber,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 昨夜睡眠 ----
        val night = state.lastNight
        SectionCard(title = "昨夜睡眠", accent = SleepIndigo) {
            if (night == null) {
                Text(
                    "暂无睡眠记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                NightSummary(night, stagesOf = { vm.stagesOf(it) })
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NightSummary(
    night: SleepNightRecord,
    stagesOf: (SleepNightRecord) -> List<com.ted.shouhuan.data.SleepStageShare>,
) {
    val shares = stagesOf(night)
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                formatDuration(night.totalMinutes),
                style = MaterialTheme.typography.titleLarge,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${night.score}",
                    style = MaterialTheme.typography.titleLarge,
                    color = SleepIndigo,
                )
                Text(
                    "睡眠得分",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SleepStageBar(
            segments = shares.map { SleepStageSegment(it.stage, it.minutes) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        KeyValueRow(
            "入睡 / 醒来",
            "${minuteOfDayToClock(night.bedMinutes)} — ${minuteOfDayToClock(night.wakeMinutes)}",
        )

        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            shares.forEach { share ->
                StageLegend(
                    color = share.stage.displayColor(),
                    label = share.stage.label(),
                    minutes = share.minutes,
                )
            }
        }
    }
}

@Composable
private fun BatteryChip(percent: Int?) {
    val color = when {
        percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        percent < 20 -> PulseRed
        percent < 40 -> NotifyAmber
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (percent != null) {
            Box(
                Modifier
                    .width(18.dp)
                    .height(9.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Transparent),
            ) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(15.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color.copy(alpha = 0.25f)),
                    )
                    Spacer(Modifier.width(1.dp))
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(color.copy(alpha = 0.25f)),
                    )
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(percent / 100f.coerceAtLeast(0.08f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            percent?.let { "$it%" } ?: "--",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun ProgressBar(progress: Float, color: Color) {
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
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
private fun StageLegend(color: Color, label: String, minutes: Int) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${minutes / 60}h${minutes % 60}m",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
