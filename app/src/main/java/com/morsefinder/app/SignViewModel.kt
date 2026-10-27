package com.morsefinder.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SignViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sign", Context.MODE_PRIVATE)
    private val store = SampleStore(app)
    private val lock = Any()

    val engine = HandEngine(app) { f, p, a -> onFrame(f, p, a) }
    private val _ui = MutableStateFlow(SignUi())
    val ui: StateFlow<SignUi> = _ui.asStateFlow()

    // detection state (touched from the MediaPipe thread)
    private var cand: String? = null
    private var streak = 0
    private var holdStart = 0L
    private var locked = false

    // recording state
    private var recBuf: ArrayList<FloatArray>? = null
    private var recTarget = 30
    private var recSeq = 0

    init {
        store.load()
        _ui.value = _ui.value.copy(
            strict = prefs.getFloat("strict", 0.28f),
            pack = prefs.getString("pack", "en") ?: "en"
        )
        refreshCounts()
    }

    private fun idsFor(pack: String): Set<String> = labelsFor(pack).map { it.id }.toSet()

    private fun refreshCounts() {
        val u = HashMap<String, Int>()
        val b = HashMap<String, Int>()
        for (l in EN_LABELS + FA_LABELS) { u[l.id] = store.userCount(l.id); b[l.id] = store.baseCount(l.id) }
        _ui.update { it.copy(counts = u, baseCounts = b) }
    }

    fun message(t: String) {
        _ui.update { it.copy(message = t) }
        viewModelScope.launch { delay(3500); _ui.update { s -> if (s.message == t) s.copy(message = null) else s } }
    }

    fun setMode(m: Int) { _ui.update { it.copy(mode = m, warning = null) }; resetDetect() }
    fun setPack(p: String) {
        prefs.edit().putString("pack", p).apply()
        _ui.update { it.copy(pack = p, selected = null, text = "", translation = "", warning = null) }
        resetDetect()
    }
    fun setFront(f: Boolean) { _ui.update { it.copy(front = f) } }
    fun setStrict(v: Float) { prefs.edit().putFloat("strict", v).apply(); _ui.update { it.copy(strict = v) } }
    fun select(id: String) { _ui.update { it.copy(selected = id, warning = null) } }

    private fun resetDetect() { cand = null; streak = 0; holdStart = 0L; locked = false }

    fun start(): Boolean {
        if (_ui.value.running) return true
        if (!engine.start()) { message("مدل تشخیص دست بارگذاری نشد"); return false }
        resetDetect()
        _ui.update { it.copy(running = true) }
        return true
    }

    fun stop() {
        engine.stop()
        synchronized(lock) { recBuf = null }
        resetDetect()
        _ui.update {
            it.copy(running = false, hand = false, points = null, label = null, conf = 0f, hold = 0f,
                recording = false, countdown = 0, recProgress = 0f)
        }
    }

    // ---- text editing ----
    fun addSpace() { _ui.update { it.copy(text = it.text + " ") } }
    fun backspace() { _ui.update { it.copy(text = it.text.dropLast(1)) } }
    fun clearText() { _ui.update { it.copy(text = "", translation = "") } }

    private fun append(id: String) {
        val st = _ui.value
        val shown = labelsFor(st.pack).firstOrNull { it.id == id }?.shown ?: return
        _ui.update { it.copy(text = it.text + shown) }
    }

    // ---- per-frame work ----
    private fun onFrame(feat: FloatArray?, pts: FloatArray?, aspect: Float) {
        // recording
        synchronized(lock) {
            val rb = recBuf
            if (rb != null && feat != null) {
                rb.add(feat)
                if (rb.size >= recTarget) { recBuf = null; finishRecording(rb) }
                else _ui.update { it.copy(recProgress = rb.size / recTarget.toFloat()) }
            }
        }
        if (feat == null) {
            resetDetect()
            _ui.update { it.copy(hand = false, points = null, label = null, conf = 0f, hold = 0f, aspect = aspect) }
            return
        }
        val st = _ui.value
        var label: String? = null
        var conf = 0f
        var hold = 0f
        if (st.mode == 0) {
            val res = Knn.classify(feat, store.snapshot(), idsFor(st.pack), st.strict)
            val now = System.currentTimeMillis()
            if (res != null) {
                if (res.id == cand) streak++ else { cand = res.id; streak = 1; holdStart = 0L; locked = false }
                if (streak >= 6) {
                    label = res.id
                    conf = (1f - res.dist / st.strict).coerceIn(0f, 1f)
                    if (holdStart == 0L) holdStart = now
                    val el = now - holdStart
                    hold = (el / 700f).coerceIn(0f, 1f)
                    if (el >= 700 && !locked) { locked = true; append(res.id) }
                }
            } else resetDetect()
        }
        _ui.update { it.copy(hand = true, points = pts, aspect = aspect, label = label, conf = conf, hold = hold) }
    }

    // ---- teaching ----
    fun record() {
        val st = _ui.value
        val id = st.selected
        if (id == null) { message("اول یک علامت را انتخاب کن"); return }
        if (!st.running) { message("اول دوربین را شروع کن"); return }
        if (st.recording || st.countdown > 0) return
        val seq = ++recSeq
        viewModelScope.launch {
            for (i in 3 downTo 1) { _ui.update { it.copy(countdown = i) }; delay(900) }
            _ui.update { it.copy(countdown = 0, recording = true, recProgress = 0f, warning = null) }
            synchronized(lock) { recBuf = ArrayList() }
            delay(10000)
            val timedOut = synchronized(lock) { if (recSeq == seq && recBuf != null) { recBuf = null; true } else false }
            if (timedOut) { _ui.update { it.copy(recording = false) }; message("دست دیده نشد، دوباره تلاش کن") }
        }
    }

    private fun finishRecording(list: List<FloatArray>) {
        val st = _ui.value
        val id = st.selected ?: return
        val c = Knn.centroid(list)
        var spread = 0f
        for (a in list) spread += Knn.dist(a, c)
        spread /= list.size
        var warn: String? = null
        if (spread > 0.2f) {
            warn = "دستت حین ضبط ثابت نبود؛ این ضبط ذخیره نشد. دوباره و ثابت‌تر ضبط کن."
        } else {
            store.addUser(id, list)
            val snap = store.snapshot()
            var worst: String? = null
            var wd = Float.MAX_VALUE
            for (other in idsFor(st.pack)) {
                if (other == id) continue
                val ol = snap[other] ?: continue
                if (ol.size < 3) continue
                val d = Knn.dist(c, Knn.centroid(ol))
                if (d < wd) { wd = d; worst = other }
            }
            if (worst != null && wd < 0.12f) {
                val shown = labelsFor(st.pack).firstOrNull { it.id == worst }?.shown ?: worst
                warn = "این علامت خیلی شبیه «$shown» است و ممکنه با هم اشتباه بشن. حالت دست را واضح‌تر متفاوت کن یا نمونهٔ بیشتر ضبط کن."
            }
        }
        refreshCounts()
        _ui.update { it.copy(recording = false, recProgress = 0f, warning = warn) }
        if (warn == null || !warn.startsWith("دستت")) message("ذخیره شد ✓")
    }

    fun deleteSamples(id: String) { store.clearUser(id); refreshCounts(); message("نمونه‌های شما پاک شد") }

    fun exportFile(): File = store.exportFile()

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException()
                val n = store.importText(text)
                refreshCounts()
                message("$n نمونه وارد شد")
            } catch (e: Exception) { message("فایل معتبر نبود") }
        }
    }

    // ---- translation ----
    fun translate() {
        val st = _ui.value
        val q = st.text.trim()
        if (q.isEmpty()) { _ui.update { it.copy(translation = "اول چیزی بنویس.") }; return }
        val (src, dst) = if (st.pack == "fa") "fa" to "en" else "en" to "fa"
        viewModelScope.launch {
            _ui.update { it.copy(translating = true, translation = "") }
            val res = Translator.translate(q.lowercase(), src, dst)
            _ui.update { it.copy(translating = false, translation = res ?: "ترجمه به اینترنت نیاز دارد.") }
        }
    }

    override fun onCleared() { engine.stop(); super.onCleared() }
}
