package com.mibandhub.app.link

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 主机侧的局域网接收服务 + 组播宣告 —— 主从转发的「收」半边。
 *
 * 主机（连手环的那台）在切到「作为主机」后：
 *  - 嵌一个极简 HTTP 服务（零依赖，标准 Socket + org.json，与 LanRelayServer 同款思路）：
 *      GET  /api/link/v1/ping    从机验连通性 + 验配对码（X-Link-Code 头），回主机的 alias
 *      POST /api/link/v1/notify  从机把过滤后的通知发过来，主机验码后交给 [onNotify] 推手环
 *  - 每 2 秒向 UDP 组播 224.0.0.168:53318 宣告自己，从机据此自动发现主机。
 *
 * 安全模型：局域网内明文 HTTP，靠 6 位配对码做门禁（主机页展示、从机页填入）；
 * 端口每次启动随机，宣告里带 port，主机重启后从机靠 fingerprint + 新宣告自动跟上。
 * 提示文案里明确「仅建议在可信局域网使用」。
 */
object MasterLinkServer {

    private const val TAG = "MasterLinkServer"
    private const val MULTICAST_ADDR = "224.0.0.168"
    private const val MULTICAST_PORT = 53318
    private const val ANNOUNCE_INTERVAL_MS = 2000L
    private const val CLIENT_TIMEOUT_MS = 8000

    /**
     * 首选固定端口：从机手动填地址时不用跟着主机重启而改（组播发现被路由器拦的
     * 环境里，这是唯一稳定的寻址方式）。被占用时回落系统随机端口。
     */
    private const val PREFERRED_PORT = 45318

    private val _port = MutableStateFlow(0)

    /** 当前 HTTP 服务监听的端口（0 = 未启动），UI 与从机宣告共用。 */
    val port: StateFlow<Int> = _port.asStateFlow()

    @Volatile private var fingerprint = ""
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var announceSocket: DatagramSocket? = null
    private var serverJob: Job? = null
    private var announceJob: Job? = null

    fun isRunning(): Boolean = running

