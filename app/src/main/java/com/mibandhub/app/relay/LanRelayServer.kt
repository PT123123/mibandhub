package com.mibandhub.app.relay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.mibandhub.app.relay.model.RelayBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 手机侧局域网伴随服务 + 组播宣告 —— 对应电脑端 health-motion 的 LAN hub 的「被拉取」半边。
 *
 * 桌面端会主动「发现 → 拉取」这台手机，所以手机必须：
 *  - 嵌一个极简 HTTP 服务，暴露两个端点（其余路径回 `{"app":"shouhuan-relay"}`）：
 *      GET /api/health-motion/v1/info     报自身 DeviceInfo（alias / device_type=phone / port / fingerprint）
 *      GET /api/health-motion/v1/snapshot 把当前手环数据打成桌面端 Snapshot 返回，供桌面「拉取」
 *  - 通过 UDP 组播 224.0.0.167:53317 每 2 秒宣告自身，桌面端据此把本机列进「已发现设备」。
 *
 * 这个服务只在「局域网中转」模式下由 [RelayRepository] 启停。
 * 零额外依赖：标准 Socket + org.json。
 */
object LanRelayServer {

    private const val TAG = "LanRelayServer"
    private const val MULTICAST_ADDR = "224.0.0.167"
    private const val MULTICAST_PORT = 53317
    private const val ANNOUNCE_INTERVAL_MS = 2000L
    private const val CLIENT_TIMEOUT_MS = 5000

    /**
     * 本地没数据时的最小合法快照。桌面端 `ShouhuanData` 的三个数组字段没有
     * `#[serde(default)]`，缺键会让整个 Snapshot 解析失败，所以这里必须带空数组。
     */
    private const val EMPTY_SNAPSHOT =
        "{\"band\":{\"sleeps\":[],\"hr_samples\":[],\"measures\":[]}}"

    /** 进程级指纹：桌面端用它去重 / 自识别；重启会变（同桌面端策略）。 */
    private val fingerprint: String = buildFingerprint()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var announceSocket: DatagramSocket? = null
    @Volatile private var running = false
    private var serverJob: Job? = null
    private var announceJob: Job? = null
    /** 组播锁：很多 Android 设备默认丢弃组播包，拿了锁接收桌面宣告才稳。 */
    private var multicastLock: WifiManager.MulticastLock? = null

    /** 当前 HTTP 服务监听的端口（0 = 未启动）。 */
    @Volatile var httpPort: Int = 0
        private set

    fun isRunning(): Boolean = running

    /**
     * 启动伴随服务（幂等：已在运行直接返回）。
     * @param context 用于申请组播锁（[Context.WIFI_SERVICE]）。
     * @param aliasProvider 取本机别名（随配置变化）。
     * @param snapshotProvider 取当前要同步的 [RelayBatch]（band 数据从这里来；为 null 时回空快照）。
     *        因为底层是 DataStore 的挂起 Flow，所以这个 provider 本身是挂起函数。
     * @param scope 运行协程的作用域（用仓库的进程级作用域即可）。
     */
    fun start(
        context: Context,
        aliasProvider: () -> String,
        snapshotProvider: suspend () -> RelayBatch?,
        scope: CoroutineScope,
    ) {
        if (running) return
        running = true
        // 申请组播锁，保证能收到桌面端的 UDP 组播宣告
        runCatching {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("relay-discovery").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }.onFailure { Log.w(TAG, "申请组播锁失败（不影响手动填地址同步）：${it.message}") }
        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val ss = ServerSocket(0) // 系统分配空闲端口
                serverSocket = ss
                httpPort = ss.localPort
                Log.i(TAG, "局域网伴随服务已启动 :$httpPort")
                announceJob = launch { announceLoop(aliasProvider) }
                while (isActive && running) {
                    runCatching {
                        ss.accept().use { client -> handleClient(client, aliasProvider, snapshotProvider) }
                    }.onFailure {
                        if (running) Log.w(TAG, "处理连接异常：${it.message}")
                    }
                }
            }.onFailure { Log.w(TAG, "伴随服务启动失败：${it.message}") }
            stopInternal()
        }
    }

    /** 停止伴随服务（幂等）。 */
    fun stop() {
        if (!running) return
        running = false
        runCatching { serverSocket?.close() }
        runCatching { announceSocket?.close() }
        announceJob?.cancel()
        serverJob?.cancel()
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    private fun stopInternal() {
        running = false
        httpPort = 0
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { announceSocket?.close() }
        announceSocket = null
        announceJob = null
        serverJob = null
        releaseMulticastLock()
    }

    /** 周期性向组播组宣告自身，让桌面端发现并得知拉取端口。 */
    private suspend fun CoroutineScope.announceLoop(aliasProvider: () -> String) {
        runCatching {
            val sock = DatagramSocket().also { announceSocket = it }
            val group = InetAddress.getByName(MULTICAST_ADDR)
            while (isActive && running) {
                runCatching {
                    val body = JSONObject()
                        .put("alias", aliasProvider())
                        .put("device_type", "phone")
                        .put("port", httpPort)
                        .put("protocol", "http")
                        .put("fingerprint", fingerprint)
                        .put("announce", true)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(body, body.size, group, MULTICAST_PORT))
                }.onFailure { if (running) Log.w(TAG, "宣告发送失败：${it.message}") }
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }.onFailure { if (running) Log.w(TAG, "宣告循环异常：${it.message}") }
    }

    /** 处理一个 HTTP 客户端连接（最小 HTTP/1.1 实现，只支持 GET）。 */
    private suspend fun handleClient(
        client: Socket,
        aliasProvider: () -> String,
        snapshotProvider: suspend () -> RelayBatch?,
    ) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        // 不用 use 包 reader：关 InputStream 会把整个 Socket 一起关掉，响应可能来不及发完。
        // Socket 由外层 accept().use 统一关闭。
        val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"
        // 读掉请求头，直到空行（HTTP 头结束）
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val body = when {
            method == "GET" && path == "/api/health-motion/v1/info" ->
                JSONObject()
                    .put("alias", aliasProvider())
                    .put("device_type", "phone")
                    .put("port", httpPort)
                    .put("protocol", "http")
                    .put("fingerprint", fingerprint)
                    .toString()
            method == "GET" && path == "/api/health-motion/v1/snapshot" ->
                snapshotProvider()?.toSnapshotJson() ?: EMPTY_SNAPSHOT
            else -> "{\"app\":\"shouhuan-relay\"}"
        }
        writeResponse(client.getOutputStream(), body)
    }

    private fun writeResponse(out: java.io.OutputStream, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(payload)
        out.flush()
    }

    private fun buildFingerprint(): String {
        val n = System.nanoTime() xor (android.os.Process.myPid().toLong() shl 16)
        return "%032x".format(n)
    }
}
