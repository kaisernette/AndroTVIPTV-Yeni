package com.example.androtviptv

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.concurrent.thread

private data class MediaItem(
    val id: String, val name: String, val url: String, val group: String,
    val type: String, val icon: String = "", val extension: String = "", val seriesId: String = ""
) {
    val favoriteKey: String get() = "$type:$id:$url"
}

class MainActivity : Activity() {
    private val items = mutableListOf<MediaItem>()
    private val favorites = mutableSetOf<String>()
    private val recent = mutableListOf<MediaItem>()
    private var selectedGroup = "Tüm kategoriler"
    private lateinit var categoryBar: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var playlistUrl: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var sourceMode: RadioGroup
    private var selectedType = "Canlı TV"
    private var xtreamUser = ""
    private var xtreamPass = ""
    private var xtreamBase = ""
    private val bg = Color.rgb(12, 14, 23)
    private val panel = Color.rgb(27, 31, 46)
    private val accent = Color.rgb(229, 9, 20)
    private val muted = Color.rgb(166, 174, 192)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        favorites.addAll(getSharedPreferences("androtv", MODE_PRIVATE).getStringSet("favorites", emptySet()) ?: emptySet())
        try {
            val saved = JSONArray(getSharedPreferences("androtv", MODE_PRIVATE).getString("recent", "[]"))
            for (i in 0 until saved.length()) {
                val o = saved.optJSONObject(i) ?: continue
                recent.add(MediaItem(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("group"), o.optString("type"), o.optString("icon"), o.optString("extension"), o.optString("seriesId")))
            }
        } catch (_: Exception) { }
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 18, 28, 16); setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "ANDRO"; textSize = 30f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) })
        header.addView(TextView(this).apply { text = "TV"; textSize = 30f; setTextColor(accent); setTypeface(null, android.graphics.Typeface.BOLD) })
        header.addView(TextView(this).apply { text = "   IPTV  •  SİNEMA KEYFİ"; textSize = 13f; setTextColor(muted) })
        root.addView(header)
        root.addView(TextView(this).apply { text = "İzlemeye devam et, favorilerini keşfet."; textSize = 16f; setTextColor(muted); setPadding(0, 3, 0, 14) })

        sourceMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val m3u = RadioButton(this).apply { id = View.generateViewId(); text = "M3U / M3U8"; setTextColor(Color.WHITE); isChecked = true }
        val xtream = RadioButton(this).apply { id = View.generateViewId(); text = "Xtream Codes"; setTextColor(Color.WHITE) }
        sourceMode.addView(m3u); sourceMode.addView(xtream); root.addView(sourceMode)
        val inputs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 4) }
        playlistUrl = edit("M3U / M3U8 oynatma listesi URL'si", false)
        server = edit("Sunucu adresi (örn. https://sunucu:8080)", false)
        username = edit("Kullanıcı adı", false)
        password = edit("Şifre", true)
        inputs.addView(playlistUrl, fieldParams()); inputs.addView(server, fieldParams())
        val creds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        creds.addView(username, LinearLayout.LayoutParams(0, 48, 1f).apply { setMargins(0, 0, 6, 0) })
        creds.addView(password, LinearLayout.LayoutParams(0, 48, 1f).apply { setMargins(6, 0, 0, 0) })
        inputs.addView(creds, fieldParams())
        root.addView(inputs)
        sourceMode.setOnCheckedChangeListener { _, checked ->
            val useXtream = checked == xtream.id
            playlistUrl.visibility = if (useXtream) View.GONE else View.VISIBLE
            server.visibility = if (useXtream) View.VISIBLE else View.GONE
            creds.visibility = if (useXtream) View.VISIBLE else View.GONE
        }
        server.visibility = View.GONE; creds.visibility = View.GONE
        root.addView(makeButton("BAĞLAN  /  LİSTEYİ YÜKLE", true) {
            hideKeyboard(); if (sourceMode.checkedRadioButtonId == xtream.id) loadXtream() else loadM3u(playlistUrl.text.toString().trim())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48).apply { setMargins(0, 3, 0, 8) })
        status = TextView(this).apply { text = "Kendi M3U listen veya Xtream hesap bilgilerinle bağlan."; textSize = 13f; setTextColor(muted); setPadding(0, 3, 0, 8) }
        root.addView(status)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Canlı TV", "Filmler", "Diziler", "Favoriler", "Son İzlenenler", "Tümü").forEach { tab ->
            tabs.addView(makeButton(tab, false) { selectedType = tab; selectedGroup = "Tüm kategoriler"; render() }, LinearLayout.LayoutParams(0, 42, 1f).apply { setMargins(2, 0, 2, 0) })
        }
        root.addView(tabs)
        categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 2) }
        root.addView(ScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(categoryBar) }, LinearLayout.LayoutParams(-1, 56))
        val scroll = ScrollView(this).apply { isFillViewport = true; isFocusable = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 8) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); render()
    }

    private fun edit(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText; textSize = 14f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(muted)
        setPadding(12, 0, 12, 0); setBackgroundColor(panel)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun fieldParams() = LinearLayout.LayoutParams(-1, 48).apply { setMargins(0, 3, 0, 3) }
    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false; isFocusable = true; isFocusableInTouchMode = true
        setTextColor(Color.WHITE); setBackgroundColor(if (primary) accent else panel); setOnClickListener { action() }
    }
    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(playlistUrl.windowToken, 0) }

    private fun loadM3u(address: String) {
        if (!address.startsWith("http://") && !address.startsWith("https://")) { status.text = "Geçerli bir http(s) M3U URL'si gir."; return }
        status.text = "Oynatma listesi yükleniyor…"
        thread {
            try {
                val text = request(address)
                val parsed = parseM3u(text)
                runOnUiThread { items.clear(); items.addAll(parsed); selectedGroup = "Tüm kategoriler"; status.text = "${parsed.size} içerik yüklendi."; render() }
            } catch (e: Exception) { runOnUiThread { status.text = "Yüklenemedi: ${e.message ?: "bağlantı hatası"}" } }
        }
    }

    private fun loadXtream() {
        val base = server.text.toString().trim().trimEnd('/')
        val user = username.text.toString().trim(); val pass = password.text.toString()
        if (!(base.startsWith("http://") || base.startsWith("https://")) || user.isBlank() || pass.isBlank()) {
            status.text = "Sunucu, kullanıcı adı ve şifre alanlarını doldur."; return
        }
        status.text = "Xtream içerikleri yükleniyor…"
        thread {
            try {
                val u = URLEncoder.encode(user, "UTF-8"); val p = URLEncoder.encode(pass, "UTF-8")
                val api = "$base/player_api.php?username=$u&password=$p"
                val root = JSONObject(request(api))
                val userInfo = root.optJSONObject("user_info")
                if (userInfo != null && userInfo.optString("auth", "1") == "0") throw Exception("Giriş bilgileri kabul edilmedi.")
                val loaded = mutableListOf<MediaItem>()
                listOf("get_live_streams", "get_vod_streams", "get_series").forEach { action ->
                    val arr = JSONArray(request("$api&action=$action"))
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString(if (action == "get_series") "series_id" else "stream_id", "")
                        if (id.isBlank()) continue
                        val name = o.optString("name", "İsimsiz içerik")
                        val group = o.optString("category_name", if (action == "get_live_streams") "Canlı TV" else if (action == "get_vod_streams") "Filmler" else "Diziler")
                        val type = when (action) { "get_live_streams" -> "Canlı TV"; "get_vod_streams" -> "Filmler"; else -> "Diziler" }
                        val ext = o.optString("container_extension", if (type == "Canlı TV") "ts" else "mp4")
                        val streamUrl = when (type) {
                            "Canlı TV" -> "$base/live/$u/$p/$id.$ext"
                            "Filmler" -> "$base/movie/$u/$p/$id.$ext"
                            else -> ""
                        }
                        loaded.add(MediaItem(id, name, streamUrl, group, type, o.optString(if (type == "Filmler") "stream_icon" else "cover", o.optString("stream_icon", "")), ext, if (type == "Diziler") id else ""))
                    }
                }
                runOnUiThread {
                    items.clear(); items.addAll(loaded); selectedGroup = "Tüm kategoriler"; xtreamBase = base; xtreamUser = user; xtreamPass = pass
                    status.text = "${loaded.size} içerik yüklendi. Xtream bağlantısı hazır."; render()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Xtream yüklenemedi: ${e.message ?: "bağlantı hatası"}" } }
        }
    }

    private fun request(address: String): String {
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 25000; setRequestProperty("User-Agent", "AndroTVIPTV/2.0")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code")
            return body
        } finally { conn.disconnect() }
    }

    private fun parseM3u(text: String): List<MediaItem> {
        val out = mutableListOf<MediaItem>(); var name = "İçerik"; var group = "Diğer"; var icon = ""
        text.lineSequence().map { it.trim() }.forEach { line ->
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(',', "İçerik").trim().ifBlank { "İçerik" }
                group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1) ?: "Diğer"
                icon = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1) ?: ""
            } else if ((line.startsWith("http://") || line.startsWith("https://")) && line.isNotBlank()) {
                val type = classify(name, group)
                out.add(MediaItem(line, name, line, group, type, icon)); name = "İçerik"; group = "Diğer"; icon = ""
            }
        }
        return out
    }
    private fun classify(name: String, group: String): String {
        val s = "$name $group".lowercase()
        return when {
            listOf("series", "dizi", "tv show", "season", "sezon", "episode", "bölüm").any { it in s } -> "Diziler"
            listOf("movie", "film", "cinema", "vod", "4k film").any { it in s } -> "Filmler"
            else -> "Canlı TV"
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val baseVisible = when (selectedType) {
            "Tümü" -> items.toList()
            "Favoriler" -> items.filter { favorites.contains(it.favoriteKey) }
            "Son İzlenenler" -> recent.toList()
            else -> items.filter { it.type == selectedType }
        }
        val groupsAvailable = baseVisible.map { it.group.ifBlank { it.type } }.distinct().sorted()
        categoryBar.removeAllViews()
        (listOf("Tüm kategoriler") + groupsAvailable).forEach { group ->
            categoryBar.addView(makeButton(if (group == selectedGroup) "• $group" else group, group == selectedGroup) {
                selectedGroup = group; render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48).apply { setMargins(0, 0, 6, 0) })
        }
        val visible = if (selectedGroup == "Tüm kategoriler") baseVisible else baseVisible.filter { it.group.ifBlank { it.type } == selectedGroup }
        if (visible.isEmpty()) {
            content.addView(TextView(this).apply { text = if (selectedType == "Favoriler") "Henüz favorin yok. İçeriklerin yanındaki ☆ düğmesine bas." else if (selectedType == "Son İzlenenler") "Henüz izlenen içerik yok." else "İçerik görünmüyor. Önce M3U veya Xtream bağlantısı yükle."; textSize = 17f; setTextColor(muted); setPadding(12, 26, 12, 26) }); return
        }
        val groups = visible.groupBy { if (selectedType == "Tümü" || selectedType == "Favoriler") it.type else it.group.ifBlank { selectedType } }
        groups.forEach { (groupName, entries) ->
            content.addView(TextView(this).apply { text = groupName.uppercase(); textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE); setPadding(4, 12, 0, 6) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            entries.take(100).forEach { item ->
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8); setBackgroundColor(panel); isFocusable = true; isFocusableInTouchMode = true }
                if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) {
                    val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(-1, 118); contentDescription = item.name }
                    card.addView(poster)
                    loadPoster(item.icon, poster)
                } else {
                    card.addView(TextView(this).apply { text = "🎬"; textSize = 32f; setTextColor(muted); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, 118) })
                }
                card.addView(TextView(this).apply { text = when (item.type) { "Canlı TV" -> "▶ LIVE"; "Filmler" -> "▶ FİLM"; "Bölümler" -> "▶ BÖLÜM"; else -> "▶ DİZİ" }; textSize = 12f; setTextColor(accent); setPadding(0, 0, 0, 8) })
                card.addView(TextView(this).apply { text = item.name; textSize = 15f; setTextColor(Color.WHITE); maxLines = 2; minHeight = 42 })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                actions.addView(makeButton("▶ İzle", true) { play(item) }, LinearLayout.LayoutParams(0, 38, 1f).apply { setMargins(0, 8, 4, 0) })
                actions.addView(makeButton(if (favorites.contains(item.favoriteKey)) "★" else "☆", false) {
                    if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
                    getSharedPreferences("androtv", MODE_PRIVATE).edit().putStringSet("favorites", favorites.toSet()).apply(); render()
                }, LinearLayout.LayoutParams(48, 38).apply { setMargins(4, 8, 0, 0) })
                card.addView(actions)
                row.addView(card, LinearLayout.LayoutParams(210, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 10, 8) })
            }
            content.addView(ScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun loadPoster(address: String, target: ImageView) {
        thread {
            try {
                val conn = (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val bitmap = try { conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
                if (bitmap != null) runOnUiThread { target.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }
    }

    private fun saveRecent(item: MediaItem) {
        recent.removeAll { it.favoriteKey == item.favoriteKey }
        recent.add(0, item)
        while (recent.size > 20) recent.removeAt(recent.lastIndex)
        val arr = JSONArray()
        recent.forEach { r -> arr.put(JSONObject().put("id", r.id).put("name", r.name).put("url", r.url).put("group", r.group).put("type", r.type).put("icon", r.icon).put("extension", r.extension).put("seriesId", r.seriesId)) }
        getSharedPreferences("androtv", MODE_PRIVATE).edit().putString("recent", arr.toString()).apply()
    }

    private fun play(item: MediaItem) {
        if (item.type == "Diziler" && item.url.isBlank()) { loadSeriesEpisodes(item); return }
        if (item.type == "Filmler") { showMovieDetails(item); return }
        startPlayback(item)
    }

    private fun startPlayback(item: MediaItem) {
        saveRecent(item)
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("name", item.name).putExtra("url", item.url))
    }

    private fun showMovieDetails(item: MediaItem) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 24)
            setBackgroundColor(bg)
            isFocusableInTouchMode = true
        }
        root.addView(makeButton("✕  Kapat", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, 52))
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = item.name
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, 12, 0, 12) }
        }
        root.addView(poster)
        if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) loadPoster(item.icon, poster)
        root.addView(TextView(this).apply {
            text = item.name; textSize = 26f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 8, 0, 16)
        })
        root.addView(makeButton("▶  FİLMİ BAŞLAT", true) { dialog.dismiss(); startPlayback(item) },
            LinearLayout.LayoutParams(-1, 64).apply { setMargins(0, 4, 0, 4) })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        root.requestFocus()
    }

    private fun showSeriesDetails(series: MediaItem, seasons: List<Pair<String, List<MediaItem>>>) {
        val dialog = Dialog(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(24, 16, 24, 24)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = series.name; textSize = 24f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(makeButton("✕ Kapat", false) { dialog.dismiss() }, LinearLayout.LayoutParams(130, 52))
        page.addView(top)
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = series.name
            layoutParams = LinearLayout.LayoutParams(-1, 250).apply { setMargins(0, 10, 0, 12) }
        }
        page.addView(poster)
        if (series.icon.startsWith("http://") || series.icon.startsWith("https://")) loadPoster(series.icon, poster)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seasons.forEach { (season, episodes) ->
            list.addView(TextView(this).apply {
                text = "SEZON $season"; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE); setPadding(4, 16, 4, 8)
            })
            episodes.forEachIndexed { index, episode ->
                list.addView(makeButton("▶  ${episode.name.ifBlank { "Bölüm ${index + 1}" }}", false) {
                    dialog.dismiss(); startPlayback(episode)
                }, LinearLayout.LayoutParams(-1, 54).apply { setMargins(0, 3, 0, 3) })
            }
        }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(page)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        page.requestFocus()
    }

    private fun loadSeriesEpisodes(series: MediaItem) {
        if (xtreamBase.isBlank() || xtreamUser.isBlank() || xtreamPass.isBlank()) {
            AlertDialog.Builder(this).setTitle(series.name).setMessage("Bu dizi Xtream Codes bağlantısından yüklenmiş olmalı. Lütfen Xtream bağlantını yeniden yükle.").setPositiveButton("Tamam", null).show(); return
        }
        status.text = "${series.name}: sezonlar yükleniyor…"
        thread {
            try {
                val u = URLEncoder.encode(xtreamUser, "UTF-8"); val p = URLEncoder.encode(xtreamPass, "UTF-8")
                val url = "$xtreamBase/player_api.php?username=$u&password=$p&action=get_series_info&series_id=${URLEncoder.encode(series.seriesId.ifBlank { series.id }, "UTF-8")}"
                val data = JSONObject(request(url))
                val episodes = data.optJSONObject("episodes") ?: JSONObject()
                val episodeRows = mutableListOf<Pair<String, List<MediaItem>>>()
                val keys = episodes.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = episodes.optJSONArray(key) ?: continue
                    val rows = mutableListOf<MediaItem>()
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val id = ep.optString("id", ep.optString("episode_id", "")); if (id.isBlank()) continue
                        val ext = ep.optString("container_extension", "mp4")
                        val epName = ep.optString("title", ep.optString("name", "Bölüm ${i + 1}"))
                        val stream = "$xtreamBase/series/$u/$p/$id.$ext"
                        rows.add(MediaItem(id, epName, stream, "${series.name} • Sezon $key", "Bölümler", ep.optString("info").takeIf { it.startsWith("http") } ?: "", ext))
                    }
                    episodeRows.add(Pair(key, rows))
                }
                runOnUiThread {
                    status.text = "Dizi bölümleri hazır."
                    if (episodeRows.isEmpty()) { Toast.makeText(this, "Bu dizi için bölüm bulunamadı.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                    showSeriesDetails(series, episodeRows)
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Bölümler yüklenemedi: ${e.message ?: "bağlantı hatası"}" } }
        }
    }

}
