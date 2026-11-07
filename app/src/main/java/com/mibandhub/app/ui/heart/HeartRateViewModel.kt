package com.mibandhub.app.ui.heart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mibandhub.app.ble.ConnectionState
import com.mibandhub.app.data.HeartMeasureRecord
import com.mibandhub.app.service.HeartMeasureController
import com.mibandhub.app.service.MeasurePhase
import kotlinx.coroutines.flow.StateFlow

/**
 * 心率页的 VM：进程级的测量编排住在 [HeartMeasureController] 里
 * （切 tab / Activity 被系统回收都不会打断测量），这里只是它的界面侧门面 ——
 * 把状态流暴露出去，把点击转发进去。
 *
 * 历史用 [records] 而不是手环那份原始列表：它把手环和手机闪光灯 PPG 合并成
 * 一条按时间排好的时间线，每条都带来源，界面才不用自己拼两份数据。
 */
class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = HeartMeasureController.get(app)

    val phase: StateFlow<MeasurePhase> = controller.phase
    val elapsedSec: StateFlow<Int> = controller.elapsedSec
    val records: StateFlow<List<HeartMeasureRecord>> = controller.records
    val lastRecord: StateFlow<HeartMeasureRecord?> = controller.lastRecord
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

    /** 清空全部测量记录（两个来源一起清，界面有确认框）。 */
    fun clearHistory() = controller.clearHistory()
}
