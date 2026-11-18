package com.mibandhub.app.ui.notify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mibandhub.app.link.DiscoveredMaster
import com.mibandhub.app.link.LinkRole
import com.mibandhub.app.link.LinkSendResult
import com.mibandhub.app.ui.components.KeyValueRow
import com.mibandhub.app.ui.components.SectionCard
import com.mibandhub.app.ui.components.SubPage
import com.mibandhub.app.ui.theme.NotifyAmber
import com.mibandhub.app.ui.theme.PulseRed

/**
 * 主从转发子页：本机角色选择 + 主机/从机各自的状态与设置。
 *
 * 角色语义（对齐「罗盘」类 App 的玩法）：
 *   主机 = 连着手环的那台，从机的通知最终由它推手环；
 *   从机 = 不连手环蓝牙的那台，把自己过滤完的通知经局域网发给主机。
 * 通讯走 HTTP + UDP 组播（见 link 包），凭 6 位配对码过门禁。
 */
@Composable
fun NotifyLinkPage(vm: NotifyViewModel, onBack: () -> Unit) {
    val role by vm.linkRole.collectAsStateWithLifecycle()
    val alias by vm.linkAlias.collectAsStateWithLifecycle()
    val pairingCode by vm.linkPairingCode.collectAsStateWithLifecycle()
    val manualHost by vm.linkManualHost.collectAsStateWithLifecycle()
    val masters by vm.linkMasters.collectAsStateWithLifecycle()
    val searchStatus by vm.linkSearchStatus.collectAsStateWithLifecycle()
    val activeEndpoint by vm.linkActiveEndpoint.collectAsStateWithLifecycle()
    val lastSend by vm.linkLastSend.collectAsStateWithLifecycle()

    var aliasDraft by remember(alias) { mutableStateOf(alias) }
    var hostDraft by remember(manualHost) { mutableStateOf(manualHost) }
    var codeDraft by remember(role) { mutableStateOf(vm.linkPairingCode.value) }

    SubPage("主从转发", onBack) {
        // ---- 角色选择 ----
        SectionCard(title = "本机角色", accent = NotifyAmber) {
            LinkOptionRow(
                title = "独立使用",
                subtitle = "一台手机连手环，跟原来一样",
                selected = role == LinkRole.NONE,
            ) { vm.setLinkRole(LinkRole.NONE) }
            LinkOptionRow(
                title = "作为主机",
                subtitle = "连着手环 · 接收从机的通知并推手环",
                selected = role == LinkRole.MASTER,
            ) { vm.setLinkRole(LinkRole.MASTER) }
            LinkOptionRow(
                title = "作为从机",
                subtitle = "不连手环蓝牙 · 把通知经 Wi-Fi 转给主机",
                selected = role == LinkRole.SLAVE,
            ) { vm.setLinkRole(LinkRole.SLAVE) }
            Spacer(Modifier.height(6.dp))
            Text(
                "本机名称会显示在对方那里（如从机的转发记录里）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = aliasDraft,
                    onValueChange = { aliasDraft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("本机名称") },
                    placeholder = { Text(vm.defaultAlias()) },
                )
                Button(
                    onClick = { vm.setLinkAlias(aliasDraft) },
                    colors = ButtonDefaults.buttonColors(containerColor = NotifyAmber),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("保存")
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when (role) {
            LinkRole.NONE -> {}
            LinkRole.SLAVE -> SlaveSection(
                vm = vm,
                masters = masters,
                searchStatus = searchStatus,
                activeEndpoint = activeEndpoint,
                lastSend = lastSend,
                codeDraft = codeDraft,
                onCodeDraftChange = { codeDraft = it },
                hostDraft = hostDraft,
                onHostDraftChange = { hostDraft = it },
            )
            LinkRole.MASTER -> MasterSection(vm, pairingCode)
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "怎么用", accent = NotifyAmber) {
            Text(
                "1. 连手环的那台手机切到「作为主机」，记下配对码；\n" +
                    "2. 另一台手机装同一个 App，切到「作为从机」，填入配对码；\n" +
                    "3. 两台手机连同一个 Wi-Fi，从机会自动发现主机；搜不到时再手动填主机的「地址」；\n" +
                    "4. 从机的通知过滤规则照常在自己手机上配置，命中后转发给主机，由主机推手环。\n\n" +
                    "注意：转发走局域网明文 HTTP，仅建议在可信的家用 Wi-Fi 下使用；" +
                    "从机不连手环蓝牙，手环永远只握在主机手里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 主机区
// ---------------------------------------------------------------------------

@Composable
private fun MasterSection(vm: NotifyViewModel, pairingCode: String) {
    SectionCard(title = "主机设置", accent = NotifyAmber) {
        Text(
            "从机在同一个 Wi-Fi 下打开「主从转发」即可自动发现本机；连接时需要输入下面的配对码。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        KeyValueRow("配对码", pairingCode.ifBlank { "生成中…" }, valueColor = NotifyAmber)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { vm.regenerateLinkPairingCode() }) {
            Text("重新生成配对码")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "换了配对码后，从机要重新填一次。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 从机区
// ---------------------------------------------------------------------------

@Composable
private fun SlaveSection(
    vm: NotifyViewModel,
    masters: List<DiscoveredMaster>,
    searchStatus: String,
    activeEndpoint: String?,
    lastSend: LinkSendResult?,
    codeDraft: String,
    onCodeDraftChange: (String) -> Unit,
    hostDraft: String,
    onHostDraftChange: (String) -> Unit,
) {
    SectionCard(title = "配对码", accent = NotifyAmber) {
        Text(
            "输入主机页面显示的 6 位配对码，保存后主机才会接受本机的转发。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = codeDraft,
                onValueChange = onCodeDraftChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("主机的配对码") },
                placeholder = { Text("6 位数字") },
            )
            Button(
                onClick = { vm.setLinkPairingCode(codeDraft) },
                colors = ButtonDefaults.buttonColors(containerColor = NotifyAmber),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("保存")
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard(title = "主机", accent = NotifyAmber) {
        if (masters.isEmpty()) {
            Text(
                searchStatus.ifBlank { "正在搜索主机… 请确认两台手机在同一 Wi-Fi，且主机已切到「作为主机」。" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "点一下选择要用的主机（不选则自动用最新发现的那台）：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            masters.forEach { master ->
                LinkOptionRow(
                    title = master.alias,
                    subtitle = "${master.endpoint} · ${if (activeEndpoint == master.endpoint) "使用中" else "已发现"}",
                    selected = activeEndpoint == master.endpoint,
                ) { vm.selectLinkMaster(master.fingerprint) }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.selectLinkMaster(null) }) {
                Text("恢复自动选择")
            }
        }
        Spacer(Modifier.height(8.dp))
        val endpoint = activeEndpoint
        if (endpoint != null) {
            KeyValueRow("当前转发到", endpoint, valueColor = NotifyAmber)
            Spacer(Modifier.height(4.dp))
        }
        lastSend?.let { send ->
            Text(
                if (send.ok) "最近转发成功：${send.note}" else "最近转发失败：${send.note}",
                style = MaterialTheme.typography.labelMedium,
                color = if (send.ok) MaterialTheme.colorScheme.primary else PulseRed,
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { vm.sendLinkTest() },
            colors = ButtonDefaults.buttonColors(containerColor = NotifyAmber),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("发送测试通知给主机")
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "会真实推到手环 —— 手环弹出「从机测试通知」即整条链路打通。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    SectionCard(title = "连接不上时", accent = NotifyAmber) {
        Text(
            "自动发现依赖 Wi-Fi 组播，部分路由器会拦。搜不到主机时，在这里手动填主机地址 " +
                "（格式 ip:端口，端口见主机页面顶部状态，主机重启后端口可能变化，以主机页为准）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = hostDraft,
                onValueChange = onHostDraftChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("主机地址 ip:端口") },
                placeholder = { Text("192.168.1.20:45123") },
            )
            Button(
                onClick = { vm.setLinkManualHost(hostDraft) },
                colors = ButtonDefaults.buttonColors(containerColor = NotifyAmber),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("保存")
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { vm.setLinkManualHost("") }) {
            Text("清除手动地址")
        }
    }
}

// ---------------------------------------------------------------------------
// 通用行
// ---------------------------------------------------------------------------

/** 单选行：整行可点，前面一个 RadioButton，用在角色选择与主机选择。 */
@Composable
private fun LinkOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
