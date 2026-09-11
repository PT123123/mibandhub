package com.ted.shouhuan.ui.sleep

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ted.shouhuan.data.DemoData
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.MiniBarChart
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.SleepStageBar
import com.ted.shouhuan.ui.components.SleepStageSegment
import com.ted.shouhuan.ui.components.displayColor
import com.ted.shouhuan.ui.components.label
import com.ted.shouhuan.ui.theme.SleepIndigo
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.util.formatDuration
import com.ted.shouhuan.util.formatDurationShort

@Composable
fun SleepScreen() {
    val night = DemoData.lastNight()
    val week = remember { DemoData.sleepWeek() }
    val weekLabels = remember { DemoData.sleepWeekLabels() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("睡眠", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // ---- 昨晚 ----
        SectionCard(title = night.dateLabel, accent = SleepIndigo) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    formatDuration(night.totalMinutes),
                    style = MaterialTheme.typography.titleLarge,
                )
                ScorePill(night.score)
            }

            Spacer(Modifier.height(16.dp))
            SleepStageBar(
                segments = night.shares.map { SleepStageSegment(it.stage, it.minutes) },
                modifier = Modifier.fillMaxWidth(),
                height = 16.dp,
            )
            Spacer(Modifier.height(16.dp))

            KeyValueRow("入睡", night.bedTime)
            KeyValueRow("醒来", night.wakeTime)
        }

        Spacer(Modifier.height(12.dp))

        // ---- 分期明细 ----
        SectionCard(title = "分期明细", accent = SleepIndigo) {
            night.shares.sortedByDescending { it.minutes }.forEachIndexed { index, share ->
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

        // ---- 近 7 天 ----
        SectionCard(title = "近 7 天", accent = StepBlue) {
            MiniBarChart(
                values = week,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                color = SleepIndigo.copy(alpha = 0.45f),
                highlightIndex = week.lastIndex,
                highlightColor = SleepIndigo,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                weekLabels.forEachIndexed { index, label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (index == weekLabels.lastIndex) {
                            SleepIndigo
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val avg = week.average().toInt()
            KeyValueRow("平均时长", formatDurationShort(avg), valueColor = SleepIndigo)
        }

        Spacer(Modifier.height(24.dp))
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
