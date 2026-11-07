package com.example.androtviptv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal MAG/Stalker portal client. Each operation performs its own handshake so
 * short-lived portal tokens are not reused after they expire.
 */
internal class StalkerPortalClient(
    private val portal: String,
    private val mac: String
) {
    data class Channel(val id: String, val name: String, val group: String, val logo: String)
    data class Session(val token: String, val cookie: String) {
        fun headers(): Map<String, String> = mapOf(
            "Cookie" to cookie,
            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3",
            "X-User-Agent" to "Model: MAG250; Link: Ethernet",
            "Accept" to "*/*"
        )
    }

    private val endpoint = portal.trim().let { if (it.endsWith("/")) it else "$it/" }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun call(session: Session?, params: List<Pair<String, String>>): JSONObject {
        val query = params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
        val separator = if (endpoint.contains("?")) "&" else "?"
        val connection = (URL(endpoint + separator + query).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            useCaches = false
            setRequestProperty("User-Agent", session?.headers()?.get("User-Agent")
                ?: "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3")
            setRequestProperty("X-User-Agent", "Model: MAG250; Link: Ethernet")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", endpoint)
            setRequestProperty("Cookie", session?.cookie ?: "mac=$mac; stb_lang=en; timezone=Europe%2FIstanbul")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Portal HTTP $code")
            val json = JSONObject(body)
            val js = json.optJSONObject("js")
            val error = js?.optString("error").orEmpty()
            if (error.isNotBlank() && error != "0") throw IllegalStateException("Portal: $error")
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun handshake(): Session {
        val response = call(null, listOf(
            "type" to "stb", "action" to "handshake", "token" to "",
            "JsHttpRequest" to "1-xml"
        ))
        val js = response.optJSONObject("js") ?: throw IllegalStateException("Portal handshake yanıtı geçersiz.")
        val token = js.optString("token")
        if (token.isBlank()) throw IllegalStateException("Portal oturum anahtarı vermedi.")
        val cookie = "mac=$mac; stb_lang=en; timezone=Europe%2FIstanbul"
        return Session(token, cookie)
    }

    fun loadChannels(): List<Channel> {
        val session = handshake()
        val response = call(session, listOf(
            "type" to "itv", "action" to "get_all_channels",
            "JsHttpRequest" to "1-xml", "token" to session.token
        ))
        val js = response.optJSONObject("js") ?: throw IllegalStateException("Portal kanal yanıtı geçersiz.")
        val data = js.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("data") ?: data.optJSONArray("channels")
            else -> null
        } ?: throw IllegalStateException("Portal kanal listesi döndürmedi.")
        val genres = loadGenres(session)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { item.optString("ch_id") }
            if (id.isBlank()) return@mapNotNull null
            val genreId = item.optString("tv_genre_id")
            Channel(
                id = id,
                name = item.optString("name").ifBlank { "Kanal $id" },
                group = genres[genreId] ?: item.optString("genre_title").ifBlank { "Diğer" },
                logo = item.optString("logo").ifBlank { item.optString("logo_2") }
            )
        }
    }

    private fun loadGenres(session: Session): Map<String, String> = try {
        val response = call(session, listOf(
            "type" to "itv", "action" to "get_genres",
            "JsHttpRequest" to "1-xml", "token" to session.token
        ))
        val js = response.optJSONObject("js")
        val data = js?.optJSONArray("data") ?: JSONArray()
        buildMap {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val title = item.optString("title")
                if (id.isNotBlank() && title.isNotBlank()) put(id, title)
            }
        }
    } catch (_: Exception) { emptyMap() }

    fun createLink(channelId: String): Pair<String, Map<String, String>> {
        val session = handshake()
        val response = call(session, listOf(
            "type" to "itv", "action" to "create_link",
            "cmd" to "ffmpeg http://localhost/ch/$channelId",
            "JsHttpRequest" to "1-xml", "token" to session.token
        ))
        val js = response.optJSONObject("js") ?: throw IllegalStateException("Portal yayın yanıtı geçersiz.")
        val raw = js.optString("cmd").ifBlank { js.optString("link") }
        val link = raw.removePrefix("ffmpeg ").trim()
        if (!link.startsWith("http://") && !link.startsWith("https://")) {
            throw IllegalStateException("Portal geçerli bir yayın bağlantısı vermedi.")
        }
        return link to session.headers()
    }
}
