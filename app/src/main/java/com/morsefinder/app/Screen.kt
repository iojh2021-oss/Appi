package com.morsefinder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MorseApp(vm: MorseViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val view = LocalView.current
    var showSettings by remember { mutableStateOf(false) }

    SideEffect { view.keepScreenOn = ui.running }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.start() else vm.message("اجازه‌ی دسترسی داده نشد. از تنظیمات گوشی فعالش کن.")
    }
    fun toggle() {
        if (ui.running) { vm.stop(); return }
        val perm = if (ui.mode == Mode.AUDIO) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permLauncher.launch(perm)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Pal.bg)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TopBar(ui.message ?: ui.status, isError = ui.message != null) { showSettings = true }
            ModeSwitch(ui.mode) { vm.setMode(it) }
            Viewport(ui, vm)
            Console(ui) { toggle() }
            Card {
                TapeView(
                    ui.pulses, ui.clockMs,
                    Modifier.fillMaxWidth().height(64.dp)
                )
            }
            OutputCard(ui, vm)
            MeaningCard(ui, vm)
            TestCard(ui, vm)
        }
    }

    if (showSettings) SettingsDialog(ui, vm) { showSettings = false }
}

@Composable
private fun TopBar(status: String, isError: Boolean, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("مورس‌یاب", color = Pal.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            status, color = if (isError) Pal.bad else Pal.muted, fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp), textAlign = TextAlign.Start
        )
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "تنظیمات", tint = Pal.text) }
    }
}

@Composable
private fun ModeSwitch(mode: Mode, onChange: (Mode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.panel)
            .border(1.dp, Pal.line, RoundedCornerShape(14.dp)).padding(4.dp)
    ) {
        for ((m, label) in listOf(Mode.AUDIO to "صدا (بوق)", Mode.CAMERA to "دوربین (نور)")) {
            val sel = m == mode
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Pal.panel2 else Color.Transparent)
                    .clickable { onChange(m) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (sel) Pal.text else Pal.muted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Viewport(ui: UiState, vm: MorseViewModel) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth()
            .height(if (ui.mode == Mode.CAMERA) 360.dp else 200.dp)
            .clip(shape).background(Pal.view).border(1.dp, Pal.line, shape)
    ) {
        if (ui.mode == Mode.AUDIO) {
            SpectrumView(ui.audio, Modifier.fillMaxSize())
            if (!ui.running) Hint("میکروفون را نزدیک منبع صدا بگیر و دکمه‌ی شروع را بزن")
        } else {
            CameraPreview(vm.light, ui.running, Modifier.fillMaxSize())
            if (ui.running) CameraOverlay(ui.cam, { u, v -> vm.selectLight(u, v) }, Modifier.fillMaxSize())
            else Hint("دوربین را روی چراغ چشمک‌زن بگیر؛ بعد از شروع، روی نور ضربه بزن تا انتخاب شود")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Pal.muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
}

@Composable
private fun Console(ui: UiState, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Metric("کلمه در دقیقه", ui.wpm?.toString() ?: "—", null, Modifier.weight(1f))
        LampButton(ui.running, ui.lampOn, onToggle)
        Metric(
            if (ui.mode == Mode.AUDIO) "فرکانس (Hz)" else "کنتراست نور",
            ui.metric, ui.level, Modifier.weight(1f)
        )
    }
}

@Composable
private fun Metric(label: String, value: String, level: Float?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, color = Pal.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(label, color = Pal.muted, fontSize = 12.sp)
        if (level != null) {
            LinearProgressIndicator(
                progress = { level }, color = Pal.sig, trackColor = Pal.panel2,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.7f)
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Pal.panel).border(1.dp, Pal.line, shape).padding(14.dp)
    ) { content() }
}

