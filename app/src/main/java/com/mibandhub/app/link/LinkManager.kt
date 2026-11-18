package com.mibandhub.app.link

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.mibandhub.app.ble.ConnectionState
import com.mibandhub.app.data.BandNotification
import com.mibandhub.app.data.BandPrefs
import com.mibandhub.app.proto.Notify
import com.mibandhub.app.service.BandSessionProvider
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 主从转发的编排中枢（进程级单例，与 RelayRepository / BandSessionProvider 同款思路）。
 *
 * 职责：
 *  - 监听角色设置（落盘的 [LinkRole]），角色一变就启停对应的服务：
 *      主机 → [MasterLinkServer]（HTTP 接收 + 组播宣告）
 *      从机 → [SlaveLinkClient]（组播发现 + HTTP 发送）
 *  - 从机侧维护「已发现主机」列表与当前生效地址，主机换端口/IP 自动跟上；
 *  - 主机侧收到从机通知后验码、连手环（必要时自动连）、推手环并记入「最近推送」。
 *
 * ViewModel / UI / 监听器都只跟这里打交道。
 */
object LinkManager {

    private const val TAG = "LinkManager"

    private lateinit var app: Application
    private lateinit var prefs: BandPrefs

    /** 进程级作用域，永不取消 —— 服务启停与后台收发的宿主。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 别名 / 配对码的内存缓存：HTTP 处理线程上要同步读，不能挂起 ----
    @Volatile private var aliasCache = ""
    @Volatile private var codeCache = ""

    // ---- 从机侧状态 ----

    /** 已发现的主机，新的在前。同指纹主机反复宣告时原地更新（端口/IP 变了也跟得上）。 */
    private val _masters = MutableStateFlow<List<DiscoveredMaster>>(emptyList())
    val masters: StateFlow<List<DiscoveredMaster>> = _masters.asStateFlow()

    /** 从机搜索/连接状态的一句话描述，给 UI 直接显示。 */
    private val _searchStatus = MutableStateFlow("")
    val searchStatus: StateFlow<String> = _searchStatus.asStateFlow()

    /** 当前生效的主机地址 "ip:端口"（自动选中或手动选择），null = 还没定。 */
    private val _activeEndpoint = MutableStateFlow<String?>(null)
    val activeEndpoint: StateFlow<String?> = _activeEndpoint.asStateFlow()

    /** 最近一次从机 → 主机转发的结果（null = 本进程还没发过）。 */
    private val _lastSend = MutableStateFlow<LinkSendResult?>(null)
    val lastSend: StateFlow<LinkSendResult?> = _lastSend.asStateFlow()

    // ---- 主机侧状态 ----

    /** 本进程是否是主机角色（服务在跑）。 */
    @Volatile var isMasterRunning: Boolean = false
        private set

    private var currentRole: LinkRole? = null

    /** 主机侧正在做「连手环」这件事：同一时刻只发一波，后到的从机通知等它收尾。 */
    @Volatile private var masterConnecting = false

    fun get(context: Context): LinkManager {
        if (!::app.isInitialized) {
            app = context.applicationContext as Application
            prefs = BandPrefs(app)
            // 别名/配对码缓存随落盘变化实时刷新
            scope.launch { prefs.linkAlias.collect { aliasCache = it } }
            scope.launch { prefs.linkPairingCode.collect { codeCache = it } }
            // 角色一变就切换服务（首次也会触发一次，冷启动即恢复上次角色）
            scope.launch { prefs.linkRole.collect { onRoleChanged(it) } }
            // 重启后先把记住的主机地址显示出来（等组播到了再被真实地址覆盖）
            scope.launch {
                val remembered = prefs.linkSelectedEndpoint.first()
                if (!remembered.isNullOrBlank()) _activeEndpoint.value = remembered
            }
        }
        return this
    }

