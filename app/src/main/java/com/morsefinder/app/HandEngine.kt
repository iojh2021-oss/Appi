package com.morsefinder.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.sqrt

/**
 * Runs MediaPipe Hand Landmarker on camera frames and reports, per frame, either null (no hand)
 * or a normalized 63-number feature vector plus 21 landmark points for drawing.
 */
class HandEngine(
    private val ctx: Context,
    private val onFrame: (feat: FloatArray?, pts: FloatArray?, aspect: Float) -> Unit
) : ImageAnalysis.Analyzer {

    private var landmarker: HandLandmarker? = null
    @Volatile private var w = 3
    @Volatile private var h = 4

    fun start(): Boolean {
        if (landmarker != null) return true
        return try {
            val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .setResultListener { result, _ -> handle(result) }
                .setErrorListener { _ -> }
                .build()
            landmarker = HandLandmarker.createFromOptions(ctx, opts)
            true
        } catch (e: Exception) { false }
    }

    fun stop() {
        try { landmarker?.close() } catch (e: Exception) { }
        landmarker = null
    }

    override fun analyze(image: ImageProxy) {
        val lm = landmarker
        if (lm == null) { image.close(); return }
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val rs = plane.rowStride
            val iw = image.width
            val ih = image.height
            val full = Bitmap.createBitmap(rs / 4, ih, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buf)
            val bmp = if (rs / 4 == iw) full else Bitmap.createBitmap(full, 0, 0, iw, ih)
            val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val rb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            w = rb.width; h = rb.height
            lm.detectAsync(BitmapImageBuilder(rb).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            // skip frame
        } finally {
            image.close()
        }
    }

    private fun handle(r: HandLandmarkerResult) {
        val aspect = w.toFloat() / h
        if (r.landmarks().isEmpty()) { onFrame(null, null, aspect); return }
        val pts = r.landmarks()[0]
        val left = r.handedness().firstOrNull()?.firstOrNull()?.categoryName() == "Left"
        val W = w.toFloat()
        val H = h.toFloat()
        val x0 = pts[0].x() * W
        val y0 = pts[0].y() * H
        val z0 = pts[0].z() * W
        val dx = pts[9].x() * W - x0
        val dy = pts[9].y() * H - y0
        val dz = pts[9].z() * W - z0
        val s = sqrt(dx * dx + dy * dy + dz * dz) + 1e-6f
        val feat = FloatArray(63)
        val disp = FloatArray(42)
        for (i in 0 until 21) {
            var vx = (pts[i].x() * W - x0) / s
            val vy = (pts[i].y() * H - y0) / s
            val vz = (pts[i].z() * W - z0) / s
            if (left) vx = -vx          // canonical right-hand shape
            feat[3 * i] = vx; feat[3 * i + 1] = vy; feat[3 * i + 2] = vz
            disp[2 * i] = pts[i].x(); disp[2 * i + 1] = pts[i].y()
        }
        onFrame(feat, disp, aspect)
    }
}
