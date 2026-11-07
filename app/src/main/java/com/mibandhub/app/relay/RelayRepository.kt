package com.mibandhub.app.relay

import android.app.Application
import com.mibandhub.app.data.BandPrefs
import com.mibandhub.app.relay.model.RelayBatch
import com.mibandhub.app.relay.model.RelayConfig
import com.mibandhub.app.relay.model.RelayDevice
import com.mibandhub.app.relay.model.RelayLogEntry
import com.mibandhub.app.relay.model.RelayLogLevel
import com.mibandhub.app.relay.model.RelayMode
import com.mibandhub.app.relay.model.RelayPushResult
import com.mibandhub.app.relay.model.RelaySyncState
import com.mibandhub.app.relay.model.RelaySyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 数据中转的编排中枢（进程级单例，宿主常驻，和 [com.mibandhub.app.service.BandSessionProvider]
 * 同款思路）：发现节点、手动/自动同步、配置驱动、日志收集，全部在这里收口。
 *
 * 上层的 ViewModel / UI 只跟这里打交道；真正“往哪发、怎么发”全在 [RelayTransport] 里，
 * 所以以后对接真实局域网中转时，这个类一行都不用改。
 *
 * 后台同步：自动同步在当前进程级作用域里按间隔循环跑。若要做到「App 完全退出也照常同步」，
 * 这一步应在前台服务 / WorkManager 里托管 —— 那是正式对接时的集成点，本文件已把触发入口
 * （[syncNow]）留好，迁移时把循环搬到服务里即可。
 */
object RelayRepository {

    private lateinit var app: Application
    private lateinit var prefs: RelayPrefs
    private lateinit var payload: BandDataPayloadSource

    /** 进程级作用域，永不取消 —— 这是「后台常驻同步」的宿主。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 模拟中转全局单例（进程内只此一个，对应一台虚拟中转）。 */
    private val mockTransport = MockRelayTransport()

    private val _devices = MutableStateFlow<List<RelayDevice>>(emptyList())
    val devices: StateFlow<List<RelayDevice>> = _devices.asStateFlow()

    private val _state = MutableStateFlow(RelaySyncState())
    val state: StateFlow<RelaySyncState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<RelayLogEntry>>(emptyList())
    val logs: StateFlow<List<RelayLogEntry>> = _logs.asStateFlow()

    private val _config = MutableStateFlow(RelayConfig())
    val config: StateFlow<RelayConfig> = _config.asStateFlow()

    private val _preview = MutableStateFlow<RelayBatch?>(null)
    val preview: StateFlow<RelayBatch?> = _preview.asStateFlow()

    private var autoJob: kotlinx.coroutines.Job? = null

    fun get(application: Application): RelayRepository {
        if (!::app.isInitialized) {
            app = application.applicationContext as Application
            prefs = RelayPrefs(app)
            payload = BandDataPayloadSource(BandPrefs(app))
            // 配置一变就重选传输层 + 重启自动同步循环
            scope.launch { prefs.config.collect { onConfigChanged(it) } }
            // 启动时把上次同步结果回填（时间戳 + 结果串还原）
            scope.launch {
                val at = prefs.lastSyncAt.first()
                val raw = prefs.lastResult.first()
                _state.value = _state.value.copy(lastSyncAt = at, lastResult = parseRaw(raw))
            }
        }
        return this
    }

    // ---- 传输层选择：唯一决定“发往 mock 还是 lan”的地方 ----
    private fun transportFor(cfg: RelayConfig): RelayTransport =
        if (cfg.mode == RelayMode.LAN) LanRelayTransport(cfg) else mockTransport

    /** 上一次生效的传输模式，用于识别「换了传输层」并清掉旧节点缓存。 */
    private var lastMode: RelayMode? = null

    private fun onConfigChanged(cfg: RelayConfig) {
        // 首次配置生效也算一次：App 冷启动时配置走 DataStore Flow 异步到达，
        // 若不重来一次，可能残留用默认 MOCK 配置发现出来的假节点。
        val modeChanged = lastMode == null || lastMode != cfg.mode
        lastMode = cfg.mode
        _config.value = cfg
        autoJob?.cancel()
        if (modeChanged) {
            // 换传输层后旧节点已失效：清空并按新模式立刻重新发现。
            // 否则「模拟中转」的节点会残留，LAN 同步会把数据推到 localhost 而静默失败。
            _devices.value = emptyList()
            _state.value = _state.value.copy(
                device = null,
                status = RelaySyncStatus.IDLE,
                progress = 0f,
            )
            if (cfg.enabled) {
                scope.launch {
                    runCatching { discover() }
                        .onFailure { log(RelayLogLevel.ERROR, "切换模式后发现失败：${it.message}") }
                }
            }
        }
        // 局域网中转模式下，启动手机侧伴随服务（暴露 /info、/snapshot 供桌面端发现与拉取）；
        // 切回模拟模式则停掉，端口释放。
        if (cfg.mode == RelayMode.LAN) {
            LanRelayServer.start(
                context = app,
                aliasProvider = { _config.value.sourceAlias },
                snapshotProvider = { runCatching { payload.buildBatch(_config.value.sourceAlias) }.getOrNull() },
                scope = scope,
            )
        } else {
            LanRelayServer.stop()
        }
        if (cfg.enabled && cfg.autoSync) {
            autoJob = scope.launch {
                while (isActive) {
                    delay(cfg.intervalMinutes * 60_000L)
                    runCatching { syncNowInternal() }
                        .onFailure { log(RelayLogLevel.ERROR, "自动同步异常：${it.message}") }
                }
            }
        }
    }

