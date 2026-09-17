package com.ted.shouhuan.ui.device

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ted.shouhuan.data.Pairing
import com.ted.shouhuan.data.PairingQr
import com.ted.shouhuan.ui.components.NoticeBanner
import com.ted.shouhuan.ui.components.SectionCard
import com.ted.shouhuan.ui.theme.Mint
import com.ted.shouhuan.ui.theme.NotifyAmber
import com.ted.shouhuan.ui.theme.PulseRed
import kotlinx.coroutines.launch

/**
 * 配对手环：填名称 + MAC + AuthKey。
 *
 * 页面存在的理由很具体 —— **换手机**。手环的密钥绑的是「小米账号 + 手环」，
 * 不绑手机；所以换机之后密钥原样填回来就能用，缺的只是一个「填进去」的地方。
 * 没有这一页，App 里的「重新配对」按钮点了也不知道该干什么。
 *
 * 输入这里做了三个不显眼但省事的设计：
 *   1. **容错解析**：`0x` 前缀、冒号、横杠、空格、换行都随便带（[Pairing] 负责收敛），
 *      从日志或聊天记录里直接粘过来不会因为多了一个字符就被拒；
 *   2. **按字段说清哪里不对**，而不是笼统报「格式错误」；
 *   3. **密钥默认盖住**，但留了「显示」——用户得有机会核对粘贴对没对。
 */
