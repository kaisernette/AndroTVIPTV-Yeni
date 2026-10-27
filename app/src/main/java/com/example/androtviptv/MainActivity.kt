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
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    private val posterExecutor = Executors.newFixedThreadPool(2)
    private val posterCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val items = mutableListOf<MediaItem>()
    private val savedSources = mutableListOf<SavedSource>()
    private lateinit var sourceName: EditText
    private val favorites = mutableSetOf<String>()
    private val recent = mutableListOf<MediaItem>()
    private var selectedGroup = "Tüm kategoriler"
    private var selectedCategorySection = "Canlı TV"
    private var expandedCategory: Pair<String, List<MediaItem>>? = null
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
    private var bg = Color.rgb(17, 20, 25)
    private var panel = Color.rgb(25, 30, 38)
    private var accent = Color.rgb(0, 166, 255)
    private var accentBright = Color.rgb(72, 190, 255)
    private var muted = Color.rgb(190, 198, 210)

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
        // Always start in the lightweight Live TV section. Restoring a huge
        // movie/series section on launch can make some devices run out of memory.
        selectedType = "Canlı TV"
        prefs.edit().putString("last_section", selectedType).apply()
        buildUi()
        loadSavedSources()
    }

    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var heroArt: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var sidebarMenuScroll: ScrollView
    private lateinit var brandView: LinearLayout
    private lateinit var sidebar: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var mainContentScroll: ScrollView
    private fun kaiserLogoView(): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.kaisertv_app_icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        contentDescription = "KaiserTV+"
    }

    private var previewPlayer: ExoPlayer? = null
    private var previewView: PlayerView? = null
    private var previewUrl: String = ""
    private var previewChannel: MediaItem? = null
    private var sidebarMode = "main"
    private var settingsPage = "main"
    private var historyType = "Canlı TV"
    private val hiddenCategories = mutableSetOf<String>()
    private var searchQuery = ""
    private var searchInputFocusPending = false
    private var isLoadingSource = false
    private var loadProgress = 0
    private var hasLoadedSource = false

    private fun buildUi() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18, 14, 18, 12)
            setBackgroundColor(bg)
        }

        rootLayout = root
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 12, 8, 10)
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        root.addView(sidebar, LinearLayout.LayoutParams(dp(140), -1).apply { setMargins(0, 0, dp(8), 0) })

        brandView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        brandView.addView(TextView(this).apply {
            val logo = android.text.SpannableString("▶ KIP+")
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 170, 0)), 5, 6, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = logo
            textSize = 23f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "KIP+"
        }, LinearLayout.LayoutParams(-1, dp(54)))
        sidebar.addView(brandView, LinearLayout.LayoutParams(-1, dp(76)))
        navBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sidebarMenuScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(navBar)
        }
        sidebar.addView(sidebarMenuScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        rebuildNavigation()

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))

        mainHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 8)
        }
        mainHeader.addView(View(this), LinearLayout.LayoutParams(0, dp(40), 1f))
        mainHeader.addView(TextView(this).apply {
            text = "Kaynaklar"; textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { showSourcesDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        mainHeader.addView(TextView(this).apply {
            text = "+"; textSize = 25f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(15, 18, 27))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(accent); setStroke(dp(1), accentBright)
            }
            isFocusable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.08f else 1f
                v.scaleY = if (hasFocus) 1.08f else 1f
            }
            contentDescription = "Kaynak ekle"
            setOnClickListener { showConnectionDialog() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(8), 0, dp(4), 0) })
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
        mainContentScroll = ScrollView(this).apply { isFillViewport = true; isFocusable = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 2, 0, 12) }
        mainContentScroll.addView(content)
        main.addView(mainContentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun rebuildNavigation() {
        if (!::navBar.isInitialized) return
        // Keep the main navigation icon-only, but expand the sidebar when a
        // category list is open so full category names remain readable.
        if (::sidebar.isInitialized) {
            val showReadableCategories = sidebarMode == "context" &&
                selectedType in listOf("Canlı TV", "Filmler", "Diziler")
            val params = sidebar.layoutParams
            val desiredWidth = dp(if (showReadableCategories) 200 else 140)
            if (params != null && params.width != desiredWidth) {
                params.width = desiredWidth
                sidebar.layoutParams = params
            }
        }
        navBar.removeAllViews()

        fun addSidebarButton(label: String, selected: Boolean = false, action: () -> Unit) {
            navBar.addView(makeButton(label, selected, action).apply {
                // Çerçevesiz yazılı menü; yalnızca odak/seçim mavi vurgulanır.
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                setTextColor(if (selected) accentBright else Color.WHITE)
                setOnFocusChangeListener { v, hasFocus ->
                    (v as Button).setTextColor(if (hasFocus || selected) accentBright else Color.WHITE)
                    v.scaleX = if (hasFocus) 1.025f else 1f
                    v.scaleY = if (hasFocus) 1.025f else 1f
                }
                textSize = 14f
                val iconEnd = label.indexOf("  ").takeIf { it in 1..3 } ?: 1
                text = android.text.SpannableString(label).apply {
                    setSpan(android.text.style.RelativeSizeSpan(1.28f), 0, iconEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, iconEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = label
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(8), 0)
                minHeight = dp(42)
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(3), 0, dp(3)) })
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
            prefs.edit().putString("last_section", "Tümü").apply()
            rebuildNavigation()
            render()
        }

        if (sidebarMode == "main") {
            listOf("⌕  Ara", "⌂  Ana Sayfa", "▣  Canlı TV", "▰  Filmler", "▤  Diziler", "♡  Favoriler", "⚙  Ayarlar").forEach { tab ->
                val normalizedTab = when {
                    tab.endsWith("Ara") -> "Ara"
                    tab.contains("Ana Sayfa") -> "Ana Sayfa"
                    tab.contains("Canlı TV") -> "TV"
                    tab.contains("Favoriler") -> "Favoriler"
                    tab.contains("Ayarlar") -> "Ayarlar"
                    tab.contains("Filmler") -> "Filmler"
                    else -> "Diziler"
                }
                val key = when (normalizedTab) {
                    "TV" -> "Canlı TV"
                    "Ana Sayfa" -> "Tümü"
                    else -> normalizedTab
                }
                if (normalizedTab == "Ayarlar") {
                    addSidebarButton(tab, selectedType == "Ayarlar") {
                        detailSeries = null
                        detailMovie = null
                        settingsPage = "main"
                        selectedType = "Ayarlar"
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = "settings"
                        rebuildNavigation()
                        render()
                    }
                } else {
                    addSidebarButton(tab, selectedType == key && normalizedTab != "Ara") {
                        if (normalizedTab == "Ara") {
                            detailSeries = null
                            detailMovie = null
                            sidebarMode = "search"
                            searchInputFocusPending = true
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
                        expandedCategory = null
                        selectedType = key
                        sidebarMode = if (normalizedTab == "TV" || normalizedTab == "Filmler" || normalizedTab == "Diziler") "context" else "main"
                        prefs.edit().putString("last_section", key).apply()
                        rebuildNavigation()
                        render()
                    }
                }
                if (normalizedTab == "Ana Sayfa") {
                    // Visually separate the primary navigation from the utility items.
                }
            }
            return
        }

        addSidebarButton("⌂") { returnToMainMenu() }

        if (sidebarMode == "search") {
            navBar.addView(TextView(this).apply {
                text = "⌕  ARA"
                textSize = 15f; setTextColor(accentBright)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(10), dp(14), dp(6), dp(8))
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }

        val heading = when (selectedType) {
            "Canlı TV" -> "KANALLAR"
            "Filmler" -> "FİLM KATEGORİLERİ"
            "Diziler" -> "DİZİ KATEGORİLERİ"
            else -> "KATEGORİLER"
        }
        navBar.addView(TextView(this).apply {
            text = heading
            textSize = 12f
            setTextColor(accentBright)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.02f
            setPadding(10, dp(10), 6, dp(6))
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (selectedType == "Canlı TV") {
            val channels = items.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
            if (channels.isEmpty()) {
                navBar.addView(TextView(this).apply {
                    text = if (items.isEmpty()) "Önce kaynak ekle." else "Kanal bulunamadı."
                    textSize = 12f; setTextColor(muted); setPadding(10, 8, 6, 8)
                })
            } else channels.take(40).forEach { channel ->
                addSidebarButton(channel.name) {
                    selectedType = "Canlı TV"
                    sidebarMode = "context"
                    render()
                    startChannelPreview(channel)
                }
                navBar.getChildAt(navBar.childCount - 1).setOnFocusChangeListener { v, hasFocus ->
                    (v as? Button)?.setTextColor(if (hasFocus) accentBright else Color.WHITE)
                    v.scaleX = if (hasFocus) 1.025f else 1f
                    v.scaleY = if (hasFocus) 1.025f else 1f
                    if (hasFocus) startChannelPreview(channel)
                }
            }
        } else {
            addSidebarButton("Tüm kategoriler", selectedGroup == "Tüm kategoriler") {
                selectedGroup = "Tüm kategoriler"
                rebuildNavigation()
                render()
            }
            items.filter { it.type == selectedType }
                .map { it.group.ifBlank { "Diğer" } }
                .distinct().sorted().filter { !hiddenCategories.contains(selectedType + "|" + it) }.take(60)
                .forEach { category ->
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
            status.text = ""
            if (items.isNotEmpty() && items.none { it.type == selectedType }) {
                selectedType = if (items.any { it.type == "Filmler" }) "Filmler" else "Tümü"
            }
            selectedGroup = "Tüm kategoriler"
            sidebarMode = "main"
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
        isLoadingSource = true
        loadProgress = 0
        status.text = "${source.name}: TV, filmler ve diziler yükleniyor…"
        render()
        thread {
            try {
                val parsed = parseM3u(request(source.address)).map { it.copy(sourceId = source.id) }
                runOnUiThread {
                    items.removeAll { it.sourceId == source.id }
                    items.addAll(parsed)
                    saveCachedItems()
                    selectedGroup = "Tüm kategoriler"
                    status.text = "${source.name}: ${parsed.size} içerik yüklendi. ${items.size} toplam içerik."
                    isLoadingSource = false
                    hasLoadedSource = true
                    sidebarMode = "main"
                    render()
                }
            } catch (e: Exception) { runOnUiThread {
                loadProgress = 0
                isLoadingSource = false
                status.text = "${source.name} yüklenemedi: ${e.message ?: "bağlantı hatası"}"
                render()
            } }
        }
    }

    private fun loadXtream(source: SavedSource, append: Boolean) {
        val base = source.address.trimEnd('/')
        val user = source.username; val pass = source.password
        if (!(base.startsWith("http://") || base.startsWith("https://")) || user.isBlank() || pass.isBlank()) {
            status.text = "Sunucu, kullanıcı adı ve şifre alanlarını doldur."; return
        }
        prefs.edit().putString("last_source_id", source.id).apply()
        isLoadingSource = true
        loadProgress = 0
        status.text = "${source.name}: TV, filmler ve diziler yükleniyor…"
        render()
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

                runOnUiThread { loadProgress = 10; status.text = "${source.name}: TV kategorileri alınıyor…"; render() }
                val liveCategories = categoryMap("get_live_categories")
                runOnUiThread { loadProgress = 20; status.text = "${source.name}: film kategorileri alınıyor…"; render() }
                val vodCategories = categoryMap("get_vod_categories")
                runOnUiThread { loadProgress = 30; status.text = "${source.name}: dizi kategorileri alınıyor…"; render() }
                val seriesCategories = categoryMap("get_series_categories")
                runOnUiThread { loadProgress = 40; status.text = "${source.name}: içerik listeleri alınıyor…"; render() }

                listOf("get_live_streams", "get_vod_streams", "get_series").forEachIndexed { actionIndex, action ->
                    runOnUiThread {
                        status.text = "${source.name}: " + when (action) {
                            "get_live_streams" -> "Canlı TV kanalları alınıyor…"
                            "get_vod_streams" -> "Filmler alınıyor…"
                            else -> "Diziler alınıyor…"
                        }
                    }
                    val response = request("$api&action=$action")
                    val arr = try { JSONArray(response) } catch (e: Exception) {
                        throw Exception("Sunucudan geçersiz liste geldi ($action)")
                    }
                    val categories = when (action) {
                        "get_live_streams" -> liveCategories
                        "get_vod_streams" -> vodCategories
                        else -> seriesCategories
                    }
                    val count = arr.length()
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
                            ext, if (type == "Diziler") id else "", source.id, plot, actors,
                            o.optString("rating", o.optString("vote_average", "")),
                            o.optString("releaseDate", o.optString("year", "")),
                            o.optString("genre", "")
                        ))
                        if (i % 100 == 0) {
                            val baseProgress = 40 + actionIndex * 20
                            val within = if (arr.length() == 0) 20 else (i * 20 / arr.length())
                            runOnUiThread { loadProgress = (baseProgress + within).coerceIn(1, 99); status.text = "${source.name}: $type yükleniyor… %$loadProgress"; if (i % 500 == 0 || i == 0) render() }
                        }
                    }
                }
                runOnUiThread {
                    items.removeAll { it.sourceId == source.id }; items.addAll(loaded)
                    saveCachedItems()
                    selectedType = "Tümü"
                    selectedGroup = "Tüm kategoriler"
                    sidebarMode = "main"
                    xtreamBase = base; xtreamUser = user; xtreamPass = pass
                    status.text = ""
                    status.visibility = View.GONE
                    isLoadingSource = false
                    hasLoadedSource = true
                    sidebarMode = "main"
                    render()
                }
            } catch (e: Exception) { runOnUiThread {
                loadProgress = 0
                isLoadingSource = false
                status.text = "Xtream yüklenemedi: ${e.message ?: "bağlantı hatası"}"
                render()
            } }
        }
    }

    private fun request(address: String): String {
        var lastError: Exception? = null
        // IPTV providers often respond slowly to large VOD/series lists. Retry transient
        // network/server failures instead of silently leaving a partially loaded library.
        for (attempt in 0..2) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "KaiserTV+/2.1")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    useCaches = false
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    if (code == 429 || code >= 500) throw Exception("HTTP $code (yeniden denenecek)")
                    throw Exception("HTTP $code")
                }
                if (body.isBlank()) throw Exception("Sunucu boş yanıt döndürdü")
                return body
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) {
                    try { Thread.sleep(700L * (attempt + 1)) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw Exception("Yükleme iptal edildi")
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }
        throw Exception("Sunucuya ulaşılamadı: ${lastError?.message ?: "bağlantı hatası"}")
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

    private fun cachedItemsFile() = java.io.File(filesDir, "kaisertv_media_cache.json")

    private fun saveCachedItems() {
        // Xtream libraries can contain tens of thousands of entries. Do not build
        // a giant JSONArray/String in memory after loading; stream each item to disk.
        // Keep the startup cache bounded for low-memory Android TV sticks.
        val snapshot = try { items.take(1000).toList() } catch (_: Exception) { return }
        thread(name = "kaisertv-cache-writer") {
            val temp = java.io.File(filesDir, "kaisertv_media_cache.tmp")
            try {
                temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("[")
                    snapshot.forEachIndexed { index, x ->
                        if (index > 0) writer.write(",")
                        val obj = JSONObject()
                            .put("id", x.id).put("name", x.name).put("url", x.url).put("group", x.group)
                            .put("type", x.type).put("icon", x.icon).put("extension", x.extension)
                            .put("seriesId", x.seriesId).put("sourceId", x.sourceId).put("plot", x.plot)
                            .put("actors", x.actors).put("rating", x.rating).put("releaseDate", x.releaseDate)
                            .put("genre", x.genre)
                        writer.write(obj.toString())
                        if (index % 250 == 0) writer.flush()
                    }
                    writer.write("]")
                }
                val target = cachedItemsFile()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("KaiserTV", "Media cache write failed", e)
                try { temp.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun loadCachedItems() {
        try {
            val file = cachedItemsFile()
            // Parsing a large JSON string plus JSONArray duplicates memory usage and
            // can crash low-memory Mi TV Stick devices on the next launch.
            if (file.exists() && file.length() > 2L * 1024L * 1024L) {
                file.delete()
                items.clear()
                prefs.edit().remove("cached_items").apply()
                return
            }
            val raw = if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8) else "[]"
            val arr = JSONArray(raw)
            val restored = ArrayList<MediaItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                restored.add(MediaItem(o.optString("id"),o.optString("name"),o.optString("url"),o.optString("group"),o.optString("type"),o.optString("icon"),o.optString("extension"),o.optString("seriesId"),o.optString("sourceId"),o.optString("plot"),o.optString("actors"),o.optString("rating"),o.optString("releaseDate"),o.optString("genre")))
            }
            items.clear()
            items.addAll(restored)
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleSafeBack()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (selectedType == "Canlı TV" && sidebarMode == "context" &&
            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
             keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
            previewChannel?.let { play(it); return true }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            handleSafeBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleSafeBack() {
        // Handle Android system/remote back in one place and keep the activity alive.
        try {
            if (detailSeries != null || detailMovie != null) {
                detailSeries = null
                detailMovie = null
                seriesEpisodeRows = emptyList()
                seriesEpisodesLoading = false
                seriesEpisodesError = ""
                render()
                return
            }

            if (expandedCategory != null) {
                expandedCategory = null
                render()
                return
            }

            if (sidebarMode != "main" || selectedGroup != "Tüm kategoriler" || selectedType != "Tümü") {
                detailSeries = null
                detailMovie = null
                seriesEpisodeRows = emptyList()
                seriesEpisodesLoading = false
                seriesEpisodesError = ""
                sidebarMode = "main"
                searchQuery = ""
                selectedType = "Tümü"
                selectedGroup = "Tüm kategoriler"
                prefs.edit().putString("last_section", "Tümü").apply()
                rebuildNavigation()
                render()
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("KaiserTV", "Back navigation failed", e)
            // Keep the current activity alive even if rebuilding a section fails.
            try {
                detailSeries = null
                detailMovie = null
                sidebarMode = "main"
                searchQuery = ""
                selectedType = "Tümü"
                selectedGroup = "Tüm kategoriler"
                prefs.edit().putString("last_section", "Tümü").apply()
                rebuildNavigation()
                render()
            } catch (ignored: Exception) {
                android.util.Log.e("KaiserTV", "Fallback navigation failed", ignored)
            }
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Kaisertv'den çıkılsın mı?")
            .setMessage("Uygulamadan çıkmak istediğine emin misin?")
            .setNegativeButton("Uygulamaya dön", null)
            .setPositiveButton("Çıkış yap") { _, _ -> finish() }
            .show()
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
        val firstEntry = items.isEmpty() && !isLoadingSource
        if (::sidebar.isInitialized) sidebar.visibility = if (firstEntry || isLoadingSource) View.GONE else View.VISIBLE
        if (::brandView.isInitialized) brandView.visibility = View.GONE
        if (::navBar.isInitialized) navBar.visibility = if (firstEntry || isLoadingSource) View.GONE else View.VISIBLE
        if (::mainHeader.isInitialized) mainHeader.visibility = View.GONE
        if (::status.isInitialized) status.visibility = if (firstEntry || isLoadingSource || detailMode || selectedType == "Filmler" || selectedType == "Diziler") View.GONE else View.VISIBLE
        if (::categoryScroll.isInitialized) categoryScroll.visibility = View.GONE
        if (firstEntry) {
            mainContentScroll.visibility = View.VISIBLE
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            val welcomeLogo = TextView(this).apply {
                val logo = android.text.SpannableString("▶ KaiserIPTV+")
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 8, 13, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = logo
                textSize = 38f; gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(20))
                setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.rgb(0, 80, 190))
            }
            welcome.addView(welcomeLogo, LinearLayout.LayoutParams(-1, dp(110)))
            welcome.addView(TextView(this).apply {
                text = "İzleme kaynağını ekleyerek başla"
                textSize = 16f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(18))
            })
            welcome.addView(makeButton("＋ KAYNAK EKLE", true) { showConnectionDialog() },
                LinearLayout.LayoutParams(dp(240), dp(48)))
            content.addView(welcome, LinearLayout.LayoutParams(-1, -1))
            sidebarMenuScroll.visibility = View.GONE
            return
        } else {
            sidebarMenuScroll.visibility = View.VISIBLE
        }
        if (isLoadingSource) {
            val loadingPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            val logoLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            logoLine.addView(TextView(this).apply {
                text = "▶"
                textSize = 30f
                setTextColor(Color.rgb(0, 190, 255))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.rgb(0, 120, 255))
            })
            logoLine.addView(TextView(this).apply {
                text = "Kaiser"
                textSize = 34f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.rgb(35, 80, 145))
            })
            logoLine.addView(TextView(this).apply {
                text = "IPTV+"
                textSize = 34f
                setTextColor(Color.rgb(0, 170, 255))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(dp(7).toFloat(), 0f, 0f, Color.rgb(0, 100, 255))
            })
            loadingPanel.addView(logoLine)
            loadingPanel.addView(TextView(this).apply {
                text = status.text.toString().ifBlank { "Kaynak yükleniyor…" }
                textSize = 18f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, 0)
            })
            // Tek bir geniş, çerçevesiz görsel alanı; görseller sağdan sola otomatik akar.
            val visualViewport = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(440), dp(145)).apply {
                    setMargins(0, dp(26), 0, 0)
                }
                clipChildren = true
                clipToPadding = true
            }
            val visualItems = listOf(
                Triple("🎬", "FİLMLER", Color.rgb(35, 105, 190)),
                Triple("📺", "TELEVİZYON", Color.rgb(145, 75, 175)),
                Triple("🎞️", "DİZİLER", Color.rgb(35, 150, 125)),
                Triple("⚽", "CANLI SPOR", Color.rgb(70, 165, 95)),
                Triple("🤖", "ANİMASYON", Color.rgb(225, 145, 55))
            )
            val slide = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            val visual = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val visualSymbol = TextView(this).apply {
                text = visualItems[0].first
                textSize = 66f
                gravity = Gravity.CENTER
                setShadowLayer(dp(14).toFloat(), 0f, 0f, visualItems[0].third)
            }
            visual.addView(visualSymbol, LinearLayout.LayoutParams(dp(125), -1))
            val visualText = TextView(this).apply {
                text = visualItems[0].second
                textSize = 18f
                setTextColor(accentBright)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                letterSpacing = 0.12f
            }
            visual.addView(visualText, LinearLayout.LayoutParams(-2, -1))
            slide.addView(visual, FrameLayout.LayoutParams(-1, -1))
            visualViewport.addView(slide)
            loadingPanel.addView(visualViewport)
            var visualIndex = 0
            val slideRunnable = object : Runnable {
                override fun run() {
                    if (!visualViewport.isAttachedToWindow) return
                    val nextIndex = (visualIndex + 1) % visualItems.size
                    val width = visualViewport.width.toFloat().coerceAtLeast(dp(440).toFloat())
                    visual.animate().translationX(-width).alpha(0f).setDuration(420L).withEndAction {
                        visualIndex = nextIndex
                        visualSymbol.text = visualItems[visualIndex].first
                        visualSymbol.setShadowLayer(dp(14).toFloat(), 0f, 0f, visualItems[visualIndex].third)
                        visualText.text = visualItems[visualIndex].second
                        visual.translationX = width
                        visual.alpha = 0f
                        visual.animate().translationX(0f).alpha(1f).setDuration(420L).start()
                    }.start()
                    visualViewport.postDelayed(this, 1900L)
                }
            }
            visualViewport.postDelayed(slideRunnable, 1300L)
            content.addView(loadingPanel, LinearLayout.LayoutParams(-1, -1))
            return
        }
        if (selectedType == "Ayarlar") { renderSettingsScreen(); return }
        if (detailSeries != null) {
            renderSeriesDetail(detailSeries!!)
            return
        }
        if (detailMovie != null) {
            stopChannelPreview()
            renderMovieDetail(detailMovie!!)
            return
        }
        if (selectedType == "Canlı TV" && sidebarMode == "context") {
            renderChannelPreviewScreen()
            return
        }
        if (sidebarMode == "search") {
            val searchPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(12))
            }
            searchPanel.addView(TextView(this).apply {
                text = "Ara"; textSize = 27f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(10))
            })
            val searchInput = EditText(this).apply {
                hint = "Film, dizi veya kanal adı yaz…"
                setSingleLine(true); textSize = 17f; setTextColor(Color.WHITE); setHintTextColor(muted)
                setPadding(dp(16), 0, dp(12), 0)
                background = roundedDrawable(Color.rgb(28, 35, 49), 14, accent)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                tag = "global_search_input"
                setText(searchQuery); setSelection(text.length)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchQuery = s?.toString().orEmpty()
                        selectedGroup = "Tüm kategoriler"
                        // Keep this EditText attached while the user types. Re-rendering the
                        // whole screen here detaches it and Android closes the keyboard per character.
                        // Results refresh when the user presses the keyboard Search action.

                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(windowToken, 0)
                        render()
                        true
                    } else false
                }
            }
            searchPanel.addView(searchInput, LinearLayout.LayoutParams(-1, dp(52)))
            content.addView(searchPanel)
            if (searchInputFocusPending) {
                searchInputFocusPending = false
                searchInput.post {
                    if (sidebarMode == "search" && searchInput.isAttachedToWindow) {
                        searchInput.requestFocus()
                        searchInput.setSelection(searchInput.text.length)
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
        val unfilteredBase = when {
            sidebarMode == "search" -> items.toList()
            selectedType == "Tümü" -> items.toList()
            selectedType == "Favoriler" -> items.filter { favorites.contains(it.favoriteKey) }
            selectedType == "Son İzlenenler" -> recent.toList()
            else -> items.filter { it.type == selectedType }
        }
        val query = if (sidebarMode == "search") searchQuery.trim() else ""
        val categoryVisibleBase = if (sidebarMode == "search") unfilteredBase else unfilteredBase.filter { item -> !hiddenCategories.contains(item.type + "|" + item.group.ifBlank { "Diğer" }) }
        val baseVisible = if (query.isBlank()) categoryVisibleBase else categoryVisibleBase.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.group.contains(query, ignoreCase = true) ||
            it.type.contains(query, ignoreCase = true)
        }
        val groupsAvailable = baseVisible.map { it.group.ifBlank { "Diğer" } }.distinct().sorted()
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
        val visible = if (selectedGroup == "Tüm kategoriler" || sidebarMode == "search") baseVisible
            else baseVisible.filter { it.group.ifBlank { it.type } == selectedGroup }
        if (visible.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 24, 24, 24); background = roundedDrawable(panel, 18, Color.rgb(42, 49, 64))
            }
            empty.addView(TextView(this).apply {
                text = when {
                    sidebarMode == "search" && query.isBlank() -> "Tüm kaynaklarda ara"
                    sidebarMode == "search" -> "Arama sonucu bulunamadı"
                    selectedType == "Favoriler" -> "Henüz favori eklemedin"
                    selectedType == "Son İzlenenler" -> "İzleme geçmişin burada görünecek"
                    else -> "Kütüphanen seni bekliyor"
                }
                textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            })
            empty.addView(TextView(this).apply {
                text = when {
                    sidebarMode == "search" && query.isBlank() -> "Film, dizi veya kanal adını yaz. Bütün kayıtlı kaynaklar taranır."
                    sidebarMode == "search" -> "Başka bir kelime deneyebilir veya yazımı kontrol edebilirsin."
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
        val groups: List<Pair<String, List<MediaItem>>> = if (expandedCategory != null) {
            listOf(expandedCategory!!)
        } else if (selectedType == "Canlı TV") {
            emptyList()
        } else if (sidebarMode == "search") {
            if (query.isBlank()) emptyList()
            else visible.take(240).groupBy { it.type }.map { (type, entries) -> "$type • Arama sonuçları" to entries }
        } else if (selectedType == "Tümü") {
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
            ).filter { it.second.isNotEmpty() }
        } else if (selectedType == "Filmler" || selectedType == "Diziler") {
            // Trend rows are shown on the home screen only; category screens list categories directly.
            val source = visible.filter { it.type == selectedType }
            source.groupBy { it.group.ifBlank { "Diğer" } }.map { it.key to it.value }
                .filter { it.second.isNotEmpty() }
        } else if (selectedType == "Son İzlenenler") {
            listOf("Diziler" to visible.filter { it.type == "Diziler" },
                "Filmler" to visible.filter { it.type == "Filmler" },
                "Canlı TV" to visible.filter { it.type == "Canlı TV" })
                .filter { it.second.isNotEmpty() }
        } else {
            visible.groupBy {
                if (selectedType == "Favoriler") it.type
                else it.group.ifBlank { "Diğer" }
            }.map { it.key to it.value }
        }
        // Xtream sources can contain hundreds of categories. Building poster cards for every
        // category at once can exhaust memory on older Android TV boxes. Keep the first
        // 12 category rows on screen; every category remains accessible from the sidebar
        // and selecting one shows that category's items.
        val groupsToRender = if (selectedType == "Filmler" || selectedType == "Diziler") groups.take(6) else groups
        groupsToRender.forEach { (groupName, entries) ->
            val showingExpandedCategory = expandedCategory?.first == groupName
            val sectionHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2, 16, 2, 8)
            }
            sectionHeader.addView(TextView(this).apply {
                text = groupName; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            sectionHeader.addView(TextView(this).apply {
                text = if (showingExpandedCategory) "← Geri dön" else "${entries.size} içerik"
                textSize = 12f; setTextColor(accent)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                if (showingExpandedCategory) setOnClickListener { expandedCategory = null; render() }
            })
            if (!showingExpandedCategory && entries.size > 5 && (selectedType == "Filmler" || selectedType == "Diziler")) {
                sectionHeader.addView(TextView(this).apply {
                    text = "Tümü"; textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.TRANSPARENT); setStroke(dp(1), accent)
                    }
                    isFocusable = true
                    setPadding(dp(16), dp(7), dp(16), dp(7))
                    setOnFocusChangeListener { v, hasFocus ->
                        v.background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(if (hasFocus) Color.rgb(43, 49, 63) else Color.TRANSPARENT)
                            setStroke(dp(2), if (hasFocus) accentBright else accent)
                        }
                    }
                    setOnClickListener { expandedCategory = groupName to entries; render() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { setMargins(dp(10), 0, 0, 0) })
            }
            content.addView(sectionHeader)
            val row = LinearLayout(this).apply { orientation = if (showingExpandedCategory) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
            val weeklyMovieFeature = selectedType == "Tümü" && (groupName.contains("Haftanın Filmleri") || groupName.contains("Haftanın Dizileri"))
            // Ana ekranda haftanın filmleri tek tek geniş, afiş odaklı kartlar olarak gösterilir.
            // Yatay kaydırma ile bir sonraki tam genişlikte afişe geçilir.
            val featureCardWidth = dp(140) // Kart genişliği afişle aynı
            val displayedEntries = if (showingExpandedCategory) entries else entries.take(5)
            var expandedPosterRow: LinearLayout? = null
            var expandedPosterCount = 0
            displayedEntries.forEach { item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(if (weeklyMovieFeature) 0 else 7, if (weeklyMovieFeature) 0 else 7, if (weeklyMovieFeature) 0 else 7, if (weeklyMovieFeature) 0 else 9)
                    background = roundedDrawable(panel, 15, Color.rgb(38, 45, 60))
                    isFocusable = true; isFocusableInTouchMode = true
                    setOnFocusChangeListener { v, hasFocus ->
                        v.background = roundedDrawable(if (hasFocus) Color.rgb(31, 39, 54) else panel, 15,
                            if (hasFocus) accent else Color.rgb(38, 45, 60))
                        v.scaleX = if (hasFocus) 1.035f else 1f; v.scaleY = if (hasFocus) 1.035f else 1f
                    }
                    setOnClickListener {
                        // Search results for live channels should start playback immediately.
                        if (sidebarMode == "search" && item.type == "Canlı TV") {
                            play(item)
                        } else {
                            when (item.type) {
                                "Diziler" -> showSeriesDetail(item)
                                "Filmler" -> showMovieDetail(item)
                                else -> showItemDetails(item)
                            }
                        }
                    }
                    setOnLongClickListener {
                        // Long-press a live-channel search result to toggle its favorite state.
                        if (sidebarMode == "search" && item.type == "Canlı TV") {
                            val added = if (favorites.contains(item.favoriteKey)) {
                                favorites.remove(item.favoriteKey)
                                false
                            } else {
                                favorites.add(item.favoriteKey)
                                true
                            }
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                            Toast.makeText(this@MainActivity,
                                if (added) "${item.name} favorilere eklendi" else "${item.name} favorilerden çıkarıldı",
                                Toast.LENGTH_SHORT).show()
                            true
                        } else {
                            false
                        }
                    }
                }
                val liveCard = item.type == "Canlı TV"
                val homeCard = selectedType == "Tümü"
                val weeklyPoster = weeklyMovieFeature && !showingExpandedCategory
                val poster = ImageView(this).apply {
                    scaleType = if (liveCard || weeklyPoster) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    contentDescription = item.name
                    background = roundedDrawable(Color.rgb(31, 37, 50), 10)
                    setPadding(if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0)
                    // Keep the poster's portrait aspect ratio; the card wraps the poster instead of stretching it.
                    layoutParams = LinearLayout.LayoutParams(
                        if (weeklyPoster) dp(140) else -1,
                        if (liveCard) 112 else if (weeklyPoster) dp(210) else if (showingExpandedCategory) 190 else if (homeCard) 230 else 210
                    )
                }
                card.addView(poster)
                if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) loadPoster(item.icon, poster)
                else poster.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(43, 51, 68), Color.rgb(17, 22, 33))))
                if (weeklyPoster) {
                    val metaRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    metaRow.addView(TextView(this).apply {
                        text = item.type.uppercase()
                        textSize = 10f; setTextColor(accent); letterSpacing = 0.03f
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    metaRow.addView(TextView(this).apply {
                        text = if (favorites.contains(item.favoriteKey)) "♥" else "♡"
                        textSize = 22f; setTextColor(if (favorites.contains(item.favoriteKey)) accent else Color.WHITE)
                        gravity = Gravity.CENTER
                        isFocusable = true
                        setOnClickListener {
                            if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                            render()
                        }
                    }, LinearLayout.LayoutParams(dp(30), dp(30)))
                    card.addView(metaRow)
                } else {
                    card.addView(TextView(this).apply {
                        text = if (item.type == "Canlı TV") "●  CANLI YAYIN" else item.type.uppercase()
                        textSize = 10f; setTextColor(accent); setPadding(3, 8, 2, 4); letterSpacing = 0.05f
                    })
                }
                card.addView(TextView(this).apply {
                    text = item.name; textSize = 14f; setTextColor(Color.WHITE); maxLines = 2
                    minHeight = 40; setPadding(if (weeklyPoster) 0 else 3, 0, if (weeklyPoster) 0 else 3, 0)
                })
                if (!weeklyPoster) {
                    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    if (!homeCard && selectedType != "Filmler" && selectedType != "Diziler") actions.addView(makeButton("▶ İzle", true) { play(item) },
                        LinearLayout.LayoutParams(0, 36, 1f).apply { setMargins(0, 7, 4, 0) })
                    actions.addView(makeButton(if (favorites.contains(item.favoriteKey)) "★" else "☆", false) {
                        if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
                        prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                        render()
                    }, LinearLayout.LayoutParams(43, 36).apply { setMargins(4, 7, 0, 0) })
                    card.addView(actions)
                }
                if (showingExpandedCategory) {
                    if (expandedPosterCount % 4 == 0) {
                        expandedPosterRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                        }
                        row.addView(expandedPosterRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    expandedPosterRow?.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(3), dp(4), dp(9))
                    })
                    expandedPosterCount++
                } else {
                    row.addView(card, LinearLayout.LayoutParams(if (liveCard) 190 else if (weeklyMovieFeature) featureCardWidth else 232, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, if (weeklyMovieFeature) dp(18) else 16, 10)
                    })
                }
            }
            if (showingExpandedCategory) {
                content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                content.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row)
                }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
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

    private fun renderChannelPreviewScreen() {
        val title = TextView(this).apply {
            text = "CANLI TV ÖNİZLEME  •  OK: TAM EKRAN"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        content.addView(title)
        val frame = FrameLayout(this).apply {
            background = roundedDrawable(Color.BLACK, 16, Color.rgb(48, 58, 76))
            clipToOutline = true
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "Canlı TV önizlemesi"
            setOnFocusChangeListener { v, hasFocus ->
                v.background = roundedDrawable(
                    Color.BLACK, 16,
                    if (hasFocus) accentBright else Color.rgb(48, 58, 76)
                )
            }
        }
        previewView = PlayerView(this).apply {
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        content.addView(frame, LinearLayout.LayoutParams(-1, dp(330)).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        content.addView(TextView(this).apply {
            text = "OK: Tam ekran izle  •  Tam ekranda ↑/↓ kanal değiştir, ← kanal listesi, → son izlenenler"
            textSize = 14f
            setTextColor(muted)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        if (previewPlayer != null) previewView?.player = previewPlayer
    }

    private fun startChannelPreview(channel: MediaItem) {
        previewChannel = channel
        if (channel.url.isBlank() || previewUrl == channel.url) return
        previewUrl = channel.url
        try {
            if (previewPlayer == null) {
                previewPlayer = ExoPlayer.Builder(this).build().apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_OFF
                    playWhenReady = true
                }
            }
            previewView?.player = previewPlayer
            previewPlayer?.setMediaItem(PlayerMediaItem.fromUri(channel.url))
            previewPlayer?.prepare()
            previewPlayer?.playWhenReady = true
        } catch (_: Exception) {
            previewUrl = ""
        }
    }

    private fun stopChannelPreview() {
        previewPlayer?.pause()
        previewView?.player = null
        previewView = null
        previewChannel = null
        previewUrl = ""
    }

    private fun showMovieDetail(movie: MediaItem) {
        detailSeries = null
        detailMovie = movie
        render()
        if (movie.sourceId.isBlank() || (movie.plot.isNotBlank() && movie.rating.isNotBlank())) return
        val source = savedSources.firstOrNull { it.id == movie.sourceId && it.mode == "xtream" } ?: return
        thread {
            try {
                val u = URLEncoder.encode(source.username, "UTF-8")
                val p = URLEncoder.encode(source.password, "UTF-8")
                val api = "${source.address.trimEnd('/')}/player_api.php?username=$u&password=$p&action=get_vod_info&vod_id=${URLEncoder.encode(movie.id, "UTF-8")}"
                val root = JSONObject(request(api))
                val info = root.optJSONObject("info") ?: root.optJSONObject("movie_data") ?: root
                fun pick(vararg keys: String): String {
                    for (key in keys) {
                        val value = info.opt(key)
                        if (value != null && value != JSONObject.NULL) {
                            val valueText = if (value is String) value else value.toString()
                            if (valueText.isNotBlank() && valueText != "0" && valueText != "null") return valueText
                        }
                    }
                    return ""
                }
                val updated = movie.copy(
                    plot = pick("plot", "description", "overview").ifBlank { movie.plot },
                    rating = pick("rating", "vote_average", "rating_5based").ifBlank { movie.rating },
                    releaseDate = pick("releasedate", "release_date", "releaseDate", "year").ifBlank { movie.releaseDate },
                    genre = pick("genre", "genres").ifBlank { movie.genre },
                    actors = pick("cast", "actors").ifBlank { movie.actors }
                )
                runOnUiThread {
                    // Ignore late metadata responses if the user has already returned to the movie list.
                    if (detailMovie?.favoriteKey != movie.favoriteKey) return@runOnUiThread
                    val index = items.indexOfFirst { it.id == movie.id && it.type == movie.type && it.sourceId == movie.sourceId }
                    if (index >= 0) items[index] = updated
                    detailMovie = updated
                    saveCachedItems()
                    render()
                }
            } catch (_: Exception) { }
        }
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
            // Keep text and buttons clear of the TV's right-edge system overlay.
            setPadding(dp(18), dp(18), dp(64), dp(18))
        }
        copy.addView(TextView(this).apply {
            text = "FİLM  •  ${movie.group.ifBlank { "DİĞER" }.uppercase()}"
            textSize = 12f; setTextColor(accentBright); letterSpacing = 0.08f
        })
        copy.addView(TextView(this).apply {
            text = movie.name; textSize = 26f; maxLines = 2
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            maxWidth = dp(760)
            setPadding(0, 8, 0, 6)
        })
        val metadata = listOf(movie.releaseDate, movie.genre, movie.rating.takeIf { it.isNotBlank() }?.let { "Puan $it" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString("   •   ")
        if (metadata.isNotBlank()) copy.addView(TextView(this).apply {
            text = metadata; textSize = 13f; setTextColor(accentBright)
            maxWidth = dp(760); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 8)
        })
        copy.addView(TextView(this).apply {
            text = movie.plot.ifBlank { "Film bilgileri kaynakta bulunmuyorsa doğrudan izleyebilirsin." }
            textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(760)
            setTextColor(Color.rgb(226, 230, 238))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(makeButton("▶  FİLMİ İZLE", true) { play(movie) }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(132), 44).apply { setMargins(0, 8, dp(8), 0) })
        val isFavorite = favorites.contains(movie.favoriteKey)
        val favoriteButton = makeButton(if (isFavorite) "★ Favorilerden çıkar" else "☆ Favorilere ekle", false) {
            if (!favorites.add(movie.favoriteKey)) favorites.remove(movie.favoriteKey)
            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
            render()
        }.apply {
            gravity = Gravity.CENTER
            val yellow = Color.rgb(255, 205, 55)
            fun updateFavoriteAppearance(hasFocus: Boolean) {
                if (isFavorite) {
                    // Keep the entire active button yellow even while focused.
                    setTextColor(Color.rgb(25, 25, 25))
                    background = roundedDrawable(yellow, 10, if (hasFocus) accentBright else yellow)
                } else {
                    // Transparent when inactive; focus only adds a blue outline.
                    setTextColor(Color.WHITE)
                    background = roundedDrawable(Color.TRANSPARENT, 10,
                        if (hasFocus) accentBright else Color.rgb(88, 96, 110))
                }
            }
            updateFavoriteAppearance(hasFocus = false)
            setOnFocusChangeListener { v, hasFocus ->
                updateFavoriteAppearance(hasFocus)
                v.scaleX = if (hasFocus) 1.035f else 1f
                v.scaleY = if (hasFocus) 1.035f else 1f
            }
        }
        actions.addView(favoriteButton, LinearLayout.LayoutParams(dp(160), 44).apply { setMargins(0, 8, 0, 0) })
        copy.addView(actions)
        hero.addView(copy, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(340)).apply { setMargins(0, 0, 0, dp(18)) })

        val infoTitle = TextView(this).apply {
            text = "FİLM HAKKINDA"; textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(4, 0, 0, 8)
        }
        content.addView(infoTitle)
        if (movie.actors.isNotBlank()) content.addView(TextView(this).apply {
            text = "Oyuncular: ${movie.actors}"; textSize = 14f; setTextColor(muted)
            maxWidth = dp(760)
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4, 0, 0, 12)
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

    private fun renderSettingsScreen() {
        mainHeader.visibility = View.GONE
        status.visibility = View.GONE
        categoryScroll.visibility = View.GONE
        mainContentScroll.visibility = View.VISIBLE
        content.removeAllViews()
        fun heading(title: String, subtitle: String) {
            content.addView(TextView(this).apply {
                text = title; textSize = 27f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE); setPadding(dp(8), dp(16), dp(8), dp(4))
            })
            content.addView(TextView(this).apply {
                text = subtitle; textSize = 14f; setTextColor(muted); setPadding(dp(8), 0, dp(8), dp(16))
            })
        }
        fun option(title: String, detail: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
                background = roundedDrawable(panel, 14, Color.rgb(48, 57, 73))
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    // Focus uses a gold outline; blue is reserved for the selected section.
                    v.background = roundedDrawable(
                        panel,
                        14,
                        if (hasFocus) Color.rgb(255, 190, 55) else Color.rgb(48, 57, 73)
                    )
                }
                setOnClickListener { action() }
            }
            row.addView(TextView(this).apply {
                text = title; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            })
            row.addView(TextView(this).apply { text = detail; textSize = 13f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
            content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(6), dp(5), dp(6), dp(7))
            })
        }
        if (settingsPage != "main") content.addView(makeButton("← Ayarlara dön", false) {
            settingsPage = "main"; rebuildNavigation(); render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { setMargins(dp(6), dp(6), 0, dp(8)) })
        when (settingsPage) {
            "main" -> {
                heading("Ayarlar", "Bir bölümü seç; ayrı ekranda açılacak.")
                option("＋  Kaynaklar", "Kaynak ekle, kayıtlı kaynakları yenile veya sil") { settingsPage = "sources"; render() }
                option("▤  Kategoriler", "Film ve dizi kategorilerini görüntüle") { settingsPage = "categories"; render() }
                option("◐  Temalar", "Uygulamanın renk görünümünü değiştir") { settingsPage = "themes"; render() }
                option("◷  Geçmiş", "İzlediğin dizileri ve filmleri görüntüle") { settingsPage = "history"; render() }
            }
            "sources" -> {
                heading("Kaynaklar", "Kaynak ekle ve kayıtlı bağlantılarını buradan yönet.")
                option("＋  Yeni kaynak ekle", "M3U/M3U8 veya Xtream Codes") { showConnectionDialog() }
                if (savedSources.isEmpty()) {
                    option("Henüz kaynak eklenmedi", "Yeni kaynak ekle düğmesini kullan.") { showConnectionDialog() }
                } else {
                    savedSources.toList().forEach { source ->
                        option("▣  ${source.name}", if (source.mode == "xtream") "Xtream Codes • ${source.address}" else "M3U/M3U8 • ${source.address}") {
                            AlertDialog.Builder(this).setTitle(source.name)
                                .setItems(arrayOf("Yenile", "Sil")) { _, action ->
                                    if (action == 0) {
                                        if (source.mode == "xtream") loadXtream(source, true) else loadM3u(source, true)
                                    } else {
                                        savedSources.removeAll { it.id == source.id }
                                        persistSources()
                                        items.removeAll { it.sourceId == source.id }
                                        render()
                                    }
                                }.setNegativeButton("İptal", null).show()
                        }
                    }
                }
            }
            "categories" -> {
                heading("Kategoriler", "TV, film ve dizi kategorilerini ayrı ayrı yönet")

                // Üç bölüm düğmesi: yalnızca seçilen türün kategori listesi gösterilir.
                val categoryTabs = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(10))
                }
                val tabInfo = listOf(
                    Triple("Canlı TV", "▣", "TV"),
                    Triple("Filmler", "▤", "Film"),
                    Triple("Diziler", "▱", "Dizi")
                )
                tabInfo.forEach { tab ->
                    val selected = selectedCategorySection == tab.first
                    val tabButton = TextView(this).apply {
                        text = "${tab.second}  ${tab.third}"
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(if (selected) Color.WHITE else muted)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        background = roundedDrawable(
                            if (selected) accentBright else panel,
                            12,
                            if (selected) accentBright else Color.rgb(48, 57, 73)
                        )
                        isFocusable = true
                        isClickable = true
                        setOnClickListener {
                            selectedCategorySection = tab.first
                            render()
                        }
                    }
                    categoryTabs.addView(tabButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
                content.addView(categoryTabs, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

                val sectionType = selectedCategorySection
                val sectionGroups = items.filter { it.type == sectionType }
                    .map { it.group.ifBlank { "Diğer" } }
                    .distinct()
                val categoryActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, dp(8))
                }
                val actionItems = listOf("Hepsini gizle", "Hepsini göster", "TR olanları göster")
                actionItems.forEach { actionLabel ->
                    val actionButton = TextView(this).apply {
                        text = actionLabel
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setPadding(dp(5), dp(11), dp(5), dp(11))
                        background = roundedDrawable(panel, 10, Color.rgb(48, 57, 73))
                        isFocusable = true
                        isClickable = true
                        setOnClickListener {
                            when (actionLabel) {
                                "Hepsini gizle" -> sectionGroups.forEach { group ->
                                    hiddenCategories.add(sectionType + "|" + group)
                                }
                                "Hepsini göster" -> sectionGroups.forEach { group ->
                                    hiddenCategories.remove(sectionType + "|" + group)
                                }
                                "TR olanları göster" -> sectionGroups.forEach { group ->
                                    if (group.contains("TR", ignoreCase = true)) {
                                        hiddenCategories.remove(sectionType + "|" + group)
                                    } else {
                                        hiddenCategories.add(sectionType + "|" + group)
                                    }
                                }
                            }
                            prefs.edit().putStringSet("hidden_categories", hiddenCategories.toSet()).apply()
                            render()
                        }
                    }
                    categoryActions.addView(actionButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
                content.addView(categoryActions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                val rows = items.filter { it.type == sectionType }
                    .map { Pair(Pair(it.type, it.group.ifBlank { "Diğer" }), it) }
                    .groupBy({ it.first }, { it.second })
                val sectionRows = rows.toList().sortedBy { it.first.second }
                if (sectionRows.isNotEmpty()) {
                    sectionRows.forEach { (key, entries) ->
                        val hiddenKey = key.first + "|" + key.second
                        val isHidden = hiddenCategories.contains(hiddenKey)
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(dp(18), dp(14), dp(18), dp(14))
                            background = roundedDrawable(panel, 14, Color.rgb(48, 57, 73))
                            isFocusable = true
                            isClickable = true
                            setOnClickListener {
                                if (hiddenCategories.contains(hiddenKey)) hiddenCategories.remove(hiddenKey)
                                else hiddenCategories.add(hiddenKey)
                                prefs.edit().putStringSet("hidden_categories", hiddenCategories.toSet()).apply()
                                render()
                            }
                        }
                        val indicator = TextView(this).apply {
                            text = if (isHidden) "◯" else "⦿"
                            textSize = 25f
                            setTextColor(if (isHidden) Color.WHITE else accentBright)
                            gravity = android.view.Gravity.CENTER
                        }
                        row.addView(indicator, LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.WRAP_CONTENT))
                        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        labels.addView(TextView(this@MainActivity).apply {
                            text = "${key.second}  ·  ${entries.size}"
                            textSize = 17f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        labels.addView(TextView(this@MainActivity).apply {
                            text = if (isHidden) "Gizli — seçerek göster" else "Gösteriliyor — seçerek gizle"
                            textSize = 13f
                            setTextColor(muted)
                            setPadding(0, dp(5), 0, 0)
                        })
                        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(dp(6), dp(5), dp(6), dp(7))
                        })
                    }
                } else {
                    content.addView(TextView(this).apply {
                        text = when (sectionType) {
                            "Canlı TV" -> "Gösterilecek TV kategorisi yok."
                            "Filmler" -> "Gösterilecek film kategorisi yok."
                            else -> "Gösterilecek dizi kategorisi yok."
                        }
                        textSize = 16f
                        setTextColor(muted)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    })
                }
            }
            "themes" -> {
                heading("Temalar", "Renk seçimi anında uygulanır.")
                val themes = listOf(
                    Triple("Antrasit • Mavi", Color.rgb(17, 20, 25), Color.rgb(0, 166, 255)),
                    Triple("Gece • Altın", Color.rgb(15, 16, 20), Color.rgb(224, 184, 95)),
                    Triple("Lacivert • Turkuaz", Color.rgb(10, 22, 38), Color.rgb(35, 205, 190)),
                    Triple("Koyu • Mor", Color.rgb(20, 16, 29), Color.rgb(174, 115, 255))
                )
                themes.forEach { theme ->
                    option(theme.first, "Zemin ve vurgu rengi") {
                        bg = theme.second
                        panel = Color.rgb((Color.red(bg) + 8).coerceAtMost(255), (Color.green(bg) + 10).coerceAtMost(255), (Color.blue(bg) + 14).coerceAtMost(255))
                        accent = theme.third; accentBright = theme.third
                        prefs.edit().putString("theme_name", theme.first).apply()
                        rootLayout.setBackgroundColor(bg); sidebar.setBackgroundColor(bg); mainContentScroll.setBackgroundColor(bg)
                        window.statusBarColor = bg; window.navigationBarColor = bg
                        settingsPage = "themes"; render()
                    }
                }
            }
            "history" -> {
                heading("Geçmiş", "Türü seçerek izleme geçmişini görüntüle.")
                val historyOptions = listOf("Canlı TV", "Filmler", "Diziler")
                val picker = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                historyOptions.forEach { type ->
                    picker.addView(makeButton(when (type) { "Canlı TV" -> "▣  TV"; "Filmler" -> "▰  Filmler"; else -> "▤  Diziler" }, historyType == type) {
                        historyType = type; render()
                    }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(8)) })
                }
                content.addView(picker)
                val entries = recent.filter { it.type == historyType }
                content.addView(TextView(this).apply {
                    text = (when (historyType) { "Canlı TV" -> "İzlenen TV kanalları"; "Filmler" -> "İzlenen filmler"; else -> "İzlenen diziler" }) + "  ·  " + entries.size
                    textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(accentBright); setPadding(dp(8), dp(12), dp(8), dp(6))
                })
                if (entries.isEmpty()) content.addView(TextView(this).apply {
                    text = "Bu türde henüz izleme geçmişi yok."; textSize = 14f; setTextColor(muted)
                    setPadding(dp(8), dp(5), dp(8), dp(12))
                })
                entries.forEach { item ->
                    option(item.name, item.group.ifBlank { item.type }) {
                        if (item.type == "Diziler" && item.url.isBlank()) showSeriesDetail(item)
                        else play(item)
                    }
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
                // Decode poster thumbnails at a reduced resolution. Some Mi TV Stick
                // devices run out of memory when provider artwork is decoded at full size.
                val boundsConn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    instanceFollowRedirects = true
                }
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    boundsConn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                } finally {
                    boundsConn.disconnect()
                }
                if (options.outWidth <= 0 || options.outHeight <= 0) return@execute
                var sampleSize = 1
                while (options.outWidth / sampleSize > 800 || options.outHeight / sampleSize > 800) {
                    sampleSize *= 2
                }
                val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    instanceFollowRedirects = true
                }
                val bitmap = try {
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions) }
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
        previewPlayer?.release()
        previewPlayer = null
        posterExecutor.shutdownNow()
        super.onDestroy()
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
        // Release the muted preview decoder before opening the full-screen player.
        // Keeping two ExoPlayer instances active can crash low-memory Android TV sticks.
        previewView?.player = null
        previewPlayer?.release()
        previewPlayer = null
        previewView = null
        previewChannel = null
        previewUrl = ""
        saveRecent(item)
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra("name", item.name)
            .putExtra("url", item.url)
        if (item.type == "Canlı TV") {
            val channels = items.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
            intent.putStringArrayListExtra("channel_names", ArrayList(channels.map { it.name }))
            intent.putStringArrayListExtra("channel_urls", ArrayList(channels.map { it.url }))
            intent.putExtra("channel_index", channels.indexOfFirst { it.url == item.url }.coerceAtLeast(0))
            val recentChannels = recent.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
                .distinctBy { it.url }.take(20)
            intent.putStringArrayListExtra("recent_channel_names", ArrayList(recentChannels.map { it.name }))
            intent.putStringArrayListExtra("recent_channel_urls", ArrayList(recentChannels.map { it.url }))
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
