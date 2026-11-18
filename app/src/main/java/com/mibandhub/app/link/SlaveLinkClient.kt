package com.mibandhub.app.link

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket

/**
 * 从机侧的发现 + 发送 —— 主从转发的「发」半边。
 *
 * 从机（不连手环的那台）在切到「作为从机」后：
 *  - 监听 UDP 组播 224.0.0.168:53318，把主机的宣告（alias/port/fingerprint）实时
 *    汇报给 [onAnnounce]（LinkManager 维护发现列表，自动跟上主机换端口/IP）；
 *  - [post] 把一条过滤完的通知 HTTP POST 给主机（带 X-Link-Code 配对码头）。
 *
 * 很多 Android 设备默认丢弃组播包，必须拿 MulticastLock 才收得到 —— 与
 * LanRelayServer 的发现对称。零额外依赖：标准 Socket + org.json。
 */
object SlaveLinkClient {

    private const val TAG = "SlaveLinkClient"
    private const val MULTICAST_ADDR = "224.0.0.168"
    private const val MULTICAST_PORT = 53318
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    @Volatile private var running = false
    private var listenJob: Job? = null
    @Volatile private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 开始监听主机宣告（幂等）。
     * @param onAnnounce 每收到一台主机的宣告回调一次（同主机会反复回调 —— 端口/IP 变了也跟得上）。
     * @param onStatus 搜索状态文案（"正在搜索主机…" / 失败原因），给 UI 直接显示。
     */
    fun start(
        context: Context,
        scope: CoroutineScope,
        onAnnounce: (DiscoveredMaster) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        if (running) return
        running = true
        // 申请组播锁，保证能收到主机的 UDP 组播宣告
        runCatching {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("link-discovery").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }.onFailure { Log.w(TAG, "申请组播锁失败（可能收不到宣告，可改用手动地址）：${it.message}") }
        listenJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val sock = MulticastSocket(MULTICAST_PORT).also { socket = it }
                sock.joinGroup(InetAddress.getByName(MULTICAST_ADDR))
                onStatus("正在搜索主机…")
                Log.i(TAG, "从机开始监听主机宣告 :$MULTICAST_PORT")
                val buf = ByteArray(4096)
                while (isActive && running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt) // 阻塞；stop() 关 socket 会以异常唤醒这里
                    val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (json.optString("app") != "shouhuan-link") continue
                    if (json.optString("role") != "master") continue
                    val port = json.optInt("port", 0)
                    val host = pkt.address?.hostAddress ?: continue
                    if (port <= 0 || host.isBlank()) continue
                    onAnnounce(
                        DiscoveredMaster(
                            fingerprint = json.optString("fingerprint").ifBlank { host },
                            alias = json.optString("alias").ifBlank { "主机" },
                            host = host,
                            port = port,
                            lastSeenAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }.onFailure {
                if (running) {
                    Log.w(TAG, "监听主机宣告异常：${it.message}")
                    onStatus("搜索失败：${it.message ?: "未知错误"}，可改用手动地址")
                }
            }
        }
    }

    /** 停止监听（幂等）。 */
    fun stop() {
        if (!running) return
        running = false
        runCatching { socket?.close() }
        socket = null
        listenJob?.cancel()
        listenJob = null
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    /**
     * 把一条通知 POST 给主机（阻塞 IO，挂起执行）。
     *
     * 响应约定：
     *   200 {"ok":true,"alias":"主机名"}                          成功（主机已接手）
     *   200 {"ok":false,"note":"原因"}                            业务失败（如主机没连上手环）
     *   403 {"ok":false,"error":"配对码不正确"}                    门禁拒绝
     */
    suspend fun post(host: String, port: Int, code: String, bodyJson: String): LinkSendResult =
        withContext(IO) {
            runCatching {
                val sock = Socket()
                try {
                    sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    sock.soTimeout = READ_TIMEOUT_MS
                    val payload = bodyJson.toByteArray(Charsets.UTF_8)
                    val header = buildString {
                        append("POST /api/link/v1/notify HTTP/1.1\r\n")
                        append("Host: $host:$port\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("X-Link-Code: $code\r\n")
                        append("Content-Length: ${payload.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    sock.getOutputStream().apply {
                        write(header.toByteArray(Charsets.UTF_8))
                        write(payload)
                        flush()
                    }
                    val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                    val statusLine = reader.readLine() ?: error("主机返回了空响应")
                    val statusOk = statusLine.contains(" 200")
                    var contentLength = -1
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.lowercase().startsWith("content-length:")) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                        }
                    }
                    val respBody = if (contentLength >= 0) {
                        val chars = CharArray(contentLength)
                        var n = 0
                        while (n < contentLength) {
                            val r = reader.read(chars, n, contentLength - n)
                            if (r < 0) break
                            n += r
                        }
                        String(chars, 0, n)
                    } else {
                        reader.readText()
                    }
                    val json = runCatching { JSONObject(respBody) }.getOrNull()
                        ?: error("主机响应不是合法 JSON")
                    val ok = statusOk && json.optBoolean("ok", false)
                    if (ok) {
                        LinkSendResult(true, json.optString("alias", "主机"))
                    } else {
                        LinkSendResult(
                            false,
                            json.optString("note", "").ifBlank {
                                json.optString("error", "").ifBlank { "主机返回失败" }
                            },
                        )
                    }
                } finally {
                    runCatching { sock.close() }
                }
            }.getOrElse {
                Log.w(TAG, "发送通知给主机失败：${it.message}")
                LinkSendResult(false, "主机不可达：${it.message ?: it.javaClass.simpleName}")
            }
        }
}
