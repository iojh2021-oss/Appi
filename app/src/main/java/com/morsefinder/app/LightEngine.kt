package com.morsefinder.app

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Finds a blinking light in the camera image and turns its brightness into on/off edges.
 * Uses the sensor timestamp of each frame, so timing does not depend on thread jitter.
 */
class LightEngine(private val sink: SignalSink) : ImageAnalysis.Analyzer {
    @Volatile var sensitivity = 70
    @Volatile var overlay = CamOverlay(0.75f, 0f, 0f, 0f, false, false, emptyList())
    @Volatile var status = "دنبال چراغ چشمک‌زن می‌گردم…"
    @Volatile var contrast = 0f
    @Volatile var level = 0f

    private val cols = 16
    private val rows = 12
    private val deb = Debounce(30.0)
    private val scanMin = FloatArray(cols * rows)
    private val scanMax = FloatArray(cols * rows)
    private var scanN = 0
    private var hasRoi = false
    private var roiX = 0f
    private var roiY = 0f
    private var roiR = 40f
    private val ht = ArrayList<Double>()
    private val hv = ArrayList<Float>()
    private var on = false
    private var lastGood = 0.0
    private var bytes = ByteArray(0)
    private var frameNo = 0

    @Volatile private var tapA = -1f
    @Volatile private var tapB = -1f
    @Volatile private var rescanFlag = true
    @Volatile private var lastRot = 0

    fun reset() { rescanFlag = true }
    fun rescan() { rescanFlag = true }

    /** u,v are normalized (0..1) positions in the upright picture. */
    fun selectAt(u: Float, v: Float) {
        when (lastRot) {
            90 -> { tapA = v; tapB = 1f - u }
            180 -> { tapA = 1f - u; tapB = 1f - v }
            270 -> { tapA = 1f - v; tapB = u }
            else -> { tapA = u; tapB = v }
        }
    }

    private fun resetScan() {
        java.util.Arrays.fill(scanMin, 255f); java.util.Arrays.fill(scanMax, 0f)
        scanN = 0; hasRoi = false
        ht.clear(); hv.clear(); deb.reset(); on = false
    }

    private fun upright(a: Float, b: Float, rot: Int): FloatArray = when (rot) {
        90 -> floatArrayOf(1f - b, a)
        180 -> floatArrayOf(1f - a, 1f - b)
        270 -> floatArrayOf(b, 1f - a)
        else -> floatArrayOf(a, b)
    }

    override fun analyze(image: ImageProxy) {
        try { process(image) } catch (e: Exception) { status = "خطا در دوربین" } finally { image.close() }
    }

