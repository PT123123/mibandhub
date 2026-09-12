package com.ted.shouhuan.ui.market

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.BrowseMode
import com.ted.shouhuan.data.MarketEntry
import com.ted.shouhuan.data.OnlineLang
import com.ted.shouhuan.data.OnlineMetric
import com.ted.shouhuan.data.OnlinePaid
import com.ted.shouhuan.data.OnlinePeriod
import com.ted.shouhuan.data.OnlineTag
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.PulseRed
import com.ted.shouhuan.ui.theme.StepBlue
import com.ted.shouhuan.ui.watchface.WatchFacePhase
import com.ted.shouhuan.ui.watchface.WatchFaceViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 表盘市场：在线源（amazfitwatchfaces.com）按 最新/热门/最近更新/搜索/组件标签/语言
 * 浏览，预览图随卡片加载，下载到本地。在线源不可达时回落自建快照目录
 * （标签退化为本地过滤）。下载完的表盘出现在表盘页「我的表盘」里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketScreen(
    vm: MarketViewModel,
    installVm: WatchFaceViewModel,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searchActive by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    // 进阶筛选（标签 / 语言 / 只看已下载）默认收起，常用入口是模式行
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }

    // ---- 卡片一键安装：走表盘页那条下发流程，进度/结果映射回这张卡片 ----
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installPhase by installVm.phase.collectAsStateWithLifecycle()
    var installingId by remember { mutableStateOf<String?>(null) }

    fun install(entry: MarketEntry) {
        if (installPhase is WatchFacePhase.Running) return
        scope.launch {
            when (val bytes = vm.faceBytes(entry)) {
                null -> Toast.makeText(context, "找不到已下载的表盘包，重新下载一次", Toast.LENGTH_LONG).show()
                else -> {
                    installingId = entry.id
                    installVm.installBytes(entry.name, bytes)
                }
            }
        }
    }

    // 安装收尾后把卡片上的结果文案留几秒再撤，让人来得及看到
    LaunchedEffect(installPhase) {
        if (installingId != null && installPhase !is WatchFacePhase.Running) {
            delay(4_000)
            installingId = null
        }
    }

    /** 这张卡片当前该显示的安装状态文案（null = 不显示）。 */
    fun installStatusFor(id: String): String? {
        if (installingId != id) return null
        return when (val p = installPhase) {
            is WatchFacePhase.Running ->
                "下发中 ${(p.progress.sentBytes * 100 / p.progress.totalBytes.coerceAtLeast(1))}%"
            is WatchFacePhase.Success -> "已装上手环 ✓"
            is WatchFacePhase.Unconfirmed -> "手环没确认，去表盘页看详情"
            is WatchFacePhase.Failure -> "失败：${p.title}"
            WatchFacePhase.Idle -> null
        }
    }

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
            IconButton(onClick = onOpenBrowser) {
                Icon(Icons.Rounded.Language, contentDescription = "打开站点浏览器")
            }
            IconButton(
                onClick = {
                    searchActive = !searchActive
                    if (!searchActive) {
                        searchInput = ""
                        vm.submitSearch("")
                    }
                },
            ) {
                Icon(
                    if (searchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (searchActive) "收起搜索" else "搜索",
                )
            }
            IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }
        if (searchActive) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                placeholder = { Text("在 amazfitwatchfaces 站内搜索…") },
                singleLine = true,
                trailingIcon = {
                    if (searchInput.isNotEmpty()) {
                        IconButton(onClick = { searchInput = ""; vm.submitSearch("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submitSearch(searchInput) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (state.loading) {
            Text(
                "正在拉取目录…（预览图会随卡片逐张加载）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.degraded) {
            Text(
                "在线源不可达（${state.degradedMessage ?: "未知原因"}），已切换到本地快照目录 · " +
                    "点右上 🌐 直接逛站，或刷新重试",
                style = MaterialTheme.typography.labelSmall,
                color = PulseRed,
            )
        } else if (state.entries.isNotEmpty()) {
            val shown = state.visibleEntries()
            val filteredNote = if (shown.size != state.entries.size) " · 符合 ${shown.size}" else ""
            Text(
                "来源 amazfitwatchfaces.com · ${state.entries.size} 张$filteredNote · " +
                    "预览 ${state.previews.size}/${state.entries.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

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
                val gridState = rememberLazyGridState()

                // 滑到倒数第 8 张以内就翻下一页 —— 卡片小，等用户真滑到底再拉会顿
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .collect { lastVisible ->
                            val s = vm.state.value
                            if (s.hasMore && !s.loadingMore && !s.loading &&
                                lastVisible >= s.entries.size - 8
                            ) {
                                vm.loadMore()
                            }
                        }
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 筛选区是网格的通栏首项，跟卡片共用同一个滚动容器 ——
                    // 它要是固定在页头，展开后的十几行 chip 会把网格压到没有可滚空间，
                    // 表现就是「点了筛选就滑不动」
                    item(key = "filters", span = { GridItemSpan(3) }) {
                        MarketFilterChips(
                            state = state,
                            filtersExpanded = filtersExpanded,
                            onToggleFilters = { filtersExpanded = !filtersExpanded },
                            onMode = { vm.setMode(it) },
                            onMetric = { vm.setMetric(it) },
                            onPeriod = { vm.setPeriod(it) },
                            onToggleTag = { vm.toggleTag(it) },
                            onClearTags = { vm.clearTags() },
                            onLang = { vm.setLang(it) },
                            onPaid = { vm.setPaid(it) },
                            onVerifiedOnly = { vm.setVerifiedOnly(it) },
                            onOnlyDownloaded = { vm.setOnlyDownloaded(it) },
                            onClearSearch = {
                                searchActive = false
                                searchInput = ""
                                vm.submitSearch("")
                            },
                        )
                    }
                    val shown = state.visibleEntries()
                    if (shown.isEmpty()) {
                        item(key = "filtered-empty", span = { GridItemSpan(3) }) {
                            Text(
                                "没有符合筛选条件的表盘",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { entry ->
                        MarketCard(
                            entry = entry,
                            preview = state.previews[entry.id],
                            loading = entry.id in state.previewLoading,
                            downloadState = downloadStateOf(state, entry.id),
                            downloaded = entry.id in state.downloadedIds,
                            installStatus = installStatusFor(entry.id),
                            installBusy = installPhase is WatchFacePhase.Running,
                            onDownload = { vm.download(entry) },
                            onInstall = { install(entry) },
                            onDelete = { vm.delete(entry) },
                            onVisible = { vm.ensurePreview(entry) },
                        )
                    }
                    if (state.loadingMore) {
                        item(key = "loading-more", span = { GridItemSpan(3) }) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (!state.hasMore && shown.isNotEmpty() && !state.degraded) {
                        item(key = "no-more", span = { GridItemSpan(3) }) {
                            Text(
                                "到底了 · 共 ${shown.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 筛选区。
 *
 * 第一行是模式（最新 / 热门 / 最近更新）和「筛选」开关；热门模式下追加
 * 口径 × 时间窗两行。展开「筛选」后是：
 *   · 功能标签 —— **多选**（与站点一致，选中几个就交集几个）
 *   · 价格（免费/付费）+ 只看认证 —— 站点 paid/verified 参数
 *   · 语言 —— 站点 lang 参数
 *   · 只看已下载 —— 本地过滤
 * 搜索激活时覆盖其余筛选（与站点行为一致），界面把被覆盖的行藏起来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketFilterChips(
    state: MarketUiState,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
    onMode: (BrowseMode) -> Unit,
    onMetric: (OnlineMetric) -> Unit,
    onPeriod: (OnlinePeriod) -> Unit,
    onToggleTag: (OnlineTag) -> Unit,
    onClearTags: () -> Unit,
    onLang: (OnlineLang) -> Unit,
    onPaid: (OnlinePaid) -> Unit,
    onVerifiedOnly: (Boolean) -> Unit,
    onOnlyDownloaded: (Boolean) -> Unit,
    onClearSearch: () -> Unit,
) {
    val searching = state.query != null
    val activeFilters =
        (if (state.tags.isNotEmpty()) 1 else 0) +
            (if (state.lang != OnlineLang.ANY) 1 else 0) +
            (if (state.paid != OnlinePaid.ANY) 1 else 0) +
            (if (state.verifiedOnly) 1 else 0) +
            (if (state.onlyDownloaded) 1 else 0)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrowseMode.entries.forEach { mode ->
            FilterChip(
                selected = !searching && state.mode == mode,
                onClick = { onMode(mode) },
                label = { Text(mode.label) },
            )
        }
        if (searching) {
            FilterChip(
                selected = true,
                onClick = onClearSearch,
                label = { Text("搜索：${state.query}") },
                trailingIcon = {
                    Icon(Icons.Rounded.Close, contentDescription = "清除搜索", Modifier.width(16.dp))
                },
            )
        }
        FilterChip(
            selected = filtersExpanded,
            onClick = onToggleFilters,
            label = { Text(if (activeFilters > 0) "筛选 · $activeFilters" else "筛选") },
        )
    }
    // 口径/时间窗只属于热门榜
    if (!searching && state.mode == BrowseMode.TOP) {
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnlineMetric.entries.forEach { metric ->
                FilterChip(
                    selected = state.metric == metric,
                    onClick = { onMetric(metric) },
                    label = { Text(metric.label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnlinePeriod.entries.forEach { period ->
                FilterChip(
                    selected = state.period == period,
                    onClick = { onPeriod(period) },
                    label = { Text(period.label) },
                )
            }
        }
    }
    if (filtersExpanded && !searching) {
        Spacer(Modifier.height(6.dp))
        Text(
            "组件标签（可多选，同时满足）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.tags.isEmpty(),
                onClick = onClearTags,
                label = { Text("全部") },
            )
            OnlineTag.entries.forEach { tag ->
                FilterChip(
                    selected = tag in state.tags,
                    onClick = { onToggleTag(tag) },
                    label = { Text(tag.label) },
                )
            }
        }
        // 价格 / 语言是站点参数，回落快照目录后无从谈起
        if (!state.degraded) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OnlinePaid.entries.forEach { paid ->
                    FilterChip(
                        selected = state.paid == paid,
                        onClick = { onPaid(paid) },
                        label = { Text(paid.label) },
                    )
                }
                FilterChip(
                    selected = state.verifiedOnly,
                    onClick = { onVerifiedOnly(!state.verifiedOnly) },
                    label = { Text("只看认证") },
                )
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OnlineLang.entries.forEach { lang ->
                    FilterChip(
                        selected = state.lang == lang,
                        onClick = { onLang(lang) },
                        label = { Text(lang.label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.onlyDownloaded,
                onClick = { onOnlyDownloaded(!state.onlyDownloaded) },
                label = { Text("只看已下载") },
            )
        }
        if (state.degraded && state.tags.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "快照目录只有自制表盘带组件标签，其余条目被隐藏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    loading: Boolean,
    downloadState: DownloadState,
    downloaded: Boolean,
    installStatus: String?,
    installBusy: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onVisible: () -> Unit,
) {
    // 卡片进画面就请求自己的预览图。真正拉不拉由 VM 决定，
    // 后台预取可能早就把它备好了，这里只是兜底。
    LaunchedEffect(entry.id) { onVisible() }
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
                if (loading) {
                    CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "无预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            // 在线条目给站点的人气数字；快照条目给大小 + 授权
            entry.stats ?: subtitleForSnapshot(entry),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
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
                Column {
                    installStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it.startsWith("失败")) PulseRed else Mint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = Mint,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            // 一键安装对齐官方 App 的体验：下载完当场就能下发，
                            // 不用再回表盘页选中。安装中禁用防重复触发。
                            "安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = StepBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !installBusy, onClick = onInstall)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                        Text(
                            "删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseRed,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !installBusy, onClick = onDelete)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            is DownloadState.Failed -> {
                Text(
                    "失败：${downloadState.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseRed,
                    maxLines = 4,
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

/** 快照条目的副标题：「395 KB · CC0」。在线条目不走这里（它们有 stats）。 */
private fun subtitleForSnapshot(entry: MarketEntry): String {
    val size = if (entry.sizeBytes > 0) formatSize(entry.sizeBytes) else "—"
    val license = entry.license.removeSuffix("-1.0")
    return if (license.isEmpty()) size else "$size · $license"
}

/** 「395 KB」这种给人看的写法。 */
private fun formatSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
