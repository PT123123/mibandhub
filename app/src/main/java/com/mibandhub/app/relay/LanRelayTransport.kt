package com.mibandhub.app.relay

import com.mibandhub.app.relay.model.RelayBatch
import com.mibandhub.app.relay.model.RelayConfig
import com.mibandhub.app.relay.model.RelayDevice
import com.mibandhub.app.relay.model.RelayPushResult
import com.mibandhub.app.relay.model.RelaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL

/**
 * 局域网真实中转：对接电脑端 health-motion 的 LAN hub（协议与桌面端 `src/lan.rs` 严格对齐）。
 *
 * 发现：UDP 组播 224.0.0.167:53317 监听桌面端宣告（device_type="desktop"），把桌面端作为中转节点
 *       返回；组播没发现时退化为用配置里的 serverUrl（手动填桌面 IP:18765）。
 * 推送：把当前手环数据打成桌面端 [Snapshot]，POST 到
 *       http://<桌面IP>:<桌面端口>/api/health-motion/v1/push。
 *       桌面端该端点是无状态的（没有 LocalSend 那种 prepare/token 流程），所以 openSession 直接原样
 *       返回设备，不建会话。
 *
 * 零额外依赖：标准 Socket + HttpURLConnection + org.json，离线可编译。
 */
class LanRelayTransport(private val config: RelayConfig) : RelayTransport {

    override val scheme: String = "health-motion-lan"

    private val multicastAddr: InetAddress get() = InetAddress.getByName(MULTICAST_ADDR)

    override suspend fun discover(timeoutMs: Long): List<RelayDevice> = withContext(Dispatchers.IO) {
        val found = multicastScan(timeoutMs)
        if (found.isNotEmpty()) return@withContext found
        // 退化：用配置里的地址（用户手动填的桌面 IP:端口）
        runCatching {
            val u = URL(config.serverUrl)
            val host = u.host
            val port = if (u.port == -1) DEFAULT_HTTP_PORT else u.port
            listOf(
                RelayDevice(
                    id = "lan:$host:$port",
                    name = "局域网中转（$host）",
                    host = host,
                    port = port,
                    fingerprint = "待连接后获取",
                    isMock = false,
                ),
            )
        }.getOrDefault(emptyList())
    }

    /** 组播扫描桌面端宣告，返回去重后的桌面节点列表。 */
    private fun multicastScan(timeoutMs: Long): List<RelayDevice> {
        val sock = runCatching { MulticastSocket(MULTICAST_PORT) }.getOrElse { return emptyList() }
        return runCatching {
            sock.joinGroup(multicastAddr)
            sock.soTimeout = 1000
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            val seen = LinkedHashMap<String, RelayDevice>() // key = host:port，去重
            while (System.currentTimeMillis() < deadline) {
                runCatching {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val json = JSONObject(text)
                    val info = if (json.has("info")) json.getJSONObject("info") else json
                    // 只认桌面端（device_type="desktop"），忽略手机自身与其它手机
                    if (info.optString("device_type") != "desktop") return@runCatching
                    val host = pkt.address.hostAddress ?: return@runCatching
                    val port = info.optInt("port", DEFAULT_HTTP_PORT)
                    val fp = info.optString("fingerprint", "")
                    val alias = info.optString("alias", "健康运动（桌面）")
                    seen["$host:$port"] = RelayDevice(
                        id = "lan:$host:$port",
                        name = alias,
                        host = host,
                        port = port,
                        fingerprint = fp,
                        isMock = false,
                    )
                }.onFailure { /* 超时或坏包，继续扫 */ }
            }
            seen.values.toList()
        }.getOrDefault(emptyList()).also {
            runCatching { sock.leaveGroup(multicastAddr) }
            runCatching { sock.close() }
        }
    }

    override suspend fun openSession(device: RelayDevice): RelaySession =
        RelaySession(device = device, token = "", capabilities = listOf("push"))

    override suspend fun push(
        session: RelaySession,
        batch: RelayBatch,
        onProgress: (Float) -> Unit,
    ): RelayPushResult = withContext(Dispatchers.IO) {
        onProgress(0.1f)
        val url = "http://${session.device.host}:${session.device.port}/api/health-motion/v1/push"
        val body = batch.toSnapshotJson()
        val bytes = body.toByteArray(Charsets.UTF_8).size.toLong()
        runCatching {
            val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault("")
            onProgress(1f)
            if (code !in 200..299) throw IOException("HTTP $code")
            val ok = runCatching { JSONObject(text).optBoolean("ok", true) }.getOrDefault(true)
            if (!ok) throw IOException("桌面端返回 ok=false")
            RelayPushResult.Success(
                acceptedItems = batch.items.sumOf { it.count },
                acceptedBytes = bytes,
                receipt = "hm-lan",
            )
        }.getOrElse { RelayPushResult.Failure(it.message ?: "网络错误") }
    }

    companion object {
        const val MULTICAST_ADDR = "224.0.0.167"
        const val MULTICAST_PORT = 53317
        const val DEFAULT_HTTP_PORT = 18765
    }
}