    private fun process(image: ImageProxy) {
        val w = image.width
        val h = image.height
        val rot = image.imageInfo.rotationDegrees
        lastRot = rot
        val t = image.imageInfo.timestamp / 1_000_000.0
        val plane = image.planes[0]
        val rs = plane.rowStride
        val bb = plane.buffer
        bb.rewind()
        val need = bb.remaining()
        if (bytes.size < need) bytes = ByteArray(need)
        bb.get(bytes, 0, need)

        val aspect = if (rot % 180 == 0) w.toFloat() / h else h.toFloat() / w
        val minContrast = 60f - 0.5f * sensitivity

        if (rescanFlag) { rescanFlag = false; resetScan(); status = "دنبال چراغ چشمک‌زن می‌گردم… گوشی را ثابت نگه دار" }
        if (tapA >= 0f) {
            roiX = tapA * w; roiY = tapB * h; roiR = 1.6f * (w / cols)
            hasRoi = true; tapA = -1f
            ht.clear(); hv.clear(); deb.reset(); on = false; lastGood = t
        }

        val cw = w / cols
        val ch = h / rows

        if (!hasRoi) {
            for (cy in 0 until rows) for (cx in 0 until cols) {
                var m = 0
                var y = cy * ch
                while (y < cy * ch + ch) {
                    var x = cx * cw
                    val base = y * rs
                    while (x < cx * cw + cw) {
                        val q = bytes[base + x].toInt() and 0xFF
                        if (q > m) m = q
                        x += 2
                    }
                    y += 2
                }
                val k = cy * cols + cx
                if (m < scanMin[k]) scanMin[k] = m.toFloat()
                if (m > scanMax[k]) scanMax[k] = m.toFloat()
            }
            scanN++
            var best = -1f
            var bk = 0
            for (k in 0 until cols * rows) {
                val r = scanMax[k] - scanMin[k]
                if (r > best) { best = r; bk = k }
            }
            if (scanN >= 45) {
                if (best >= maxOf(35f, minContrast)) {
                    roiX = (bk % cols) * cw + cw / 2f
                    roiY = (bk / cols) * ch + ch / 2f
                    roiR = 1.6f * cw
                    hasRoi = true; ht.clear(); hv.clear(); lastGood = t
                } else {
                    java.util.Arrays.fill(scanMin, 255f); java.util.Arrays.fill(scanMax, 0f); scanN = 0
                }
            }
            contrast = best.coerceAtLeast(0f)
            level = (contrast / 150f).coerceIn(0f, 1f)
            if (frameNo++ % 3 == 0) publishScan(rot, aspect, cw, ch, w, h)
            status = "دنبال چراغ چشمک‌زن می‌گردم… گوشی را ثابت نگه دار"
            sink.clock(t)
            return
        }

        // tracking: brightness of the brightest few percent of pixels inside the ROI
        val hist = IntArray(256)
        var count = 0
        val y0 = maxOf(0, (roiY - roiR).toInt()); val y1 = minOf(h, (roiY + roiR).toInt() + 1)
        val x0 = maxOf(0, (roiX - roiR).toInt()); val x1 = minOf(w, (roiX + roiR).toInt() + 1)
        var y = y0
        while (y < y1) {
            var x = x0
            val base = y * rs
            while (x < x1) { hist[bytes[base + x].toInt() and 0xFF]++; count++; x += 2 }
            y += 2
        }
        if (count == 0) return
        val k = maxOf(4, (count * 0.03f).toInt())
        var rem = k
        var sum = 0L
        var v = 255
        while (v >= 0 && rem > 0) {
            val c = minOf(hist[v], rem); sum += c.toLong() * v; rem -= c; v--
        }
        val sig = sum.toFloat() / k

        ht.add(t); hv.add(sig)
        while (ht.isNotEmpty() && t - ht[0] > 5000) { ht.removeAt(0); hv.removeAt(0) }
        var lo = 255f; var hi = 0f
        for (x in hv) { if (x < lo) lo = x; if (x > hi) hi = x }
        val c = hi - lo
        contrast = c
        var raw = false
        if (c >= minContrast) {
            val mid = (hi + lo) / 2f
            val hy = 0.12f * c
            raw = if (on) sig > mid - hy else sig > mid + hy
            lastGood = t
        }
        on = raw
        deb.push(raw, t) { o, tt -> sink.edge(o, tt) }
        sink.clock(t)
        level = (c / 150f).coerceIn(0f, 1f)

        if (t - lastGood > 8000) {
            resetScan(); status = "نور از دست رفت، دوباره می‌گردم"
        } else {
            status = if (c >= minContrast) "نور را دنبال می‌کنم" else "منتظر چشمک نور… (یا روی نور ضربه بزن)"
        }
        val p = upright(roiX / w, roiY / h, rot)
        val rn = roiR / (if (rot % 180 == 0) w else h)
        overlay = CamOverlay(aspect, p[0], p[1], rn, true, on, emptyList())
    }

    private fun publishScan(rot: Int, aspect: Float, cw: Int, ch: Int, w: Int, h: Int) {
        val cells = ArrayList<HeatCell>()
        for (k in 0 until cols * rows) {
            val r = scanMax[k] - scanMin[k]
            if (r < 20f) continue
            val a = ((k % cols) + 0.5f) * cw / w
            val b = ((k / cols) + 0.5f) * ch / h
            val p = upright(a, b, rot)
            val sw = if (rot % 180 == 0) cw.toFloat() / w else ch.toFloat() / h
            val sh = if (rot % 180 == 0) ch.toFloat() / h else cw.toFloat() / w
            cells.add(HeatCell(p[0], p[1], sw, sh, (r / 255f).coerceIn(0.1f, 0.6f)))
        }
        overlay = CamOverlay(aspect, 0f, 0f, 0f, false, false, cells)
    }
}