    /**
     * 启动接收服务（幂等：已在运行直接返回）。
     * @param aliasProvider 本机名字（宣告 + 响应里回给从机）。
     * @param codeProvider 当前配对码 —— 每个请求实时取，重新生成后立即生效。
     * @param onNotify 校验通过的从机通知，交给 [LinkManager] 推手环并落记录。
     */
    fun start(
        context: Context,
        aliasProvider: () -> String,
        codeProvider: () -> String,
        onNotify: suspend (JSONObject) -> LinkSendResult,
        scope: CoroutineScope,
    ) {
        if (running) return
        fingerprint = buildFingerprint(context.applicationContext)
        running = true
        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                // 先试固定端口（手动寻址稳定），被占再回落随机端口
                val ss = runCatching { ServerSocket(PREFERRED_PORT) }
                    .getOrElse { ServerSocket(0) }
                serverSocket = ss
                _port.value = ss.localPort
                Log.i(
                    TAG,
                    "主从接收服务已启动 :${_port.value}" +
                        (if (_port.value == PREFERRED_PORT) "（固定端口）" else "（固定端口被占，回落随机）") +
                        "，指纹 $fingerprint",
                )
                announceJob = launch { announceLoop(aliasProvider) }
                while (isActive && running) {
                    runCatching {
                        ss.accept().use { handleClient(it, aliasProvider, codeProvider, onNotify) }
                    }.onFailure {
                        if (running) Log.w(TAG, "处理从机连接异常：${it.message}")
                    }
                }
            }.onFailure { Log.w(TAG, "主从接收服务启动失败：${it.message}") }
            stopInternal()
        }
    }

    /** 停止接收服务（幂等）。 */
    fun stop() {
        if (!running) return
        running = false
        runCatching { serverSocket?.close() }
        runCatching { announceSocket?.close() }
        announceJob?.cancel()
        serverJob?.cancel()
        stopInternal()
    }

    private fun stopInternal() {
        running = false
        _port.value = 0
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { announceSocket?.close() }
        announceSocket = null
        announceJob = null
        serverJob = null
    }

    /** 周期性向组播组宣告自己是主机，从机据此发现并得知接收端口。 */
    private suspend fun CoroutineScope.announceLoop(aliasProvider: () -> String) {
        runCatching {
            val sock = DatagramSocket().also { announceSocket = it }
            val group = InetAddress.getByName(MULTICAST_ADDR)
            while (isActive && running) {
                runCatching {
                    val body = JSONObject()
                        .put("app", "shouhuan-link")
                        .put("role", "master")
                        .put("alias", aliasProvider())
                        .put("port", _port.value)
                        .put("fingerprint", fingerprint)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(body, body.size, group, MULTICAST_PORT))
                }.onFailure { if (running) Log.w(TAG, "宣告发送失败：${it.message}") }
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }.onFailure { if (running) Log.w(TAG, "宣告循环异常：${it.message}") }
    }

    /** 处理一个从机的 HTTP 连接（最小 HTTP/1.1：GET ping / POST notify）。 */
    private suspend fun handleClient(
        client: Socket,
        aliasProvider: () -> String,
        codeProvider: () -> String,
        onNotify: suspend (JSONObject) -> LinkSendResult,
    ) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        // 头部用 ASCII 逐行读、请求体按 Content-Length 读**字节**再 UTF-8 解码。
        // 不能用 BufferedReader：缓冲会预读 body 字节，且中文 1 字符 = 3 字节，
        // 按字符数读字节长度的 body 会读到不存在的字符、阻塞到超时。
        val input = client.getInputStream()
        val requestLine = readAsciiLine(input)
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: "/"
        var contentLength = 0
        var linkCode = ""
        while (true) {
            val line = readAsciiLine(input)
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            if (lower.startsWith("x-link-code:")) {
                linkCode = line.substringAfter(':').trim()
            }
        }
        val bodyJson = if (contentLength > 0) readUtf8Body(input, contentLength) else ""

        val out = client.getOutputStream()
        when {
            method == "GET" && path == "/api/link/v1/ping" -> {
                val ok = linkCode.isNotBlank() && linkCode == codeProvider()
                val json = JSONObject()
                    .put("ok", ok)
                    .put("alias", aliasProvider())
                    .put("role", "master")
                writeJson(out, if (ok) 200 else 403, json)
            }

            method == "POST" && path == "/api/link/v1/notify" -> {
                if (linkCode.isBlank() || linkCode != codeProvider()) {
                    Log.w(TAG, "从机请求配对码不正确")
                    writeJson(out, 403, JSONObject().put("ok", false).put("error", "配对码不正确"))
                    return
                }
                val json = runCatching { JSONObject(bodyJson) }.getOrNull()
                if (json == null) {
                    writeJson(out, 400, JSONObject().put("ok", false).put("error", "请求体不是合法 JSON"))
                    return
                }
                val result = runCatching { onNotify(json) }
                    .getOrElse { LinkSendResult(false, "主机处理异常：${it.message}") }
                writeJson(
                    out,
                    200,
                    JSONObject()
                        .put("ok", result.ok)
                        .put("alias", aliasProvider())
                        .put("note", result.note),
                )
            }

            else -> writeJson(out, 200, JSONObject().put("app", "shouhuan-link"))
        }
    }

    /** 从流里读一行 ASCII 头（消费掉行尾 CRLF）。 */
    private fun readAsciiLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == 10) break // LF 结束一行
            if (c != 13) sb.append(c.toChar()) // 丢弃 CR
        }
        return sb.toString()
    }

    /** 按 Content-Length 精确读满请求体的**字节**，再 UTF-8 解码 —— 中文正文才有正确长度语义。 */
    private fun readUtf8Body(input: java.io.InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(bytes, n, length - n)
            if (r < 0) break
            n += r
        }
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    private fun writeJson(out: java.io.OutputStream, status: Int, body: JSONObject) {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(payload)
        out.flush()
    }

    /** 与 LanRelayServer 同款 FNV-1a 64，按 ANDROID_ID 派生，跨重启稳定。 */
    private fun buildFingerprint(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (androidId.isNullOrBlank()) {
            val n = System.nanoTime() xor (android.os.Process.myPid().toLong() shl 16)
            return "%032x".format(n)
        }
        val seed = "shouhuan-link|$androidId"
        var h: Long = 0xcbf2_9ce4_8422_2325uL.toLong()
        for (b in seed.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100_0000_01b3L
        }
        return java.lang.Long.toHexString(h).padStart(32, '0')
    }
}
