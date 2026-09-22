package com.ted.shouhuan.service

import android.app.Application
import android.content.Context
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.proto.FULL_HISTORY_DAYS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 睡眠同步走到哪一步了。设备页和睡眠页共用同一份状态（原在 DeviceViewModel）。 */
sealed interface SleepSyncPhase {
    data object Idle : SleepSyncPhase

    /** received/expected 是样本字节数（手环全量约几十万字节）。 */
    data class Syncing(val received: Int, val expected: Int) : SleepSyncPhase

    data class Done(val nights: Int, val sampleMinutes: Int) : SleepSyncPhase

    data class Failed(val message: String) : SleepSyncPhase
}

/**
 * 睡眠同步的进程级编排：连手环（需要时）→ 全量拉活动明细 → 落库。
 *
 * 从 DeviceViewModel 抽出来是因为现在有两个入口：
 *   - 设备页的「同步手环数据」按钮（手动）；
 *   - 睡眠页的进入自动拉取 / 下拉刷新（自动，带去抖）。
 * 两边看的是同一个 [phase] 流 —— 在睡眠页拉，设备页的进度也跟着动，反之亦然。
 * 同一条 GATT 上同一时刻只跑一轮取数（BandSession.syncMutex 串行），心率测量进行中让路。
 */
object SleepSyncManager {

    private const val TAG = "SleepSync"

    /** 连接 + 认证的总兜底（底层每一步自己还有 15 秒超时）。 */
    private const val CONNECT_TIMEOUT_MS = 35_000L

    /** 自动触发（进入睡眠页）的最小间隔：频繁切 tab 不反复折腾手环。手动触发不受限。 */
    private const val AUTO_MIN_INTERVAL_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _phase = MutableStateFlow<SleepSyncPhase>(SleepSyncPhase.Idle)
    val phase: StateFlow<SleepSyncPhase> = _phase.asStateFlow()

    private var job: Job? = null
    private var lastAutoAt = 0L

    /** 同步是否在跑（给界面禁用按钮用）。 */
    fun isBusy(): Boolean = job?.isActive == true

    /** 手动同步：设备页/睡眠页的按钮、下拉刷新。总是真的跑。 */
    fun sync(context: Context) = start(context)

    /** 自动同步（进入睡眠页触发）：正在跑或刚跑过就静默跳过。 */
    fun syncAuto(context: Context) {
        if (isBusy()) return
        val now = System.currentTimeMillis()
        if (now - lastAutoAt < AUTO_MIN_INTERVAL_MS) return
        lastAutoAt = now
        start(context)
    }

    private fun start(context: Context) {
        if (isBusy()) return
        // HeartMeasureController / BandPrefs 都按 Application 持有；
        // applicationContext 对 Activity/Service 返回的都是同一个 Application 实例
        val app = context.applicationContext as Application
        if (HeartMeasureController.get(app).busy) {
            _phase.value = SleepSyncPhase.Failed("心率测量进行中，等它跑完再同步")
            return
        }
        _phase.value = SleepSyncPhase.Syncing(0, 0)
        job = scope.launch {
            try {
                val prefs = BandPrefs(app)
                val session = BandSessionProvider.get(app)
                if (!session.authenticated.value) {
                    val mac = prefs.mac.first()
                    val key = prefs.authKey.first()
                    if (mac.isNullOrBlank() || key.isNullOrBlank()) {
                        _phase.value = SleepSyncPhase.Failed("还没配对手环")
                        return@launch
                    }
                    // 连接失败的细分原因在设备页能看全，这里给一句能照做的
                    val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        session.connectAndAuthenticate(mac, key)
                    } ?: false
                    if (!ok) {
                        _phase.value = SleepSyncPhase.Failed("连接失败，去设备页手动连一次看原因")
                        return@launch
                    }
                }
                val result = session.syncActivity(sinceDays = FULL_HISTORY_DAYS) { p ->
                    _phase.value = SleepSyncPhase.Syncing(p.receivedBytes, p.expectedBytes)
                }
                ActivityDataImport.import(app, prefs, result.nights, result.samples)
                _phase.value = SleepSyncPhase.Done(result.nights.size, result.sampleMinutes)
            } catch (e: Exception) {
                _phase.value = SleepSyncPhase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * 外部数据源导入（如健康连接）也走同一份 [phase] 状态与互斥：块里做完导入，
     * 返回「晚数 / 样本分钟数」给完成态展示。[failurePrefix] 拼在失败信息前面。
     */
    fun importExternal(
        context: Context,
        failurePrefix: String,
        block: suspend (Context) -> Pair<Int, Int>,
    ) {
        if (isBusy()) return
        val app = context.applicationContext as Application
        _phase.value = SleepSyncPhase.Syncing(0, 0)
        job = scope.launch {
            try {
                val (nights, minutes) = block(app)
                _phase.value = SleepSyncPhase.Done(nights, minutes)
            } catch (e: Exception) {
                _phase.value = SleepSyncPhase.Failed("$failurePrefix：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