@Composable
fun PairingScreen(vm: DeviceViewModel, onDone: () -> Unit) {
    val seed by vm.pairingSeed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 扫码覆盖层开关：点「扫码填入」打开相机，扫到本工具的配对二维码自动回填
    var scanning by remember { mutableStateOf(false) }

    // 以「已存的配对信息」为初值：重新配对就是来改的，不该让用户从空白开始敲。
    // 用 seed 做 remember 的键 —— 存储还没读出来时 seed 是 null（先渲染空表单），
    // 读出来之后一次性填上。这个间隔只有几毫秒，人来不及输入。
    var nameText by remember(seed) { mutableStateOf(seed?.name.orEmpty()) }
    var macText by remember(seed) { mutableStateOf(seed?.mac.orEmpty()) }
    var keyText by remember(seed) { mutableStateOf(seed?.authKey.orEmpty()) }
    var keyVisible by remember { mutableStateOf(false) }

    val normalizedMac = Pairing.normalizeMac(macText)
    val normalizedKey = Pairing.normalizeAuthKey(keyText)
    val canSave = normalizedMac != null && normalizedKey != null

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text("配对手环", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(10.dp))

        // ---- 先讲清楚这一页是干什么的，以及「换机不用重新取密钥」这个反直觉的事实 ----
        NoticeBanner(
            title = "填一次，之后就不用管了",
            tone = Mint,
            detail = "AuthKey 绑的是「小米账号 + 手环」，不绑手机 —— 换手机后把原来的 " +
                "MAC 和密钥填回来就能连，不必重新取。",
            hint = "认证不过时，先在「小米运动健康」里断开它再试 —— 两边同时连着的话会抢指令。",
        )

        Spacer(Modifier.height(12.dp))

        // ---- 设备身份 ----
        SectionCard(title = "设备身份", accent = MaterialTheme.colorScheme.primary) {
            // 最省事的入口：电脑上 `python pairing_qr.py` 弹出二维码，这里扫一下直填
            Button(
                onClick = { scanning = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("扫码填入（推荐）")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "对着电脑屏幕上的二维码扫一下，名称、MAC、AuthKey 自动填好。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text("名称（随便起）") },
                placeholder = { Text(Pairing.DEFAULT_NAME) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            val macProblem = if (macText.isNotBlank()) Pairing.macProblem(macText) else null
            OutlinedTextField(
                value = macText,
                onValueChange = { macText = it },
                label = { Text("MAC 地址") },
                placeholder = { Text(Pairing.MAC_EXAMPLE) },
                singleLine = true,
                isError = macProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = {
                    if (macProblem != null) {
                        Text(macProblem, color = PulseRed, style = MaterialTheme.typography.labelMedium)
                    } else {
                        Text(
                            if (normalizedMac != null) {
                                "将保存为 $normalizedMac"
                            } else {
                                "冒号、横杠、大小写都无所谓，位数对上就行"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (normalizedMac != null) {
                                Mint
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            val keyProblem = if (keyText.isNotBlank()) Pairing.authKeyProblem(keyText) else null
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text("AuthKey") },
                placeholder = { Text("0x 加 32 位十六进制") },
                singleLine = true,
                isError = keyProblem != null,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(
                            if (keyVisible) "隐藏" else "显示",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                supportingText = {
                    if (keyProblem != null) {
                        Text(keyProblem, color = PulseRed, style = MaterialTheme.typography.labelMedium)
                    } else {
                        Text(
                            if (normalizedKey != null) {
                                "将保存为 ${Pairing.maskAuthKey(normalizedKey)}"
                            } else {
                                "手环的控制权就在这串字符上，只存在本机、不上云"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (normalizedKey != null) {
                                Mint
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 怎么拿 AuthKey ----
        SectionCard(title = "怎么拿 AuthKey", accent = NotifyAmber) {
            Text(
                "没有密钥就认证不过去，手环会在握手阶段直接断连。四条路，从省事到麻烦：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Text("① 手机连着电脑（推荐）", style = MaterialTheme.typography.bodyMedium, color = Mint)
            Spacer(Modifier.height(6.dp))
            Text(
                "手机端零操作：官方 App 一启动就把密钥写进日志，直接从 adb 拉下来解析。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            MonoBlock("just fetch", onCopy = { clipboard.setText(AnnotatedString("just fetch")) })

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(14.dp))

            Text("② 只有手机", style = MaterialTheme.typography.bodyMedium, color = Mint)
            Spacer(Modifier.height(6.dp))
            Text(
                "用 MT 管理器把官方 App 的日志导出到 Download，再解析；仓库里的 Termux 小部件 " +
                    "把这一步做成了点一下。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            MonoBlock(
                "python parse_log.py <日志文件>",
                onCopy = { clipboard.setText(AnnotatedString("python parse_log.py ")) },
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(14.dp))

            Text("③ 登录小米接口", style = MaterialTheme.typography.bodyMedium, color = Mint)
            Spacer(Modifier.height(6.dp))
            Text(
                "不用碰日志，直接拿账号换设备列表。会触发风控，别连着重试。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            MonoBlock(
                "python xiaomi_authkey.py -e 你的账号",
                onCopy = { clipboard.setText(AnnotatedString("python xiaomi_authkey.py -e ")) },
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(14.dp))

            Text("④ 电脑生成二维码，扫一下直填", style = MaterialTheme.typography.bodyMedium, color = Mint)
            Spacer(Modifier.height(6.dp))
            Text(
                "手机连电脑（USB 调试），电脑上跑一条命令，屏幕弹出配对二维码 —— " +
                    "点上面的「扫码填入」扫一下，MAC 和 AuthKey 自动填好，一个字符都不用敲。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            MonoBlock(
                "python pairing_qr.py",
                onCopy = { clipboard.setText(AnnotatedString("python pairing_qr.py")) },
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "四条路的完整说明、排障和踩过的坑都写在仓库 README 的「怎么获取 AuthKey」一节。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val mac = normalizedMac ?: return@Button
                val key = normalizedKey ?: return@Button
                scope.launch {
                    vm.savePairing(mac, Pairing.normalizeName(nameText), key)
                    onDone()
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("保存配对信息")
        }

        if (!canSave) {
            Spacer(Modifier.height(8.dp))
            Text(
                "MAC 和 AuthKey 都填对（或填全）之后才能保存。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (scanning) {
        QrScannerOverlay(
            onDismiss = { scanning = false },
            onResult = { text ->
                val payload = PairingQr.parse(text)
                if (payload == null) {
                    Toast.makeText(context, "不是手环管家的配对二维码", Toast.LENGTH_SHORT).show()
                } else {
                    nameText = payload.name ?: nameText
                    macText = payload.mac
                    keyText = payload.authKey
                    scanning = false
                }
            },
        )
    }
    }
}

/** 一行可以点一下复制的命令 —— 让人手敲一条 `just fetch` 是不必要的折磨。 */
@Composable
private fun MonoBlock(command: String, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(10.dp),
            )
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f)) {
            Text(
                command,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(onClick = onCopy) { Text("复制") }
    }
}
