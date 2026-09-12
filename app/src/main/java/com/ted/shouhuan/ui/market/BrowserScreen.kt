package com.ted.shouhuan.ui.market

import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 浏览器起点：直接落到 Mi Band 5 的最新表盘列表，站内导航（热门/标签/搜索）随便逛。 */
private const val START_URL = "https://amazfitwatchfaces.com/mi-band-5/fresh"

/**
 * 内置浏览器：直接逛 amazfitwatchfaces.com。
 *
 * 为什么要它：HttpURLConnection 那条路要跟源站反爬斗智斗勇 —— Dalvik UA 直接 403、
 * 偶发 200 + 空响应（实测）；而 WebView 是真浏览器内核，JS、Cookie、TLS 指纹都是
 * 正品，稳过。在这里看中哪张表盘，记住名字回市场页搜来下载即可。
 *
 * 系统返回键优先让 WebView 回退浏览历史，退无可退才退出本页。
 */
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text("站点浏览器", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (progress in 1..99) {
                    CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
            Text(
                "amazfitwatchfaces.com · 看中哪张回市场页搜名字下载",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                            canGoBack = v.canGoBack()
                        }

                        override fun onPageFinished(v: WebView, url: String?) {
                            progress = 100
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(v: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    loadUrl(START_URL)
                }.also { webView = it }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
