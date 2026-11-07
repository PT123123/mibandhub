package com.mibandhub.app.ppg

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 闪光灯 PPG 的信号分析，纯 Kotlin、无 Android 依赖，方便离线拿样本回放调参。
 *
 * 原理：手指盖住摄像头和闪光灯后，皮下血液随心跳充盈，反射回 CMOS 的光强
 * 会出现一个和心率同频的微弱交流分量（叠加在很强的直流分量上）。流程是
 * **重采样 → 去趋势（高通）→ 平滑（低通）→ 自相关找周期 → 抛物线插值细化**。
 *
 * 不直接数峰值：运动伪影和呼吸造成的低频起伏会让峰值检测错得离谱；
 * 自相关对偶发的尖峰不敏感，稳得多。频段锁 45–180 BPM（0.75–3 Hz）。
 */
object PpgSignalAnalyzer {

    data class Sample(val atMillis: Long, val value: Float)

    data class Result(
        val bpm: Int,
        /** 0–100，自相关峰相对背景的突出程度 + 交流分量强度。 */
        val quality: Int,
        /** 归一化到 ±1 的最近一段波形，给界面画实时曲线。 */
        val waveform: List<Float>,
        val sampleCount: Int,
    )

    /** 手指放置状态的提示，只作用于文案，不阻断测量。 */
    enum class SignalState { NO_SIGNAL, TOO_BRIGHT, WEAK, GOOD }

    /** 重采样目标频率。相机帧率各机型不一样，先归一到固定频率再算 lag → BPM。 */
    const val TARGET_HZ = 30.0

    private const val MIN_BPM = 45
    private const val MAX_BPM = 180
    private const val MIN_SECONDS = 8
    private const val MAX_SECONDS = 60
    private const val WAVE_POINTS = 150

    /**
     * 从一段亮度样本里估心率。样本太短、信号太弱、找不到周期都返回 null ——
     * 宁可让用户重测，也不给一个看着像真的假读数。
     */
    fun analyze(samples: List<Sample>): Result? {
        if (samples.size < 2) return null
        val ordered = resample(samples) ?: return null
        if (ordered.size < MIN_SECONDS * TARGET_HZ.toInt()) return null

        // 减掉长窗滑动均值 = 去掉直流与缓慢漂移（呼吸、手抖、自动曝光调整）
        val trend = movingAverage(ordered, (TARGET_HZ * 1.5).toInt() or 1)
        val detrended = DoubleArray(ordered.size) { i -> ordered[i] - trend[i] }
        val smoothed = movingAverage(detrended, 3)

        val minLag = max(2, (60.0 * TARGET_HZ / MAX_BPM).roundToInt())
        val maxLag = min(smoothed.size - 2, (60.0 * TARGET_HZ / MIN_BPM).roundToInt())
        if (minLag >= maxLag) return null

        val scores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) scores[lag] = correlation(smoothed, lag)
        val best = (minLag..maxLag).maxOf { scores[it] }
        if (best < 0.22) return null

        // 自相关的倍频陷阱：lag = 2T 处同样有高相关。取「达到主峰 88% 的第一个
        // 局部极大」，把结果拉回基频；都不满足才退回全局最大值。
        var chosen = -1
        for (lag in (minLag + 1) until maxLag) {
            if (scores[lag] >= best * 0.88 && scores[lag] >= 0.22 &&
                scores[lag] >= scores[lag - 1] && scores[lag] >= scores[lag + 1]
            ) {
                chosen = lag
                break
            }
        }
        if (chosen < 0) chosen = (minLag..maxLag).maxByOrNull { scores[it] } ?: return null

        val c0 = scores[(chosen - 1).coerceAtLeast(minLag)]
        val c1 = scores[chosen]
        val c2 = scores[(chosen + 1).coerceAtMost(maxLag)]
        val denom = c0 - 2.0 * c1 + c2
        val delta = if (abs(denom) > 1e-9) (0.5 * (c0 - c2) / denom).coerceIn(-0.5, 0.5) else 0.0
        val refinedLag = (chosen + delta).coerceAtLeast(1.0)
        val bpm = (60.0 * TARGET_HZ / refinedLag).roundToInt().coerceIn(MIN_BPM, MAX_BPM)

