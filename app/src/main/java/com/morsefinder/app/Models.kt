package com.morsefinder.app

enum class Mode { AUDIO, CAMERA }

/** A mark (beep / light on). e < 0 means it is still open. */
class Pulse(val s: Double, var e: Double)

interface SignalSink {
    fun edge(on: Boolean, t: Double)
    fun clock(t: Double)
}

class AudioInfo(
    val spectrum: FloatArray, val binHz: Float, val noise: Float,
    val thr: Float, val lockHz: Float, val on: Boolean
)

class HeatCell(val u: Float, val v: Float, val w: Float, val h: Float, val alpha: Float)

class CamOverlay(
    val aspect: Float, val roiU: Float, val roiV: Float, val roiR: Float,
    val hasRoi: Boolean, val on: Boolean, val heat: List<HeatCell>
)

data class Settings(
    val sens: Int = 70,
    val wpmFixed: Boolean = false,
    val wpm: Int = 15,
    val freqFixed: Boolean = false,
    val freq: Int = 700,
    val src: String = "en",
    val dst: String = "fa"
)

data class UiState(
    val mode: Mode = Mode.AUDIO,
    val running: Boolean = false,
    val lampOn: Boolean = false,
    val text: String = "",
    val morse: String = "",
    val live: String = "",
    val wpm: Int? = null,
    val metric: String = "—",
    val level: Float = 0f,
    val status: String = "آماده",
    val message: String? = null,
    val audio: AudioInfo? = null,
    val cam: CamOverlay? = null,
    val pulses: List<Pulse> = emptyList(),
    val clockMs: Double = 0.0,
    val meanings: List<Pair<String, String>> = emptyList(),
    val translating: Boolean = false,
    val translation: String = "",
    val tx: Int = 0,               // 0 idle, 1 sound, 2 torch
    val settings: Settings = Settings()
)
