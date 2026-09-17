package com.ted.shouhuan.ui.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.ted.shouhuan.ui.theme.Mint
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配对页的扫码覆盖层：相机预览 + 二维码解码。
 *
 * 选型：CameraX 出预览 + ZXing core 解码。刻意不用 ML Kit 的扫码 —— 它依赖
 * Google Play 服务，国产 ROM 没 GMS 就跑不了；ZXing 是纯 Java，而且能直接吃
 * 相机 YUV 帧的亮度平面（PlanarYUVLuminanceSource），不用转 Bitmap，最省内存。
 *
 * 只把解码出的文本透传给上层；「是不是本工具的配对二维码」由 [PairingQr] 判断，
 * 扫到无关二维码静默忽略，不打扰。
 */
@Composable
fun QrScannerOverlay(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 顶部：关闭按钮 —— 任何时候都能退出去
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.TopStart),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭扫码", tint = Color.White)
        }

        if (granted) {
            ScannerCamera(onDecoded = onResult, onUnavailable = onDismiss)

            // 取景框
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(250.dp)
                    .border(3.dp, Mint, RoundedCornerShape(20.dp)),
            )
            Text(
                "把二维码放进取景框",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要相机权限", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "扫码自动填 MAC 和 AuthKey 需要调用相机。也可以直接关闭，手动输入。",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授权相机")
                }
                TextButton(onClick = { openAppPermissionSettings(context) }) {
                    Text("去系统设置", color = Color.White)
                }
                TextButton(onClick = onDismiss) {
                    Text("不用了，手动输入", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** 相机预览 + 帧解码。绑定失败 / 没有相机时通过 [onUnavailable] 通知上层退出。 */
@Composable
private fun ScannerCamera(onDecoded: (String) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        val mainHandler = Handler(Looper.getMainLooper())
        val consumed = AtomicBoolean(false)
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }

        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "无法打开相机：${e.message}", Toast.LENGTH_SHORT).show()
                    onUnavailable()
                }
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                // Preview 只有 setSurfaceProvider 没有 getter，Kotlin 不合成属性，要显式调 setter
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val text = try {
                    if (consumed.get()) null else decodeQr(image, reader)
                } finally {
                    image.close()
                }
                if (text != null && consumed.compareAndSet(false, true)) {
                    mainHandler.post { onDecoded(text) }
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "无法打开相机：${e.message}", Toast.LENGTH_SHORT).show()
                    onUnavailable()
                }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** 从一帧 YUV 图像里解出二维码文本；解不到返回 null。 */
private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes[0]
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride

    // ZXing 的 PlanarYUVLuminanceSource 要求亮度平面是紧凑排布（rowStride==width）。
    // 不少摄像头有行填充，这里按行拷成紧凑缓冲，别让解码吃错像素。
    val data = if (rowStride == width) {
        ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
    } else {
        ByteArray(width * height).also { out ->
            val buf = plane.buffer
            for (row in 0 until height) {
                buf.position(row * rowStride)
                buf.get(out, row * width, width)
            }
        }
    }

    // QR 码自带三个定位角，旋转 90°/180°/270° 都能解，不用管 rotationDegrees。
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
