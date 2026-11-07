package com.mibandhub.app.ppg

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * 相机侧：绑定后置摄像头、常亮闪光灯、逐帧取中心区域平均亮度。
 *
 * 刻意用 [ImageAnalysis] 的 Y 平面（YUV_420_888 的 plane[0]）而不是截图转 Bitmap：
 * 亮度本来就是现成的，转一次 Bitmap 既慢又会把帧率拖到 10 以下，PPG 需要 ~30fps。
 * 预览只为了让用户把手指对准镜头，所以给一个很小的分辨率即可。
 *
 * 只需要 `CAMERA` 运行时权限（配对页扫码时已经申请过）；CameraX 的
 * `enableTorch` 不需要 `android.permission.FLASHLIGHT`。
 */
class PpgCameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (Long, Float) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    @Volatile
    private var stopped = false

    fun open() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (stopped) return@addListener
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(320, 240))
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (!stopped) {
                                val brightness = centerBrightness(image)
                                if (brightness != null) {
                                    onFrame(System.currentTimeMillis(), brightness)
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }

                    p.unbindAll()
                    val cam = p.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    camera = cam

                    if (!cam.cameraInfo.hasFlashUnit()) {
                        main.post {
                            onError("这台设备没有闪光灯，没法用摄像头测心率。可以切回「手环」方式测量。")
                        }
                        return@addListener
                    }
                    val torch = cam.cameraControl.enableTorch(true)
                    torch.addListener(
                        {
                            if (stopped) return@addListener
                            val ok = runCatching { torch.get(); true }.getOrDefault(false)
                            main.post {
                                if (ok) onReady()
                                else onError("闪光灯打不开，可能被系统相机占用了。关掉其它相机应用再试。")
                            }
                        },
                        ContextCompat.getMainExecutor(context),
                    )
                } catch (e: Exception) {
                    main.post { onError("无法打开相机：${e.message ?: e.javaClass.simpleName}") }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** 关灯 + 解绑。测量结束、页面退出、切 tab 都会走到这里，重复调用安全。 */
    fun close() {
        stopped = true
        runCatching { camera?.cameraControl?.enableTorch(false) }
        runCatching { provider?.unbindAll() }
        camera = null
        provider = null
        executor.shutdown()
    }

    /**
     * 取画面中心 50% 区域的平均亮度（隔 4 点采样，几千个点足够，省得每帧扫全图）。
     * 用 ByteBuffer 的绝对 get(index) 读，不改动原 position；rowStride / pixelStride
     * 都按相机给的值算，兼容有行填充或 U/V 交错的机型。
     */
    private fun centerBrightness(image: ImageProxy): Float? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null

        val x0 = image.width / 4
        val x1 = image.width * 3 / 4
        val y0 = image.height / 4
        val y1 = image.height * 3 / 4
        val step = 4
        var sum = 0L
        var count = 0
        var y = y0
        while (y < y1) {
            var index = y * rowStride + x0 * pixelStride
            var x = x0
            while (x < x1) {
                if (index in 0 until buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
                index += pixelStride * step
                x += step
            }
            y += step
        }
        return if (count > 0) sum.toFloat() / count else null
    }
}
