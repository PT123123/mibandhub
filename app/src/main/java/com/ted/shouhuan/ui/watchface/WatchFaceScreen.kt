package com.ted.shouhuan.ui.watchface

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.WatchfaceLibrary
import com.ted.shouhuan.data.WatchfaceRef
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
 * 表盘页。
 *
 * 这条链路 2026-09-12 真机验证通过：协议对齐 Gadgetbridge 后，表盘
 * 下发到手环并成功换上。此前长期卡在「包被完整接收但生效未确认」，根因是
 * 少了表盘槽选择命令、校验命令没带 CRC16（详见 docs/watchface.md §2.1）。
 *
 * 页面结构：在线市场（拉仓库目录、下载）→ 我的表盘（内置 + 已下载）→
 * 表盘包/下发。最后那张「协议日志」卡是有意留的：这条协议的应答语义
 * （比如 0x20 的拒绝码）没有完整文档，出问题时唯一的线索就是原始字节。
 */
@Composable
fun WatchFaceScreen(
    vm: WatchFaceViewModel,
    onOpenMarket: () -> Unit,
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val file by vm.file.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val selectedLibraryId by vm.selectedLibraryId.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    // 从市场页回来时重读一遍库 —— 那边可能下了新的、也可能删了旧的。
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshLibrary() }

    val context = LocalContext.current
    // 预览图都很小（每张几 KB ~ 十几 KB），库里变了就整批重解码一次。
    val previews = remember(library) {
        library.mapNotNull { ref ->
            WatchfaceLibrary.readPreview(context, ref)?.let { ref.id to it.asImageBitmap() }
        }.toMap()
    }

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
        }

        Spacer(Modifier.height(14.dp))

        // ---- 能做什么、要注意什么，开门见山 ----
        NoticeBanner(
            title = "已真机验证",
            tone = Mint,
            detail = "协议已对齐 Gadgetbridge，2026-09-12 真机换表盘成功。" +
                "Mi Band 5 有 3 个自定义表盘槽位。",
            hint = "传坏不会毁手环：包体带校验和，不完整或对不上时手环会自己丢弃，现有表盘不受影响。",
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

        // ---- 在线市场：仓库里的 market/ 目录，下载后进「我的表盘」 ----
        SectionCard(title = "在线市场", accent = Mint) {
            Text(
                "从本仓库的表盘市场浏览并下载更多表盘（带预览图），下载完出现在下面的「我的表盘」里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenMarket,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text("进入市场")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 我的表盘：内置 + 市场下载，点一张就填进下面的「表盘包」 ----
        if (library.isNotEmpty()) {
            SectionCard(title = "我的表盘", accent = StepBlue) {
                Text(
                    "内置的随 App 走，市场下载的存在本机。点一张即可选用，来源与授权随表盘原样标注。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                library.chunked(3).forEach { rowRefs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowRefs.forEach { ref ->
                            LibraryCard(
                                ref = ref,
                                preview = previews[ref.id],
                                selected = ref.id == selectedLibraryId,
                                enabled = !busy,
                                onSelect = { vm.selectLibraryFace(ref) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowRefs.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                library.firstOrNull { it.id == selectedLibraryId }?.let { ref ->
                    Text(
                        "${ref.source} · ${ref.license}" + (ref.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- 表盘包 ----
        SectionCard(title = "表盘包", accent = StepBlue) {
            val picked = file
            if (picked == null) {
                Text(
                    "从上面选一张内置表盘，或挑一个 .bin 表盘文件 —— " +
                        "社区表盘站（如 amazfitwatchfaces.com）的 Mi Band 5 表盘就是 .bin。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValueRow("名称", picked.name)
                KeyValueRow("大小", formatSize(picked.sizeBytes))
                KeyValueRow("CRC32", WatchFace.hex32(picked.crc32), valueColor = StepBlue)
                picked.author?.let { KeyValueRow("来源", it) }
                picked.license?.let { KeyValueRow("授权", it) }
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

/** 「我的表盘」的小卡片：预览图 + 名字 + 作者，选中描蓝边；市场下载的带标记。 */
@Composable
private fun LibraryCard(
    ref: WatchfaceRef,
    preview: ImageBitmap?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = if (selected) {
        Modifier.border(BorderStroke(2.dp, StepBlue), RoundedCornerShape(12.dp))
    } else {
        Modifier.border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            RoundedCornerShape(12.dp),
        )
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .then(outline)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val imageModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(126f / 294f)
            .clip(RoundedCornerShape(8.dp))
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = ref.name,
                modifier = imageModifier,
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                imageModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "无预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            ref.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            ref.author + if (ref.source == WatchfaceRef.Source.DOWNLOADED) " · 已下载" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
