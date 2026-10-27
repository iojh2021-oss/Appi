package com.morsefinder.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Translator {
    suspend fun translate(text: String, src: String, dst: String): String? = withContext(Dispatchers.IO) {
        try {
            val pair = URLEncoder.encode("$src|$dst", "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=" +
                URLEncoder.encode(text.take(450), "UTF-8") + "&langpair=" + pair)
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).getJSONObject("responseData").getString("translatedText")
        } catch (e: Exception) { null }
    }
}
