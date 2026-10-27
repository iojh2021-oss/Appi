package com.morsefinder.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Hand-shape samples (63 numbers = 21 landmarks x,y,z, normalized).
 * "base" samples ship inside the APK (assets/packs/base.json); "user" samples are taught on the phone.
 */
class SampleStore(private val ctx: Context) {
    private val user = HashMap<String, ArrayList<FloatArray>>()
    private val base = HashMap<String, ArrayList<FloatArray>>()
    @Volatile private var cache: Map<String, List<FloatArray>> = emptyMap()
    private val file get() = File(ctx.filesDir, "samples.json")

    @Synchronized
    fun load() {
        base.clear(); user.clear()
        try { ctx.assets.open("packs/base.json").bufferedReader().use { parseInto(it.readText(), base) } } catch (e: Exception) { }
        try { if (file.exists()) parseInto(file.readText(), user) } catch (e: Exception) { }
        rebuild()
    }

    private fun parseInto(text: String, into: MutableMap<String, ArrayList<FloatArray>>): Int {
        val o = JSONObject(text)
        var n = 0
        for (k in o.keys()) {
            val arr = o.getJSONArray(k)
            val list = into.getOrPut(k) { ArrayList() }
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                if (a.length() != 63) continue
                list.add(FloatArray(63) { a.getDouble(it).toFloat() })
                n++
            }
        }
        return n
    }

    private fun rebuild() {
        val m = HashMap<String, List<FloatArray>>()
        for (k in (base.keys + user.keys)) m[k] = (base[k] ?: emptyList<FloatArray>()) + (user[k] ?: emptyList<FloatArray>())
        cache = m
    }

    fun snapshot(): Map<String, List<FloatArray>> = cache

    @Synchronized fun userCount(id: String) = user[id]?.size ?: 0
    @Synchronized fun baseCount(id: String) = base[id]?.size ?: 0

    @Synchronized
    fun addUser(id: String, samples: List<FloatArray>) {
        val l = user.getOrPut(id) { ArrayList() }
        l.addAll(samples)
        while (l.size > 90) l.removeAt(0)
        save(); rebuild()
    }

    @Synchronized
    fun clearUser(id: String) { user.remove(id); save(); rebuild() }

    @Synchronized
    fun importText(text: String): Int {
        val n = parseInto(text, user)
        for (l in user.values) while (l.size > 90) l.removeAt(0)
        save(); rebuild()
        return n
    }

    @Synchronized
    fun exportFile(): File {
        val f = File(ctx.cacheDir, "sign_pack.json")
        f.writeText(toJson(user))
        return f
    }

    private fun save() { try { file.writeText(toJson(user)) } catch (e: Exception) { } }

    private fun toJson(m: Map<String, List<FloatArray>>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (v.isEmpty()) continue
            if (!first) sb.append(',')
            first = false
            sb.append(JSONObject.quote(k)).append(":[")
            v.forEachIndexed { i, a ->
                if (i > 0) sb.append(',')
                sb.append('[')
                a.forEachIndexed { j, x -> if (j > 0) sb.append(','); sb.append(String.format(Locale.US, "%.3f", x)) }
                sb.append(']')
            }
            sb.append(']')
        }
        return sb.append('}').toString()
    }
}

object Knn {
    class Result(val id: String, val dist: Float, val margin: Float)

    fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until 21) {
            val dx = a[3 * i] - b[3 * i]
            val dy = a[3 * i + 1] - b[3 * i + 1]
            val dz = (a[3 * i + 2] - b[3 * i + 2]) * 0.5f
            s += dx * dx + dy * dy + dz * dz
        }
        return sqrt(s / 21f)
    }

    fun centroid(list: List<FloatArray>): FloatArray {
        val c = FloatArray(63)
        for (a in list) for (i in 0 until 63) c[i] += a[i]
        for (i in 0 until 63) c[i] /= list.size
        return c
    }

    /**
     * Returns a label only if it is close enough AND clearly better than the runner-up.
     * Anything ambiguous returns null ("unknown") instead of a wrong guess.
     */
    fun classify(f: FloatArray, data: Map<String, List<FloatArray>>, ids: Set<String>, maxDist: Float): Result? {
        var best: String? = null
        var bs = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        for (id in ids) {
            val list = data[id] ?: continue
            if (list.size < 3) continue
            var m1 = Float.MAX_VALUE; var m2 = Float.MAX_VALUE; var m3 = Float.MAX_VALUE
            for (s in list) {
                val d = dist(f, s)
                if (d < m1) { m3 = m2; m2 = m1; m1 = d } else if (d < m2) { m3 = m2; m2 = d } else if (d < m3) m3 = d
            }
            val score = (m1 + m2 + m3) / 3f
            if (score < bs) { second = bs; bs = score; best = id } else if (score < second) second = score
        }
        val b = best ?: return null
        if (bs > maxDist) return null
        val margin = if (second == Float.MAX_VALUE) 9f else second / maxOf(bs, 1e-4f)
        if (margin < 1.25f) return null
        return Result(b, bs, margin)
    }
}
