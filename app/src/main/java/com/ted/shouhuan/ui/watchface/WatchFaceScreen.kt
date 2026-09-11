package com.ted.shouhuan.ui.watchface

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.proto.WatchFace
import com.ted.shouhuan.ui.components.KeyValueRow
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.components.StatusDot
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.StepBlue

/**
 * 表盘页（**实验性**）。
 *
 * 页面顶上就写着「实验性」，不是谦虚：这条链路只实测到「包被完整接收」，
 * 「手环是否真的换上了表盘」还没确认。所以界面把三件事分开说清楚 ——
 * 包选了什么、传到哪一步、手环最后回了什么 —— 而不是含糊地报一句成功或失败。
 *
 * 最后那张「协议日志」卡是有意留的：这条协议还没有可靠的上游参考，
 * 出问题时唯一的线索就是手环回来的原始字节。
 */
@Composable
fun WatchFaceScreen(vm: WatchFaceViewModel) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val file by vm.file.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
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

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(vm::selectFile) },
    )

    val busy = isBusy(phase)
    val failure = phase as? WatchFacePhase.Failure

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("表盘", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
            ExperimentalChip()
        }

        Spacer(Modifier.height(14.dp))

        // ---- 先把「实验性」写明白：能做什么、不能保证什么 ----
        NoticeBanner(
            title = "实验性功能",
            tone = NotifyAmber,
            detail = "协议只实测到「表盘包被手环完整接收」，最后一步「手环是否真的换上」" +
                "还没确认 —— 传完请自己看一眼手环屏幕。",
            hint = "传坏不会毁手环：包体带 CRC32 校验，不完整或对不上时手环会自己丢弃，现有表盘不受影响。",
        )

        if (!configured) {
            Spacer(Modifier.height(12.dp))
            NoticeBanner(
                title = "还没有配对手环",
                tone = NotifyAmber,
                detail = "本地没有设备 MAC 和 AuthKey，连不上任何手环。",
                hint = "先到「设备」页完成配对。",
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 表盘包 ----
        SectionCard(title = "表盘包", accent = StepBlue) {
            val picked = file
            if (picked == null) {
                Text(
                    "挑一个表盘包（zip）。小米运动健康会把用过的表盘缓存在手机里，" +
                        "也可以直接选它。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValueRow("文件", picked.name)
                KeyValueRow("大小", formatSize(picked.sizeBytes))
                KeyValueRow("CRC32", WatchFace.hex32(picked.crc32), valueColor = StepBlue)
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(if (file == null) "选择文件" else "换一个文件")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 下发 ----
        SectionCard(title = "下发", accent = StepBlue) {
            val running = phase as? WatchFacePhase.Running
            if (running != null) {
                val p = running.progress
                Text(
                    "已发 ${p.sentPackets}/${p.totalPackets} 包 · ${formatSize(p.sentBytes)}/${formatSize(p.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ProgressBar(p.percent / 100f, StepBlue)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${p.percent}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = StepBlue,
                    )
                    Text(
                        "已用 ${p.elapsedSec}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "全程约 80 秒（242 KB 的包实测 73 秒）。速度是刻意压着的 —— " +
                        "灌太快手环的接收缓冲会满，传到一半被整包打回。期间别切走、别锁屏。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    Button(
                        onClick = { vm.cancel() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text("取消")
                    }
                } else {
                    Button(
                        onClick = {
                            if (vm.hasBluetoothPermission()) vm.start() else requestPermission()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = file != null && configured,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StepBlue,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            when (phase) {
                                is WatchFacePhase.Success,
                                is WatchFacePhase.Unconfirmed,
                                is WatchFacePhase.Failure,
                                -> "再传一次"

                                else -> "开始下发"
                            },
                        )
                    }
                }
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
        }

        // ---- 结果 ----
        when (val current = phase) {
            WatchFacePhase.Success -> {
                Spacer(Modifier.height(12.dp))
                NoticeBanner(
                    title = "下发完成",
                    tone = Mint,
                    detail = "手环回了「校验通过」。看一眼手环屏幕确认表盘换上了。",
                )
            }

            is WatchFacePhase.Unconfirmed -> {
                Spacer(Modifier.height(12.dp))
                NoticeBanner(
                    title = "包送完了，生效没确认",
                    tone = NotifyAmber,
                    detail = "所有数据包都被收下了（手环回了「数据齐了」），但收尾回复不符合预期：" +
                        "手环回的是 ${current.lastResponse}，而不是预期的 `10 04 01`。",
                    hint = "麻烦看一眼手环屏幕：表盘换了吗？这决定了下一步往哪查。",
                )
            }

            else -> Unit
        }

        if (failure != null) {
            Spacer(Modifier.height(12.dp))
            NoticeBanner(
                title = failure.title,
                tone = PulseRed,
                detail = failure.detail,
                hint = failure.hint,
                action = if (failure.canGrantPermission) {
                    { TextButton(onClick = requestPermission) { Text("去授权", color = PulseRed) } }
                } else {
                    null
                },
            )
        }

        // ---- 协议日志 ----
        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "协议日志", accent = NotifyAmber) {
                val shown = logs.takeLast(40)
                shown.forEachIndexed { index, line ->
                    if (index > 0) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 5.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        )
                    }
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 标题旁那个「实验性」小标，提醒这一页的能力还没定型。 */
@Composable
private fun ExperimentalChip() {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NotifyAmber.copy(alpha = 0.16f))
            .border(1.dp, NotifyAmber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            "实验性",
            style = MaterialTheme.typography.labelSmall,
            color = NotifyAmber,
        )
    }
}

/** 自己画进度条 —— 和心率页里区间分布那个同一种做法，省得跟 M3 的 API 版本较劲。 */
@Composable
private fun ProgressBar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
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

private fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected,
    is ConnectionState.Authenticated,
    -> "已连接"

    is ConnectionState.Connecting,
    is ConnectionState.Discovering,
    -> "正在连接"

    is ConnectionState.Failed -> "连接失败"

    ConnectionState.Disconnected -> "未连接"
}

/** 「242 KB」这种给人看的写法。 */
private fun formatSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
