package com.ted.shouhuan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ted.shouhuan.ui.theme.ShouhuanTheme
import com.ted.shouhuan.ui.theme.SleepIndigo

/**
 * 健康连接的「数据用途说明」页 —— 外部数据源（HealthConnectSleepSource）的接入要求。
 *
 * 健康连接 SDK 在建连时会校验调用方 manifest：必须有一个能响应
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`（Android 13-）或
 * `android.intent.action.VIEW_PERMISSION_USAGE`（Android 14+，经 activity-alias，
 * 见 AndroidManifest）的入口，否则直接抛 IllegalStateException
 * （真机报过 "incorrect health permission states"）。健康连接的
 * 「应用权限 → 了解详情」也会跳到这里，所以内容按给人看的说明写。
 *
 * 只展示、不读任何数据，也没有任何交互入口。
 */
class HealthRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShouhuanTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "睡眠数据用途说明",
                        style = MaterialTheme.typography.titleLarge,
                        color = SleepIndigo,
                    )
                    Text(
                        "「手环管家」通过健康连接读取你的睡眠记录，用于在应用内展示" +
                            "睡眠分期、时长与历史统计。数据只保存在手机本机（应用私有目录），" +
                            "不上传任何服务器；卸载应用即一并删除。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "来源说明：手环同步的数据来自与你配对的小米手环；健康连接导入的" +
                            "数据来自系统健康连接中已授权的数据源（如小米运动健康）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("知道了")
                    }
                }
            }
        }
    }
}
