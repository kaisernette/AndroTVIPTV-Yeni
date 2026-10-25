package com.example.androtviptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Bitmap
import android.util.LruCache
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
import java.util.concurrent.Executors

private data class MediaItem(
    val id: String, val name: String, val url: String, val group: String,
    val type: String, val icon: String = "", val extension: String = "", val seriesId: String = ""
) {
    val favoriteKey: String get() = "$type:$id:$url"
}

class MainActivity : Activity() {
    private val posterExecutor = Executors.newFixedThreadPool(3)
    private val posterCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
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
    private val bg = Color.rgb(7, 10, 17)
    private val panel = Color.rgb(20, 26, 39)
    private val accent = Color.rgb(222, 181, 91)
    private val accentBright = Color.rgb(246, 218, 151)
    private val muted = Color.rgb(157, 169, 188)

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
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 18, 34, 16)
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "KAISER"; textSize = 29f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD); letterSpacing = 0.08f
        })
        header.addView(TextView(this).apply {
            text = "TV"; textSize = 29f; setTextColor(accent)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "    SİNEMA  •  DİZİ  •  CANLI TV"; textSize = 11f
            setTextColor(muted); letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(makeButton("＋  Kaynak ekle", true) { showConnectionDialog() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44))
        root.addView(header)

        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(0, 14, 0, 10)
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Ana sayfa", "Canlı TV", "Filmler", "Diziler", "Favoriler", "Son İzlenenler", "Tümü").forEach { tab ->
            val key = if (tab == "Ana sayfa") "Tümü" else tab
            tabs.addView(makeButton(tab, selectedType == key) {
                selectedType = key; selectedGroup = "Tüm kategoriler"; render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 42).apply { setMargins(0, 0, 8, 0) })
        }
        tabsScroll.addView(tabs); root.addView(tabsScroll)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(26, 20, 20, 20)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(28, 35, 49), Color.rgb(13, 18, 29))).apply {
                cornerRadius = 22f; setStroke(1, Color.rgb(58, 62, 73))
            }
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(TextView(this).apply {
            text = "KAISERTV  /  SENİN EĞLENCE DÜNYAN"; textSize = 11f
            setTextColor(accentBright); letterSpacing = 0.12f
        })
        heroCopy.addView(TextView(this).apply {
            text = if (items.isEmpty()) "İzleme keyfi\nyeni bir seviyede." else "İzlemeye\ndevam et."
            textSize = 29f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 4); maxLines = 2
        })
        heroCopy.addView(TextView(this).apply {
            text = if (items.isEmpty()) "Kendi yayın listeni ekle, içeriklerini tek ekranda keşfet." else "${items.size} içerik keşfedilmeyi bekliyor."
            textSize = 14f; setTextColor(muted); maxLines = 2
        })
        heroCopy.addView(makeButton(if (items.isEmpty()) "İLK KAYNAĞINI EKLE  →" else "İÇERİKLERİ KEŞFET  →", true) {
            if (items.isEmpty()) showConnectionDialog() else {
                selectedType = "Tümü"; selectedGroup = "Tüm kategoriler"; render()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0, 14, 0, 0) })
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val heroArt = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(180, 170).apply { setMargins(18, 0, 0, 0) }
            background = roundedDrawable(Color.rgb(29, 35, 49), 18); clipToOutline = true
        }
        val featured = items.firstOrNull { it.icon.startsWith("http://") || it.icon.startsWith("https://") }
        if (featured != null) {
            val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = featured.name }
            heroArt.addView(poster, FrameLayout.LayoutParams(-1, -1)); loadPoster(featured.icon, poster)
            heroArt.addView(TextView(this).apply {
                text = featured.name; textSize = 12f; setTextColor(Color.WHITE)
                setPadding(10, 26, 10, 8); gravity = Gravity.BOTTOM
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, 0xDD070A11.toInt()))
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            heroArt.addView(TextView(this).apply {
                text = "K"; textSize = 94f; setTextColor(accent)
                setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        hero.addView(heroArt)
        root.addView(hero, LinearLayout.LayoutParams(-1, 205).apply { setMargins(0, 0, 0, 12) })

        status = TextView(this).apply {
            text = "Kaisertv hazır. Başlamak için kendi M3U/M3U8 listen veya Xtream hesabınla bağlan."
            textSize = 12f; setTextColor(muted); setPadding(2, 0, 0, 5); maxLines = 2
        }
        root.addView(status)
        categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 3, 0, 3) }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(categoryBar)
        }, LinearLayout.LayoutParams(-1, 42))
        val scroll = ScrollView(this).apply { isFillViewport = true; isFocusable = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 12) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); render()
    }

    private fun showConnectionDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 4); setBackgroundColor(bg)
        }
        sourceMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val m3u = RadioButton(this).apply {
            id = View.generateViewId(); text = "M3U / M3U8"; setTextColor(Color.WHITE); isChecked = true
        }
        val xtream = RadioButton(this).apply {
            id = View.generateViewId(); text = "Xtream Codes"; setTextColor(Color.WHITE)
        }
        sourceMode.addView(m3u); sourceMode.addView(xtream); form.addView(sourceMode)
        playlistUrl = edit("M3U / M3U8 oynatma listesi URL'si", false)
        server = edit("Sunucu adresi (https://...)", false)
        username = edit("Kullanıcı adı", false); password = edit("Şifre", true)
        form.addView(playlistUrl, fieldParams()); form.addView(server, fieldParams())
        val creds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        creds.addView(username, LinearLayout.LayoutParams(0, 50, 1f).apply { setMargins(0, 3, 6, 3) })
        creds.addView(password, LinearLayout.LayoutParams(0, 50, 1f).apply { setMargins(6, 3, 0, 3) })
        form.addView(creds)
        sourceMode.setOnCheckedChangeListener { _, checked ->
            val useXtream = checked == xtream.id
            playlistUrl.visibility = if (useXtream) View.GONE else View.VISIBLE
            server.visibility = if (useXtream) View.VISIBLE else View.GONE
            creds.visibility = if (useXtream) View.VISIBLE else View.GONE
        }
        server.visibility = View.GONE; creds.visibility = View.GONE
        val dialog = AlertDialog.Builder(this).setTitle("İzleme kaynağı ekle")
            .setMessage("Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle.")
            .setView(form).setNegativeButton("İptal", null).setPositiveButton("BAĞLAN", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val useXtream = sourceMode.checkedRadioButtonId == xtream.id
                if (useXtream) {
                    if (!(server.text.toString().trim().startsWith("http://") || server.text.toString().trim().startsWith("https://")) ||
                        username.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                        Toast.makeText(this, "Sunucu, kullanıcı adı ve şifreyi doldur.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    hideKeyboard(); dialog.dismiss(); loadXtream()
                } else {
                    val address = playlistUrl.text.toString().trim()
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, "Geçerli bir M3U/M3U8 URL'si gir.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    hideKeyboard(); dialog.dismiss(); loadM3u(address)
                }
            }
        }
        dialog.show()
    }

    private fun roundedDrawable(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != null) setStroke((2 * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        }

    private fun edit(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText; textSize = 14f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(muted)
        setPadding(12, 0, 12, 0); setBackgroundColor(panel)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun fieldParams() = LinearLayout.LayoutParams(-1, 48).apply { setMargins(0, 3, 0, 3) }
    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; isFocusable = true; isFocusableInTouchMode = true
        setPadding(16, 0, 16, 0)
        setTextColor(if (primary) Color.rgb(12, 14, 19) else Color.WHITE)
        background = roundedDrawable(if (primary) accent else panel, 12,
            if (primary) accentBright else Color.rgb(44, 52, 68))
        stateListAnimator = null
        setOnFocusChangeListener { v, hasFocus ->
            v.background = roundedDrawable(if (primary) accent else if (hasFocus) Color.rgb(39, 47, 63) else panel,
                12, if (hasFocus) accentBright else if (primary) accentBright else Color.rgb(44, 52, 68))
            v.scaleX = if (hasFocus) 1.035f else 1f; v.scaleY = if (hasFocus) 1.035f else 1f
        }
        setOnClickListener { action() }
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
        if (!::content.isInitialized || !::categoryBar.isInitialized) return
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
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36).apply { setMargins(0, 0, 8, 0) })
        }
        val visible = if (selectedGroup == "Tüm kategoriler") baseVisible
            else baseVisible.filter { it.group.ifBlank { it.type } == selectedGroup }
        if (visible.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 24, 24, 24); background = roundedDrawable(panel, 18, Color.rgb(42, 49, 64))
            }
            empty.addView(TextView(this).apply {
                text = when (selectedType) {
                    "Favoriler" -> "Henüz favori eklemedin"
                    "Son İzlenenler" -> "İzleme geçmişin burada görünecek"
                    else -> "Kütüphanen seni bekliyor"
                }
                textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            })
            empty.addView(TextView(this).apply {
                text = when {
                    selectedType == "Favoriler" -> "Bir içerikte yıldız düğmesine basarak favorilerine ekle."
                    selectedType == "Son İzlenenler" -> "Bir içerik oynattığında burada kolayca devam edebilirsin."
                    items.isEmpty() -> "Başlamak için kendi M3U/M3U8 veya Xtream kaynağını ekle."
                    else -> "Bu bölümde şu anda gösterilecek içerik yok."
                }
                textSize = 14f; setTextColor(muted); setPadding(0, 7, 0, 14)
            })
            if (items.isEmpty()) empty.addView(makeButton("KAYNAK EKLE  →", true) { showConnectionDialog() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44))
            content.addView(empty, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 16) })
            return
        }
        val groups = visible.groupBy {
            if (selectedType == "Tümü" || selectedType == "Favoriler" || selectedType == "Son İzlenenler") it.type
            else it.group.ifBlank { selectedType }
        }
        groups.forEach { (groupName, entries) ->
            val sectionHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2, 16, 2, 8)
            }
            sectionHeader.addView(TextView(this).apply {
                text = groupName; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            sectionHeader.addView(TextView(this).apply {
                text = "${entries.size} içerik   ›"; textSize = 12f; setTextColor(accent)
            })
            content.addView(sectionHeader)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            entries.take(80).forEach { item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(7, 7, 7, 9)
                    background = roundedDrawable(panel, 15, Color.rgb(38, 45, 60))
                    isFocusable = true; isFocusableInTouchMode = true
                    setOnFocusChangeListener { v, hasFocus ->
                        v.background = roundedDrawable(if (hasFocus) Color.rgb(31, 39, 54) else panel, 15,
                            if (hasFocus) accent else Color.rgb(38, 45, 60))
                        v.scaleX = if (hasFocus) 1.035f else 1f; v.scaleY = if (hasFocus) 1.035f else 1f
                    }
                    setOnClickListener { play(item) }
                }
                val poster = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = item.name
                    background = roundedDrawable(Color.rgb(31, 37, 50), 10)
                    layoutParams = LinearLayout.LayoutParams(-1, 205)
                }
                card.addView(poster)
                if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) loadPoster(item.icon, poster)
                else poster.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(43, 51, 68), Color.rgb(17, 22, 33))))
                card.addView(TextView(this).apply {
                    text = if (item.type == "Canlı TV") "●  CANLI YAYIN" else item.type.uppercase()
                    textSize = 10f; setTextColor(accent); setPadding(3, 8, 2, 4); letterSpacing = 0.05f
                })
                card.addView(TextView(this).apply {
                    text = item.name; textSize = 14f; setTextColor(Color.WHITE); maxLines = 2
                    minHeight = 40; setPadding(3, 0, 3, 0)
                })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                actions.addView(makeButton("▶ İzle", true) { play(item) },
                    LinearLayout.LayoutParams(0, 36, 1f).apply { setMargins(0, 7, 4, 0) })
                actions.addView(makeButton(if (favorites.contains(item.favoriteKey)) "★" else "☆", false) {
                    if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
                    getSharedPreferences("androtv", MODE_PRIVATE).edit().putStringSet("favorites", favorites.toSet()).apply()
                    render()
                }, LinearLayout.LayoutParams(43, 36).apply { setMargins(4, 7, 0, 0) })
                card.addView(actions)
                row.addView(card, LinearLayout.LayoutParams(190, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 12, 8) })
            }
            content.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row)
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun loadPoster(address: String, target: ImageView) {
        target.tag = address
        val cached = posterCache.get(address)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        posterExecutor.execute {
            try {
                val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                }
                val bitmap = try {
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
                if (bitmap != null) {
                    posterCache.put(address, bitmap)
                    runOnUiThread {
                        if (target.tag == address) target.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        posterExecutor.shutdownNow()
        super.onDestroy()
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
        saveRecent(item)
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("name", item.name).putExtra("url", item.url))
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
                    if (episodeRows.isEmpty()) { AlertDialog.Builder(this).setTitle(series.name).setMessage("Bu dizi için bölüm bulunamadı.").setPositiveButton("Tamam", null).show(); return@runOnUiThread }
                    val seasonLabels = episodeRows.map { pair -> "Sezon ${pair.first}" }
                    AlertDialog.Builder(this).setTitle(series.name).setItems(seasonLabels.toTypedArray()) { _, which ->
                        val chosen = episodeRows[which].second
                        if (chosen.isEmpty()) { Toast.makeText(this, "Bu sezonda bölüm bulunamadı.", Toast.LENGTH_SHORT).show(); return@setItems }
                        AlertDialog.Builder(this).setTitle(seasonLabels[which]).setItems(chosen.map { it.name }.toTypedArray()) { _, epIndex -> play(chosen[epIndex]) }.setNegativeButton("Geri", null).show()
                    }.setNegativeButton("Kapat", null).show()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Bölümler yüklenemedi: ${e.message ?: "bağlantı hatası"}" } }
        }
    }

}
