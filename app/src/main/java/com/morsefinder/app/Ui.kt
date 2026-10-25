package com.morsefinder.app

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import android.hardware.camera2.CaptureRequest
import android.util.Size as AndroidSize

object Pal {
    val bg = Color(0xFF0F1F2B)
    val panel = Color(0xFF172E3E)
    val panel2 = Color(0xFF1D3A4E)
    val line = Color(0xFF2B4C61)
    val text = Color(0xFFE8F1F4)
    val muted = Color(0xFF8FA9B6)
    val lamp = Color(0xFFFFB938)
    val sig = Color(0xFF45D9C8)
    val bad = Color(0xFFFF6B5E)
    val view = Color(0xFF0A1620)
}

@Composable
fun MorseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.sig, onPrimary = Color(0xFF06312C),
            secondary = Pal.lamp, background = Pal.bg, onBackground = Pal.text,
            surface = Pal.panel, onSurface = Pal.text, surfaceVariant = Pal.panel2,
            onSurfaceVariant = Pal.muted, outline = Pal.line, error = Pal.bad
        ),
        content = content
    )
}

/** Big round lamp button: starts/stops listening and lights up with the signal. */
@Composable
fun LampButton(running: Boolean, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(124.dp)
            .drawBehind {
                if (on) {
                    drawCircle(Pal.lamp.copy(alpha = 0.16f), radius = size.minDimension / 2)
                    drawCircle(Pal.lamp.copy(alpha = 0.22f), radius = size.minDimension / 2 - 8.dp.toPx())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val brush = if (on) Brush.radialGradient(listOf(Color(0xFFFFE3A1), Pal.lamp))
        else Brush.radialGradient(listOf(Color(0xFF24485F), Color(0xFF122838)))
        Box(
            Modifier
                .size(96.dp)
                .background(brush, CircleShape)
                .border(2.dp, if (running) Pal.sig else Pal.line, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) "توقف" else "شروع",
                color = if (on) Color(0xFF3A2600) else Pal.text,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp
            )
        }
    }
}

@Composable
fun SpectrumView(info: AudioInfo?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (info == null) return@Canvas
        val w = size.width
        val h = size.height
        fun y(v: Float) = h - ((v.coerceIn(-120f, -20f) + 120f) / 100f) * h
        val nb = minOf(info.spectrum.size, (3000f / info.binHz).toInt())
        val path = Path().apply {
            moveTo(0f, h)
            for (i in 0 until nb) lineTo(i.toFloat() / nb * w, y(info.spectrum[i]))
            lineTo(w, h); close()
        }
        val col = if (info.on) Pal.lamp else Pal.sig
        drawPath(path, col.copy(alpha = 0.3f))
        drawPath(path, col, style = Stroke(2.dp.toPx()))
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        drawLine(Pal.muted, Offset(0f, y(info.noise)), Offset(w, y(info.noise)), 1.dp.toPx(), pathEffect = dash)
        drawLine(Pal.lamp, Offset(0f, y(info.noise + info.thr)), Offset(w, y(info.noise + info.thr)), 1.dp.toPx(), pathEffect = dash)
        if (info.lockHz > 0f) {
            val x = info.lockHz / 3000f * w
            drawLine(Pal.text, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
        }
    }
}

@Composable
fun TapeView(pulses: List<Pulse>, clock: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val mid = size.height / 2
        val span = 8000.0
        drawLine(Pal.line, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
        for (p in pulses) {
            val e = if (p.e < 0) clock else p.e
            val x1 = (w - (clock - p.s) / span * w).toFloat()
            val x2 = (w - (clock - e) / span * w).toFloat()
            if (x2 < 0f) continue
            val bw = maxOf(3.dp.toPx(), x2 - x1)
            drawRoundRect(Pal.lamp, Offset(x1, mid - 13.dp.toPx()), Size(bw, 26.dp.toPx()), CornerRadius(6.dp.toPx()))
        }
        drawRect(Pal.sig, Offset(w - 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
    }
}

private fun contentRect(w: Float, h: Float, aspect: Float): Rect {
    val boxAspect = w / h
    return if (boxAspect > aspect) {
        val cw = h * aspect
        Rect((w - cw) / 2, 0f, (w + cw) / 2, h)
    } else {
        val ch = w / aspect
        Rect(0f, (h - ch) / 2, w, (h + ch) / 2)
    }
}

/** Draws the scan heat map / tracked light ring over the camera preview and handles taps. */
@Composable
fun CameraOverlay(cam: CamOverlay?, onTap: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val aspect by rememberUpdatedState(cam?.aspect ?: 0.75f)
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { off ->
                val r = contentRect(size.width.toFloat(), size.height.toFloat(), aspect)
                val u = ((off.x - r.left) / r.width).coerceIn(0f, 1f)
                val v = ((off.y - r.top) / r.height).coerceIn(0f, 1f)
                onTap(u, v)
            }
        }
    ) {
        if (cam == null) return@Canvas
        val r = contentRect(size.width, size.height, cam.aspect)
        if (!cam.hasRoi) {
            for (c in cam.heat) {
                drawRect(
                    Pal.sig.copy(alpha = c.alpha),
                    Offset(r.left + (c.u - c.w / 2) * r.width, r.top + (c.v - c.h / 2) * r.height),
                    Size(c.w * r.width, c.h * r.height)
                )
            }
        } else {
            drawCircle(
                if (cam.on) Pal.lamp else Pal.sig,
                radius = cam.roiR * r.width,
                center = Offset(r.left + cam.roiU * r.width, r.top + cam.roiV * r.height),
                style = Stroke(3.dp.toPx())
            )
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun lockExposure(camera: Camera) {
    try {
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true).build()
        )
    } catch (e: Exception) { /* not supported on this phone */ }
}

@Composable
fun CameraPreview(analyzer: ImageAnalysis.Analyzer, active: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    DisposableEffect(active) {
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val handler = Handler(Looper.getMainLooper())
        if (active) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(Runnable {
                if (disposed) return@Runnable
                try {
                    val p = future.get()
                    provider = p
                    val previewSel = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(AndroidSize(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                    val analysisSel = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(AndroidSize(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                    val preview = Preview.Builder().setResolutionSelector(previewSel).build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(analysisSel)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build().also { it.setAnalyzer(executor, analyzer) }
                    p.unbindAll()
                    val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    handler.postDelayed({ if (!disposed) lockExposure(cam) }, 1500)
                } catch (e: Exception) { /* camera busy or unavailable */ }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose {
            disposed = true
            try { provider?.unbindAll() } catch (e: Exception) { }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