    /** 发现局域网中转节点。 */
    suspend fun discover(timeoutMs: Long = 3000) {
        val cfg = _config.value
        _state.value = _state.value.copy(status = RelaySyncStatus.DISCOVERING)
        log(RelayLogLevel.INFO, "开始发现中转节点（模式=${cfg.mode}）…")
        runCatching {
            val list = transportFor(cfg).discover(timeoutMs)
            _devices.value = list
            val chosen = list.firstOrNull { it.id == cfg.selectedDeviceId } ?: list.firstOrNull()
            _state.value = _state.value.copy(status = RelaySyncStatus.IDLE, device = chosen)
            // 顺手预览一下本次会带什么数据
            runCatching { _preview.value = payload.buildBatch(cfg.sourceAlias) }
            if (chosen == null) {
                log(RelayLogLevel.WARN, "没有发现可用中转节点")
            } else {
                log(RelayLogLevel.INFO, "发现中转：${chosen.name}（${chosen.host}）指纹 ${chosen.fingerprint}")
            }
        }.onFailure { e ->
            _state.value = _state.value.copy(status = RelaySyncStatus.ERROR)
            log(RelayLogLevel.ERROR, "发现失败：${e.message}")
        }
    }

    /** 手动触发一次同步（线程安全，可重复调用）。 */
    fun syncNow() {
        scope.launch {
            runCatching { syncNowInternal() }
                .onFailure { log(RelayLogLevel.ERROR, "同步异常：${it.message}") }
        }
    }

    private suspend fun syncNowInternal() {
        val cfg = _config.value
        if (!cfg.enabled) {
            log(RelayLogLevel.WARN, "中转总开关已关，跳过同步")
            return
        }
        val device = _state.value.device ?: run {
            discover()
            _state.value.device ?: run {
                log(RelayLogLevel.WARN, "没有可用中转，无法同步")
                return
            }
        }

        val transport = transportFor(cfg)
        _state.value = _state.value.copy(status = RelaySyncStatus.SYNCING, progress = 0f)
        try {
            log(RelayLogLevel.INFO, "与 ${device.name} 建立会话…")
            val batch = payload.buildBatch(cfg.sourceAlias)
            _preview.value = batch
            if (batch.items.isEmpty()) {
                log(RelayLogLevel.WARN, "本地没有可同步的数据")
                _state.value = _state.value.copy(status = RelaySyncStatus.IDLE)
                return
            }
            val session = transport.openSession(device)
            log(
                RelayLogLevel.INFO,
                "会话已建立（token=${session.token.take(12)}…），推送 ${batch.items.sumOf { it.count }} 条",
            )
            val summary = batchSummary(batch)
            val result = transport.push(session, batch) { p ->
                _state.value = _state.value.copy(progress = p)
            }
            runCatching { transport.closeSession(session) }

            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(
                status = RelaySyncStatus.IDLE,
                progress = 0f,
                lastSyncAt = now,
                lastResult = result,
                lastBatchSummary = summary,
            )
            prefs.recordSync(now, result.toRaw())
            when (result) {
                is RelayPushResult.Success ->
                    log(
                        RelayLogLevel.INFO,
                        "同步成功：对方接收 ${result.acceptedItems} 条 / ${result.acceptedBytes} 字节（回执 ${result.receipt}）",
                    )

                is RelayPushResult.Failure ->
                    log(RelayLogLevel.ERROR, "同步失败：${result.error}")
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(status = RelaySyncStatus.ERROR, progress = 0f)
            log(RelayLogLevel.ERROR, "同步异常：${e.message}")
        }
    }

    suspend fun updateConfig(transform: (RelayConfig) -> RelayConfig) {
        val next = transform(_config.value)
        prefs.saveConfig(next)
        // onConfigChanged 会经 prefs.config 的收集者自动触发
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun log(level: RelayLogLevel, message: String) {
        val entry = RelayLogEntry(System.currentTimeMillis(), level, message)
        _logs.value = (_logs.value + entry).takeLast(200)
    }

    private fun batchSummary(b: RelayBatch): String =
        b.items.joinToString(" · ") { "${typeLabel(it.type)} ${it.count} 条" }

    private fun typeLabel(t: String): String = when (t) {
        "heart_rate" -> "心率"
        "sleep" -> "睡眠"
        "measure" -> "测量"
        "notification" -> "通知"
        else -> t
    }

    internal fun RelayPushResult.toRaw(): String = when (this) {
        is RelayPushResult.Success -> "ok:$acceptedItems:$acceptedBytes"
        is RelayPushResult.Failure -> "err:$error"
    }

    private fun parseRaw(raw: String?): RelayPushResult? = raw?.let {
        val p = it.split(":", limit = 3)
        when (p.firstOrNull()) {
            "ok" -> RelayPushResult.Success(
                p.getOrNull(1)?.toIntOrNull() ?: 0,
                p.getOrNull(2)?.toLongOrNull() ?: 0,
                "",
            )

            "err" -> RelayPushResult.Failure(p.getOrNull(1) ?: "未知错误")
            else -> null
        }
    }
}