        // 质量分：主峰相对背景越突出、交流分量越强，读数越可信。
        val meanScore = (minLag..maxLag).sumOf { scores[it] } / (maxLag - minLag + 1).toDouble()
        val prominence = ((best - meanScore) / (1.0 - meanScore + 1e-6)).coerceIn(0.0, 1.0)
        val acRms = sqrt(smoothed.sumOf { it * it } / smoothed.size.toDouble())
        val strength = (acRms / 2.0).coerceIn(0.0, 1.0)
        val quality = (prominence * 70.0 + strength * 30.0).roundToInt().coerceIn(0, 100)

        val peak = smoothed.maxOf { abs(it) }.coerceAtLeast(1e-6)
        val normalized = smoothed.map { (it / peak).toFloat() }
        val waveform = normalized.takeLast(WAVE_POINTS)

        return Result(bpm, quality, waveform, samples.size)
    }

    /**
     * 实时提示：手指有没有盖住镜头、闪光灯是不是真的亮着、信号是不是太弱。
     * 各机型 CMOS/闪光灯亮度差异很大，所以只看「最近一秒」的相对状态，
     * 且**不拿它当硬门槛** —— 最终成不成功由 [analyze] 的质量分说了算。
     */
    fun signalState(samples: List<Sample>): SignalState {
        if (samples.size < 15) return SignalState.NO_SIGNAL
        val recent = samples.takeLast(45)
        val mean = recent.sumOf { it.value.toDouble() } / recent.size.toDouble()
        if (mean < 6.0) return SignalState.NO_SIGNAL // 闪光灯没亮 / 镜头被完全遮死
        if (mean > 215.0) return SignalState.TOO_BRIGHT // 手指没盖住，环境光直接灌进来
        val variance = recent.sumOf {
            val d = it.value.toDouble() - mean
            d * d
        } / recent.size.toDouble()
        val rms = sqrt(variance)
        return if (rms < 0.12) SignalState.WEAK else SignalState.GOOD
    }

    /** 线性插值到固定 [TARGET_HZ]；时间戳乱序/重复都由排序和去重兜住。 */
    private fun resample(samples: List<Sample>): DoubleArray? {
        val ordered = samples.sortedBy { it.atMillis }
        val t0 = ordered.first().atMillis.toDouble()
        val t1 = ordered.last().atMillis.toDouble()
        val seconds = (t1 - t0) / 1000.0
        if (seconds < MIN_SECONDS.toDouble() || seconds > MAX_SECONDS.toDouble()) return null
        val n = (seconds * TARGET_HZ).toInt() + 1
        if (n < 2) return null

        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = t0 + i * 1000.0 / TARGET_HZ
            while (j + 1 < ordered.lastIndex && ordered[j + 1].atMillis <= t) j++
            val a = ordered[j]
            val b = ordered.getOrNull(j + 1)
            out[i] = if (b == null || b.atMillis <= a.atMillis) {
                a.value.toDouble()
            } else {
                val f = ((t - a.atMillis) / (b.atMillis - a.atMillis)).coerceIn(0.0, 1.0)
                a.value + (b.value - a.value) * f
            }
        }
        return out
    }

    /** 前缀和实现的滑动均值，窗口在边界自动收缩，不引入零填充造成的假边沿。 */
    private fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
        val half = window / 2
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        return DoubleArray(x.size) { i ->
            val l = max(0, i - half)
            val r = min(x.size, i + half + 1)
            (prefix[r] - prefix[l]) / (r - l).toDouble()
        }
    }

    /** 归一化自相关：对不同机型不同的整体亮度不敏感。 */
    private fun correlation(x: DoubleArray, lag: Int): Double {
        var num = 0.0
        var da = 0.0
        var db = 0.0
        val end = x.size - lag
        for (i in 0 until end) {
            val a = x[i]
            val b = x[i + lag]
            num += a * b
            da += a * a
            db += b * b
        }
        return if (da > 1e-12 && db > 1e-12) num / sqrt(da * db) else 0.0
    }
}
