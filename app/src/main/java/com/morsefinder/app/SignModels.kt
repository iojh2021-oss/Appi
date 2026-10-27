package com.morsefinder.app

data class SignLabel(val id: String, val shown: String)

private val FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

val EN_LABELS: List<SignLabel> =
    ('A'..'Z').map { SignLabel("en:$it", "$it") } + (0..9).map { SignLabel("d:$it", "$it") }

/** Persian alphabet gestures + vowels, in the same 37-letter layout used by the MPSL paper. */
val FA_LABELS: List<SignLabel> =
    listOf(
        "آ", "اَ", "اِ", "اُ", "او", "ای", "ب", "پ", "ت", "ث", "ج", "چ", "ح", "خ", "د", "ذ", "ر", "ز", "ژ",
        "س", "ش", "ص", "ض", "ط", "ظ", "ع", "غ", "ف", "ق", "ک", "گ", "ل", "م", "ن", "و", "ه", "ی"
    ).map { SignLabel("fa:$it", it) } + (0..9).map { SignLabel("d:$it", FA_DIGITS[it].toString()) }

fun labelsFor(pack: String): List<SignLabel> = if (pack == "fa") FA_LABELS else EN_LABELS

data class SignUi(
    val mode: Int = 0,                 // 0 detect, 1 teach
    val pack: String = "en",
    val front: Boolean = false,
    val running: Boolean = false,
    val hand: Boolean = false,
    val points: FloatArray? = null,    // 21 x (x,y) normalized, upright image
    val aspect: Float = 0.75f,
    val label: String? = null,         // id of the stable candidate
    val conf: Float = 0f,
    val hold: Float = 0f,
    val strict: Float = 0.28f,
    val text: String = "",
    val counts: Map<String, Int> = emptyMap(),
    val baseCounts: Map<String, Int> = emptyMap(),
    val selected: String? = null,
    val countdown: Int = 0,
    val recording: Boolean = false,
    val recProgress: Float = 0f,
    val warning: String? = null,
    val translating: Boolean = false,
    val translation: String = "",
    val message: String? = null
)
