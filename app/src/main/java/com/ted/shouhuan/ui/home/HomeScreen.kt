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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ted.shouhuan.data.DemoData
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
import com.ted.shouhuan.util.formatDuration

@Composable
fun HomeScreen() {
    val status = DemoData.bandStatus()
    val day = remember { DemoData.heartRateDay() }
    val sleep = DemoData.lastNight()

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
                    StatusDot(status.connected)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (status.connected) "${status.name} · 已连接" else "未连接",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BatteryChip(status.batteryPercent)
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
                        "${DemoData.latestBpm()}",
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
                Spacer(Modifier.height(14.dp))
                Sparkline(
                    values = day.map { it.bpm.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    color = PulseRed,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 步数 / 电量 并排 ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(Modifier.weight(1f), title = "今日步数", accent = StepBlue) {
                MetricTile(
                    value = "%,d".format(DemoData.steps()),
                    unit = "步",
                    label = "目标 %,d".format(DemoData.stepsGoal()),
                    accent = StepBlue,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ProgressBar(
                    progress = DemoData.steps().toFloat() / DemoData.stepsGoal(),
                    color = StepBlue,
                )
            }
            SectionCard(Modifier.weight(1f), title = "设备电量", accent = NotifyAmber) {
                MetricTile(
                    value = "${status.batteryPercent}",
                    unit = "%",
                    label = if (status.batteryPercent < 20) "该充电了" else "约可用 9 天",
                    accent = NotifyAmber,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ProgressBar(
                    progress = status.batteryPercent / 100f,
                    color = if (status.batteryPercent < 20) PulseRed else NotifyAmber,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 昨夜睡眠 ----
        SectionCard(title = "昨夜睡眠", accent = SleepIndigo) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    formatDuration(sleep.totalMinutes),
                    style = MaterialTheme.typography.titleLarge,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${sleep.score}",
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
                segments = sleep.shares.map { SleepStageSegment(it.stage, it.minutes) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            KeyValueRow("入睡 / 醒来", "${sleep.bedTime} — ${sleep.wakeTime}")

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                sleep.shares.forEach { share ->
                    StageLegend(
                        color = share.stage.displayColor(),
                        label = share.stage.label(),
                        minutes = share.minutes,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BatteryChip(percent: Int) {
    val color = when {
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
        Text(
            "$percent%",
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
