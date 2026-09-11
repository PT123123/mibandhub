package com.ted.shouhuan.ui.notify

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ted.shouhuan.data.DemoData
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.theme.NotifyAmber

@Composable
fun NotifyScreen() {
    var forwardEnabled by remember { mutableStateOf(true) }
    var rules by remember { mutableStateOf(DemoData.appRules()) }
    val recent = remember { DemoData.notifications() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("通知", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // ---- 总开关 ----
        SectionCard(title = "转发到手机", accent = NotifyAmber) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("手机通知同步到手环", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (forwardEnabled) "已开启 · 命中白名单的应用会振动提醒" else "已关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = forwardEnabled,
                    onCheckedChange = { forwardEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = NotifyAmber,
                    ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 应用白名单 ----
        SectionCard(title = "允许转发的应用", accent = NotifyAmber) {
            rules.forEachIndexed { index, rule ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(rule.appName, style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = rule.enabled,
                        enabled = forwardEnabled,
                        onCheckedChange = { checked ->
                            rules = rules.map {
                                if (it.packageName == rule.packageName) it.copy(enabled = checked) else it
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = NotifyAmber,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 最近 ----
        SectionCard(title = "最近推送", accent = NotifyAmber) {
            recent.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (item.forwarded) NotifyAmber else MaterialTheme.colorScheme.outline,
                            ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
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
                        Spacer(Modifier.height(3.dp))
                        Text(
                            item.body,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!item.forwarded) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "未推送（被规则拦下）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
