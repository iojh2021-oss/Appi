package com.morsefinder.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Feeds a synthetic noisy Morse recording through the same chain AudioEngine uses. */
class DspTest {
    private val sr = 16000; private val n = 512; private val hop = 64

    private fun synth(text: String, wpm: Int, hz: Double, snrDb: Double, seed: Long): FloatArray {
        val ev = Transmitter.timeline(text, wpm)
        var total = sr * 6 / 10 * 2
        for ((_, ms) in ev) total += (ms * sr / 1000).toInt()
        val x = FloatArray(total)
        var pos = sr * 6 / 10
        val ramp = sr * 4 / 1000
        val amp = 0.3
        for ((on, ms) in ev) {
            val len = (ms * sr / 1000).toInt()
            if (on) for (i in 0 until len) {
                var a = 1.0
                if (i < ramp) a *= i / ramp.toDouble()
                if (len - i < ramp) a *= (len - i) / ramp.toDouble()
                x[pos + i] = (amp * a * sin(2 * PI * hz * i / sr)).toFloat()
            }
            pos += len
        }
        val sigma = sqrt(amp * amp / 2 / Math.pow(10.0, snrDb / 10))
        val r = Random(seed)
        for (i in x.indices) x[i] = (x[i] + sigma * r.nextGaussian()).toFloat().coerceIn(-1f, 1f)
        return x
    }

    private fun decode(x: FloatArray): String {
        val sb = StringBuilder()
        val dec = MorseDecoder(object : MorseDecoder.Listener {
            override fun onChar(ch: String, pattern: String) { sb.append(ch) }
            override fun onSpace() { sb.append(' ') }
            override fun onBackspace() { if (sb.isNotEmpty()) sb.setLength(sb.length - 1) }
        })
        val fft = Fft(n)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
        val det = ToneDetector(); val deb = Debounce(14.0)
        val re = FloatArray(n); val im = FloatArray(n); val db = FloatArray(n / 2)
        var end = n
        while (end <= x.size) {
            for (i in 0 until n) { re[i] = x[end - n + i] * win[i]; im[i] = 0f }
            fft.transform(re, im)
            for (k in 0 until n / 2) db[k] = 20f * log10(sqrt(re[k] * re[k] + im[k] * im[k]) / (n / 4f) + 1e-9f)
            val t = (end - n / 2) * 1000.0 / sr
            val r = det.process(db, sr.toFloat() / n, t, 30f - 0.24f * 70)
            deb.push(r.on, t) { on, tt -> dec.edge(on, tt) }
            dec.tick(t)
            end += hop
        }
        dec.flushPending()
        return sb.toString().trim()
    }

    @Test fun decodesSosClean() { assertEquals("SOS", decode(synth("SOS", 15, 700.0, 20.0, 1))) }
    @Test fun decodesTextInNoise() { assertEquals("CQ TEST 73", decode(synth("CQ TEST 73", 18, 640.0, -3.0, 2))) }
    @Test fun decodesFastCode() { assertEquals("PARIS", decode(synth("PARIS", 30, 800.0, 3.0, 3))) }
    @Test fun silenceProducesNothing() {
        val r = Random(9); val x = FloatArray(sr * 20) { (0.05 * r.nextGaussian()).toFloat() }
        assertEquals("", decode(x))
    }
}
