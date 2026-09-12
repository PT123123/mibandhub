package com.ted.shouhuan.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.data.SleepStageShare
import com.ted.shouhuan.data.SleepStage
import com.ted.shouhuan.service.BandSessionProvider
import com.ted.shouhuan.service.HeartMeasureController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 首页的整体状态 —— 全部来自共享会话与本地存储，不再有演示数据。 */
data class HomeUiState(
    val deviceName: String? = null,
    /** GATT 已连上（含认证中）。和设备页的口径一致。 */
    val connected: Boolean = false,
    val battery: Int? = null,
    val steps: Int? = null,
    /** 今日心率大数字：测量中跟实时读数走，平时显示最近一次测量结果。 */
    val bpm: Int? = null,
    val lastMeasuredAt: Long? = null,
    /** 近几次测量的 bpm（旧 → 新），给趋势线用。 */
    val bpmTrend: List<Int> = emptyList(),
    val lastNight: SleepNightRecord? = null,
)

/**
 * 首页状态机。
 *
 * 数据只有两个来源：
 *   - **共享会话**（[BandSessionProvider]）：连接状态 / 电量 / 步数 / 实时心率 ——
 *     设备页点「连接」、心率页测量，更新的是同一条会话，这里跟着自动刷新；
 *   - **本地存储**（[BandPrefs]）：测量历史、最近一晚睡眠。
 *
 * 和通知栏（BandService）看的是同一份数据，两个地方显示的数字必然一致。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = BandPrefs(app)
    private val session = BandSessionProvider.get(app)
    private val controller = HeartMeasureController.get(app)

    private val _stepsGoal = MutableStateFlow(8_000)
    val stepsGoal: StateFlow<Int> = _stepsGoal.asStateFlow()

    val state: StateFlow<HomeUiState> = combine(
        combine(
            prefs.name,
            session.connectionState,
            session.battery,
            session.steps,
            session.heartRate,
        ) { name, conn, battery, steps, liveBpm ->
            HomeUiState(
                deviceName = name,
                connected = conn is com.ted.shouhuan.ble.ConnectionState.Connected ||
                    conn is com.ted.shouhuan.ble.ConnectionState.Authenticated,
                battery = battery,
                steps = steps,
                bpm = liveBpm,
            )
        },
        prefs.measureHistory,
        prefs.sleepHistory,
    ) { base, history, sleep ->
        base.copy(
            bpm = base.bpm ?: history.firstOrNull()?.bpm,
            lastMeasuredAt = history.firstOrNull()?.finishedAtMillis,
            bpmTrend = history.take(30).reversed().map { it.bpm },
            lastNight = sleep.firstOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    /** 一晚的四段分期，顺序和睡眠页主卡一致（清醒在最前）。 */
    fun stagesOf(night: SleepNightRecord): List<SleepStageShare> = listOf(
        SleepStageShare(SleepStage.AWAKE, night.awakeMinutes),
        SleepStageShare(SleepStage.REM, night.remMinutes),
        SleepStageShare(SleepStage.LIGHT, night.lightMinutes),
        SleepStageShare(SleepStage.DEEP, night.deepMinutes),
    )
}
