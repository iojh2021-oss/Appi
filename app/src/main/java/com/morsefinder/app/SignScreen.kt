package com.morsefinder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URLEncoder
import java.util.concurrent.Executors
import android.util.Size as AndroidSize

private val CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20
)

private fun fitRect(w: Float, h: Float, aspect: Float): Rect {
    val boxAspect = w / h
    return if (boxAspect > aspect) {
        val cw = h * aspect
        Rect((w - cw) / 2, 0f, (w + cw) / 2, h)
    } else {
        val ch = w / aspect
        Rect(0f, (h - ch) / 2, w, (h + ch) / 2)
    }
}

@Composable
private fun HandCameraPreview(analyzer: ImageAnalysis.Analyzer, active: Boolean, front: Boolean, modifier: Modifier) {
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
    DisposableEffect(active, front) {
        var provider: ProcessCameraProvider? = null
        var disposed = false
        if (active) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(Runnable {
                if (disposed) return@Runnable
                try {
                    val p = future.get()
                    provider = p
                    val sel = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(AndroidSize(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                    val preview = Preview.Builder().setResolutionSelector(sel).build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(sel)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().also { it.setAnalyzer(executor, analyzer) }
                    p.unbindAll()
                    p.bindToLifecycle(
                        owner,
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis
                    )
                } catch (e: Exception) { }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose {
            disposed = true
            try { provider?.unbindAll() } catch (e: Exception) { }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun HandOverlay(ui: SignUi, modifier: Modifier) {
    Canvas(modifier) {
        val pts = ui.points ?: return@Canvas
        val r = fitRect(size.width, size.height, ui.aspect)
        val col = if (ui.label != null) Pal.lamp else Pal.sig
        fun pt(i: Int): Offset {
            val x = if (ui.front) 1f - pts[2 * i] else pts[2 * i]
            return Offset(r.left + x * r.width, r.top + pts[2 * i + 1] * r.height)
        }
        for ((a, b) in CONNECTIONS) drawLine(col, pt(a), pt(b), 3.dp.toPx())
        for (i in 0 until 21) drawCircle(Color.White, 3.5.dp.toPx(), pt(i))
    }
}

@Composable
private fun SCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Pal.panel).border(1.dp, Pal.line, shape).padding(14.dp)) { content() }
}

@Composable
private fun Segment(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(Pal.panel).border(1.dp, Pal.line, shape).padding(4.dp)) {
        for ((key, label) in options) {
            val sel = key == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Pal.panel2 else Color.Transparent)
                    .clickable { onSelect(key) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (sel) Pal.text else Pal.muted, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignScreen(vm: SignViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    SideEffect { view.keepScreenOn = ui.running }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.start() else vm.message("اجازهٔ دوربین داده نشد")
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }
    fun toggle() {
        if (ui.running) { vm.stop(); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    val labels = labelsFor(ui.pack)
    val taught = labels.count { (ui.counts[it.id] ?: 0) + (ui.baseCounts[it.id] ?: 0) >= 3 }
    val shownLabel = labels.firstOrNull { it.id == ui.label }?.shown

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding()
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("زبان اشاره", color = Pal.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    ui.message ?: if (ui.running) (if (ui.hand) "دست دیده می‌شود" else "دستت را جلوی دوربین بگیر") else "آماده",
                    color = Pal.muted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
            }
            Segment(listOf("0" to "تشخیص", "1" to "آموزش"), ui.mode.toString()) { vm.setMode(it.toInt()) }
            Segment(listOf("en" to "English (ASL)", "fa" to "فارسی"), ui.pack) { vm.setPack(it) }

            // camera
            Box(
                Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(20.dp))
                    .background(Pal.view).border(1.dp, Pal.line, RoundedCornerShape(20.dp))
            ) {
                HandCameraPreview(vm.engine, ui.running, ui.front, Modifier.fillMaxSize())
                HandOverlay(ui, Modifier.fillMaxSize())
                if (!ui.running) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("دوربین را شروع کن و یک دستت را با نور کافی جلوی آن بگیر", color = Pal.muted,
                            textAlign = TextAlign.Center, lineHeight = 22.sp)
                    }
                }
                if (ui.countdown > 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(ui.countdown.toString(), color = Pal.lamp, fontSize = 96.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (ui.recording) {
                    Box(Modifier.fillMaxWidth().padding(12.dp)) {
                        LinearProgressIndicator(progress = { ui.recProgress }, color = Pal.bad, trackColor = Pal.panel2, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { toggle() }, modifier = Modifier.weight(1f)) { Text(if (ui.running) "توقف دوربین" else "شروع دوربین") }
                IconButton(onClick = { vm.setFront(!ui.front) }) { Icon(Icons.Filled.FlipCameraAndroid, "چرخاندن دوربین", tint = Pal.text) }
            }

            if (ui.mode == 0) {
                // ---------- detect ----------
                SCard {
                    Text("تشخیص", color = Pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                        if (shownLabel != null) {
                            Text(shownLabel, color = Pal.lamp, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold)
                        } else {
                            Text(if (ui.hand) "نامشخص" else "—", color = Pal.muted, fontSize = 28.sp)
                        }
                    }
                    LinearProgressIndicator(progress = { ui.hold }, color = Pal.lamp, trackColor = Pal.panel2, modifier = Modifier.fillMaxWidth())
                    Text("حالت دست را ثابت نگه دار تا حرف نوشته شود. برای حرف تکراری، دست را یک لحظه تغییر بده.",
                        color = Pal.muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                    if (taught == 0) {
                        Text("هنوز هیچ علامتی آموزش داده نشده. به تب «آموزش» برو.", color = Pal.bad, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                SCard {
                    Text("متن", color = Pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    CompositionLocalProvider(LocalLayoutDirection provides if (ui.pack == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Text(
                            if (ui.text.isEmpty()) "…" else ui.text, color = Pal.text, fontSize = 28.sp, lineHeight = 38.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.addSpace() }) { Icon(Icons.Filled.SpaceBar, null, Modifier.size(18.dp)); Text("  فاصله") }
                        OutlinedButton(onClick = { vm.backspace() }) { Icon(Icons.Filled.Backspace, null, Modifier.size(18.dp)); Text("  حذف") }
                        OutlinedButton(onClick = { vm.clearText() }) { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = Pal.bad) }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ui.text)); vm.message("کپی شد") }) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Text("  کپی")
                        }
                        Button(onClick = { vm.translate() }, enabled = !ui.translating) {
                            Icon(Icons.Filled.Translate, null, Modifier.size(18.dp))
                            Text(if (ui.translating) "  …" else if (ui.pack == "fa") "  ترجمه به انگلیسی" else "  ترجمه به فارسی")
                        }
                    }
                    if (ui.translation.isNotEmpty()) {
                        Text(ui.translation, color = Pal.text, fontSize = 18.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            } else {
                // ---------- teach ----------
                SCard {
                    Text("آموزش علامت‌ها ($taught از ${labels.size})", color = Pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("۱) یک علامت را انتخاب کن  ۲) دوربین را شروع کن  ۳) «ضبط» را بزن و حالت را ثابت نگه دار.",
                        color = Pal.muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in labels) {
                            val n = (ui.counts[l.id] ?: 0) + (ui.baseCounts[l.id] ?: 0)
                            val sel = ui.selected == l.id
                            val shape = RoundedCornerShape(12.dp)
                            Column(
                                Modifier.clip(shape).background(if (sel) Pal.panel2 else Pal.bg)
                                    .border(if (sel) 2.dp else 1.dp, if (sel) Pal.lamp else Pal.line, shape)
                                    .clickable { vm.select(l.id) }.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(l.shown, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(n.toString(), color = if (n >= 20) Pal.sig else if (n >= 3) Pal.lamp else Pal.muted, fontSize = 11.sp)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.record() }, enabled = ui.selected != null && !ui.recording && ui.countdown == 0) {
                            Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(16.dp), tint = Pal.bad)
                            Text("  ضبط ۳۰ نمونه")
                        }
                        OutlinedButton(onClick = { ui.selected?.let { vm.deleteSamples(it) } }, enabled = ui.selected != null) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = Pal.bad); Text("  پاک")
                        }
                    }
                    ui.warning?.let { Text(it, color = Pal.lamp, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp)) }
                    Text("برای دقت بیشتر هر علامت را ۲ بار ضبط کن: یکی نزدیک‌تر و یکی کمی دورتر یا با زاویهٔ متفاوت. زمینهٔ ساده و نور کافی کمک می‌کند.",
                        color = Pal.muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
                    if (ui.pack == "en") {
                        Text("حروف J و Z در ASL حرکت دارند؛ فقط حالت شروعشان ضبط می‌شود.", color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text("علامت ارقام ۰ تا ۹ بین فارسی و انگلیسی مشترک است؛ یک بار آموزش دادن برای هر دو کافی است.", color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                SCard {
                    Text("سخت‌گیری: ${"%.2f".format(ui.strict)}", color = Pal.text)
                    Slider(value = ui.strict, onValueChange = { vm.setStrict(it) }, valueRange = 0.15f..0.45f)
                    Text("کمتر = خطای کمتر ولی گاهی «نامشخص». بیشتر = تشخیص راحت‌تر ولی ریسک اشتباه بیشتر. برای کمترین خطا روی مقدار پایین بگذار.",
                        color = Pal.muted, fontSize = 12.sp, lineHeight = 19.sp)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val q = URLEncoder.encode(if (ui.pack == "fa") "الفبای زبان اشاره فارسی" else "ASL alphabet chart", "UTF-8")
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?tbm=isch&q=$q")))
                        }) { Text("راهنمای شکل علامت‌ها") }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try {
                                val f = vm.exportFile()
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "ارسال بستهٔ نمونه‌ها"))
                            } catch (e: Exception) { vm.message("ارسال انجام نشد") }
                        }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text("  خروجی نمونه‌ها") }
                        OutlinedButton(onClick = { importLauncher.launch("*/*") }) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Text("  ورود نمونه‌ها")
                        }
                    }
                }
            }
        }
    }
}