    /** 本机显示名：用户设置的名字优先，默认用机型兜底。 */
    fun defaultAlias(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "手机"

    private suspend fun alias(): String = aliasCache.ifBlank { defaultAlias() }

    private suspend fun onRoleChanged(role: LinkRole) {
        if (role == currentRole) return
        currentRole = role
        Log.i(TAG, "主从角色切换：$role")
        when (role) {
            LinkRole.MASTER -> {
                // 直接读盘判断配对码，不能用 codeCache —— 冷启动时 code 和 role 两个
                // collector 并发发射，缓存还没填充就会被误判成空、把好码重新生成掉。
                val stored = prefs.linkPairingCode.first()
                codeCache = stored
                if (stored.isBlank()) {
                    Log.i(TAG, "主机还没有配对码，自动生成")
                    prefs.regenerateLinkPairingCode()
                }
                SlaveLinkClient.stop()
                _masters.value = emptyList()
                _activeEndpoint.value = null
                _searchStatus.value = ""
                MasterLinkServer.start(
                    context = app,
                    aliasProvider = { aliasCache.ifBlank { defaultAlias() } },
                    codeProvider = { codeCache },
                    onNotify = { onSlaveNotify(it) },
                    scope = scope,
                )
                isMasterRunning = true
            }

            LinkRole.SLAVE -> {
                MasterLinkServer.stop()
                isMasterRunning = false
                _searchStatus.value = "正在搜索主机…"
                SlaveLinkClient.start(
                    context = app,
                    scope = scope,
                    onAnnounce = { onAnnounce(it) },
                    onStatus = { _searchStatus.value = it },
                )
            }

            LinkRole.NONE -> {
                MasterLinkServer.stop()
                SlaveLinkClient.stop()
                isMasterRunning = false
                _masters.value = emptyList()
                _activeEndpoint.value = null
                _searchStatus.value = ""
            }
        }
    }

    // ------------------------------------------------------------------
    // 从机侧：发现与选择
    // ------------------------------------------------------------------

    private fun onAnnounce(m: DiscoveredMaster) {
        val list = _masters.value.filterNot { it.fingerprint == m.fingerprint } + m
        _masters.value = list.sortedByDescending { it.lastSeenAt }
        scope.launch {
            val selected = prefs.linkSelectedMaster.first()
            when (selected) {
                null -> {
                    // 自动模式：跟随最新宣告（m 刚插入列表，时间戳最新必然排第一）；
                    // 主动换主机只在地址真的变了时刷新文案，避免每 2 秒重写一遍。
                    if (_activeEndpoint.value != m.endpoint) {
                        _activeEndpoint.value = m.endpoint
                        _searchStatus.value = "已发现主机：${m.alias}（${m.endpoint}）"
                    }
                }

                m.fingerprint -> {
                    // 选中主机换了端口/IP：宣告里带新地址，实时跟上
                    if (_activeEndpoint.value != m.endpoint) {
                        Log.i(TAG, "选中主机地址更新：${_activeEndpoint.value} → ${m.endpoint}")
                    }
                    _activeEndpoint.value = m.endpoint
                    _searchStatus.value = "已连接主机：${m.alias}（${m.endpoint}）"
                }
            }
            // 记住生效地址（选中或自动均可）：进程重启后第一次发送、组播还没到时兜底用
            if (selected == null || selected == m.fingerprint) {
                prefs.setLinkSelection(selected, m.endpoint)
            }
        }
    }

    /** 手动选择/清除选中主机（null = 回到自动选择）。 */
    fun selectMaster(fingerprint: String?) {
        scope.launch {
            val target = fingerprint?.let { f -> _masters.value.firstOrNull { it.fingerprint == f } }
            prefs.setLinkSelection(fingerprint?.takeIf { it.isNotBlank() }, target?.endpoint)
            _activeEndpoint.value = target?.endpoint
            _searchStatus.value = when {
                target != null -> "已选择主机：${target.alias}（${target.endpoint}）"
                fingerprint == null -> "自动选择主机"
                else -> "等待所选主机上线…"
            }
        }
    }

    // ------------------------------------------------------------------
    // 从机侧：把过滤完的通知发给主机（监听器在过滤链末尾调用）
    // ------------------------------------------------------------------

    /**
     * 从机把一条已过滤的通知转发给主机。
     *
     * 候选地址按优先级依次尝试，直到有一台成功：
     *   选中主机的最新宣告地址 → 发现列表最新主机（自动模式） → 记住的上次地址 → 手动填的地址。
     */
    suspend fun relayFromSlave(
        pkg: String,
        appLabel: String,
        title: String,
        body: String,
        alertCategory: Int,
    ): LinkSendResult {
        val code = prefs.linkPairingCode.first()
        if (code.isBlank()) return LinkSendResult(false, "未设置配对码，无法转发")
        val payload = JSONObject()
            .put("package", pkg)
            .put("app", appLabel)
            .put("title", title)
            .put("body", body)
            .put("alertCategory", alertCategory)
            .put("from", alias())
            .put("sentAt", System.currentTimeMillis())
            .toString()

        val selectedFp = prefs.linkSelectedMaster.first()
        val remembered = prefs.linkSelectedEndpoint.first()
        val candidates = buildList {
            _masters.value.firstOrNull { it.fingerprint == selectedFp }?.let { add(it.endpoint) }
            if (selectedFp.isNullOrBlank()) _masters.value.firstOrNull()?.let { add(it.endpoint) }
            if (!remembered.isNullOrBlank()) add(remembered)
            val manual = prefs.linkManualHost.first()
            if (manual.isNotBlank()) add(manual)
        }.distinct()

        if (candidates.isEmpty()) {
            val fail = LinkSendResult(false, "未发现主机（请确认主机与本机在同一 Wi-Fi，或手动填主机地址）")
            _lastSend.value = fail
            return fail
        }

        var lastNote = ""
        for (endpoint in candidates) {
            val host = endpoint.substringBeforeLast(':')
            val port = endpoint.substringAfterLast(':').toIntOrNull() ?: continue
            if (host.isBlank() || port <= 0) continue
            val result = SlaveLinkClient.post(host, port, code, payload)
            if (result.ok) {
                _activeEndpoint.value = endpoint
                _lastSend.value = result
                Log.i(TAG, "从机通知已转发给主机 $endpoint：$appLabel / ${title.take(30)}")
                return result
            }
            lastNote = result.note
            Log.w(TAG, "转发到 $endpoint 失败：${result.note}")
        }
        val fail = LinkSendResult(false, lastNote)
        _lastSend.value = fail
        return fail
    }

    // ------------------------------------------------------------------
    // 主机侧：接收从机通知并推手环
    // ------------------------------------------------------------------

    /**
     * 主机收到一条验过配对码的从机通知：连手环（必要时自动连）→ 推送 → 记录。
     * 返回给从机的结果里带上手环侧成败，从机的「最近推送」据此显示。
     */
    private suspend fun onSlaveNotify(json: JSONObject): LinkSendResult {
        val title = json.optString("title").ifBlank { "(无标题)" }
        val body = json.optString("body")
        val appLabel = json.optString("app", "未知应用")
        val from = json.optString("from", "从机")
        val pkg = json.optString("package")
        val alertCategory = json.optInt("alertCategory", Notify.alertCategoryFor(null))

        val session = BandSessionProvider.get(app)
        if (session.connectionState.value != ConnectionState.Authenticated) {
            val mac = prefs.mac.first()
            val key = prefs.authKey.first()
            if (mac.isNullOrBlank() || key.isNullOrBlank()) {
                return LinkSendResult(false, "主机未配对手环，收到通知也无法推送")
            }
            if (masterConnecting) {
                // 另一波（自动连接或前一条从机通知）正在连，等它
                delay(10_000)
                if (session.connectionState.value != ConnectionState.Authenticated) {
                    return LinkSendResult(false, "主机手环未连接")
                }
            } else {
                masterConnecting = true
                try {
                    Log.i(TAG, "从机通知触发主机连手环：$mac")
                    val ok = withTimeoutOrNull(35_000L) {
                        session.connectAndAuthenticate(mac, key)
                    } ?: false
                    if (!ok) return LinkSendResult(false, "主机连不上手环，通知未推送")
                } finally {
                    masterConnecting = false
                }
            }
        }

        val sent = runCatching { session.sendNotification(appLabel, title, body, alertCategory) }
            .onFailure { Log.w(TAG, "主机推送从机通知失败", it) }
            .getOrDefault(false)

        val now = LocalTime.now()
        prefs.recordNotification(
            BandNotification(
                appName = appLabel,
                title = title,
                body = body,
                timeLabel = "%02d:%02d".format(now.hour, now.minute),
                forwarded = sent,
                packageName = pkg,
                dropReason = if (sent) "来自从机「$from」" else "来自从机「$from」，但推送手环失败",
            ),
        )
        Log.i(TAG, "从机通知${if (sent) "已推手环" else "推送失败"}：$appLabel / ${title.take(30)}（来自 $from）")
        return if (sent) LinkSendResult(true, aliasCache.ifBlank { defaultAlias() })
        else LinkSendResult(false, "主机推送手环失败")
    }
}
