package com.mibandhub.app.ppg

import android.app.Application
import com.mibandhub.app.data.BandPrefs
import com.mibandhub.app.data.PpgMeasureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次闪光灯测量走到哪一步。 */
sealed interface PpgPhase {
    data object Idle : PpgPhase

    /** 相机/闪光灯正在启动，还没开始计时。 */
    data object Preparing : PpgPhase

    data object Sampling : PpgPhase

    data class Success(val result: PpgMeasureResult) : PpgPhase

    data class Failure(val title: String, val detail: String, val hint: String?) : PpgPhase
}

/** 采集过程中的实时状态，界面每 200ms 收一次。 */
data class PpgLive(
    val progress: Float = 0f,
    val remainingSec: Int = 0,
    val bpm: Int? = null,
    val quality: Int = 0,
    val waveform: List<Float> = emptyList(),
    val signal: PpgSignalAnalyzer.SignalState = PpgSignalAnalyzer.SignalState.NO_SIGNAL,
)

/**
 * 闪光灯测量的编排者：等相机就绪 → 计时采样 → 实时估频 → 到点定稿 → 落盘。
 *
 * 和手环那条 [com.mibandhub.app.service.HeartMeasureController] 一样放在**进程级作用域**：
 * 相机本身绑在界面的 LifecycleOwner 上（页面退了必须解绑，否则闪光灯关不掉），
 * 但状态、样本、落盘不该因为切 tab / 旋转屏幕被清掉。
 *
 * 相机帧回调在后台线程，界面读状态在主线程；样本列表统一走 [lock]，不靠
 * 「单线程碰运气」。
 */
class PpgMeasureController private constructor(app: Application) {

    companion object {
        /** 可选的采样时长（秒）。越长越稳，30 秒质量最好但用户得举着手不动。 */
        val DURATION_OPTIONS: List<Int> get() = BandPrefs.PPG_DURATION_OPTIONS

        const val DEFAULT_DURATION_SEC = 20

        /** 低于这个质量分不落盘 —— 宁可提示重测，也不往历史里塞假读数。 */
        const val MIN_QUALITY = 30

        @Volatile
        private var instance: PpgMeasureController? = null

        fun get(app: Application): PpgMeasureController = instance ?: synchronized(this) {
            instance ?: PpgMeasureController(app).also { instance = it }
        }
    }

    private val prefs = BandPrefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val samples = ArrayList<PpgSignalAnalyzer.Sample>(1024)

    private val _phase = MutableStateFlow<PpgPhase>(PpgPhase.Idle)
    val phase: StateFlow<PpgPhase> = _phase.asStateFlow()

    private val _live = MutableStateFlow(PpgLive())
    val live: StateFlow<PpgLive> = _live.asStateFlow()

    val durationSec: StateFlow<Int> = prefs.ppgDurationSec
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_DURATION_SEC)

    private var targetSec = DEFAULT_DURATION_SEC
    private var startedAtMillis = 0L
    private var ticker: Job? = null

    /** 点「开始」：先清场，等 [onCameraReady] 回来才真正开始计时。 */
    fun start() {
        if (_phase.value is PpgPhase.Preparing || _phase.value is PpgPhase.Sampling) return
        synchronized(lock) { samples.clear() }
        _live.value = PpgLive()
        _phase.value = PpgPhase.Preparing
    }

    /** 相机绑定成功、闪光灯已亮 —— 从这里开始算耗时。 */
    fun onCameraReady() {
        if (_phase.value !is PpgPhase.Preparing) return
        startedAtMillis = System.currentTimeMillis()
        _phase.value = PpgPhase.Sampling
        _live.value = PpgLive(progress = 0f, remainingSec = targetSec)
        ticker?.cancel()
        ticker = scope.launch {
            // 时长落盘在 DataStore，进采样前定下来，避免和用户刚改的设置对不上
            targetSec = prefs.ppgDurationSec.first()
            val total = targetSec * 1000L
            while (isActive) {
                delay(200)
                val elapsed = System.currentTimeMillis() - startedAtMillis
                val snapshot = synchronized(lock) { samples.toList() }
                val (estimate, signal) = withContext(Dispatchers.Default) {
                    PpgSignalAnalyzer.analyze(snapshot) to PpgSignalAnalyzer.signalState(snapshot)
                }
                _live.value = PpgLive(
                    progress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                    remainingSec = ((total - elapsed) / 1000).toInt().coerceAtLeast(0),
                    bpm = estimate?.bpm,
                    quality = estimate?.quality ?: 0,
                    waveform = estimate?.waveform ?: emptyList(),
                    signal = signal,
                )
                if (elapsed >= total) {
                    finish()
                    break
                }
            }
        }
    }

    /** 相机线程：一帧一个亮度样本。非采样阶段的帧直接丢掉。 */
    fun onFrame(atMillis: Long, brightness: Float) {
        if (_phase.value !is PpgPhase.Sampling) return
        synchronized(lock) {
            samples.add(PpgSignalAnalyzer.Sample(atMillis, brightness))
            // 兜底上限：60 秒 × 60fps 也才 3600 点，防止极端机型把内存吃满
            if (samples.size > 4000) samples.removeAt(0)
        }
    }

    fun onCameraError(message: String) {
        ticker?.cancel()
        ticker = null
        _live.value = PpgLive()
        _phase.value = PpgPhase.Failure(
            title = "相机没准备好",
            detail = message,
            hint = "确认相机权限已给、没有别的 App 占着摄像头；也可以切回「手环」方式测量。",
        )
    }

    /** 取消 / 关闭页面。注意关灯解绑由界面侧的相机 DisposableEffect 负责。 */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        synchronized(lock) { samples.clear() }
        _live.value = PpgLive()
        _phase.value = PpgPhase.Idle
    }

    fun retry() {
        cancel()
        start()
    }

    fun setDurationSec(seconds: Int) {
        if (seconds !in DURATION_OPTIONS) return
        scope.launch { prefs.setPpgDurationSec(seconds) }
    }

    private suspend fun finish() {
        ticker?.cancel()
        ticker = null
        val snapshot = synchronized(lock) { samples.toList() }
        val estimate = withContext(Dispatchers.Default) { PpgSignalAnalyzer.analyze(snapshot) }
        val finishedAt = System.currentTimeMillis()
        val duration = ((finishedAt - startedAtMillis) / 1000).toInt().coerceAtLeast(1)

        val reason = when {
            snapshot.size < 60 -> "只采到 ${snapshot.size} 帧，样本太少"
            estimate == null -> "亮度变化里找不到稳定的周期性波动"
            estimate.quality < MIN_QUALITY -> "信号质量只有 ${estimate.quality}/100，低于可信门槛 $MIN_QUALITY"
            else -> null
        }
        if (reason != null || estimate == null) {
            _phase.value = PpgPhase.Failure(
                title = "没读到稳定的脉搏信号",
                detail = "$reason。这通常是指头没盖严、按得太用力、手在动，或者环境光太强。",
                hint = "用食指指腹把摄像头和闪光灯一起盖住，轻压到不发白，保持不动，避开强光，再测一次。",
            )
            return
        }
        val finalEstimate = estimate ?: return

        val result = PpgMeasureResult(
            bpm = finalEstimate.bpm,
            finishedAtMillis = finishedAt,
            durationSec = duration,
            quality = finalEstimate.quality,
            sampleCount = snapshot.size,
        )
        // 先落盘再改状态：历史以存储为唯一来源，界面和存储不会各说各话。
        prefs.recordPpgMeasure(result)
        _phase.value = PpgPhase.Success(result)
    }
}
