package com.morsefinder.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Removes glitches shorter than minMs from a boolean signal and emits clean edges. */
class Debounce(private val minMs: Double) {
    private var state = false
    private var pend: Double? = null

    fun reset() { state = false; pend = null }

    fun push(raw: Boolean, t: Double, cb: (Boolean, Double) -> Unit) {
        if (raw == state) { pend = null; return }
        val p = pend
        if (p == null) { pend = t; return }
        if (t - p >= minMs) { state = raw; cb(raw, p); pend = null }
    }
}

/** In-place radix-2 FFT. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { (-sin(2.0 * PI * it / n)).toFloat() }
    private val rev = IntArray(n).also { r ->
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var x = i; var y = 0
            for (b in 0 until bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

class ToneResult(val on: Boolean, val snr: Float, val noise: Float, val freq: Float)

/**
 * Narrow-band beep detector working on a dB spectrum.
 * Finds the strongest narrow peak, locks onto its frequency, and uses hysteresis
 * plus a relative-drop rule so the on/off edges are sharp.
 */
class ToneDetector {
    var fixedHz = 0f
    private val fmin = 200f
    private val fmax = 2500f
    private var lockHz = 0f
    private var on = false
    private var ref = -140f
    private var cnt = 0
    private var lastOn = 0.0
    private val band = FloatArray(256)

    fun reset() { lockHz = 0f; on = false; ref = -140f; cnt = 0; lastOn = 0.0 }

    fun process(db: FloatArray, binHz: Float, t: Double, thr: Float): ToneResult {
        val lo = maxOf(8, (fmin / binHz).toInt())
        val hi = minOf(db.size - 9, (fmax / binHz).toInt() + 1)
        val len = hi - lo + 1
        for (i in 0 until len) band[i] = maxOf(db[lo + i], -140f)
        java.util.Arrays.sort(band, 0, len)
        val noise = band[len / 2]

        var a = lo
        var b = hi
        val center = if (fixedHz > 0f) fixedHz else lockHz
        if (center > 0f) {
            val cb = Math.round(center / binHz)
            a = maxOf(lo, cb - 3); b = minOf(hi, cb + 3)
        }
        var pk = a
        var pkDb = -1000f
        for (i in a..b) if (db[i] > pkDb) { pkDb = db[i]; pk = i }
        var shoulder = -140f
        for (d in 4..8) shoulder = maxOf(shoulder, db[pk - d], db[pk + d])

        val snr = pkDb - noise
        val narrow = pkDb - shoulder >= 6f
        val was = on
        val raw = pkDb > -85f && narrow &&
            (if (was) (snr >= thr - 4f && pkDb >= ref - 12f) else snr >= thr)
        if (raw) ref = if (was) maxOf(pkDb, ref - 0.05f) else pkDb

        if (raw) {
            lastOn = t
            val y0 = db[pk - 1]; val y1 = db[pk]; val y2 = db[pk + 1]
            val den = y0 - 2 * y1 + y2
            val delta = if (den != 0f) (0.5f * (y0 - y2) / den).coerceIn(-1f, 1f) else 0f
            val f = (pk + delta) * binHz
            cnt++
            if (fixedHz <= 0f) {
                if (lockHz == 0f) { if (cnt >= 3) lockHz = f } else lockHz = 0.9f * lockHz + 0.1f * f
            }
        } else cnt = 0
        if (fixedHz <= 0f && lockHz > 0f && t - lastOn > 5000) lockHz = 0f
        on = raw
        return ToneResult(raw, snr, noise, if (fixedHz > 0f) fixedHz else lockHz)
    }
}
