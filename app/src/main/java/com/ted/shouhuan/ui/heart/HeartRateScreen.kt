package com.ted.shouhuan.ui.heart

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.DemoData
import com.ted.shouhuan.ui.components.MetricTile
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.Sparkline
import com.ted.shouhuan.ui.components.StatusDot
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.StepBlue

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
    val day = remember { DemoData.heartRateDay() }
    val phase by vm.phase.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedSec.collectAsStateWithLifecycle()
    val lastBpm by vm.lastBpm.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()

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
                    statusLabel(phase, elapsed),
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

        Spacer(Modifier.height(12.dp))

        // ---- 今日曲线（仍是演示数据，等历史同步接上再换）----
        SectionCard(title = "今日曲线", accent = PulseRed) {
            Sparkline(
                values = day.map { it.bpm.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                color = PulseRed,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 统计 ----
        SectionCard(title = "今日统计", accent = StepBlue) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricTile("${DemoData.restingBpm()}", "BPM", "静息", accent = StepBlue)
                MetricTile("${DemoData.avgBpm()}", "BPM", "平均", accent = StepBlue)
                MetricTile(
                    "${DemoData.maxBpm()}",
                    "BPM",
                    "最高",
                    accent = PulseRed,
                    valueColor = PulseRed,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 区间分布 ----
        SectionCard(title = "区间分布", accent = PulseRed) {
            ZoneRow("过缓", "< 60", 0.08f, MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            ZoneRow("正常", "60 – 100", 0.84f, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            ZoneRow("偏高", "> 100", 0.08f, PulseRed)
        }

        Spacer(Modifier.height(24.dp))
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
    is MeasurePhase.Success -> phase.bpm.toString()
    MeasurePhase.Connecting, MeasurePhase.Authenticating, MeasurePhase.Measuring -> "--"
    is MeasurePhase.Failure, MeasurePhase.Idle -> lastBpm?.toString() ?: "--"
}

/** 顶部那行小字。带上秒数，用户才知道程序在动。 */
private fun statusLabel(phase: MeasurePhase, elapsedSec: Int): String {
    val base = when (phase) {
        MeasurePhase.Connecting -> "正在连接手环…"
        MeasurePhase.Authenticating -> "正在认证手环…"
        MeasurePhase.Measuring -> "正在测量…"
        is MeasurePhase.Failure -> "测量未完成"
        else -> "当前心率"
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
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}
