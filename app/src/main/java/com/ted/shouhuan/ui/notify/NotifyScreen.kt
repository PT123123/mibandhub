package com.ted.shouhuan.ui.notify

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.ui.components.NavRow
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed

/**
 * 通知 tab 的子页面路由。目录页和 AppRoot 认同一份字符串 ——
 * 路由写错会在导航时直接抛异常，所以只在这里定义一次。
 */
object NotifyRoutes {
    const val FORWARD = "notify-forward"
    const val TEST = "notify-test"
    const val DND = "notify-dnd"
    const val KEYWORD = "notify-keyword"
    const val SENSITIVE = "notify-sensitive"
    const val DEDUPE = "notify-dedupe"
    const val VIBRATION = "notify-vibration"
    const val APPS = "notify-apps"
    const val RECENT = "recent-notifications"
}

/**
 * 通知 tab 的目录页。
 *
 * 这里只放「点一下进哪一页」的入口 + 每项当前状态的一句话摘要，
 * 具体开关、名单、时间选择全在各自的子页面里 —— 原本一屏铺到底的设置，
 * 现在都要多点一下才进得去，滑动列表时不会顺手就把配置改了。
 */
@Composable
fun NotifyScreen(
    vm: NotifyViewModel,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val forwardEnabled by vm.forwardEnabled.collectAsStateWithLifecycle()
    val notifyPermission by vm.notifyPermission.collectAsStateWithLifecycle()
    val dndEnabled by vm.dndEnabled.collectAsStateWithLifecycle()
    val dndStart by vm.dndStart.collectAsStateWithLifecycle()
    val dndEnd by vm.dndEnd.collectAsStateWithLifecycle()
    val keywordBlacklist by vm.keywordBlacklist.collectAsStateWithLifecycle()
    val keywordWhitelist by vm.keywordWhitelist.collectAsStateWithLifecycle()
    val sensitiveEnabled by vm.sensitiveEnabled.collectAsStateWithLifecycle()
    val sensitiveRules by vm.sensitiveRules.collectAsStateWithLifecycle()
    val appFilterBlacklist by vm.appFilterBlacklist.collectAsStateWithLifecycle()
    val dedupeEnabled by vm.dedupeEnabled.collectAsStateWithLifecycle()
    val dedupeSeconds by vm.dedupeSeconds.collectAsStateWithLifecycle()
    val vibration by vm.vibration.collectAsStateWithLifecycle()
    val appRules by vm.appRules.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    // 每次进入页面都刷新一次权限状态（从系统设置授权回来也能看到最新状态）
    LaunchedEffect(Unit) { vm.refreshNotifyPermission() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("通知", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // ---- 通知监听权限检查：没授权就高亮提示，这是一切转发的前提 ----
        if (!notifyPermission) {
            NoticeBanner(
                title = "需要开启「通知使用权限」",
                tone = PulseRed,
                detail = "手环管家需要读取手机通知，才能把微信、短信等消息转发到手环。" +
                    "现在没有权限，下面的转发规则都不会生效。",
                action = {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { context.startActivity(vm.notifyListenerSettingsIntent()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PulseRed,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("去系统设置授权")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        Text(
            "下面每一项都要点进去才改得到 —— 目录上只显示当前状态。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // ---- 转发总开关 ----
        NavRow(
            title = "转发到手机",
            subtitle = if (forwardEnabled) {
                "已开启 · 命中规则的通知会振动提醒"
            } else {
                "已关闭 · 手机通知不推到手环"
            },
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.FORWARD) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 测试通知 ----
        NavRow(
            title = "测试通知",
            subtitle = "真实发一条到手环，验证整条转发链路",
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.TEST) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 勿扰时段 ----
        NavRow(
            title = "勿扰时段",
            subtitle = "${vm.dndWindowLabel(dndStart, dndEnd)} · " +
                if (dndEnabled) "已开启" else "已关闭",
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.DND) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 关键词过滤 ----
        NavRow(
            title = "关键词过滤",
            subtitle = "黑名单 ${keywordBlacklist.size} 条 · 白名单 ${keywordWhitelist.size} 条",
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.KEYWORD) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 敏感信息过滤 ----
        NavRow(
            title = "敏感信息过滤",
            subtitle = if (sensitiveEnabled) {
                "已开启 · 自定义敏感词 ${sensitiveRules.count { it.kind == null }} 条"
            } else {
                "已关闭 · 验证卡号等正文照常上手环"
            },
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.SENSITIVE) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 防重复 ----
        NavRow(
            title = "防重复转发",
            subtitle = if (dedupeEnabled) "已开启 · $dedupeSeconds 秒内只推一次" else "已关闭",
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.DEDUPE) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 振动模式 ----
        NavRow(
            title = "振动",
            subtitle = "当前：${vibrationLabel(vibration)}",
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.VIBRATION) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 应用名单 ----
        NavRow(
            title = "应用名单",
            subtitle = "${if (appFilterBlacklist) "黑名单" else "白名单"}模式 · ${appRules.size} 个应用",
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.APPS) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- 最近推送：完整历史本来就在独立页面里 ----
        NavRow(
            title = "最近推送",
            subtitle = "查看全部推送记录（可按应用筛选、搜索）",
            badge = "${recent.size} 条",
            accent = NotifyAmber,
            onClick = { onNavigate(NotifyRoutes.RECENT) },
        )
    }
}
