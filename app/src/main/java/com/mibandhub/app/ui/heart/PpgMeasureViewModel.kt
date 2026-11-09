package com.mibandhub.app.ui.heart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mibandhub.app.data.MeasureActivity
import com.mibandhub.app.ppg.PpgLive
import com.mibandhub.app.ppg.PpgMeasureController
import com.mibandhub.app.ppg.PpgPhase
import kotlinx.coroutines.flow.StateFlow

/**
 * 闪光灯测量覆盖层的 VM 门面。真正的状态/样本/落盘都在进程级的
 * [PpgMeasureController] 里 —— 相机绑在界面生命周期上（退出必须关灯），
 * 但测量状态不跟着界面的重组走。
 */
class PpgMeasureViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = PpgMeasureController.get(app)

    val phase: StateFlow<PpgPhase> = controller.phase
    val live: StateFlow<PpgLive> = controller.live
    val durationSec: StateFlow<Int> = controller.durationSec

    fun start() = controller.start()
    fun retry() = controller.retry()
    fun cancel() = controller.cancel()
    fun onCameraReady() = controller.onCameraReady()
    fun onFrame(atMillis: Long, brightness: Float) = controller.onFrame(atMillis, brightness)
    fun onCameraError(message: String) = controller.onCameraError(message)
    fun setDurationSec(seconds: Int) = controller.setDurationSec(seconds)
    fun setActivity(activity: MeasureActivity) = controller.setActivity(activity)
}
