package com.ted.shouhuan.ui.market

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.MarketEntry
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.StepBlue

/**
 * 表盘市场：从本仓库的 market/ 目录拉清单，浏览带预览的表盘，
 * 下载到本地。下载完的表盘出现在表盘页「我的表盘」里，选中即安装。
 */
@Composable
fun MarketScreen(
    vm: MarketViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text("表盘市场", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (state.loading) {
                CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }
        if (state.loading) {
            Text(
                "正在拉取目录…（预览图会随卡片逐张加载）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.entries.isNotEmpty()) {
            Text(
                "更新于 ${state.updated} · ${state.entries.size} 张 · " +
                    "来源 amazfitwatchfaces.com（免费）· 预览 ${state.previews.size}/${state.entries.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        when {
            // 目录还没拉到，但已下载的还能看/删 —— 这里只处理「整个目录没有」的情况
            state.error != null && state.entries.isEmpty() -> {
                NoticeBanner(
                    title = "市场打不开",
                    tone = PulseRed,
                    detail = state.error ?: "未知错误",
                    hint = "检查网络后点上面的刷新重试。",
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.refresh() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StepBlue),
                ) {
                    Text("重试")
                }
            }

            state.loading && state.entries.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        MarketCard(
                            entry = entry,
                            preview = state.previews[entry.id],
                            downloadState = downloadStateOf(state, entry.id),
                            downloaded = entry.id in state.downloadedIds,
                            onDownload = { vm.download(entry) },
                            onDelete = { vm.delete(entry) },
                            onVisible = { vm.ensurePreview(entry) },
                        )
                    }
                }
            }
        }
    }
}

private fun downloadStateOf(state: MarketUiState, id: String): DownloadState = when {
    state.progress.containsKey(id) -> DownloadState.Running(state.progress[id] ?: 0)
    state.failed.containsKey(id) -> DownloadState.Failed(state.failed[id] ?: "下载失败")
    id in state.downloadedIds -> DownloadState.Done
    else -> DownloadState.Idle
}

@Composable
private fun MarketCard(
    entry: MarketEntry,
    preview: ImageBitmap?,
    downloadState: DownloadState,
    downloaded: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onVisible: () -> Unit,
) {
    // 懒加载：卡片滑进画面才拉自己的预览图（有磁盘缓存，只拉一次）
    androidx.compose.runtime.LaunchedEffect(entry.id) { onVisible() }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
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
                contentDescription = entry.name,
                modifier = imageModifier,
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                imageModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "预览加载中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            (if (entry.sizeBytes > 0) formatSize(entry.sizeBytes) else "—") +
                " · ${entry.license.removeSuffix("-1.0")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))

        when (downloadState) {
            is DownloadState.Running -> {
                Text(
                    "下载中 ${downloadState.percent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = StepBlue,
                )
            }

            DownloadState.Done -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = Mint,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseRed,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            is DownloadState.Failed -> {
                Text(
                    "失败：${downloadState.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseRed,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDownload),
                )
            }

            DownloadState.Idle -> {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 4.dp,
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StepBlue,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("下载", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 「395 KB」这种给人看的写法。 */
private fun formatSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
