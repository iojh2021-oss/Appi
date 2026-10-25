package com.morsefinder.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Plays or flashes Morse so the decoder can be tested without a second device. */
object Transmitter {

    fun timeline(text: String, wpm: Int): List<Pair<Boolean, Double>> {
        val u = 1200.0 / wpm
        val ev = ArrayList<Pair<Boolean, Double>>()
        val words = text.uppercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        words.forEachIndexed { wi, w ->
            val chs = w.filter { MORSE_REVERSE.containsKey(it.toString()) }
            chs.forEachIndexed { ci, ch ->
                val pat = MORSE_REVERSE[ch.toString()] ?: return@forEachIndexed
                pat.forEachIndexed { si, s ->
                    ev.add(true to (if (s == '.') u else 3 * u))
                    val lastSym = si == pat.length - 1
                    val gap = when {
                        !lastSym -> u
                        ci < chs.length - 1 -> 3 * u
                        wi < words.size - 1 -> 7 * u
                        else -> u
                    }
                    ev.add(false to gap)
                }
            }
        }
        return ev
    }

    suspend fun playTone(text: String, wpm: Int, hz: Int) {
        val ev = timeline(text, wpm)
        if (ev.isEmpty()) return
        val sr = 16000
        val lead = sr * 3 / 10
        var total = lead
        for ((_, ms) in ev) total += (ms * sr / 1000).toInt()
        total += sr / 5
        val pcm = ShortArray(total)
        var pos = lead
        val ramp = sr * 4 / 1000
        for ((on, ms) in ev) {
            val len = (ms * sr / 1000).toInt()
            if (on) {
                for (i in 0 until len) {
                    var a = 0.6
                    if (i < ramp) a *= i / ramp.toDouble()
                    if (len - i < ramp) a *= (len - i) / ramp.toDouble()
                    pcm[pos + i] = (a * 32767 * sin(2.0 * PI * hz * i / sr)).toInt().toShort()
                }
            }
            pos += len
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(total * 1000L / sr + 300)
        } finally {
            try { track.stop() } catch (e: Exception) { }
            track.release()
        }
    }

    suspend fun flashTorch(ctx: Context, text: String, wpm: Int) {
        val ev = timeline(text, wpm)
        if (ev.isEmpty()) return
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        try {
            var target = SystemClock.elapsedRealtime() + 800
            for ((on, ms) in ev) {
                val wait = target - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                cm.setTorchMode(id, on)
                target += ms.toLong()
            }
            val wait = target - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
        } finally {
            try { cm.setTorchMode(id, false) } catch (e: Exception) { }
        }
    }
}
