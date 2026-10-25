package com.morsefinder.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

class MorseViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("morse", Context.MODE_PRIVATE)
    private val lock = Any()
    private val chars = ArrayList<Pair<String, String>>()
    private val pulses = ArrayList<Pulse>()
    private var lampOn = false
    private var clockMs = 0.0

    private val decoder = MorseDecoder(object : MorseDecoder.Listener {
        override fun onChar(ch: String, pattern: String) { chars.add(ch to pattern) }
        override fun onSpace() {
            if (chars.isNotEmpty() && chars[chars.size - 1].first != " ") chars.add(" " to "/")
        }
        override fun onBackspace() { if (chars.isNotEmpty()) chars.removeAt(chars.size - 1) }
    })

    private val sink = object : SignalSink {
        override fun edge(on: Boolean, t: Double) {
            synchronized(lock) {
                if (on) pulses.add(Pulse(t, -1.0))
                else if (pulses.isNotEmpty() && pulses[pulses.size - 1].e < 0) pulses[pulses.size - 1].e = t
                decoder.edge(on, t)
                lampOn = on
            }
        }
        override fun clock(t: Double) {
            synchronized(lock) {
                clockMs = t
                decoder.tick(t)
                while (pulses.isNotEmpty() && pulses[0].e >= 0 && pulses[0].e < t - 12000) pulses.removeAt(0)
            }
        }
    }

    val audio = AudioEngine(sink)
    val light = LightEngine(sink)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var ticker: Job? = null
    private var txJob: Job? = null

    init {
        val s = Settings(
            sens = prefs.getInt("sens", 70),
            wpmFixed = prefs.getBoolean("wpmFixed", false),
            wpm = prefs.getInt("wpm", 15),
            freqFixed = prefs.getBoolean("freqFixed", false),
            freq = prefs.getInt("freq", 700),
            src = prefs.getString("src", "en") ?: "en",
            dst = prefs.getString("dst", "fa") ?: "fa"
        )
        _ui.value = _ui.value.copy(settings = s)
        apply(s)
    }

    private fun apply(s: Settings) {
        audio.sensitivity = s.sens
        light.sensitivity = s.sens
        audio.fixedHz = if (s.freqFixed) s.freq else 0
        synchronized(lock) { decoder.setFixedWpm(if (s.wpmFixed) s.wpm else null) }
    }

    fun updateSettings(s: Settings) {
        _ui.update { it.copy(settings = s) }
        prefs.edit()
            .putInt("sens", s.sens).putBoolean("wpmFixed", s.wpmFixed).putInt("wpm", s.wpm)
            .putBoolean("freqFixed", s.freqFixed).putInt("freq", s.freq)
            .putString("src", s.src).putString("dst", s.dst).apply()
        apply(s)
    }

    fun setMode(m: Mode) {
        if (_ui.value.running) stop()
        _ui.update { it.copy(mode = m, metric = "—", level = 0f, audio = null, cam = null) }
    }

    fun message(text: String) {
        _ui.update { it.copy(message = text) }
        viewModelScope.launch { delay(3500); _ui.update { s -> if (s.message == text) s.copy(message = null) else s } }
    }

    fun start() {
        val st = _ui.value
        if (st.running) return
        synchronized(lock) {
            decoder.reset(); pulses.clear(); lampOn = false; clockMs = 0.0
        }
        if (st.mode == Mode.AUDIO) {
            if (!audio.start()) { message("میکروفون باز نشد"); return }
        } else {
            light.reset()
        }
        _ui.update { it.copy(running = true) }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) { publish(); delay(33) }
        }
    }

    fun stop() {
        if (!_ui.value.running) return
        audio.stop()
        ticker?.cancel()
        synchronized(lock) { decoder.flushPending(); lampOn = false }
        _ui.update { it.copy(running = false, lampOn = false) }
        publish()
        _ui.update { it.copy(status = "آماده", lampOn = false, audio = null, cam = null, level = 0f, metric = "—") }
    }

    fun clear() {
        synchronized(lock) { chars.clear() }
        _ui.update { it.copy(translation = "") }
        publish()
    }

    fun currentText(): String = synchronized(lock) { chars.joinToString("") { it.first } }

    fun selectLight(u: Float, v: Float) = light.selectAt(u, v)
    fun rescanLight() = light.rescan()

    private fun publish() {
        var text = ""; var morse = ""; var live = ""; var wpm: Int? = null
        var snap: List<Pulse> = emptyList(); var clock = 0.0; var lamp = false
        synchronized(lock) {
            text = chars.joinToString("") { it.first }
            live = decoder.live()
            morse = chars.joinToString(" ") { prettyMorse(it.second) } +
                if (live.isNotEmpty()) " " + prettyMorse(live) else ""
            wpm = decoder.wpm?.roundToInt()
            snap = pulses.map { Pulse(it.s, it.e) }
            clock = clockMs; lamp = lampOn
        }
        val st = _ui.value
        val running = st.running
        val meanings = text.uppercase().split(Regex("[\\s,.;:!?]+")).filter { it.isNotEmpty() }
            .distinct().filter { MEANINGS.containsKey(it) }.map { it to (MEANINGS[it] ?: "") }
        _ui.update {
            if (st.mode == Mode.AUDIO) {
                it.copy(
                    text = text, morse = morse, live = live, wpm = wpm, lampOn = lamp, pulses = snap, clockMs = clock,
                    meanings = meanings, audio = if (running) audio.info else null,
                    metric = if (audio.freq > 0f) audio.freq.roundToInt().toString() else "—",
                    level = audio.level, status = if (running) audio.status else it.status
                )
            } else {
                it.copy(
                    text = text, morse = morse, live = live, wpm = wpm, lampOn = lamp, pulses = snap, clockMs = clock,
                    meanings = meanings, cam = if (running) light.overlay else null,
                    metric = light.contrast.roundToInt().toString(),
                    level = light.level, status = if (running) light.status else it.status
                )
            }
        }
    }

    fun translate() {
        val q = currentText().trim().lowercase()
        if (q.isEmpty()) { _ui.update { it.copy(translation = "اول چیزی دریافت کن.") }; return }
        val s = _ui.value.settings
        viewModelScope.launch {
            _ui.update { it.copy(translating = true, translation = "") }
            val res = withContext(Dispatchers.IO) {
                try {
                    val pair = URLEncoder.encode(s.src + "|" + s.dst, "UTF-8")
                    val url = URL("https://api.mymemory.translated.net/get?q=" +
                        URLEncoder.encode(q.take(450), "UTF-8") + "&langpair=" + pair)
                    val c = url.openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body).getJSONObject("responseData").getString("translatedText")
                } catch (e: Exception) { null }
            }
            _ui.update {
                it.copy(translating = false,
                    translation = res ?: "ترجمه به اینترنت نیاز دارد. معنی اختصارها بدون اینترنت کار می‌کند.")
            }
        }
    }

    /** kind: 1 = sound, 2 = flashlight. Calling again while playing stops it. */
    fun transmit(kind: Int, text: String, wpm: Int, hz: Int) {
        if (txJob?.isActive == true) { txJob?.cancel(); return }
        txJob = viewModelScope.launch {
            _ui.update { it.copy(tx = kind) }
            try {
                if (kind == 1) Transmitter.playTone(text, wpm, hz)
                else Transmitter.flashTorch(getApplication(), text, wpm)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message("ارسال انجام نشد")
            } finally {
                _ui.update { it.copy(tx = 0) }
            }
        }
    }

    override fun onCleared() {
        audio.stop()
        super.onCleared()
    }
}
