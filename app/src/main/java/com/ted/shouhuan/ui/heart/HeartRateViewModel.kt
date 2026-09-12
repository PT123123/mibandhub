package com.ted.shouhuan.ui.heart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ted.shouhuan.ble.ConnectionState
import com.ted.shouhuan.data.MeasureResult
import com.ted.shouhuan.service.HeartMeasureController
import com.ted.shouhuan.service.MeasureError
import com.ted.shouhuan.service.MeasurePhase
import kotlinx.coroutines.flow.StateFlow

/**
 * 心率页的 VM：进程级的测量编排住在 [HeartMeasureController] 里
 * （切 tab / Activity 被系统回收都不会打断测量），这里只是它的界面侧门面 ——
 * 把状态流暴露出去，把点击转发进去。
 */
class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = HeartMeasureController.get(app)

    val phase: StateFlow<MeasurePhase> = controller.phase
    val elapsedSec: StateFlow<Int> = controller.elapsedSec
    val history: StateFlow<List<MeasureResult>> = controller.history
    val lastResult: StateFlow<MeasureResult?> = controller.lastResult
    val lastBpm: StateFlow<Int?> = controller.lastBpm
    val configured: StateFlow<Boolean> = controller.configured
    val connectionState: StateFlow<ConnectionState> = controller.connectionState

    /** 点「测量一次 / 再测一次 / 重试」。 */
    fun startMeasure() = controller.startMeasure()

    /** 点「取消 / 停止测量」。 */
    fun cancelMeasure() = controller.cancelMeasure()

    /** 界面申请运行时权限后回填结果。 */
    fun onPermissionResult(granted: Boolean) = controller.onPermissionResult(granted)

    /** 当前有没有连手环所需的运行时权限（Android 12 以下恒为 true）。 */
    fun hasBluetoothPermission(): Boolean = controller.hasBluetoothPermission(getApplication())

    /** 清空全部测量记录（界面有确认框，这里只负责落盘）。 */
    fun clearHistory() = controller.clearHistory()
}
