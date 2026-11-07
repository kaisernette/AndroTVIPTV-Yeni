package com.example.androtviptv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MAG/Stalker portal client.
 *
 * A fresh handshake and profile request are made for each operation. Cookies
 * returned by the portal are retained together with the MAC cookie because
 * some middleware installations require both for channel and stream requests.
 */
internal class StalkerPortalClient(
    private val portal: String,
    private val mac: String
) {
    data class Channel(val id: String, val name: String, val group: String, val logo: String)

    data class Session(val token: String, val cookie: String) {
        fun headers(): Map<String, String> = mapOf(
            "Cookie" to cookie,
            "User-Agent" to USER_AGENT,
            "X-User-Agent" to X_USER_AGENT,
            "Accept" to "*/*"
        )
    }

    private data class ApiResponse(
        val json: JSONObject,
        val setCookies: List<String>
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3"
        private const val X_USER_AGENT = "Model: MAG270; Link: Wifi"
    }

    // Stalker providers expose several standard API entry points. Keep the
    // supplied portal prefix and try its common API routes during handshake.
    private fun buildEndpointCandidates(value: String): List<String> {
        val parsed = URL(value.trim())
        val origin = "${parsed.protocol}://${parsed.authority}"
        val path = parsed.path.trimEnd('/')
        val exact = value.trim().trimEnd('/')
        val candidates = mutableListOf<String>()

        if (path.endsWith(".php", ignoreCase = true) || path.endsWith("load.php", ignoreCase = true)) {
            candidates += exact
        } else {
            if (path.isNotEmpty()) candidates += "$path/portal.php".let { origin + it }
            candidates += "$origin/stalker_portal/server/load.php"
            if (path.isNotEmpty()) candidates += "$path/".let { origin + it }
            candidates += "$origin/portal.php"
        }
        return candidates.distinct()
    }

    private val endpointCandidates = buildEndpointCandidates(portal)
    @Volatile private var activeEndpoint = endpointCandidates.first()

    private val baseCookie: String
        get() = "mac=${mac.trim().uppercase(java.util.Locale.ROOT)}; stb_lang=en; timezone=Europe%2FIstanbul"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun mergeCookies(existing: String, setCookies: List<String>): String {
        val values = linkedMapOf<String, String>()
        fun add(cookie: String) {
            cookie.split(";").firstOrNull()?.trim()?.takeIf { it.contains("=") }?.let { pair ->
                val name = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=").trim()
                if (name.isNotEmpty()) values[name] = value
            }
        }
        existing.split(";").forEach(::add)
        setCookies.forEach(::add)
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun call(session: Session?, params: List<Pair<String, String>>): ApiResponse {
        val query = params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
        val separator = if (activeEndpoint.contains("?")) "&" else "?"
        val connection = (URL(activeEndpoint + separator + query).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-User-Agent", X_USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", activeEndpoint)
            setRequestProperty("Cookie", session?.cookie ?: baseCookie)
            // Stalker middleware commonly reads the token from Authorization, not query params.
            session?.token?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Portal HTTP $code")
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IllegalStateException("Portal JSON yanıtı geçersiz.", e)
            }
            val js = json.optJSONObject("js")
            val error = js?.optString("error").orEmpty()
            if (error.isNotBlank() && error != "0" && error != "false") {
                throw IllegalStateException("Portal: $error")
            }
            val cookies = connection.headerFields.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value.orEmpty()
            return ApiResponse(json, cookies)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Stalker portals commonly expect get_profile after handshake before
     * returning the channel list or a playable link.
     */
    private fun openSession(): Session {
        var handshake: ApiResponse? = null
        var lastHandshakeError: Throwable? = null
        for (candidate in endpointCandidates) {
            activeEndpoint = candidate
            try {
                val response = call(null, listOf(
                    "type" to "stb",
                    "action" to "handshake",
                    "token" to "",
                    "JsHttpRequest" to "1-xml"
                ))
                val tokenValue = response.json.optJSONObject("js")?.optString("token").orEmpty()
                if (tokenValue.isNotBlank()) {
                    handshake = response
                    break
                }
                lastHandshakeError = IllegalStateException("Bu portal adresinde oturum anahtarı alınamadı.")
            } catch (error: Exception) {
                lastHandshakeError = error
            }
        }
        val handshakeResponse = handshake
            ?: throw IllegalStateException(
                "Portal API adresi yanıt vermedi. Sağlayıcının verdiği tam portal adresini kontrol edin. " +
                    (lastHandshakeError?.message ?: "")
            )
        val handshakeJs = handshakeResponse.json.optJSONObject("js")
            ?: throw IllegalStateException("Portal handshake yanıtı geçersiz.")
        val token = handshakeJs.optString("token")
        if (token.isBlank()) throw IllegalStateException("Portal oturum anahtarı vermedi.")

        var session = Session(token, mergeCookies(baseCookie, handshakeResponse.setCookies))
        val profile = call(session, listOf(
            "type" to "stb",
            "action" to "get_profile",
            "hd" to "1",
            "ver" to "ImageDescription: 0.2.18-r23-250",
            "num" to "1",
            "stb_type" to "MAG270",
            "client_type" to "STB",
            "JsHttpRequest" to "1-xml",
            "token" to session.token
        ))
        val profileJs = profile.json.optJSONObject("js")
            ?: throw IllegalStateException("Portal profil yanıtı geçersiz.")
        val profileStatus = profileJs.optString("status")
        if (profileStatus.equals("0", ignoreCase = true) ||
            profileStatus.equals("unauthorized", ignoreCase = true) ||
            profileStatus.equals("blocked", ignoreCase = true)) {
            throw IllegalStateException("Portal MAC adresini yetkilendirmedi.")
        }
        session = session.copy(cookie = mergeCookies(session.cookie, profile.setCookies))
        return session
    }

    fun loadChannels(): List<Channel> {
        val session = openSession()
        val response = call(session, listOf(
            "type" to "itv",
            "action" to "get_all_channels",
            "force_ch_link_check" to "",
            "JsHttpRequest" to "1-xml"
        ))
        val js = response.json.optJSONObject("js")
            ?: throw IllegalStateException("Portal kanal yanıtı geçersiz.")
        val data = js.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("data")
                ?: data.optJSONArray("channels")
                ?: data.optJSONArray("items")
            is String -> runCatching { JSONArray(data) }.getOrNull()
                ?: runCatching { JSONObject(data).let { obj ->
                    obj.optJSONArray("data") ?: obj.optJSONArray("channels") ?: obj.optJSONArray("items")
                } }.getOrNull()
            else -> null
        } ?: throw IllegalStateException(
            "Portal kanal listesi döndürmedi (yanıt: ${js.toString().take(220)})."
        )

        val genres = loadGenres(session)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { item.optString("ch_id") }
            if (id.isBlank()) return@mapNotNull null
            val genreId = item.optString("tv_genre_id")
            Channel(
                id = id,
                name = item.optString("name").ifBlank { "Kanal $id" },
                group = genres[genreId]
                    ?: item.optString("genre_title").ifBlank { "Diğer" },
                logo = item.optString("logo").ifBlank { item.optString("logo_2") }
            )
        }
    }

    private fun loadGenres(session: Session): Map<String, String> = try {
        val response = call(session, listOf(
            "type" to "itv",
            "action" to "get_genres",
            "JsHttpRequest" to "1-xml"
        ))
        val js = response.json.optJSONObject("js")
        val data = js?.optJSONArray("data")
            ?: (js?.opt("data") as? JSONObject)?.optJSONArray("data")
            ?: JSONArray()
        buildMap {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val title = item.optString("title")
                if (id.isNotBlank() && title.isNotBlank()) put(id, title)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    fun createLink(channelId: String): Pair<String, Map<String, String>> {
        val session = openSession()
        val response = call(session, listOf(
            "type" to "itv",
            "action" to "create_link",
            "cmd" to "ffmpeg http://localhost/ch/$channelId",
            "JsHttpRequest" to "1-xml"
        ))
        val js = response.json.optJSONObject("js")
            ?: throw IllegalStateException("Portal yayın yanıtı geçersiz.")
        val raw = js.optString("cmd").ifBlank { js.optString("link") }
        val link = raw.removePrefix("ffmpeg ").trim()
        if (!link.startsWith("http://") && !link.startsWith("https://")) {
            throw IllegalStateException("Portal geçerli bir yayın bağlantısı vermedi.")
        }
        return link to session.headers()
    }
}
