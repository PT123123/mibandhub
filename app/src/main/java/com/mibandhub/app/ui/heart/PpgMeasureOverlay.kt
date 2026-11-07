package com.mibandhub.app.ui.heart

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mibandhub.app.ppg.PpgCameraSession
import com.mibandhub.app.ppg.PpgMeasureController
import com.mibandhub.app.ppg.PpgPhase
import com.mibandhub.app.ppg.PpgSignalAnalyzer
import com.mibandhub.app.ui.components.FilterChipRow
import com.mibandhub.app.ui.theme.Mint
import com.mibandhub.app.ui.theme.NotifyAmber
import com.mibandhub.app.ui.theme.PulseRed
import com.mibandhub.app.ui.theme.StepBlue
import com.mibandhub.app.util.formatSeconds

/**
 * 闪光灯测心率的覆盖层：准备 → 采集中（实时波形 + 倒计时） → 结果 / 失败。
 *
 * 相机只在 Preparing/Sampling 两个阶段绑定，组件一移除就走 DisposableEffect
 * 的 onDispose 关灯解绑 —— 用户按返回、切 tab、测完出结果，闪光灯都不会留着亮。
 */
@Composable
fun PpgMeasureOverlay(vm: PpgMeasureViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val phase by vm.phase.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val duration by vm.durationSec.collectAsStateWithLifecycle()

    var granted by remember {
        mutableStateOf(hasCameraPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val sampling = phase is PpgPhase.Preparing || phase is PpgPhase.Sampling

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF080B12)),
    ) {
        // 相机取景区：闪光灯常亮时用户靠它对准手指位置，不需要看清画面
        if (granted && sampling) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp),
                contentAlignment = Alignment.Center,
            ) {
                PpgCameraStage(vm)
                Box(
                    Modifier
                        .size(150.dp)
                        .border(2.dp, Mint.copy(alpha = 0.75f), CircleShape),
                )
            }
        }

        // 顶部：关闭（任何阶段都能退出）
        IconButton(
            onClick = {
                vm.cancel()
                onClose()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 268.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                is PpgPhase.Idle -> IdleContent(
                    granted = granted,
                    duration = duration,
                    onDuration = vm::setDurationSec,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { openAppPermissionSettings(context) },
                    onStart = {
                        if (granted) vm.start()
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                )

                is PpgPhase.Preparing -> {
                    Text("正在打开闪光灯…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "把食指指腹轻轻盖住背面的摄像头和闪光灯，保持不动。",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(color = Mint)
                }

                is PpgPhase.Sampling -> SamplingContent(live = live, onCancel = vm::cancel)

                is PpgPhase.Success -> SuccessContent(
                    phase = phase as PpgPhase.Success,
                    onAgain = vm::retry,
                    onDone = {
                        vm.cancel()
                        onClose()
                    },
                )

                is PpgPhase.Failure -> FailureContent(
                    phase = phase as PpgPhase.Failure,
                    onRetry = vm::retry,
                    onDone = {
                        vm.cancel()
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    granted: Boolean,
    duration: Int,
    onDuration: (Int) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
) {
    Icon(
        Icons.Rounded.FlashlightOn,
        contentDescription = null,
        tint = Mint,
        modifier = Modifier.size(40.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text("用闪光灯测心率", color = Color.White, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Text(
        "手指盖住摄像头和闪光灯，光强会随脉搏微弱起伏。保持不动、避开强光，测完会自动出结果。\n" +
            "结果会记进心率历史，并标注为「手机闪光灯」，和手环数据分开可查。",
        color = Color.White.copy(alpha = 0.72f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "采样时长",
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.height(8.dp))
    FilterChipRow(
        options = PpgMeasureController.DURATION_OPTIONS.map { "$it 秒" },
        selectedIndex = PpgMeasureController.DURATION_OPTIONS.indexOf(duration).coerceAtLeast(0),
        accent = Mint,
        onSelect = { onDuration(PpgMeasureController.DURATION_OPTIONS[it]) },
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "时间越长越稳，但要一直举着手别动。",
        color = Color.White.copy(alpha = 0.45f),
        style = MaterialTheme.typography.labelSmall,
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onStart,
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF08120E)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(if (granted) "开始测量" else "授权相机并开始")
    }
    if (!granted) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings) { Text("去系统设置授权", color = Color.White.copy(alpha = 0.7f)) }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "提示：这是光学估算，不能代替医疗设备；手环测量结果优先作为参考。",
        color = Color.White.copy(alpha = 0.4f),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SamplingContent(live: com.mibandhub.app.ppg.PpgLive, onCancel: () -> Unit) {
    Text(
        "保持不动  ·  剩余 ${live.remainingSec} 秒",
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(
        progress = { live.progress },
        modifier = Modifier.fillMaxWidth(),
        color = Mint,
        trackColor = Color.White.copy(alpha = 0.12f),
    )
    Spacer(Modifier.height(22.dp))
    Text(
        live.bpm?.let { "$it" } ?: "--",
        color = PulseRed,
        style = MaterialTheme.typography.displayLarge,
    )
    Text("BPM（实时估计，未定稿）", color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(20.dp))
    PpgWaveform(values = live.waveform, color = Mint)
    Spacer(Modifier.height(14.dp))
    val signalText = when (live.signal) {
        PpgSignalAnalyzer.SignalState.GOOD -> "信号良好，继续按住别动"
        PpgSignalAnalyzer.SignalState.WEAK -> "信号偏弱：手指压稳一点，别移动"
        PpgSignalAnalyzer.SignalState.TOO_BRIGHT -> "光线太亮：把摄像头完全盖住，避开强光"
        PpgSignalAnalyzer.SignalState.NO_SIGNAL -> "还没读到信号：确认手指盖住了闪光灯"
    }
    val signalColor = if (live.signal == PpgSignalAnalyzer.SignalState.GOOD) Mint else NotifyAmber
    Text(signalText, color = signalColor, style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "当前信号质量 ${live.quality}/100",
        color = Color.White.copy(alpha = 0.45f),
        style = MaterialTheme.typography.labelSmall,
    )
    Spacer(Modifier.height(20.dp))
    TextButton(onClick = onCancel) { Text("取消测量", color = Color.White.copy(alpha = 0.7f)) }
}

@Composable
private fun SuccessContent(phase: PpgPhase.Success, onAgain: () -> Unit, onDone: () -> Unit) {
    val result = phase.result
    Text("测量完成", color = Mint, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${result.bpm}", color = PulseRed, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.width(6.dp))
        Text("BPM", color = PulseRed, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 10.dp))
    }
    Spacer(Modifier.height(12.dp))
    SourceLine()
    Spacer(Modifier.height(6.dp))
    Text(
        "耗时 ${formatSeconds(result.durationSec)}  ·  信号质量 ${result.quality}/100  ·  ${result.sampleCount} 帧",
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onAgain) { Text("再测一次", color = Color.White.copy(alpha = 0.8f)) }
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF08120E)),
            shape = RoundedCornerShape(14.dp),
        ) { Text("完成") }
    }
}

@Composable
private fun FailureContent(phase: PpgPhase.Failure, onRetry: () -> Unit, onDone: () -> Unit) {
    Text(phase.title, color = NotifyAmber, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        phase.detail,
        color = Color.White.copy(alpha = 0.8f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    phase.hint?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            it,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onDone) { Text("返回", color = Color.White.copy(alpha = 0.7f)) }
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = NotifyAmber, contentColor = Color(0xFF1A1004)),
            shape = RoundedCornerShape(14.dp),
        ) { Text("重试") }
    }
}

@Composable
private fun SourceLine() {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(StepBlue.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(StepBlue, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("来源：手机闪光灯", color = StepBlue, style = MaterialTheme.typography.labelMedium)
    }
}

/** 实时 PPG 波形：值已归一化到 ±1，画不出来时给一条平线，界面不会突然空一块。 */
@Composable
private fun PpgWaveform(values: List<Float>, color: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f)),
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        drawLine(
            color = Color.White.copy(alpha = 0.10f),
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = 1.dp.toPx(),
        )
        if (values.size < 2) return@Canvas
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = w * i / (values.size - 1)
            val y = mid - v.coerceIn(-1f, 1f) * (mid * 0.86f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** 相机只在采样阶段存在；组件移除即关灯解绑。 */
@Composable
private fun PpgCameraStage(vm: PpgMeasureViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(Unit) {
        val session = PpgCameraSession(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onFrame = vm::onFrame,
            onReady = vm::onCameraReady,
            onError = vm::onCameraError,
        )
        session.open()
        onDispose { session.close() }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .size(150.dp)
            .clip(CircleShape),
    )
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
