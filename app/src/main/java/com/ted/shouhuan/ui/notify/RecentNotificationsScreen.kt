package com.ted.shouhuan.ui.notify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.AppRule
import com.ted.shouhuan.data.BandNotification
import com.ted.shouhuan.ui.theme.NotifyAmber

private const val PAGE_SIZE = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentNotificationsScreen(
    vm: RecentNotificationsViewModel,
    onBack: () -> Unit,
) {
    val allNotifications by vm.allNotifications.collectAsStateWithLifecycle()
    val appStats by vm.appStats.collectAsStateWithLifecycle()
    val selectedApp by vm.selectedApp.collectAsStateWithLifecycle()
    val filteredNotifications by vm.filteredNotifications.collectAsStateWithLifecycle()
    val appRules by vm.appRules.collectAsStateWithLifecycle()
    val blacklistMode by vm.blacklistMode.collectAsStateWithLifecycle()

    // 确保父级 VM 已加载应用列表（用于快捷加名单时的反查）
    LaunchedEffect(Unit) {
        vm.installedApps.value // 触发父级加载
    }

    // 分页加载状态
    var displayedCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(PAGE_SIZE) }
    val listState = rememberLazyListState()

    // 滚动到底部时加载更多
    LaunchedEffect(listState, filteredNotifications.size) {
        if (!listState.isScrollInProgress) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= displayedCount - 10 && displayedCount < filteredNotifications.size) {
            displayedCount = (displayedCount + PAGE_SIZE).coerceAtMost(filteredNotifications.size)
        }
    }

    val displayed = filteredNotifications.take(displayedCount)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("最近推送") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // ---- 统计信息栏 ----
            if (allNotifications.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "共 ${allNotifications.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectedApp != null) {
                        Text(
                            "筛选：$selectedApp · ${filteredNotifications.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = NotifyAmber,
                        )
                    }
                }
            }

            // ---- 按应用统计筛选（横向滚动）----
            if (appStats.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 「全部」芯片
                    FilterChip(
                        selected = selectedApp == null,
                        onClick = { vm.selectApp(null) },
                        label = { Text("全部") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NotifyAmber.copy(alpha = 0.18f),
                            selectedLabelColor = NotifyAmber,
                        ),
                    )
                    appStats.forEach { stat ->
                        FilterChip(
                            selected = selectedApp == stat.appName,
                            onClick = { vm.selectApp(stat.appName) },
                            label = { Text("${stat.appName} (${stat.count})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NotifyAmber.copy(alpha = 0.18f),
                                selectedLabelColor = NotifyAmber,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // ---- 通知列表 ----
            if (filteredNotifications.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            if (allNotifications.isEmpty()) {
                                "还没有推送记录"
                            } else {
                                "没有符合条件的内容"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = displayed,
                        key = { "${it.packageName}|${it.title}|${it.timeLabel}|${it.body.hashCode()}" },
                    ) { item ->
                        NotificationItem(
                            item = item,
                            appRules = appRules,
                            blacklistMode = blacklistMode,
                            resolvePackageName = { vm.resolvePackageName(it) },
                            onQuickAdd = { pkg, name -> vm.quickAddToList(pkg, name) },
                        )
                    }

                    // 加载更多提示
                    if (displayedCount < filteredNotifications.size) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "向下滚动加载更多（${displayedCount}/${filteredNotifications.size}）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    item: BandNotification,
    appRules: List<AppRule>,
    blacklistMode: Boolean,
    resolvePackageName: (BandNotification) -> String?,
    onQuickAdd: (String, String) -> Unit,
) {
    val pkg = resolvePackageName(item)
    val inList = pkg != null && appRules.any { it.packageName == pkg }
    val canQuickAdd = pkg != null && !inList

    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 左边彩色指示条：绿色=成功转发，灰色=失败/被拦截
        Box(
            Modifier
                .width(3.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (item.forwarded) NotifyAmber
                    else MaterialTheme.colorScheme.outline,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${item.appName} · ${item.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    item.timeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    item.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!item.forwarded) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "未推送（被规则拦下）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canQuickAdd) {
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = { onQuickAdd(pkg!!, item.appName) },
                    label = {
                        Text(
                            if (blacklistMode) "+ 加入黑名单" else "+ 加入白名单",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = NotifyAmber,
                    ),
                    border = AssistChipDefaults.assistChipBorder(
                        enabled = true,
                        borderColor = NotifyAmber.copy(alpha = 0.5f),
                    ),
                )
            }
        }
    }
}
