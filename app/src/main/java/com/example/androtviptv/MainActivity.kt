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

private data class SavedSource(val id: String, val name: String, val mode: String, val address: String, val username: String = "", val password: String = "")

private data class MediaItem(
    val id: String, val name: String, val url: String, val group: String,
    val type: String, val icon: String = "", val extension: String = "", val seriesId: String = "", val sourceId: String = "", val plot: String = "", val actors: String = "", val rating: String = "", val releaseDate: String = "", val genre: String = ""
) {
    val favoriteKey: String get() = "$type:$id:$url"
}

class MainActivity : Activity() {
    private val posterExecutor = Executors.newFixedThreadPool(3)
    private val posterCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val items = mutableListOf<MediaItem>()
    private val savedSources = mutableListOf<SavedSource>()
    private lateinit var sourceName: EditText
    private val favorites = mutableSetOf<String>()
    private val recent = mutableListOf<MediaItem>()
    private val hiddenCategories = mutableSetOf<String>()
    private var selectedGroup = "Tüm kategoriler"
    private lateinit var categoryBar: LinearLayout
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var mainHeader: LinearLayout
    private lateinit var content: LinearLayout
    private var detailSeries: MediaItem? = null
    private var detailMovie: MediaItem? = null
    private var seriesEpisodeRows: List<Pair<String, List<MediaItem>>> = emptyList()
    private var seriesEpisodesLoading = false
    private var seriesEpisodesError = ""
    private lateinit var status: TextView
    private lateinit var playlistUrl: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var sourceMode: RadioGroup
    private var selectedType = "Tümü"
    private var xtreamUser = ""
    private var xtreamPass = ""
    private var xtreamBase = ""
    private val prefs by lazy { getSharedPreferences("androtv", MODE_PRIVATE) }
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
                recent.add(MediaItem(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("group"), o.optString("type"), o.optString("icon"), o.optString("extension"), o.optString("seriesId"), o.optString("sourceId"), o.optString("plot"), o.optString("actors"), o.optString("rating"), o.optString("releaseDate"), o.optString("genre")))
            }
        } catch (_: Exception) { }
        hiddenCategories.addAll(prefs.getStringSet("hidden_categories", emptySet()) ?: emptySet())
        loadCachedItems()
        buildUi()
        loadSavedSources()
    }

    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var heroArt: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var sidebarMenuScroll: ScrollView
    private var sidebarMode = "main"
    private var searchQuery = ""

    private fun buildUi() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18, 14, 18, 12)
            setBackgroundColor(bg)
        }

        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 16, 14, 14)
            background = roundedDrawable(Color.rgb(14, 19, 29), 20, Color.rgb(35, 42, 56))
        }
        root.addView(sidebar, LinearLayout.LayoutParams(360, -1).apply { setMargins(0, 0, 20, 0) })

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        brand.addView(TextView(this).apply {
            text = "♛"; textSize = 25f; setTextColor(accent)
            gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(28)))
        brand.addView(TextView(this).apply {
            text = "KAISER"; textSize = 18f; setTextColor(Color.rgb(16, 43, 79))
            setTypeface(null, android.graphics.Typeface.BOLD); letterSpacing = 0.07f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(23)))
        brand.addView(TextView(this).apply {
            text = "—  TV  —"; textSize = 12f; setTextColor(accent)
            setTypeface(null, android.graphics.Typeface.BOLD); letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(18)))
        sidebar.addView(brand, LinearLayout.LayoutParams(-1, dp(76)))
        navBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sidebarMenuScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(navBar)
        }
        sidebar.addView(sidebarMenuScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        rebuildNavigation()

        sidebar.addView(View(this).apply { setBackgroundColor(Color.rgb(39, 46, 60)) },
            LinearLayout.LayoutParams(-1, 1).apply { setMargins(0, 12, 0, 10) })
        sidebar.addView(makeButton("＋ Kaynak ekle", true) { showConnectionDialog() }.apply { textSize = 11f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, 42).apply { setMargins(0, 0, 0, 5) })
        sidebar.addView(makeButton("Kaynaklar", false) { showSourcesDialog() }.apply { textSize = 11f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, 42).apply { setMargins(0, 0, 0, 5) })
        sidebar.addView(makeButton("⚙ Ayarlar", false) { showSettingsDialog() }.apply { textSize = 11f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 42))

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))

        mainHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 12)
        }
        mainHeader.addView(TextView(this).apply {
            text = "EĞLENCE DÜNYAN"; textSize = 11f; setTextColor(muted)
            letterSpacing = 0.16f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        mainHeader.addView(TextView(this).apply {
            text = "KAISERTV  /  PREMIUM"; textSize = 10f; setTextColor(accentBright)
            letterSpacing = 0.08f
        })
        main.addView(mainHeader)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 18, 18, 18)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(33, 42, 59), Color.rgb(12, 17, 28))).apply {
                cornerRadius = 24f; setStroke(1, Color.rgb(54, 64, 82))
            }
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(TextView(this).apply {
            text = "KAISERTV ORIGINAL  •  SENİN KÜTÜPHANEN"; textSize = 10f
            setTextColor(accentBright); letterSpacing = 0.10f
        })
        heroTitle = TextView(this).apply {
            text = "Eğlenceye yeniden hoş geldin"; textSize = 27f
            setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 4); maxLines = 2
        }
        heroCopy.addView(heroTitle)
        heroSubtitle = TextView(this).apply {
            text = "Canlı yayınlarını, filmlerini ve dizilerini keşfet."; textSize = 13f
            setTextColor(muted); maxLines = 2
        }
        heroCopy.addView(heroSubtitle)
        heroCopy.addView(makeButton("İÇERİKLERİ KEŞFET  →", true) {
            selectedType = "Tümü"; selectedGroup = "Tüm kategoriler"; rebuildNavigation(); render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40).apply { setMargins(0, 13, 0, 0) })
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        heroArt = FrameLayout(this).apply {
            background = roundedDrawable(Color.rgb(25, 33, 48), 18, Color.rgb(60, 70, 89))
            clipToOutline = true
        }
        heroArt.addView(TextView(this).apply {
            text = "K"; textSize = 88f; setTextColor(accent)
            setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(heroArt, LinearLayout.LayoutParams(164, 150).apply { setMargins(16, 0, 0, 0) })
        main.addView(hero, LinearLayout.LayoutParams(-1, 190).apply { setMargins(0, 0, 0, 10) })
        hero.visibility = View.GONE

        status = TextView(this).apply {
            text = "Kaisertv hazır. Kaynak ekleyerek içeriklerini yükleyebilirsin."
            textSize = 12f; setTextColor(muted); setPadding(3, 2, 0, 8); maxLines = 2
        }
        main.addView(status)
        categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            addView(categoryBar)
            visibility = View.VISIBLE
        }
        main.addView(categoryScroll, LinearLayout.LayoutParams(-1, 46).apply { setMargins(0, 0, 0, 8) })
        val scroll = ScrollView(this).apply { isFillViewport = true; isFocusable = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 2, 0, 12) }
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun rebuildNavigation() {
        if (!::navBar.isInitialized) return
        navBar.removeAllViews()

        fun addSidebarButton(label: String, selected: Boolean = false, action: () -> Unit) {
            navBar.addView(makeButton(if (selected) "●  $label" else label, selected, action).apply {
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(14, 0, 8, 0)
                minHeight = 46
            }, LinearLayout.LayoutParams(-1, 46).apply { setMargins(0, 2, 0, 2) })
        }

        fun returnToMainMenu() {
            detailSeries = null
            detailMovie = null
            seriesEpisodeRows = emptyList()
            seriesEpisodesLoading = false
            seriesEpisodesError = ""
            selectedType = "Tümü"
            selectedGroup = "Tüm kategoriler"
            sidebarMode = "main"
            searchQuery = ""
            rebuildNavigation()
            render()
        }

        if (sidebarMode == "main") {
            listOf("⌕  Ara", "Ana sayfa", "TV", "Filmler", "Diziler", "Favoriler", "İzlenenler").forEach { tab ->
                val normalizedTab = tab.substringAfter("  ", tab)
                val key = when (normalizedTab) {
                    "Ana sayfa" -> "Tümü"
                    "TV" -> "Canlı TV"
                    "İzlenenler" -> "Son İzlenenler"
                    else -> normalizedTab
                }
                addSidebarButton(normalizedTab, selectedType == key && normalizedTab != "Ara") {
                    if (normalizedTab == "Ara") {
                        detailSeries = null
                        detailMovie = null
                        sidebarMode = "search"
                        searchQuery = ""
                        selectedType = "Tümü"
                        selectedGroup = "Tüm kategoriler"
                        rebuildNavigation()
                        render()
                        return@addSidebarButton
                    }
                    detailSeries = null
                    detailMovie = null
                    seriesEpisodeRows = emptyList()
                    seriesEpisodesLoading = false
                    seriesEpisodesError = ""
                    selectedGroup = "Tüm kategoriler"
                    selectedType = key
                    sidebarMode = if (normalizedTab == "TV" || normalizedTab == "Filmler" || normalizedTab == "Diziler") "context" else "main"
                    rebuildNavigation()
                    render()
                }
            }
            return
        }

        addSidebarButton("← Ana menü") { returnToMainMenu() }

        if (sidebarMode == "search") {
            navBar.addView(TextView(this).apply {
                text = "İÇERİK ARA"
                textSize = 11f
                setTextColor(accentBright)
                setTypeface(null, android.graphics.Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(14, dp(12), 8, dp(8))
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            val searchInput = EditText(this).apply {
                hint = "Film, dizi veya kanal ara"
                setSingleLine(true)
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                setPadding(dp(12), 0, dp(12), 0)
                background = roundedDrawable(Color.rgb(28, 35, 49), 12, Color.rgb(58, 68, 86))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setText(searchQuery)
                setSelection(text.length)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchQuery = s?.toString().orEmpty()
                        selectedGroup = "Tüm kategoriler"
                        render()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            navBar.addView(searchInput, LinearLayout.LayoutParams(-1, dp(46)).apply {
                setMargins(dp(6), dp(2), dp(6), dp(8))
            })
            addSidebarButton("Temizle") {
                searchQuery = ""
                rebuildNavigation()
                render()
                navBar.findViewById<EditText>(searchInput.id)?.requestFocus()
            }
            return
        }

        val heading = when (selectedType) {
            "Canlı TV" -> "CANLI KANALLAR"
            "Filmler" -> "FİLM KATEGORİLERİ"
            "Diziler" -> "DİZİ KATEGORİLERİ"
            else -> "KATEGORİLER"
        }
        navBar.addView(TextView(this).apply {
            text = heading
            textSize = 11f
            setTextColor(accentBright)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(14, dp(12), 8, dp(8))
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (selectedType == "Canlı TV") {
            val channels = items.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
            if (channels.isEmpty()) {
                navBar.addView(TextView(this).apply {
                    text = if (items.isEmpty()) "Önce bir yayın kaynağı ekle." else "Canlı kanal bulunamadı."
                    textSize = 13f
                    setTextColor(muted)
                    setPadding(14, 8, 8, 8)
                })
            } else {
                channels.take(300).forEach { channel ->
                    addSidebarButton(channel.name) { play(channel) }
                }
            }
        } else {
            addSidebarButton("Tüm kategoriler", selectedGroup == "Tüm kategoriler") {
                selectedGroup = "Tüm kategoriler"
                rebuildNavigation()
                render()
            }
            val categories = items.filter { it.type == selectedType }
                .map { it.group.ifBlank { "Diğer" } }
                .distinct()
                .filter { it !in hiddenCategories }
                .sorted()
            categories.take(200).forEach { category ->
                addSidebarButton(category, selectedGroup == category) {
                    selectedGroup = category
                    rebuildNavigation()
                    render()
                }
            }
        }
    }

    private fun showConnectionDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 4); setBackgroundColor(bg)
        }
        sourceName = edit("Kaynak adı (ör. Ev Xtream)", false)
        form.addView(sourceName, fieldParams())
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
                    val label = sourceName.text.toString().trim().ifBlank { "Xtream ${savedSources.size + 1}" }
                    val src = SavedSource(java.util.UUID.randomUUID().toString(), label, "xtream", server.text.toString().trim().trimEnd('/'), username.text.toString().trim(), password.text.toString())
                    saveSource(src); hideKeyboard(); dialog.dismiss(); loadXtream(src, false)
                } else {
                    val address = playlistUrl.text.toString().trim()
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, "Geçerli bir M3U/M3U8 URL'si gir.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val label = sourceName.text.toString().trim().ifBlank { "M3U ${savedSources.size + 1}" }
                    val src = SavedSource(java.util.UUID.randomUUID().toString(), label, "m3u", address)
                    saveSource(src); hideKeyboard(); dialog.dismiss(); loadM3u(src, false)
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


    private fun saveSource(source: SavedSource) {
        savedSources.removeAll { it.id == source.id }; savedSources.add(source)
        prefs.edit().putString("last_source_id", source.id).apply()
        persistSources()
    }
    private fun persistSources() {
        val arr = JSONArray()
        savedSources.forEach { s -> arr.put(JSONObject().put("id", s.id).put("name", s.name).put("mode", s.mode).put("address", s.address).put("username", s.username).put("password", s.password)) }
        getSharedPreferences("androtv", MODE_PRIVATE).edit().putString("sources", arr.toString()).apply()
    }
    private fun loadSavedSources() {
        try {
            val arr = JSONArray(getSharedPreferences("androtv", MODE_PRIVATE).getString("sources", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                savedSources.add(SavedSource(o.optString("id"), o.optString("name"), o.optString("mode"), o.optString("address"), o.optString("username"), o.optString("password")))
            }
        } catch (_: Exception) { }
        if (savedSources.isNotEmpty()) {
            val lastId = prefs.getString("last_source_id", "") ?: ""
            val last = savedSources.firstOrNull { it.id == lastId }
            status.text = if (items.isNotEmpty()) "${items.size} kayıtlı içerik hazır. Yenilemek için Kaynaklar bölümünü aç." else "Kayıtlı kaynak bulundu. İçerikleri ilk kez yüklemek için Kaynaklar bölümünden yenile."
            if (last != null && prefs.getBoolean("resume_home", true)) selectedType = prefs.getString("last_section", "Tümü") ?: "Tümü"
            render()
        }
    }
    private fun showSourcesDialog() {
        if (savedSources.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Kaynaklar").setMessage("Henüz kayıtlı kaynak yok.")
                .setPositiveButton("Kaynak ekle") { _, _ -> showConnectionDialog() }.setNegativeButton("Kapat", null).show(); return
        }
        val labels = savedSources.map { "${it.name} • ${if (it.mode == "xtream") "Xtream" else "M3U"}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Kayıtlı kaynaklar").setItems(labels) { _, which ->
            val s = savedSources[which]
            AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf("Yenile", "Sil")) { _, action ->
                if (action == 0) { if (s.mode == "xtream") loadXtream(s, true) else loadM3u(s, true) }
                else { savedSources.removeAll { it.id == s.id }; persistSources(); items.removeAll { it.sourceId == s.id }; render() }
            }.setNegativeButton("İptal", null).show()
        }.setPositiveButton("Kaynak ekle") { _, _ -> showConnectionDialog() }.setNegativeButton("Kapat", null).show()
    }

    private fun loadM3u(source: SavedSource, append: Boolean) {
        prefs.edit().putString("last_source_id", source.id).apply()
        status.text = "${source.name}: oynatma listesi yükleniyor…"
        thread {
            try {
                val parsed = parseM3u(request(source.address)).take(1200).map { it.copy(sourceId = source.id) }
                runOnUiThread {
                    items.removeAll { it.sourceId == source.id }
                    items.addAll(parsed)
                    saveCachedItems()
                    selectedGroup = "Tüm kategoriler"
                    status.text = "${source.name}: ${parsed.size} içerik yüklendi. ${items.size} toplam içerik."
                    render()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "${source.name} yüklenemedi: ${e.message ?: "bağlantı hatası"}" } }
        }
    }

    private fun loadXtream(source: SavedSource, append: Boolean) {
        val base = source.address.trimEnd('/')
        val user = source.username; val pass = source.password
        if (!(base.startsWith("http://") || base.startsWith("https://")) || user.isBlank() || pass.isBlank()) {
            status.text = "Sunucu, kullanıcı adı ve şifre alanlarını doldur."; return
        }
        prefs.edit().putString("last_source_id", source.id).apply()
        status.text = "${source.name}: Xtream içerikleri yükleniyor…"
        thread {
            try {
                val u = URLEncoder.encode(user, "UTF-8"); val p = URLEncoder.encode(pass, "UTF-8")
                val api = "$base/player_api.php?username=$u&password=$p"
                val root = JSONObject(request(api))
                val userInfo = root.optJSONObject("user_info")
                if (userInfo != null && userInfo.optString("auth", "1") == "0") throw Exception("Giriş bilgileri kabul edilmedi.")
                val loaded = mutableListOf<MediaItem>()

                // TV cihazlarında aynı anda altı büyük JSON yanıtını bellekte tutma.
                // Kategorileri ve içerik listelerini sırayla alıp her yanıtı hemen işle.
                fun categoryMap(action: String): Map<String, String> {
                    val arr = JSONArray(request("$api&action=$action"))
                    val result = mutableMapOf<String, String>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optString("category_id")
                        val name = item.optString("category_name")
                        if (id.isNotBlank() && name.isNotBlank()) result[id] = name
                    }
                    return result
                }

                val liveCategories = categoryMap("get_live_categories")
                val vodCategories = categoryMap("get_vod_categories")
                val seriesCategories = categoryMap("get_series_categories")

                listOf("get_live_streams", "get_vod_streams", "get_series").forEach { action ->
                    val arr = JSONArray(request("$api&action=$action"))
                    val categories = when (action) {
                        "get_live_streams" -> liveCategories
                        "get_vod_streams" -> vodCategories
                        else -> seriesCategories
                    }
                    // Limit memory usage on low-RAM TV devices.
                    val count = minOf(arr.length(), 400)
                    for (i in 0 until count) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString(if (action == "get_series") "series_id" else "stream_id", "")
                        if (id.isBlank()) continue
                        val name = o.optString("name", "İsimsiz içerik")
                        val categoryId = o.optString("category_id")
                        val group = o.optString("category_name").takeIf { it.isNotBlank() } ?: categories[categoryId] ?: "Diğer"
                        val type = when (action) {
                            "get_live_streams" -> "Canlı TV"
                            "get_vod_streams" -> "Filmler"
                            else -> "Diziler"
                        }
                        val ext = o.optString("container_extension", if (type == "Canlı TV") "ts" else "mp4")
                        val plot = o.optString("plot", o.optString("description", ""))
                        val actors = o.optString("cast", o.optString("actors", ""))
                        val streamUrl = when (type) {
                            "Canlı TV" -> "$base/live/$u/$p/$id.$ext"
                            "Filmler" -> "$base/movie/$u/$p/$id.$ext"
                            else -> ""
                        }
                        loaded.add(MediaItem(
                            id, name, streamUrl, group, type,
                            o.optString(if (type == "Filmler") "stream_icon" else "cover", o.optString("stream_icon", "")),
                            ext, if (type == "Diziler") id else "", source.id, plot, actors
                        ))
                    }
                }
                runOnUiThread {
                    items.removeAll { it.sourceId == source.id }; items.addAll(loaded)
                    saveCachedItems()
                    selectedGroup = "Tüm kategoriler"; xtreamBase = base; xtreamUser = user; xtreamPass = pass
                    status.text = "${source.name}: ${loaded.size} içerik yüklendi. ${items.size} toplam içerik."; render()
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

    private fun saveCachedItems() {
        val arr = JSONArray()
        // Keep large playlists from exhausting app memory while serializing the cache.
        items.take(1200).forEach { x ->
            arr.put(JSONObject().put("id",x.id).put("name",x.name).put("url",x.url).put("group",x.group)
                .put("type",x.type).put("icon",x.icon).put("extension",x.extension).put("seriesId",x.seriesId)
                .put("sourceId",x.sourceId).put("plot",x.plot).put("actors",x.actors).put("rating",x.rating)
                .put("releaseDate",x.releaseDate).put("genre",x.genre))
        }
        prefs.edit().putString("cached_items", arr.toString()).apply()
    }

    private fun loadCachedItems() {
        try {
            val arr = JSONArray(prefs.getString("cached_items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(MediaItem(o.optString("id"),o.optString("name"),o.optString("url"),o.optString("group"),o.optString("type"),o.optString("icon"),o.optString("extension"),o.optString("seriesId"),o.optString("sourceId"),o.optString("plot"),o.optString("actors"),o.optString("rating"),o.optString("releaseDate"),o.optString("genre")))
            }
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (detailSeries != null || detailMovie != null) {
            detailSeries = null
            detailMovie = null
            seriesEpisodeRows = emptyList()
            seriesEpisodesLoading = false
            seriesEpisodesError = ""
            render()
            return
        }
        if (selectedGroup != "Tüm kategoriler") {
            selectedGroup = "Tüm kategoriler"
            render()
            rebuildNavigation()
            return
        }
        if (selectedType != "Tümü") {
            selectedType = "Tümü"
            selectedGroup = "Tüm kategoriler"
            render()
            rebuildNavigation()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Kaisertv'den çıkılsın mı?")
            .setMessage("Uygulamadan çıkmak istediğine emin misin?")
            .setNegativeButton("Uygulamaya dön", null)
            .setPositiveButton("Çıkış yap") { _, _ -> finish() }
            .show()
    }

    private fun showSettingsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8); setBackgroundColor(bg) }
        box.addView(TextView(this).apply { text = "Genel ayarlar"; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 8, 0, 8) })
        val compact = CheckBox(this).apply { text = "Afiş kartlarını daha kompakt göster"; setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent); isChecked = prefs.getBoolean("compact_cards", false) }
        val resume = CheckBox(this).apply { text = "Uygulama açıldığında son bölümü seç"; setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent); isChecked = prefs.getBoolean("resume_home", true) }
        box.addView(compact); box.addView(resume)
        box.addView(TextView(this).apply { text = "Gizlenecek kategoriler"; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 14, 0, 8) })
        val available = items.map { it.group.ifBlank { "Diğer" } }.distinct().sorted()
        if (available.isEmpty()) box.addView(TextView(this).apply { text = "Kategoriler kaynak yüklendikten sonra burada görünür."; setTextColor(muted); textSize = 14f })
        available.forEach { category ->
            box.addView(CheckBox(this).apply { text = category; setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent); isChecked = category in hiddenCategories; setOnCheckedChangeListener { _, checked -> if (checked) hiddenCategories.add(category) else hiddenCategories.remove(category); prefs.edit().putStringSet("hidden_categories", hiddenCategories.toSet()).apply(); if (selectedGroup == category && checked) selectedGroup = "Tüm kategoriler"; render() } })
        }
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Ayarlar").setView(scroll).setPositiveButton("Kaydet") { _, _ -> prefs.edit().putBoolean("compact_cards", compact.isChecked).putBoolean("resume_home", resume.isChecked).apply(); render() }.setNegativeButton("Kapat", null).show()
    }

    private fun showItemDetails(item: MediaItem) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(18, 18, 18, 18); background = roundedDrawable(panel, 18, Color.rgb(45, 54, 71)) }
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = roundedDrawable(Color.rgb(31, 37, 50), 12) }
        body.addView(poster, LinearLayout.LayoutParams(150, 220).apply { setMargins(0, 0, 18, 0) })
        if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) loadPoster(item.icon, poster)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply { text = item.name; textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE) })
        info.addView(TextView(this).apply { text = item.type + "  •  " + item.group.ifBlank { "Diğer" }; textSize = 13f; setTextColor(accent); setPadding(0, 8, 0, 8) })
        val metaScroll = ScrollView(this)
        val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(label: String, value: String, fallback: String = ""): TextView {
            meta.addView(TextView(this).apply { text = label; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(accent); setPadding(0, 4, 0, 3) })
            return TextView(this).apply { text = value.ifBlank { fallback }; textSize = 14f; setTextColor(muted); setPadding(0, 0, 0, 8) }.also { meta.addView(it) }
        }
        val ratingView = field("PUAN", item.rating, "Bilgi yükleniyor…")
        val dateView = field("YAYIN TARİHİ", item.releaseDate, "")
        val genreView = field("TÜR", item.genre, "")
        val plotView = field("KONU", item.plot, if (item.sourceId.isNotBlank()) "Bilgiler yükleniyor…" else "Bu kaynak konu bilgisi sağlamıyor.")
        val actorsView = field("OYUNCULAR", item.actors, if (item.sourceId.isNotBlank()) "Bilgiler yükleniyor…" else "Oyuncu bilgisi bulunmuyor.")
        metaScroll.addView(meta)
        info.addView(metaScroll, LinearLayout.LayoutParams(-1, 125))
        info.addView(makeButton("▶  İZLE", true) { (body.tag as? AlertDialog)?.dismiss(); play(item) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44))
        info.addView(makeButton(if (favorites.contains(item.favoriteKey)) "★ Favorilerden çıkar" else "☆ Favorilere ekle", false) { if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey); prefs.edit().putStringSet("favorites", favorites.toSet()).apply() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40).apply { setMargins(0, 8, 0, 0) })
        body.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dialog = AlertDialog.Builder(this).setView(body).setNegativeButton("Kapat", null).create(); body.tag = dialog
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.78f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        if (item.type == "Filmler" || item.type == "Diziler") {
            val source = savedSources.firstOrNull { it.id == item.sourceId && it.mode == "xtream" }
            if (source != null) thread {
                try {
                    val u = URLEncoder.encode(source.username, "UTF-8")
                    val p = URLEncoder.encode(source.password, "UTF-8")
                    val api = "${source.address.trimEnd('/')}/player_api.php?username=$u&password=$p"
                    val action = if (item.type == "Diziler") "get_series_info&series_id=" else "get_vod_info&vod_id="
                    val root = JSONObject(request("$api&action=$action${URLEncoder.encode(item.id, "UTF-8")}"))
                    val detail = root.optJSONObject("info") ?: root.optJSONObject("movie_data") ?: root
                    fun pick(vararg keys: String): String {
                        for (key in keys) {
                            val value = detail.opt(key)
                            if (value != null && value != JSONObject.NULL) {
                                val text = if (value is String) value else value.toString()
                                if (text.isNotBlank() && text != "0" && text != "null") return text
                            }
                        }
                        return ""
                    }
                    val plot = pick("plot", "description", "overview")
                    val actors = pick("cast", "actors")
                    val rating = pick("rating", "rating_5based", "vote_average")
                    val date = pick("releasedate", "releaseDate", "release_date", "year")
                    val genre = pick("genre", "genres")
                    runOnUiThread {
                        plotView.text = plot.ifBlank { "Konu bilgisi kaynakta bulunmuyor." }
                        actorsView.text = actors.ifBlank { "Oyuncu bilgisi kaynakta bulunmuyor." }
                        ratingView.text = rating.ifBlank { "Puan bilgisi bulunmuyor." }
                        dateView.text = date
                        genreView.text = genre
                        val updated = item.copy(plot = plot, actors = actors, rating = rating, releaseDate = date, genre = genre)
                        val index = items.indexOfFirst { it.id == item.id && it.type == item.type && it.sourceId == item.sourceId }
                        if (index >= 0) items[index] = updated
                        saveCachedItems()
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (plotView.text.toString() == "Bilgiler yükleniyor…") plotView.text = "Konu bilgisi alınamadı."
                        if (actorsView.text.toString() == "Bilgiler yükleniyor…") actorsView.text = "Oyuncu bilgisi alınamadı."
                        if (ratingView.text.toString() == "Bilgi yükleniyor…") ratingView.text = "Puan bilgisi alınamadı."
                    }
                }
            }
        }
    }

    private fun render() {
        if (!::content.isInitialized || !::categoryBar.isInitialized) return
        content.removeAllViews()
        val detailMode = detailSeries != null || detailMovie != null
        val fullContentMode = detailMode || selectedType == "Filmler" || selectedType == "Diziler"
        if (::mainHeader.isInitialized) mainHeader.visibility = if (fullContentMode) View.GONE else View.VISIBLE
        if (::status.isInitialized) status.visibility = if (fullContentMode) View.GONE else View.VISIBLE
        if (::categoryScroll.isInitialized) categoryScroll.visibility = View.GONE
        if (detailSeries != null) {
            renderSeriesDetail(detailSeries!!)
            return
        }
        if (detailMovie != null) {
            renderMovieDetail(detailMovie!!)
            return
        }
        val unfilteredBase = when {
            sidebarMode == "search" -> items.toList()
            selectedType == "Tümü" -> items.toList()
            selectedType == "Favoriler" -> items.filter { favorites.contains(it.favoriteKey) }
            selectedType == "Son İzlenenler" -> recent.toList()
            else -> items.filter { it.type == selectedType }
        }
        val query = if (sidebarMode == "search") searchQuery.trim() else ""
        val baseVisible = if (query.isBlank()) unfilteredBase else unfilteredBase.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.group.contains(query, ignoreCase = true) ||
            it.type.contains(query, ignoreCase = true)
        }
        val groupsAvailable = baseVisible.map { it.group.ifBlank { "Diğer" } }.distinct().filter { it !in hiddenCategories }.sorted()
        categoryBar.removeAllViews()
        groupsAvailable.take(100).forEach { group ->
            categoryBar.addView(makeButton(if (group == selectedGroup) "● $group" else group, group == selectedGroup) {
                selectedGroup = group
                render()
            }.apply {
                textSize = 11f; minWidth = 0; minimumWidth = 0
                setPadding(12, 0, 12, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36).apply { setMargins(0, 2, 8, 2) })
        }
        val visible = if (selectedGroup == "Tüm kategoriler" || sidebarMode == "search") baseVisible.filter { (it.group.ifBlank { "Diğer" }) !in hiddenCategories }
            else baseVisible.filter { it.group.ifBlank { it.type } == selectedGroup && it.group !in hiddenCategories }
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
        if (::heroTitle.isInitialized) {
            heroTitle.text = when {
                selectedType == "Canlı TV" -> "Canlı yayın zamanı"
                selectedType == "Filmler" -> "Film gecesi başlasın"
                selectedType == "Diziler" -> "Bir sonraki bölüm seni bekliyor"
                selectedType == "Favoriler" -> "Favorilerin burada"
                selectedType == "Son İzlenenler" -> "Kaldığın yerden devam et"
                else -> "Kütüphaneni keşfet"
            }
            heroSubtitle.text = if (items.isEmpty()) "Kaynağını ekle, içeriklerini keşfet." else "${items.size} içerik kütüphanende hazır."
            if (::heroArt.isInitialized) {
                heroArt.removeAllViews()
                val featured = baseVisible.firstOrNull { it.icon.startsWith("http://") || it.icon.startsWith("https://") }
                if (featured != null) {
                    val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = featured.name }
                    heroArt.addView(image, FrameLayout.LayoutParams(-1, -1))
                    loadPoster(featured.icon, image)
                    heroArt.addView(TextView(this).apply {
                        text = featured.name; textSize = 11f; setTextColor(Color.WHITE)
                        gravity = Gravity.BOTTOM; setPadding(8, 24, 8, 8)
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xDD070A11.toInt()))
                    }, FrameLayout.LayoutParams(-1, -1))
                } else {
                    heroArt.addView(TextView(this).apply {
                        text = "K"; textSize = 88f; setTextColor(accent)
                        setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
        if (sidebarMode != "search") rebuildNavigation()
        val groups: List<Pair<String, List<MediaItem>>> = if (selectedType == "Tümü") {
            // Weekly Turkey streaming chart, 19–26 Oct 2026. Existing playlist items are
            // prioritized by title; titles missing from the user's source are not fabricated.
            val trendingMovies = listOf(
                "gençlik", "organize işler: sazan sarmalı", "harry potter ve ateş kadehi",
                "iyi ki aldatılmışım", "sevimli hayalet casper",
                "harry potter ve ölüm yadigârları", "hizmetçi", "addams ailesi", "aşk hipotezi"
            )
            val trendingSeries = listOf(
                "organize işler: karun hazinesi", "off campus", "game of thrones", "from",
                "mezarlık", "çekiç ve gül", "tulsa king", "prens", "sex education", "konuşanlar"
            )
            fun rankedByTrend(source: List<MediaItem>, terms: List<String>): List<MediaItem> {
                val remaining = source.toMutableList()
                val ranked = mutableListOf<MediaItem>()
                terms.forEach { term ->
                    val match = remaining.firstOrNull { it.name.lowercase().contains(term) }
                    if (match != null) {
                        ranked.add(match)
                        remaining.remove(match)
                    }
                }
                ranked.addAll(remaining)
                return ranked
            }
            listOf(
                "Türkiye Trendleri • Haftanın Filmleri" to rankedByTrend(items.filter { it.type == "Filmler" }, trendingMovies).take(24),
                "Türkiye Trendleri • Haftanın Dizileri" to rankedByTrend(items.filter { it.type == "Diziler" }, trendingSeries).take(24),
                "Son İzlenenler" to recent.take(24)
            ).filter { it.second.isNotEmpty() }
        } else {
            visible.take(60).groupBy {
                if (selectedType == "Favoriler" || selectedType == "Son İzlenenler") it.type
                else it.group.ifBlank { "Diğer" }
            }.map { it.key to it.value }
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
                    setOnClickListener {
                        when (item.type) {
                            "Diziler" -> showSeriesDetail(item)
                            "Filmler" -> showMovieDetail(item)
                            else -> showItemDetails(item)
                        }
                    }
                }
                val liveCard = item.type == "Canlı TV"
                val homeCard = selectedType == "Tümü"
                val poster = ImageView(this).apply {
                    scaleType = if (liveCard) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    contentDescription = item.name
                    background = roundedDrawable(Color.rgb(31, 37, 50), 10)
                    setPadding(if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0)
                    layoutParams = LinearLayout.LayoutParams(-1, if (liveCard) 112 else if (homeCard) 230 else 210)
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
                if (!homeCard && selectedType != "Filmler" && selectedType != "Diziler") actions.addView(makeButton("▶ İzle", true) { play(item) },
                    LinearLayout.LayoutParams(0, 36, 1f).apply { setMargins(0, 7, 4, 0) })
                actions.addView(makeButton(if (favorites.contains(item.favoriteKey)) "★" else "☆", false) {
                    if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
                    prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                    render()
                }, LinearLayout.LayoutParams(43, 36).apply { setMargins(4, 7, 0, 0) })
                card.addView(actions)
                row.addView(card, LinearLayout.LayoutParams(if (liveCard) 190 else 232, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 16, 10) })
            }
            content.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row)
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun showSeriesDetail(series: MediaItem) {
        detailSeries = series
        seriesEpisodeRows = emptyList()
        seriesEpisodesLoading = true
        seriesEpisodesError = ""
        render()

        val source = savedSources.firstOrNull { it.id == series.sourceId && it.mode == "xtream" }
        if (source == null) {
            seriesEpisodesLoading = false
            seriesEpisodesError = "Bölümleri göstermek için bu dizinin Xtream kaynağına bağlan."
            render()
            return
        }

        thread {
            try {
                val u = URLEncoder.encode(source.username, "UTF-8")
                val p = URLEncoder.encode(source.password, "UTF-8")
                val api = "${source.address.trimEnd('/')}/player_api.php?username=$u&password=$p&action=get_series_info&series_id=${URLEncoder.encode(series.seriesId.ifBlank { series.id }, "UTF-8")}"
                val data = JSONObject(request(api))
                val episodes = data.optJSONObject("episodes") ?: JSONObject()
                val rows = mutableListOf<Pair<String, List<MediaItem>>>()
                val keys = episodes.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = episodes.optJSONArray(key) ?: continue
                    val seasonItems = mutableListOf<MediaItem>()
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val id = ep.optString("id", ep.optString("episode_id", ""))
                        if (id.isBlank()) continue
                        val ext = ep.optString("container_extension", "mp4")
                        val name = ep.optString("title", ep.optString("name", "Bölüm ${i + 1}"))
                        val stream = "${source.address.trimEnd('/')}/series/$u/$p/$id.$ext"
                        seasonItems.add(MediaItem(id, name, stream, "${series.name} • Sezon $key", "Bölümler", "", ext, series.seriesId, source.id))
                    }
                    if (seasonItems.isNotEmpty()) rows.add(Pair(key, seasonItems))
                }
                val sortedRows = rows.sortedWith(compareBy { it.first.toIntOrNull() ?: Int.MAX_VALUE })
                runOnUiThread {
                    if (detailSeries?.favoriteKey != series.favoriteKey) return@runOnUiThread
                    seriesEpisodeRows = sortedRows
                    seriesEpisodesLoading = false
                    if (sortedRows.isEmpty()) seriesEpisodesError = "Bu dizi için bölüm bulunamadı."
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (detailSeries?.favoriteKey != series.favoriteKey) return@runOnUiThread
                    seriesEpisodesLoading = false
                    seriesEpisodesError = "Bölümler yüklenemedi. Kaynağı kontrol edip yeniden dene."
                    render()
                }
            }
        }
    }

    private fun showMovieDetail(movie: MediaItem) {
        detailSeries = null
        detailMovie = movie
        render()
    }

    private fun renderMovieDetail(movie: MediaItem) {
        val hero = FrameLayout(this).apply {
            background = roundedDrawable(Color.rgb(24, 31, 45), 18, Color.rgb(50, 60, 78))
            clipToOutline = true
        }
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = movie.name
            alpha = 0.78f
        }
        hero.addView(poster, FrameLayout.LayoutParams(-1, -1))
        if (movie.icon.startsWith("http://") || movie.icon.startsWith("https://")) loadPoster(movie.icon, poster)
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22070A11, 0xF5070A11.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(26, 22, 26, 22)
        }
        copy.addView(TextView(this).apply {
            text = "FİLM  •  ${movie.group.ifBlank { "DİĞER" }.uppercase()}"
            textSize = 12f; setTextColor(accentBright); letterSpacing = 0.08f
        })
        copy.addView(TextView(this).apply {
            text = movie.name; textSize = 26f; maxLines = 2
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 6)
        })
        val metadata = listOf(movie.releaseDate, movie.genre, movie.rating.takeIf { it.isNotBlank() }?.let { "Puan $it" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString("   •   ")
        if (metadata.isNotBlank()) copy.addView(TextView(this).apply {
            text = metadata; textSize = 13f; setTextColor(accentBright); setPadding(0, 0, 0, 8)
        })
        copy.addView(TextView(this).apply {
            text = movie.plot.ifBlank { "Film bilgileri kaynakta bulunmuyorsa doğrudan izleyebilirsin." }
            textSize = 13f; maxLines = 2; setTextColor(Color.rgb(226, 230, 238))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(makeButton("▶  FİLMİ İZLE", true) { play(movie) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0, 14, 10, 0) })
        actions.addView(makeButton(if (favorites.contains(movie.favoriteKey)) "★ Favorilerden çıkar" else "☆ Favorilere ekle", false) {
            if (!favorites.add(movie.favoriteKey)) favorites.remove(movie.favoriteKey)
            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0, 14, 10, 0) })
        actions.addView(makeButton("← Geri", false) {
            detailMovie = null
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0, 14, 0, 0) })
        copy.addView(actions)
        hero.addView(copy, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(300)).apply { setMargins(0, 0, 0, dp(18)) })

        val infoTitle = TextView(this).apply {
            text = "FİLM HAKKINDA"; textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(4, 0, 0, 8)
        }
        content.addView(infoTitle)
        if (movie.actors.isNotBlank()) content.addView(TextView(this).apply {
            text = "Oyuncular: ${movie.actors}"; textSize = 14f; setTextColor(muted)
            setPadding(4, 0, 4, 12)
        })
    }

    private fun renderSeriesDetail(series: MediaItem) {
        val hero = FrameLayout(this).apply {
            background = roundedDrawable(Color.rgb(24, 31, 45), 18, Color.rgb(50, 60, 78))
            clipToOutline = true
        }
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = series.name
            alpha = 0.82f
        }
        hero.addView(poster, FrameLayout.LayoutParams(-1, -1))
        if (series.icon.startsWith("http://") || series.icon.startsWith("https://")) loadPoster(series.icon, poster)
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22070A11, 0xEE070A11.toInt()))
        }
        hero.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(24, 22, 24, 22)
        }
        copy.addView(TextView(this).apply {
            text = "DİZİ  •  ${series.group.ifBlank { "DİĞER" }.uppercase()}"
            textSize = 12f; setTextColor(accentBright); letterSpacing = 0.08f
        })
        copy.addView(TextView(this).apply {
            text = series.name; textSize = 26f; maxLines = 2
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 6)
        })
        copy.addView(TextView(this).apply {
            text = series.plot.ifBlank { "Sezonları ve bölümleri aşağıdan seç." }
            textSize = 13f; maxLines = 2; setTextColor(Color.rgb(226, 230, 238))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(makeButton(if (favorites.contains(series.favoriteKey)) "★ Favorilerden çıkar" else "☆ Favorilere ekle", false) {
            if (!favorites.add(series.favoriteKey)) favorites.remove(series.favoriteKey)
            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 42).apply { setMargins(0, 14, 10, 0) })
        actions.addView(makeButton("← Geri", false) {
            detailSeries = null
            seriesEpisodeRows = emptyList()
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 42).apply { setMargins(0, 14, 0, 0) })
        copy.addView(actions)
        hero.addView(copy, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(300)).apply { setMargins(0, 0, 0, dp(18)) })

        val episodesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(Color.rgb(14, 19, 29), 16, Color.rgb(42, 50, 66))
        }
        content.addView(episodesPanel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })
        episodesPanel.addView(TextView(this).apply {
            text = "SEZONLAR VE BÖLÜMLER"
            textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        })
        when {
            seriesEpisodesLoading -> episodesPanel.addView(TextView(this).apply {
                text = "Bölümler yükleniyor…"; textSize = 15f; setTextColor(muted); setPadding(4, 12, 4, 12)
            })
            seriesEpisodesError.isNotBlank() -> episodesPanel.addView(TextView(this).apply {
                text = seriesEpisodesError; textSize = 15f; setTextColor(muted); setPadding(4, 12, 4, 12)
            })
            seriesEpisodeRows.isEmpty() -> episodesPanel.addView(TextView(this).apply {
                text = "Bu dizi için bölüm bulunamadı."; textSize = 15f; setTextColor(muted); setPadding(4, 12, 4, 12)
            })
            else -> seriesEpisodeRows.forEach { (season, episodes) ->
                episodesPanel.addView(TextView(this).apply {
                    text = "Sezon $season"; textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(accentBright)
                    setPadding(0, dp(12), 0, dp(8))
                })
                episodes.forEachIndexed { index, episode ->
                    val episodeRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(10), dp(12), dp(10))
                        background = roundedDrawable(panel, 12, Color.rgb(42, 50, 66))
                    }
                    episodeRow.addView(TextView(this).apply {
                        text = "${index + 1}."; textSize = 15f; setTextColor(accentBright)
                    }, LinearLayout.LayoutParams(38, ViewGroup.LayoutParams.WRAP_CONTENT))
                    episodeRow.addView(TextView(this).apply {
                        text = episode.name; textSize = 15f; maxLines = 2; setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    episodeRow.addView(makeButton("▶ İzle", true) { play(episode) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40).apply { setMargins(12, 0, 0, 0) })
                    episodesPanel.addView(episodeRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
                }
            }
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



    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        posterExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API; retained for Android TV Back-key handling")
    override fun onBackPressed() {
        if (detailSeries != null) {
            detailSeries = null
            seriesEpisodeRows = emptyList()
            seriesEpisodesLoading = false
            seriesEpisodesError = ""
            render()
            return
        }
        if (detailMovie != null) {
            detailMovie = null
            render()
            return
        }
        if (sidebarMode != "main") {
            selectedType = "Tümü"
            selectedGroup = "Tüm kategoriler"
            sidebarMode = "main"
            searchQuery = ""
            rebuildNavigation()
            render()
            return
        }
        super.onBackPressed()
    }

    private fun saveRecent(item: MediaItem) {
        recent.removeAll { it.favoriteKey == item.favoriteKey }
        recent.add(0, item)
        while (recent.size > 20) recent.removeAt(recent.lastIndex)
        val arr = JSONArray()
        recent.forEach { r -> arr.put(JSONObject().put("id", r.id).put("name", r.name).put("url", r.url).put("group", r.group).put("type", r.type).put("icon", r.icon).put("extension", r.extension).put("seriesId", r.seriesId).put("sourceId", r.sourceId)) }
        getSharedPreferences("androtv", MODE_PRIVATE).edit().putString("recent", arr.toString()).apply()
    }

    private fun play(item: MediaItem) {
        if (item.type == "Diziler" && item.url.isBlank()) { loadSeriesEpisodes(item); return }
        saveRecent(item)
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra("name", item.name)
            .putExtra("url", item.url)
        if (item.type == "Canlı TV") {
            val channels = items.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
            intent.putStringArrayListExtra("channel_names", ArrayList(channels.map { it.name }))
            intent.putStringArrayListExtra("channel_urls", ArrayList(channels.map { it.url }))
            intent.putExtra("channel_index", channels.indexOfFirst { it.url == item.url }.coerceAtLeast(0))
        }
        startActivity(intent)
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
