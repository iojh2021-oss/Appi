package com.morsefinder.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the microphone, runs a 512-point FFT every 4 ms (16 kHz audio) and detects the beep.
 * Edge times come from the sample clock, so timing is independent of thread scheduling.
 *
 * Robustness notes (vs. the first version):
 *  - read errors end the loop with a visible status instead of spinning at 100 % CPU;
 *  - blocks of 256 samples are read per call (4 hops) -> 4x fewer wake-ups, fewer overruns;
 *  - AGC / noise-suppressor / echo-canceller are switched off when the platform lets us
 *    (they smear or mute steady tones);
 *  - if the UNPROCESSED source delivers pure digital silence (happens on some phones) we
 *    automatically fall back to VOICE_RECOGNITION, then MIC;
 *  - start() after stop() can no longer leave two capture threads alive.
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
    private val readSize = 256
    private val fft = Fft(n)
    private val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val detector = ToneDetector()
    private val deb = Debounce(14.0)
    private val sources = intArrayOf(
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC
    )
    @Volatile private var running = false
    private var thread: Thread? = null

    private class Rec(val rec: AudioRecord, val srcIdx: Int, val fx: List<AudioEffect>) {
        fun close() {
            for (e in fx) try { e.release() } catch (_: Exception) { }
            try { rec.stop() } catch (_: Exception) { }
            try { rec.release() } catch (_: Exception) { }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(fromIdx: Int): Rec? {
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        for (idx in fromIdx until sources.size) {
            try {
                val r = AudioRecord(
                    sources[idx], sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, 16384)
                )
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                val fx = ArrayList<AudioEffect>()
                val sid = r.audioSessionId
                try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.let { it.setEnabled(false); fx.add(it) } } catch (_: Exception) { }
                try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.let { it.setEnabled(false); fx.add(it) } } catch (_: Exception) { }
                try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.let { it.setEnabled(false); fx.add(it) } } catch (_: Exception) { }
                return Rec(r, idx, fx)
            } catch (_: Exception) { /* try next source */ }
        }
        return null
    }

    fun start(): Boolean {
        if (running) return true
        // make sure an old capture thread is really gone before we start a new one
        thread?.let { try { it.join(1000) } catch (_: Exception) { } }
        val first = open(0) ?: return false
        detector.reset(); deb.reset(); info = null; freq = 0f; level = 0f
        status = "در حال شنیدن… منتظر بوق"
        running = true
        thread = Thread { loop(first) }.also { it.name = "morse-audio"; it.start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(1000) } catch (_: Exception) { }
        thread = null
        info = null
    }

    private fun loop(first: Rec) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) { }
        var cur: Rec = first
        val ring = FloatArray(n)
        var pos = 0
        var total = 0L
        var hopCnt = 0
        var zeroRun = 0
        val buf = ShortArray(readSize)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val db = FloatArray(n / 2)
        val binHz = sr.toFloat() / n
        var frame = 0

        fun processFrame() {
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

        try {
            cur.rec.startRecording()
            while (running) {
                val got = cur.rec.read(buf, 0, readSize)
                if (got < 0) { status = "خطا در میکروفون"; break }          // ERROR_* -> stop, don't spin
                if (got == 0) { try { Thread.sleep(2) } catch (_: Exception) { }; continue }

                // some phones return endless digital zeros for UNPROCESSED -> fall back
                var allZero = true
                for (i in 0 until got) if (buf[i].toInt() != 0) { allZero = false; break }
                zeroRun = if (allZero) zeroRun + got else 0
                if (zeroRun >= sr && cur.srcIdx + 1 < sources.size) {
                    val next = open(cur.srcIdx + 1)
                    if (next != null) {
                        cur.close(); cur = next; zeroRun = 0
                        cur.rec.startRecording()
                        continue
                    }
                }

                for (i in 0 until got) {
                    ring[pos] = buf[i] / 32768f
                    pos++; if (pos == n) pos = 0
                    total++
                    if (++hopCnt == hop) {
                        hopCnt = 0
                        if (total >= n) processFrame()
                    }
                }
            }
        } catch (e: Exception) {
            status = "خطا در میکروفون"
        } finally {
            cur.close()
        }
    }
}
