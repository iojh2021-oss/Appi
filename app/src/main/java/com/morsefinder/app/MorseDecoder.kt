package com.morsefinder.app

val MORSE_TABLE: Map<String, String> = mapOf(
    ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E", "..-." to "F", "--." to "G",
    "...." to "H", ".." to "I", ".---" to "J", "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N",
    "---" to "O", ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T", "..-" to "U",
    "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y", "--.." to "Z",
    "-----" to "0", ".----" to "1", "..---" to "2", "...--" to "3", "....-" to "4",
    "....." to "5", "-...." to "6", "--..." to "7", "---.." to "8", "----." to "9",
    ".-.-.-" to ".", "--..--" to ",", "..--.." to "?", ".----." to "'", "-.-.--" to "!",
    "-..-." to "/", "-.--." to "(", "-.--.-" to ")", ".-..." to "&", "---..." to ":",
    "-.-.-." to ";", "-...-" to "=", ".-.-." to "+", "-....-" to "-", "..--.-" to "_",
    ".-..-." to "\"", "...-..-" to "$", ".--.-." to "@",
    "...---..." to "SOS", "...-.-" to "<SK>"
)

/** char -> pattern, used for sending */
val MORSE_REVERSE: Map<String, String> = HashMap<String, String>().also { m ->
    for ((k, v) in MORSE_TABLE) if (!m.containsKey(v)) m[v] = k
}

fun prettyMorse(p: String): String = p.replace('.', '·').replace('-', '–')

/**
 * Adaptive Morse timing decoder. Feed it on/off edges with timestamps in ms.
 * Dit/dah unit is learned from the signal (or fixed by the user).
 */
class MorseDecoder(private val listener: Listener) {
    interface Listener {
        fun onChar(ch: String, pattern: String)
        fun onSpace()
        fun onBackspace()
    }

    private var state = false
    private var last: Double? = null
    private val marks = ArrayList<Double>()
    private val cur = ArrayList<Double>()
    private var unit = 80.0
    private var fixed: Int? = null
    private var flushed = true
    private var wordDone = true

    val wpm: Double? get() = if (marks.size >= 2) 1200.0 / unit else null

    fun reset() {
        state = false; last = null; marks.clear(); cur.clear()
        unit = fixed?.let { 1200.0 / it } ?: 80.0
        flushed = true; wordDone = true
    }

    fun setFixedWpm(w: Int?) {
        fixed = w
        if (w != null) unit = 1200.0 / w
    }

    fun edge(on: Boolean, t: Double) {
        val l = last
        if (l == null) { state = on; last = t; return }
        val d = t - l
        if (state && !on) {
            cur.add(d); marks.add(d)
            if (marks.size > 24) marks.removeAt(0)
            flushed = false; wordDone = false
            estimate()
        } else if (!state && on) {
            checkGap(d)
        }
        state = on; last = t
    }

    fun tick(t: Double) {
        val l = last
        if (l != null && !state) checkGap(t - l)
    }

    fun flushPending() { if (!flushed) flush() }

    fun live(): String = if (cur.isEmpty()) "" else pattern()

    private fun checkGap(gap: Double) {
        if (!flushed && gap >= 2 * unit) flush()
        if (!wordDone && flushed && gap >= 5 * unit) { listener.onSpace(); wordDone = true }
    }

    private fun estimate() {
        fixed?.let { unit = 1200.0 / it; return }
        val s = marks.sorted()
        var c1 = s.first()
        var c2 = s.last()
        var two = false
        var t = unit
        if (s.size >= 3 && c2 / c1 >= 1.9) {
            for (i in 0 until 10) {
                val mid = (c1 + c2) / 2
                val g1 = s.filter { it < mid }
                val g2 = s.filter { it >= mid }
                if (g1.isEmpty() || g2.isEmpty()) break
                c1 = g1.average(); c2 = g2.average()
            }
            val r = c2 / c1
            if (r >= 2.0 && r <= 4.8) { two = true; t = (c1 + c2 / 3) / 2 }
        }
        if (!two) {
            val med = s[s.size / 2]
            t = if (med > 2 * unit) med / 3 else med
            t = 0.5 * unit + 0.5 * t
        } else {
            t = 0.3 * unit + 0.7 * t
        }
        unit = t.coerceIn(20.0, 400.0)
    }

    private fun pattern(): String {
        val thr = 2 * unit
        val sb = StringBuilder()
        for (d in cur) sb.append(if (d >= thr) '-' else '.')
        return sb.toString()
    }

    private fun flush() {
        val p = pattern()
        cur.clear(); flushed = true
        if (p.length >= 8 && p.all { it == '.' }) { listener.onBackspace(); return }
        listener.onChar(MORSE_TABLE[p] ?: "?", p)
    }
}
