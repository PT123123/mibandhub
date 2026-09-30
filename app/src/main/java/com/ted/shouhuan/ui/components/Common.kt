package com.ted.shouhuan.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 统一的卡片容器：左侧一条强调色竖线 + 小标题，内容放下面。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accent != null) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 13.dp)
                            .background(accent, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

/** 一个「数值 + 单位 + 说明」的指标块，用于并排展示。 */
@Composable
fun MetricTile(
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = valueColor,
            )
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 设备在线状态的小圆点。 */
@Composable
fun StatusDot(connected: Boolean, modifier: Modifier = Modifier) {
    val color = if (connected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/** 一行「标签 —— 值」，设备页和设置项里反复用。 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

/**
 * 一条「出了什么事 → 为什么 → 怎么办」的提示条。
 *
 * 比 Toast 好在：不会被错过、切走页面也还在、能带操作指引，
 * 用户可以对着它一步步排查，而不是只看到一句转瞬即逝的报错。
 */
@Composable
fun NoticeBanner(
    title: String,
    tone: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    hint: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.45f)), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(tone, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tone,
                modifier = Modifier.weight(1f),
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/**
 * 横向排列的筛选 chips（近7天 / 近30天 / …）。
 *
 * 选项多时整体横向滚动，不换行 —— 筛选器换行会显得像两排标签。
 */
@Composable
fun FilterChipRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, option ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(option) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = accent.copy(alpha = 0.16f),
                    selectedLabelColor = accent,
                ),
                border = if (index == selectedIndex) {
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = true,
                        borderColor = accent.copy(alpha = 0.45f),
                    )
                } else {
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = false,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                },
            )
        }
    }
}

/**
 * 可收缩 / 可扩展的大卡片：标题行常驻（点击整行切换展开），内容区带展开动画。
 *
 * 睡眠页的「详细数据」、心率页的「全部测量记录」都用它兜底 —— 长列表不该
 * 一进页面就全部铺开，但也不能藏到用户找不到。
 *
 * @param badge 标题右侧的小字（比如「128 条」），不占太多空间的关键信息。
 * @param headerTrailing 标题行末尾的额外动作（比如「清空」按钮）。
 */
@Composable
fun CollapsibleSection(
    title: String,
    accent: Color? = null,
    modifier: Modifier = Modifier,
    badge: String? = null,
    initiallyExpanded: Boolean = true,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "collapse-chevron",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accent != null) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 13.dp)
                        .background(accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent ?: MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (headerTrailing != null) headerTrailing()
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 12.dp)) { content() }
        }
    }
}

/**
 * 「标题 + 副标题 + 开关」的设置行。通知页的详细设置全部用它，
 * 视觉上和设备页 / 心率页已有的开关行保持一致。
 */
@Composable
fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = accent,
            ),
        )
    }
}

/**
 * 子页面外壳：顶部返回栏 + 可滚动内容。
 *
 * 目录页只放入口行，具体设置全在这道门后面 —— 多点一下才进得来。
 * 一屏铺满开关的页面，手指在列表上滑一下就可能改掉配置；拆成子页面之后，
 * 每次改动都必然来自一次「主动点进来的」操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    status: String? = null,
    statusAccent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // 状态条钉在顶栏下面、不跟着内容滚 —— 弹窗确认完那一句「已下发到手环」
            // 必须还在眼睛看得见的地方，不然要往上翻才知道刚才那下算不算数。
            AnimatedVisibility(visible = status != null) {
                val tint = statusAccent ?: MaterialTheme.colorScheme.primary
                Text(
                    status.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tint.copy(alpha = 0.10f))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
            ) {
                Spacer(Modifier.height(14.dp))
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 目录页的一行入口：标题 + 当前状态（副标题 / 徽标）+ 右箭头。
 *
 * 副标题不是装饰 —— 它是「不点进去也能看见的那部分状态」，
 * 比如「已开启 · 30 秒去重」，够用来判断要不要再点进那一页。
 */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    accent: Color? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 32.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                color = accent ?: MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/**
 * 需要额外点一次的确认弹窗。
 *
 * 开关、删除这类「一下就生效」的操作统一过这道门：先把要点什么、影响多大
 * 说清楚，再由用户点 [confirmLabel] 才执行。多这一步，滑动列表时手一抖
 * 不至于直接改掉配置。
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 一次待确认的操作：问什么、怎么答、答了执行什么。 */
class ConfirmSpec internal constructor(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val accent: Color?,
    internal val action: () -> Unit,
)

/**
 * 「先问一句再执行」的控制器：页面里 `confirm.ask(...)` 提问，
 * [ConfirmHost] 负责把弹窗画出来。整个页面共用一个，避免每处删除各挂一份状态。
 */
class ConfirmController internal constructor(private val spec: MutableState<ConfirmSpec?>) {
    val current: ConfirmSpec? get() = spec.value

    fun ask(
        title: String,
        message: String,
        confirmLabel: String,
        accent: Color? = null,
        action: () -> Unit,
    ) {
        spec.value = ConfirmSpec(title, message, confirmLabel, accent, action)
    }

    fun dismiss() {
        spec.value = null
    }
}

/** 建一个确认控制器（配合 [ConfirmHost] 使用）。 */
@Composable
fun rememberConfirm(): ConfirmController {
    val spec = remember { mutableStateOf<ConfirmSpec?>(null) }
    return remember { ConfirmController(spec) }
}

/** 把 [rememberConfirm] 挂着的那次提问画出来。没有提问时什么都不画。 */
@Composable
fun ConfirmHost(controller: ConfirmController) {
    val spec = controller.current ?: return
    ConfirmActionDialog(
        title = spec.title,
        message = spec.message,
        confirmLabel = spec.confirmLabel,
        accent = spec.accent ?: MaterialTheme.colorScheme.primary,
        onConfirm = {
            controller.dismiss()
            spec.action()
        },
        onDismiss = { controller.dismiss() },
    )
}

/**
 * 短暂的状态提示：[StatusHint.show] 一句，过几秒自己消失。
 *
 * 用来给「确认过了才执行」的操作一个落点 —— 弹窗关掉之后界面要能说出
 * 「已经生效了」，不然用户会怀疑刚才那下到底算不算数。
 */
class StatusHint internal constructor(private val state: MutableState<String?>) {
    val value: String? get() = state.value

    fun show(text: String) {
        state.value = text
    }

    fun clear() {
        state.value = null
    }
}

/** 建一个会自动过期的状态提示（默认 4 秒后消失）。 */
@Composable
fun rememberStatusHint(durationMillis: Long = 4_000): StatusHint {
    val state = remember { mutableStateOf<String?>(null) }
    val shown = state.value
    LaunchedEffect(shown) {
        if (shown == null) return@LaunchedEffect
        delay(durationMillis)
        if (state.value == shown) state.value = null
    }
    return remember { StatusHint(state) }
}
