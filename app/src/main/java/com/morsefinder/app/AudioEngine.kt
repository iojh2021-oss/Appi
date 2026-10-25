package com.morsefinder.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the microphone, runs a 512-point FFT every 4 ms (16 kHz audio) and detects the beep.
 * Edge times come from the sample clock, so timing is independent of thread scheduling.
 */
class AudioEngine(private val sink: SignalSink) {
    @Volatile var sensitivity = 70
    @Volatile var fixedHz = 0
    @Volatile var info: AudioInfo? = null
    @Volatile var status = ""
    @Volatile var level = 0f
    @Volatile var freq = 0f

    private val sr = 16000
    private val n = 512
    private val hop = 64
    private val fft = Fft(n)
    private val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val detector = ToneDetector()
    private val deb = Debounce(14.0)
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        var rec: AudioRecord? = null
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            try {
                val r = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
            } catch (e: Exception) { /* try next source */ }
        }
        val r = rec ?: return false
        detector.reset(); deb.reset(); info = null; freq = 0f; level = 0f
        status = "در حال شنیدن… منتظر بوق"
        running = true
        thread = Thread { loop(r) }.also { it.priority = Thread.MAX_PRIORITY; it.start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (e: Exception) { }
        thread = null
        info = null
    }

    private fun loop(rec: AudioRecord) {
        val ring = FloatArray(n)
        var pos = 0
        var total = 0L
        val buf = ShortArray(hop)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val db = FloatArray(n / 2)
        val binHz = sr.toFloat() / n
        var frame = 0
        try {
            rec.startRecording()
            while (running) {
                val got = rec.read(buf, 0, hop)
                if (got <= 0) continue
                for (i in 0 until got) {
                    ring[pos] = buf[i] / 32768f
                    pos++; if (pos == n) pos = 0
                    total++
                }
                if (total < n) continue
                for (i in 0 until n) { re[i] = ring[(pos + i) % n] * win[i]; im[i] = 0f }
                fft.transform(re, im)
                for (k in 0 until n / 2) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / (n / 4f)
                    db[k] = 20f * log10(mag + 1e-9f)
                }
                val t = (total - n / 2) * 1000.0 / sr
                val thr = 30f - 0.24f * sensitivity
                detector.fixedHz = fixedHz.toFloat()
                val r = detector.process(db, binHz, t, thr)
                deb.push(r.on, t) { on, tt -> sink.edge(on, tt) }
                sink.clock(t)
                level = (r.snr / 40f).coerceIn(0f, 1f)
                freq = r.freq
                if (frame++ % 4 == 0) {
                    val spec = FloatArray(100)
                    System.arraycopy(db, 0, spec, 0, 100)
                    info = AudioInfo(spec, binHz, r.noise, thr, r.freq, r.on)
                    status = if (r.freq > 0f) "بوق ${Math.round(r.freq)} هرتز پیدا شد" else "در حال شنیدن… منتظر بوق"
                }
            }
        } catch (e: Exception) {
            status = "خطا در میکروفون"
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
        }
    }
}