@Composable
private fun CardTitle(t: String) {
    Text(t, color = Pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun OutputCard(ui: UiState, vm: MorseViewModel) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Card {
        CardTitle("متن دریافتی")
        if (ui.text.isEmpty() && ui.live.isEmpty()) {
            Text("متن اینجا نوشته می‌شود…", color = Pal.muted, fontSize = 15.sp, modifier = Modifier.height(64.dp))
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val shown = buildAnnotatedString {
                    append(ui.text)
                    if (ui.live.isNotEmpty()) withStyle(SpanStyle(color = Pal.lamp)) { append("▮") }
                }
                Text(
                    shown, color = Pal.text, fontFamily = FontFamily.Monospace, fontSize = 26.sp, lineHeight = 36.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
                Text(
                    ui.morse, color = Pal.sig, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(vm.currentText())); vm.message("کپی شد")
            }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Text("  کپی") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.currentText()) }
                ctx.startActivity(Intent.createChooser(send, "اشتراک‌گذاری"))
            }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text("  ارسال") }
            OutlinedButton(onClick = { vm.clear() }) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = Pal.bad); Text("  پاک", color = Pal.bad)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeaningCard(ui: UiState, vm: MorseViewModel) {
    Card {
        CardTitle("معنی و ترجمه")
        if (ui.meanings.isEmpty()) {
            Text("اختصارهای رادیویی (SOS، CQ، 73، Q-code…) اینجا معنی می‌شوند.", color = Pal.muted, fontSize = 13.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((code, meaning) in ui.meanings) {
                    Row(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(Pal.panel2)
                            .border(1.dp, Pal.line, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(meaning, color = Pal.text, fontSize = 13.sp)
                        Text("  $code", color = Pal.lamp, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LangPicker("از زبان", ui.settings.src, { vm.updateSettings(ui.settings.copy(src = it)) }, Modifier.weight(1f))
            LangPicker("به زبان", ui.settings.dst, { vm.updateSettings(ui.settings.copy(dst = it)) }, Modifier.weight(1f))
        }
        Button(
            onClick = { vm.translate() }, enabled = !ui.translating,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Icon(Icons.Filled.Translate, null, Modifier.size(18.dp))
            Text(if (ui.translating) "  در حال ترجمه…" else "  ترجمه (آنلاین)")
        }
        if (ui.translation.isNotEmpty()) {
            Text(ui.translation, color = Pal.text, fontSize = 17.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun LangPicker(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(LANGS.firstOrNull { it.first == value }?.second ?: value, color = Pal.text)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((code, name) in LANGS) {
                    DropdownMenuItem(text = { Text(name) }, onClick = { onChange(code); open = false })
                }
            }
        }
    }
}

@Composable
private fun TestCard(ui: UiState, vm: MorseViewModel) {
    var text by remember { mutableStateOf("CQ CQ DE TEST") }
    var wpm by remember { mutableIntStateOf(12) }
    var hz by remember { mutableIntStateOf(700) }
    Card {
        CardTitle("تست و ارسال مورس")
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("Text") },
                keyboardOptions = KeyboardOptions.Default
            )
        }
        Text("سرعت: $wpm WPM", color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Slider(value = wpm.toFloat(), onValueChange = { wpm = it.toInt() }, valueRange = 5f..25f)
        Text("فرکانس بوق: $hz Hz", color = Pal.muted, fontSize = 13.sp)
        Slider(value = hz.toFloat(), onValueChange = { hz = (it / 10).toInt() * 10 }, valueRange = 400f..1200f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.transmit(1, text, wpm, hz) }, enabled = ui.tx != 2) {
                Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp))
                Text(if (ui.tx == 1) "  توقف" else "  پخش بوق")
            }
            Button(
                onClick = { vm.transmit(2, text, minOf(wpm, 10), hz) }, enabled = ui.tx != 1,
                colors = ButtonDefaults.buttonColors(containerColor = Pal.lamp, contentColor = Color(0xFF3A2600))
            ) {
                Icon(Icons.Filled.FlashlightOn, null, Modifier.size(18.dp))
                Text(if (ui.tx == 2) "  توقف" else "  چشمک چراغ‌قوه")
            }
        }
        Text(
            "تست صدا: «شروع» را بزن و بعد «پخش بوق». تست نور: حالت دوربین را شروع کن و چراغ‌قوه‌ی گوشی دیگر را جلوش بگیر. چراغ‌قوه حداکثر ۱۰ WPM چشمک می‌زند.",
            color = Pal.muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun SettingsDialog(ui: UiState, vm: MorseViewModel, onClose: () -> Unit) {
    val s = ui.settings
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Pal.panel,
        confirmButton = { TextButton(onClick = onClose) { Text("بستن") } },
        title = { Text("تنظیمات", color = Pal.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("حساسیت: ${s.sens}", color = Pal.text)
                Slider(value = s.sens.toFloat(), onValueChange = { vm.updateSettings(s.copy(sens = it.toInt())) }, valueRange = 0f..100f)
                Text(
                    "اگر چیزی نمی‌نویسد بالاتر ببر. اگر حرف اضافه می‌نویسد پایین بیاور. در نمودار صدا، خط زرد نقطه‌چین حد تشخیص است.",
                    color = Pal.muted, fontSize = 12.sp, lineHeight = 19.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("سرعت ثابت (به‌جای خودکار)", color = Pal.text, modifier = Modifier.weight(1f))
                    Switch(checked = s.wpmFixed, onCheckedChange = { vm.updateSettings(s.copy(wpmFixed = it)) })
                }
                if (s.wpmFixed) {
                    Text("${s.wpm} WPM", color = Pal.muted, fontSize = 13.sp)
                    Slider(value = s.wpm.toFloat(), onValueChange = { vm.updateSettings(s.copy(wpm = it.toInt())) }, valueRange = 5f..40f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("قفل فرکانس بوق", color = Pal.text, modifier = Modifier.weight(1f))
                    Switch(checked = s.freqFixed, onCheckedChange = { vm.updateSettings(s.copy(freqFixed = it)) })
                }
                if (s.freqFixed) {
                    Text("${s.freq} Hz", color = Pal.muted, fontSize = 13.sp)
                    Slider(
                        value = s.freq.toFloat(),
                        onValueChange = { vm.updateSettings(s.copy(freq = (it / 10).toInt() * 10)) },
                        valueRange = 300f..2000f
                    )
                }
                if (ui.mode == Mode.CAMERA) {
                    OutlinedButton(onClick = { vm.rescanLight(); onClose() }) { Text("پیدا کردن دوباره‌ی نور") }
                }
            }
        }
    )
}
