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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.WeakReference

private data class SavedSource(val id: String, val name: String, val mode: String, val address: String, val username: String = "", val password: String = "")

private data class MediaItem(
    val id: String, val name: String, val url: String, val group: String,
    val type: String, val icon: String = "", val extension: String = "", val seriesId: String = "", val sourceId: String = "", val plot: String = "", val actors: String = "", val rating: String = "", val releaseDate: String = "", val genre: String = ""
) {
    val favoriteKey: String get() = "$type:$id:$url"
}

class MainActivity : Activity() {
    private val posterExecutor = Executors.newFixedThreadPool(4)
    private val posterCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val items = mutableListOf<MediaItem>()
    private val posterRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>>()
    private val savedSources = mutableListOf<SavedSource>()
    private val homeSourceIds = mutableSetOf<String>()
    private lateinit var sourceName: EditText
    private val favorites = mutableSetOf<String>()
    private val recent = mutableListOf<MediaItem>()
    private var selectedGroup = "Tüm kategoriler"
    private var selectedCategorySection = "Canlı TV"
    // Render live channels in small batches to avoid first-open freezes on Android TV.
    private var liveChannelsLimit = 72
    private var expandedCategory: Pair<String, List<MediaItem>>? = null
    private lateinit var categoryBar: LinearLayout
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var mainHeader: LinearLayout
    private lateinit var content: LinearLayout
    private var detailSeries: MediaItem? = null
    private var detailMovie: MediaItem? = null
    private var seriesEpisodeRows: List<Pair<String, List<MediaItem>>> = emptyList()
    private var selectedSeriesSeason: String = ""
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
    private var bg = Color.BLACK
    private var panel = Color.rgb(25, 30, 38)
    private var accent = Color.rgb(0, 166, 255)
    private var accentBright = Color.rgb(72, 190, 255)
    // Focus styling is independent from the selectable app theme.
    private val focusColor: Int = Color.rgb(0, 166, 255)
    private var muted = Color.rgb(190, 198, 210)
    // Sidebar transparency is independent from the selected color theme.
    private var transparentMenu = true
    private var sidebarTransparency = 100
    private var buttonRadiusDp = 10
    private var buttonSelectionStyle = "Dolu"
    private var customAccentColor: Int? = Color.rgb(255, 112, 79)

    private fun sidebarBackgroundColor(): Int = Color.argb(255 * (100 - sidebarTransparency) / 100, Color.red(panel), Color.green(panel), Color.blue(panel))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        transparentMenu = prefs.getBoolean("transparent_menu", true)
        loadSearchHistory()
        sidebarTransparency = prefs.getInt("sidebar_transparency", if (transparentMenu) 100 else 0)
        buttonRadiusDp = prefs.getInt("button_radius_dp", 10)
        buttonSelectionStyle = prefs.getString("button_selection_style", "Dolu") ?: "Dolu"
        // Restore the selected theme before constructing any views.
        when (prefs.getString("theme_name", "Siyah • Mavi")) {
            "Gece • Altın" -> { bg = Color.rgb(15, 16, 20); accent = Color.rgb(224, 184, 95); accentBright = accent }
            "Lacivert • Turkuaz" -> { bg = Color.rgb(10, 22, 38); accent = Color.rgb(35, 205, 190); accentBright = accent }
            "Koyu • Mor" -> { bg = Color.rgb(20, 16, 29); accent = Color.rgb(174, 115, 255); accentBright = accent }
            "Siyah • Mavi" -> { bg = Color.BLACK; accent = Color.rgb(0, 166, 255); accentBright = accent }
            else -> { bg = Color.BLACK; accent = Color.rgb(0, 166, 255); accentBright = accent }
        }
        panel = Color.rgb((Color.red(bg) + 8).coerceAtMost(255), (Color.green(bg) + 10).coerceAtMost(255), (Color.blue(bg) + 14).coerceAtMost(255))
        val savedAccent = prefs.getInt("custom_accent_color", Color.rgb(0, 166, 255))
        // Migrate the previous coral default to the new blue default.
        val defaultCoral = Color.rgb(255, 112, 79)
        customAccentColor = if (savedAccent == defaultCoral) Color.rgb(0, 166, 255) else savedAccent
        if (savedAccent == defaultCoral) prefs.edit().putInt("custom_accent_color", Color.rgb(0, 166, 255)).apply()
        customAccentColor?.let { accent = it; accentBright = it }
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
        // Restore the last section instead of overwriting it with Live TV on every launch.
        val allowedSections = setOf("Tümü", "Canlı TV", "Filmler", "Diziler", "Favoriler", "Son İzlenenler", "Ayarlar")
        selectedType = prefs.getString("last_section", "Canlı TV")
            ?.takeIf { it in allowedSections } ?: "Canlı TV"
        selectedGroup = prefs.getString("last_group", "Tüm kategoriler") ?: "Tüm kategoriler"
        buildUi()
        showPreviousCrashIfAny()
        loadSavedSources()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(writer))
                getSharedPreferences("androtv_diagnostics", MODE_PRIVATE).edit()
                    .putString("last_crash", writer.toString().take(12000))
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .commit()
            } catch (_: Exception) { }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showPreviousCrashIfAny() {
        val diagnostics = getSharedPreferences("androtv_diagnostics", MODE_PRIVATE)
        val trace = diagnostics.getString("last_crash", null) ?: return
        diagnostics.edit().remove("last_crash").apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("Önceki kanal açılış hatası")
            .setMessage(trace.take(3500))
            .setPositiveButton("Kopyala") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("KaiserTv hata kaydı", trace))
                android.widget.Toast.makeText(this, "Hata kaydı kopyalandı; buraya yapıştır.", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
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
    private val searchHistory = mutableListOf<String>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRenderRunnable: Runnable? = null

    private fun saveSearchHistory(query: String) {
        val value = query.trim()
        if (value.isBlank()) return
        searchHistory.remove(value)
        searchHistory.add(0, value)
        while (searchHistory.size > 12) searchHistory.removeAt(searchHistory.lastIndex)
        val json = JSONArray()
        searchHistory.forEach { json.put(it) }
        prefs.edit().putString("search_history", json.toString()).apply()
    }

    private fun loadSearchHistory() {
        searchHistory.clear()
        try {
            val json = JSONArray(prefs.getString("search_history", "[]") ?: "[]")
            for (i in 0 until json.length()) {
                val value = json.optString(i).trim()
                if (value.isNotBlank() && value !in searchHistory) searchHistory.add(value)
            }
        } catch (_: Exception) { }
    }
    private var isLoadingSource = false
    private var loadProgress = 0
    private var hasLoadedSource = false

    private fun isDescendantView(parent: ViewGroup, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun buildUi() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = object : LinearLayout(this) {
            private fun centerOnScreen(view: View): Pair<Float, Float> {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                return Pair(location[0] + view.width / 2f, location[1] + view.height / 2f)
            }

            private fun isAhead(from: View, to: View, direction: Int): Boolean {
                val (fx, fy) = centerOnScreen(from)
                val (tx, ty) = centerOnScreen(to)
                return when (direction) {
                    View.FOCUS_LEFT -> tx < fx - 1f
                    View.FOCUS_RIGHT -> tx > fx + 1f
                    View.FOCUS_UP -> ty < fy - 1f
                    View.FOCUS_DOWN -> ty > fy + 1f
                    else -> false
                }
            }

            private fun directionalFallback(focused: View, direction: Int, sidebarOnly: Boolean?): View? {
                val (fx, fy) = centerOnScreen(focused)
                val candidates = ArrayList<View>()
                fun collect(parent: ViewGroup) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child.isShown && child.isEnabled && child.isFocusable &&
                            child.width > 0 && child.height > 0 && child !== focused) {
                            val inSidebar = ::sidebar.isInitialized && isDescendantView(sidebar, child)
                            if (sidebarOnly == null || inSidebar == sidebarOnly) candidates.add(child)
                        }
                        if (child is ViewGroup) collect(child)
                    }
                }
                collect(this)
                return candidates.mapNotNull { candidate ->
                    val (x, y) = centerOnScreen(candidate)
                    val primary = when (direction) {
                        View.FOCUS_LEFT -> fx - x
                        View.FOCUS_RIGHT -> x - fx
                        View.FOCUS_UP -> fy - y
                        View.FOCUS_DOWN -> y - fy
                        else -> -1f
                    }
                    if (primary <= 1f) null else {
                        val secondary = if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                            kotlin.math.abs(y - fy)
                        } else kotlin.math.abs(x - fx)
                        candidate to (primary + secondary * 2.2f)
                    }
                }.minByOrNull { it.second }?.first
            }

            override fun focusSearch(focused: View, direction: Int): View? {
                val focusIsInSidebar = ::sidebar.isInitialized && isDescendantView(sidebar, focused)
                val focusIsInContent = ::mainContentScroll.isInitialized && isDescendantView(mainContentScroll, focused)
                val candidate = super.focusSearch(focused, direction)
                val crossingAllowed = (focusIsInSidebar && direction == View.FOCUS_RIGHT) ||
                    (focusIsInContent && direction == View.FOCUS_LEFT)
                val candidateIsInSidebar = candidate != null && ::sidebar.isInitialized &&
                    isDescendantView(sidebar, candidate)
                val crossesBoundary = (focusIsInSidebar && candidate != null && !candidateIsInSidebar) ||
                    (focusIsInContent && candidateIsInSidebar)
                if (crossesBoundary && !crossingAllowed) {
                    // Keep navigation in its current area unless the user presses the
                    // direction that explicitly crosses between sidebar and content.
                    return directionalFallback(focused, direction, focusIsInSidebar) ?: focused
                }
                if (candidate != null && candidate.isShown && candidate.isFocusable &&
                    isAhead(focused, candidate, direction) &&
                    (!crossesBoundary || crossingAllowed)) return candidate

                val sidebarOnly = when {
                    focusIsInSidebar && direction != View.FOCUS_RIGHT -> true
                    focusIsInContent && direction != View.FOCUS_LEFT -> false
                    else -> null
                }
                return directionalFallback(focused, direction, sidebarOnly) ?: candidate ?: focused
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18, 14, 18, 12)
            setBackgroundColor(bg)
        }

        rootLayout = root
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 12, 8, 10)
            background = android.graphics.drawable.ColorDrawable(sidebarBackgroundColor())
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
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(2), dp(30), dp(12)); clipToPadding = false }
        mainContentScroll.addView(content)
        main.addView(mainContentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (!::sidebar.isInitialized || newFocus == null) return@addOnGlobalFocusChangeListener
            var node: android.view.ViewParent? = newFocus.parent
            var insideSidebar = newFocus === sidebar
            while (node != null && !insideSidebar) {
                if (node === sidebar) insideSidebar = true else node = node.parent
            }
            if (sidebarMode == "context" && !insideSidebar && sidebar.visibility == View.VISIBLE) {
                sidebar.visibility = View.GONE
            } else if (insideSidebar && sidebar.visibility != View.VISIBLE) {
                sidebar.visibility = View.VISIBLE
            }
        }
        render()
    }

    private fun rebuildNavigation() {
        if (!::navBar.isInitialized) return
        // Keep the main navigation icon-only, but expand the sidebar when a
        // category list is open so full category names remain readable.
        if (::sidebar.isInitialized) {
            val params = sidebar.layoutParams
            val desiredWidth = dp(180)
            if (params != null && params.width != desiredWidth) {
                params.width = desiredWidth
                sidebar.layoutParams = params
            }
        }
        val previousFocusWasInSidebar = run {
            var node: android.view.ViewParent? = currentFocus?.parent
            var inside = currentFocus === sidebar
            while (node != null && !inside) { if (node === sidebar) inside = true else node = node.parent }
            inside
        }
        val previousSidebarIndex = if (previousFocusWasInSidebar) (0 until navBar.childCount).firstOrNull { navBar.getChildAt(it) === currentFocus } else null
        navBar.removeAllViews()

        fun addSidebarButton(label: String, selected: Boolean = false, action: () -> Unit) {
            navBar.addView(makeButton(label, selected, action).apply {
                // Selected navigation item is filled with the active theme accent.
                background = roundedDrawable(if (selected) accent else Color.TRANSPARENT, buttonRadiusDp,
                    if (selected) accent else Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = roundedDrawable(
                        if (hasFocus) accent else Color.TRANSPARENT,
                        buttonRadiusDp,
                        if (hasFocus) accent else Color.TRANSPARENT
                    )
                    (v as Button).setTextColor(Color.WHITE)
                    // Keep sidebar buttons at a fixed size on every screen.
                    // Scaling/elevation can leave vertical focus trails on some Android TV renderers.
                    v.elevation = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
                textSize = 14f
                val iconEnd = label.indexOf("  ").takeIf { it in 1..3 } ?: 1
                text = android.text.SpannableString(label).apply {
                    setSpan(android.text.style.RelativeSizeSpan(1.7f), 0, iconEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

        // Ana menü normal ekranlarda kalır; TV/Film/Dizi seçilince sol menü kategori listesine dönüşür.
        if (sidebarMode in listOf("main", "settings", "search")) {
            listOf("⌕  Ara", "⌂  Ana Sayfa", "▣  Canlı TV", "▶  Filmler", "▤  Diziler", "♡  Favoriler", "◷  Geçmiş", "⚙  Ayarlar").forEach { tab ->
                val normalizedTab = when {
                    tab.endsWith("Ara") -> "Ara"
                    tab.contains("Ana Sayfa") -> "Ana Sayfa"
                    tab.contains("Canlı TV") -> "TV"
                    tab.contains("Favoriler") -> "Favoriler"
                    tab.contains("Geçmiş") -> "Geçmiş"
                    tab.contains("Ayarlar") -> "Ayarlar"
                    tab.contains("Filmler") -> "Filmler"
                    else -> "Diziler"
                }
                val key = when (normalizedTab) {
                    "TV" -> "Canlı TV"
                    "Ana Sayfa" -> "Tümü"
                    else -> normalizedTab
                }
                if (normalizedTab == "Geçmiş") {
                    addSidebarButton(tab, selectedType == "Son İzlenenler") {
                        detailSeries = null
                        detailMovie = null
                        seriesEpisodeRows = emptyList()
                        seriesEpisodesLoading = false
                        seriesEpisodesError = ""
                        selectedType = "Son İzlenenler"
                        prefs.edit().putString("last_section", "Son İzlenenler").apply()
                        selectedGroup = "Tüm kategoriler"
                        expandedCategory = null
                        sidebarMode = "main"
                        rebuildNavigation()
                        render()
                    }
                } else if (normalizedTab == "Ayarlar") {
                    addSidebarButton(tab, selectedType == "Ayarlar" && settingsPage != "history") {
                        detailSeries = null
                        detailMovie = null
                        // Ayarlar menüsüne her girişte ana ayarlar kartlarını göster.
                        settingsPage = "main"
                        selectedType = "Ayarlar"
                        prefs.edit().putString("last_settings_page", "main").putString("last_section", "Ayarlar").apply()
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = "main"
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
                        sidebarMode = if (normalizedTab in listOf("TV", "Filmler", "Diziler")) "context" else "main"
                        prefs.edit().putString("last_section", key).putString("last_group", "Tüm kategoriler").apply()
                        rebuildNavigation()
                        render()
                    }
                }
                if (normalizedTab == "Ana Sayfa") {
                    // Visually separate the primary navigation from the utility items.
                }
            }
            if (sidebarMode == "main") return
        }

        if (sidebarMode == "context") {
            // Section mode: replace the main menu with compact, readable category names.
            val sectionItems = when (selectedType) {
                "Canlı TV", "Filmler", "Diziler" -> items.filter { it.type == selectedType }
                "Favoriler" -> items.filter { favorites.contains(it.favoriteKey) }
                "Son İzlenenler" -> recent.toList()
                else -> emptyList()
            }
            val categories = listOf("Tüm kategoriler") + sectionItems
                .map { it.group.ifBlank { "Diğer" } }
                .distinct()
                .sorted()
            categories.forEach { category ->
                navBar.addView(makeButton(category, selectedGroup == category) {
                    selectedGroup = category
                    expandedCategory = null
                    prefs.edit().putString("last_group", category).apply()
                    render()
                }.apply {
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), 0, dp(8), 0)
                    minHeight = dp(38)
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(0, dp(2), 0, dp(2)) })
            }
            return
        }

        if (sidebarMode == "search") {
            addSidebarButton("⌕  Ara", true) {
                searchInputFocusPending = true
                render()
            }
            addSidebarButton("⌂  Ana Sayfa") { returnToMainMenu() }
            return
        }

        // Kategori başlığını ve sol kategori listesini göstermiyoruz.
        // Ana menü seçenekleri ana menü modunda kalır; TV ekranındaki kanal listesi korunur.
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
        }
        // Keep the remote on the last sidebar destination instead of resetting to Search.
        navBar.post {
            if (previousFocusWasInSidebar && navBar.childCount > 0) {
                val restoreIndex = (previousSidebarIndex ?: when (selectedType) {
                    "Canlı TV" -> 2; "Filmler" -> 3; "Diziler" -> 4
                    "Favoriler" -> 5; "Son İzlenenler" -> 6; "Ayarlar" -> 7
                    else -> 1
                }).coerceIn(0, navBar.childCount - 1)
                navBar.getChildAt(restoreIndex)?.requestFocus()
            } else if (currentFocus == null && sidebarMode == "main" && navBar.childCount > 1) {
                val selectedIndex = when (selectedType) {
                    "Canlı TV" -> 2; "Filmler" -> 3; "Diziler" -> 4
                    "Favoriler" -> 5; "Son İzlenenler" -> 6; "Ayarlar" -> 7
                    else -> 1
                }.coerceIn(0, navBar.childCount - 1)
                navBar.getChildAt(selectedIndex)?.requestFocus()
            }
        }
    }

    private fun showConnectionDialog(editSource: SavedSource? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 4); setBackgroundColor(bg)
        }
        sourceName = edit("Kaynak adı (ör. Ev Xtream)", false).apply { setText(editSource?.name.orEmpty()) }
        form.addView(sourceName, fieldParams())
        sourceMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val m3u = RadioButton(this).apply {
            id = View.generateViewId(); text = "M3U / M3U8"; setTextColor(Color.WHITE); isChecked = true
        }
        val xtream = RadioButton(this).apply {
            id = View.generateViewId(); text = "Xtream Codes"; setTextColor(Color.WHITE)
        }
        sourceMode.addView(m3u); sourceMode.addView(xtream); form.addView(sourceMode)
        playlistUrl = edit("M3U / M3U8 oynatma listesi URL'si", false).apply { setText(if (editSource?.mode == "m3u") editSource.address else "") }
        server = edit("Sunucu adresi (https://...)", false).apply { setText(if (editSource?.mode == "xtream") editSource.address else "") }
        username = edit("Kullanıcı adı", false).apply { setText(editSource?.username.orEmpty()) }; password = edit("Şifre", true).apply { setText(editSource?.password.orEmpty()) }
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
        if (editSource?.mode == "xtream") sourceMode.check(xtream.id)
        val dialog = AlertDialog.Builder(this).setTitle(if (editSource == null) "İzleme kaynağı ekle" else "Kaynağı düzenle")
            .setMessage("Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle.")
            .setView(form).setNegativeButton("İptal", null).setPositiveButton(if (editSource == null) "BAĞLAN" else "KAYDET", null).create()
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
                    val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "xtream", server.text.toString().trim().trimEnd('/'), username.text.toString().trim(), password.text.toString())
                    saveSource(src); hideKeyboard(); dialog.dismiss(); loadXtream(src, editSource != null)
                } else {
                    val address = playlistUrl.text.toString().trim()
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, "Geçerli bir M3U/M3U8 URL'si gir.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val label = sourceName.text.toString().trim().ifBlank { "M3U ${savedSources.size + 1}" }
                    val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "m3u", address)
                    saveSource(src); hideKeyboard(); dialog.dismiss(); loadM3u(src, editSource != null)
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
    private fun makeFavoriteButton(item: MediaItem, onChanged: (() -> Unit)? = null): TextView {
        fun update(view: TextView, selected: Boolean, focused: Boolean = false) {
            val selectedYellow = Color.rgb(255, 196, 0)
            view.text = if (selected) "★ Favori" else "☆ Favori"
            // Keep the existing normal button appearance unchanged; only the selected state is yellow.
            view.setTextColor(if (selected) Color.rgb(25, 22, 12) else Color.WHITE)
            view.background = roundedDrawable(
                if (selected) selectedYellow else panel,
                buttonRadiusDp,
                if (selected) selectedYellow else if (focused) focusColor else Color.rgb(44, 52, 68)
            )
        }
        return TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(46)
            minimumHeight = dp(46)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(12), 0, dp(12), 0)
            update(this, favorites.contains(item.favoriteKey))
            setOnFocusChangeListener { v, hasFocus -> update(v as TextView, favorites.contains(item.favoriteKey), hasFocus) }
            setOnClickListener {
                val nowSelected = if (favorites.contains(item.favoriteKey)) {
                    favorites.remove(item.favoriteKey); false
                } else {
                    favorites.add(item.favoriteKey); true
                }
                prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                update(this, nowSelected, hasFocus())
                onChanged?.invoke()
            }
        }
    }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        includeFontPadding = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) Color.rgb(12, 14, 19) else Color.WHITE)
        background = roundedDrawable(if (primary) accent else panel, 12,
            if (primary) accentBright else Color.rgb(44, 52, 68))
        stateListAnimator = null
        setOnFocusChangeListener { v, hasFocus ->
            v.background = roundedDrawable(
                if (primary) accent else if (hasFocus) Color.rgb(27, 39, 53) else panel,
                12, if (hasFocus) focusColor else if (primary) accentBright else Color.rgb(44, 52, 68))
            v.elevation = 0f
            v.scaleX = 1f
            v.scaleY = 1f
        }
        setOnClickListener { action() }
    }
    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(playlistUrl.windowToken, 0) }


    private fun saveSource(source: SavedSource) {
        savedSources.removeAll { it.id == source.id }; savedSources.add(source)
        homeSourceIds.add(source.id)
        persistHomeSourceIds()
        prefs.edit().putString("last_source_id", source.id).apply()
        persistSources()
    }

    private fun persistHomeSourceIds() {
        prefs.edit().putStringSet("home_source_ids", homeSourceIds.toSet()).apply()
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
            if (prefs.contains("home_source_ids")) {
                homeSourceIds.clear()
                homeSourceIds.addAll(prefs.getStringSet("home_source_ids", emptySet()) ?: emptySet())
                homeSourceIds.retainAll(savedSources.map { it.id }.toSet())
            } else {
                homeSourceIds.clear()
                homeSourceIds.addAll(savedSources.map { it.id })
                persistHomeSourceIds()
            }
            val lastId = prefs.getString("last_source_id", "") ?: ""
            val last = savedSources.firstOrNull { it.id == lastId }

            // A live-only cache is incomplete: the Movies and Series tabs and
            // the home screen cannot render their rows from it. Reuse the last
            // saved source (or the most recently saved source as a fallback) to
            // repair the cache, while a complete cache opens instantly.
            val sourceToRestore = last ?: savedSources.lastOrNull()
            val cacheHasLiveChannels = items.any { it.type == "Canlı TV" && it.url.isNotBlank() }
            val cacheHasMovies = items.any { it.type == "Filmler" }
            val cacheHasSeries = items.any { it.type == "Diziler" }
            // A cache missing any major Xtream section must not be treated as complete.
            // In particular, otherwise TV can open into an empty "Tüm Kanallar" screen.
            val cacheNeedsRepair = sourceToRestore?.mode == "xtream" &&
                (!cacheHasLiveChannels || !cacheHasMovies || !cacheHasSeries)
            if (items.isNotEmpty() && !cacheNeedsRepair) {
                val availableGroups = items.map { it.group.ifBlank { "Diğer" } }.toSet()
                if (selectedGroup != "Tüm kategoriler" && selectedGroup !in availableGroups) {
                    selectedGroup = "Tüm kategoriler"
                    prefs.edit().putString("last_group", selectedGroup).apply()
                }
                isLoadingSource = false
                status.visibility = View.GONE
                status.text = ""
                sidebarMode = if (selectedType in listOf("Canlı TV", "Filmler", "Diziler")) "context" else "main"
                render()
            } else if (sourceToRestore != null && !isLoadingSource) {
                sidebarMode = if (selectedType in listOf("Canlı TV", "Filmler", "Diziler")) "context" else "main"
                status.visibility = View.VISIBLE
                status.text = if (items.isEmpty()) "${sourceToRestore.name}: kayıtlı kaynak yükleniyor…" else "${sourceToRestore.name}: film ve dizi kayıtları tamamlanıyor…"
                render()
                if (sourceToRestore.mode == "xtream") loadXtream(sourceToRestore, true) else loadM3u(sourceToRestore, true)
            } else {
                render()
            }
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
        // Keep every loaded item in the disk cache; write entries incrementally
        // so large IPTV libraries do not require one giant JSON string in memory.
        val snapshot = try {
            items.toList()
        } catch (_: Exception) { return }
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
            // A valid 1000-item cache can exceed 2 MB due to URLs and metadata.
            // Do not delete it on startup just because it crosses the old tiny limit.
            if (file.exists() && file.length() > 20L * 1024L * 1024L) {
                android.util.Log.w("KaiserTV", "Media cache exceeds safe size; skipping disk cache.")
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
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            handleSafeBack()
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && event?.action == android.view.KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            var parent: android.view.ViewParent? = focused?.parent
            var focusInSidebar = focused === sidebar
            while (parent != null && !focusInSidebar) {
                if (parent === sidebar) focusInSidebar = true else parent = parent.parent
            }
            if (sidebarMode == "context" && focusInSidebar) {
                sidebarMode = "main"
                rebuildNavigation()
                sidebar.visibility = View.VISIBLE
                val target = navBar.getChildAt(when (selectedType) {
                    "Canlı TV" -> 2
                    "Filmler" -> 3
                    "Diziler" -> 4
                    "Favoriler" -> 5
                    "Son İzlenenler" -> 6
                    "Ayarlar" -> 7
                    else -> 1
                }) ?: navBar.getChildAt(0)
                target?.requestFocus()
                return true
            }
            if (sidebarMode == "context" && !focusInSidebar) {
                sidebar.visibility = View.VISIBLE
                rebuildNavigation()
                val selectedCategoryIndex = (0 until navBar.childCount).firstOrNull {
                    (navBar.getChildAt(it) as? TextView)?.text?.toString()?.removePrefix("● ") == selectedGroup
                } ?: 0
                navBar.getChildAt(selectedCategoryIndex)?.requestFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleSafeBack() {
        // Handle Android system/remote back in one place and keep the activity alive.
        try {
            if (selectedType == "Ayarlar" && settingsPage != "main") {
                settingsPage = when (settingsPage) {
                    "button_appearance" -> "themes"
                    else -> "main"
                }
                prefs.edit().putString("last_settings_page", settingsPage).apply()
                rebuildNavigation()
                render()
                return
            }
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

        val dialogBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(22))
            background = roundedDrawable(Color.rgb(18, 22, 31), 18, accent)
        }
        dialogBody.addView(TextView(this).apply {
            text = "▶ KaiserTv+"
            textSize = 27f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(14))
        })
        dialogBody.addView(TextView(this).apply {
            text = "KaiserTv'den çıkılsın mı?"
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        dialogBody.addView(TextView(this).apply {
            text = "Uygulamadan çıkmak istediğine emin misin?"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(20))
        })
        val dialog = AlertDialog.Builder(this).setView(dialogBody).create()
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        val backButton = makeButton("UYGULAMAYA DÖN", true) { dialog.dismiss() }
        val exitButton = makeButton("ÇIKIŞ YAP", false) { dialog.dismiss(); finish() }
        // Dialog buttons must not scale beyond their rounded outline when focused.
        listOf(backButton, exitButton).forEach { button ->
            button.setOnFocusChangeListener { v, focused ->
                v.background = roundedDrawable(
                    if (button === backButton) accent else if (focused) Color.rgb(27, 39, 53) else panel,
                    buttonRadiusDp,
                    if (focused) focusColor else if (button === backButton) accentBright else Color.rgb(44, 52, 68)
                )
                v.scaleX = 1f
                v.scaleY = 1f
                v.elevation = 0f
            }
        }
        buttons.addView(backButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(8), 0) })
        buttons.addView(exitButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        dialogBody.addView(buttons)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        backButton.requestFocus()
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
        val detailActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        detailActions.addView(makeFavoriteButton(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { setMargins(0, 0, dp(8), 0) })
        detailActions.addView(makeButton("▶  İZLE", true) { (body.tag as? AlertDialog)?.dismiss(); play(item) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44))
        info.addView(detailActions)
        
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
        if (::sidebar.isInitialized) sidebar.visibility = if (firstEntry || isLoadingSource || detailMode) View.GONE else View.VISIBLE
        if (::brandView.isInitialized) brandView.visibility = View.GONE
        if (::navBar.isInitialized) navBar.visibility = if (firstEntry || isLoadingSource || detailMode) View.GONE else View.VISIBLE
        if (::mainHeader.isInitialized) mainHeader.visibility = View.GONE
        if (::status.isInitialized) status.visibility = View.GONE
        if (::categoryScroll.isInitialized) categoryScroll.visibility = View.GONE
        if (firstEntry) {
            mainContentScroll.visibility = View.VISIBLE
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            val welcomeLogo = TextView(this).apply {
                val logo = android.text.SpannableString("▶ KaiserTv+")
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 190, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 8, 10, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 10, 11, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            val inlineForm = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            sourceName = edit("Kaynak adı girin", false)
            sourceName.hint = "Kaynak adı girin"
            inlineForm.addView(TextView(this).apply {
                text = "Kaynak adı (ör. Ev Xtream)"
                textSize = 13f; setTextColor(muted)
                setPadding(0, dp(4), 0, dp(3))
            })
            sourceName.id = View.generateViewId()
            sourceName.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            inlineForm.addView(sourceName, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            sourceMode = RadioGroup(this).apply {
                id = View.generateViewId()
                orientation = RadioGroup.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val inlineM3u = RadioButton(this).apply {
                id = View.generateViewId(); text = "M3U / M3U8"
                setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = true
            }
            val inlineXtream = RadioButton(this).apply {
                id = View.generateViewId(); text = "Xtream Codes"
                setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            inlineM3u.id = View.generateViewId()
            inlineXtream.id = View.generateViewId()
            sourceMode.addView(inlineM3u)
            sourceMode.addView(inlineXtream)
            inlineForm.addView(sourceMode, LinearLayout.LayoutParams(-1, dp(42)))
            playlistUrl = edit("URL adresini girin", false).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            server = edit("Sunucu adresi (https://...)", false).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            username = edit("Kullanıcı adı", false).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            password = edit("Şifre", true).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            val playlistLabel = TextView(this).apply {
                text = "M3U / M3U8 oynatma listesi URL'si"
                textSize = 13f; setTextColor(muted)
                setPadding(0, dp(4), 0, dp(3))
            }
            inlineForm.addView(playlistLabel)
            inlineForm.addView(playlistUrl, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            inlineForm.addView(server, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            val inlineCredentials = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            inlineCredentials.addView(username, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            })
            inlineCredentials.addView(password, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            })
            inlineForm.addView(inlineCredentials)
            fun updateInlineFields() {
                val useXtream = sourceMode.checkedRadioButtonId == inlineXtream.id
                playlistLabel.visibility = if (useXtream) View.GONE else View.VISIBLE
                playlistUrl.visibility = if (useXtream) View.GONE else View.VISIBLE
                server.visibility = if (useXtream) View.VISIBLE else View.GONE
                inlineCredentials.visibility = if (useXtream) View.VISIBLE else View.GONE
            }
            sourceMode.setOnCheckedChangeListener { _, _ -> updateInlineFields() }
            updateInlineFields()

            // Keep keyboard Enter and TV-remote Down navigation in the same visual order.
            fun focusNextOnDown(view: View, next: () -> View) {
                view.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                        event.action == android.view.KeyEvent.ACTION_DOWN) {
                        next().requestFocus()
                        true
                    } else false
                }
            }
            sourceName.setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                     event.action == android.view.KeyEvent.ACTION_DOWN)) {
                    (if (sourceMode.checkedRadioButtonId == inlineXtream.id) inlineXtream else inlineM3u).requestFocus()
                    true
                } else false
            }
            focusNextOnDown(sourceName) { if (sourceMode.checkedRadioButtonId == inlineXtream.id) inlineXtream else inlineM3u }
            focusNextOnDown(inlineM3u) { if (sourceMode.checkedRadioButtonId == inlineXtream.id) server else playlistUrl }
            focusNextOnDown(inlineXtream) { if (sourceMode.checkedRadioButtonId == inlineXtream.id) server else playlistUrl }
            focusNextOnDown(playlistUrl) { playlistUrl.focusSearch(View.FOCUS_DOWN) ?: playlistUrl }
            server.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { username.requestFocus(); true } else false
            }
            username.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
            }
            focusNextOnDown(server) { username }
            focusNextOnDown(username) { password }
            inlineForm.addView(makeButton("＋ KAYNAK EKLE", true) {
                val useXtream = sourceMode.checkedRadioButtonId == inlineXtream.id
                if (useXtream) {
                    val serverAddress = server.text.toString().trim()
                    if (!(serverAddress.startsWith("http://") || serverAddress.startsWith("https://")) ||
                        username.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                        Toast.makeText(this, "Sunucu, kullanıcı adı ve şifreyi doldur.", Toast.LENGTH_LONG).show()
                        return@makeButton
                    }
                    val label = sourceName.text.toString().trim().ifBlank { "Xtream ${savedSources.size + 1}" }
                    val src = SavedSource(java.util.UUID.randomUUID().toString(), label, "xtream",
                        serverAddress.trimEnd('/'), username.text.toString().trim(), password.text.toString())
                    saveSource(src)
                    hideKeyboard()
                    loadXtream(src, false)
                } else {
                    val address = playlistUrl.text.toString().trim()
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, "Geçerli bir M3U/M3U8 URL'si gir.", Toast.LENGTH_LONG).show()
                        return@makeButton
                    }
                    val label = sourceName.text.toString().trim().ifBlank { "M3U ${savedSources.size + 1}" }
                    val src = SavedSource(java.util.UUID.randomUUID().toString(), label, "m3u", address)
                    saveSource(src)
                    hideKeyboard()
                    loadM3u(src, false)
                }
            }, LinearLayout.LayoutParams(dp(280), dp(52)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(18), 0, 0)
            })
            welcome.addView(inlineForm, LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(10), 0, 0)
            })
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
                val logo = android.text.SpannableString("▶ KaiserTv+")
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 190, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 8, 10, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 10, 11, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = logo
                textSize = 34f
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
            // Sadece görseller/karakterler geçiş yapar; metin etiketi gösterilmez.
            val visualItems = listOf(
                "🎬" to Color.rgb(35, 105, 190),
                "📺" to Color.rgb(145, 75, 175),
                "🎞️" to Color.rgb(35, 150, 125),
                "⚽" to Color.rgb(70, 165, 95),
                "🏆" to Color.rgb(215, 155, 35),
                "🦸" to Color.rgb(70, 130, 240),
                "🥷" to Color.rgb(135, 90, 210),
                "🐼" to Color.rgb(65, 175, 135),
                "🐉" to Color.rgb(225, 95, 70),
                "🎮" to Color.rgb(70, 180, 220)
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
                textSize = 82f
                gravity = Gravity.CENTER
                setShadowLayer(dp(18).toFloat(), 0f, 0f, visualItems[0].second)
            }
            visual.addView(visualSymbol, LinearLayout.LayoutParams(-1, -1))
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
                        visualSymbol.setShadowLayer(dp(18).toFloat(), 0f, 0f, visualItems[visualIndex].second)
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
                        // Debounce live results and restore the edit field after refreshing.
                        searchRenderRunnable?.let { searchHandler.removeCallbacks(it) }
                        searchRenderRunnable = Runnable {
                            if (sidebarMode == "search") { searchInputFocusPending = true; render() }
                        }
                        searchHandler.postDelayed(searchRenderRunnable!!, 280)

                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        saveSearchHistory(searchQuery)
                        searchInputFocusPending = true
                        render()
                        true
                    } else false
                }
            }
            searchPanel.addView(searchInput, LinearLayout.LayoutParams(-1, dp(52)))
            if (searchQuery.isBlank() && searchHistory.isNotEmpty()) {
                searchPanel.addView(TextView(this).apply {
                    text = "Son aramalar"; textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE); setPadding(0, dp(14), 0, dp(6))
                })
                searchHistory.forEach { term ->
                    searchPanel.addView(makeButton("⌕  $term", false) {
                        searchQuery = term; saveSearchHistory(term); searchInputFocusPending = true; render()
                    }, LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(0, dp(2), 0, dp(2)) })
                }
                searchPanel.addView(makeButton("Arama geçmişini temizle", false) {
                    searchHistory.clear()
                    prefs.edit().remove("search_history").apply()
                    render()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(0, dp(8), 0, 0) })
            }
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
            selectedType == "Tümü" -> {
                val selectedHomeItems = items.filter { homeSourceIds.contains(it.sourceId) }
                if (homeSourceIds.isEmpty()) items.toList() else selectedHomeItems
            }
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
        groupsAvailable.take(40).forEach { group ->
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

        // Live TV category content: compact logo/name grid; OK opens the channel fullscreen.
        if (selectedType == "Canlı TV" && sidebarMode == "context") {
            content.removeAllViews()
            content.addView(TextView(this).apply {
                text = if (selectedGroup == "Tüm kategoriler") "Tüm Kanallar" else selectedGroup
                textSize = 21f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(8), dp(10), dp(8), dp(12))
            })
            val tvChannels = visible.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
            val displayedTvChannels = tvChannels.take(liveChannelsLimit)
            if (tvChannels.isEmpty()) {
                content.addView(TextView(this).apply {
                    text = "Bu kategoride kanal bulunamadı."
                    textSize = 15f; setTextColor(muted); setPadding(dp(8), dp(12), dp(8), dp(12))
                })
            } else {
                var channelRow: LinearLayout? = null
                displayedTvChannels.forEachIndexed { index, channel ->
                    if (index % 6 == 0) {
                        channelRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                            setBaselineAligned(false)
                        }
                        content.addView(channelRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        background = roundedDrawable(panel, 12)
                        setOnFocusChangeListener { v, hasFocus ->
                            v.background = roundedDrawable(
                                if (hasFocus) Color.rgb(27, 39, 53) else panel,
                                12,
                                if (hasFocus) accent else null
                            )
                            v.elevation = if (hasFocus) dp(4).toFloat() else 0f
                            v.scaleX = if (hasFocus) 1.02f else 1f
                            v.scaleY = if (hasFocus) 1.035f else 1f
                        }
                        setOnClickListener { play(channel) }
                        contentDescription = channel.name
                    }
                    val logo = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = channel.name
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                    tile.addView(logo, LinearLayout.LayoutParams(-1, dp(66)))
                    if (channel.icon.startsWith("http://") || channel.icon.startsWith("https://")) {
                        loadPoster(channel.icon, logo)
                    } else {
                        logo.setImageDrawable(GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(Color.rgb(31, 39, 54))
                        })
                    }
                    tile.addView(TextView(this).apply {
                        text = channel.name
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(5), 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(36)))
                    channelRow?.addView(tile, LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(6))
                    })
                }
                if (displayedTvChannels.size < tvChannels.size) {
                    content.addView(makeButton("Daha fazla kanal göster  (${displayedTvChannels.size}/${tvChannels.size})", false) {
                        liveChannelsLimit = (liveChannelsLimit + 72).coerceAtMost(tvChannels.size)
                        render()
                    }, LinearLayout.LayoutParams(-1, dp(48)).apply {
                        setMargins(dp(8), dp(10), dp(8), dp(18))
                    })
                }
            }
            return
        }
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
            val homeItems = if (homeSourceIds.isEmpty()) items.toList() else items.filter { homeSourceIds.contains(it.sourceId) }
            listOf(
                "Türkiye Trendleri • Haftanın Filmleri" to rankedByTrend(homeItems.filter { it.type == "Filmler" }, trendingMovies).take(24),
                "Türkiye Trendleri • Haftanın Dizileri" to rankedByTrend(homeItems.filter { it.type == "Diziler" }, trendingSeries).take(24),
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
                text = if (showingExpandedCategory) "← Geri dön" else ""
                textSize = 12f; setTextColor(accent)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                if (showingExpandedCategory) setOnClickListener { expandedCategory = null; render() }
            })

            content.addView(sectionHeader)
            val row = LinearLayout(this).apply {
                orientation = if (showingExpandedCategory) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            val weeklyMovieFeature = selectedType == "Tümü" && (groupName.contains("Haftanın Filmleri") || groupName.contains("Haftanın Dizileri"))
            // Ana ekranda haftanın filmleri tek tek geniş, afiş odaklı kartlar olarak gösterilir.
            // Yatay kaydırma ile bir sonraki tam genişlikte afişe geçilir.
            // Kart genişliğini kullanılabilir içerik alanına göre hesapla: yatay satırda dört kart görünür.
            val screenWidthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
            val posterCardWidthDp = ((screenWidthDp - 308) / 4).coerceAtLeast(180)
            val posterCardWidth = dp(posterCardWidthDp)
            val compactThreeCardRow = !showingExpandedCategory && (selectedType == "Filmler" || selectedType == "Diziler")
            val displayedEntries = if (showingExpandedCategory || weeklyMovieFeature) entries else entries.take(if (compactThreeCardRow) 3 else 5)
            var expandedPosterRow: LinearLayout? = null
            var expandedPosterCount = 0
            displayedEntries.forEach { item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.TOP
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    background = roundedDrawable(panel, 10)
                    isFocusable = true; isFocusableInTouchMode = true
                    setOnFocusChangeListener { v, hasFocus ->
                        v.background = roundedDrawable(panel, 10, if (hasFocus) accent else null)
                        v.elevation = 0f
                        v.scaleX = if (hasFocus) 1.02f else 1f; v.scaleY = if (hasFocus) 1.02f else 1f
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
                        -1,
                        if (liveCard) 112 else if (weeklyPoster) dp((posterCardWidthDp * 1.45f).toInt()) else if (showingExpandedCategory) 190 else if (homeCard) 230 else 210
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
                if (compactThreeCardRow && item == displayedEntries.lastOrNull()) {
                    card.addView(TextView(this).apply {
                        text = "Tümü >"
                        textSize = 13f
                        setTextColor(accent)
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        setPadding(0, dp(7), dp(3), dp(2))
                        isFocusable = true
                        setOnClickListener { expandedCategory = groupName to entries; render() }
                        setOnFocusChangeListener { v, focused -> (v as TextView).setTextColor(if (focused) Color.WHITE else accent) }
                    }, LinearLayout.LayoutParams(-1, dp(30)))
                }
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                if (!weeklyPoster && !homeCard && selectedType != "Filmler" && selectedType != "Diziler") {
                    actions.addView(makeButton("▶ İzle", true) { play(item) },
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, dp(7), dp(4), 0) })
                }
                if (actions.childCount > 0) card.addView(actions)
                if (showingExpandedCategory) {
                    if (expandedPosterCount % 4 == 0) {
                        expandedPosterRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                            setBaselineAligned(false)
                        }
                        row.addView(expandedPosterRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    expandedPosterRow?.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(3), dp(4), dp(9))
                    })
                    expandedPosterCount++
                } else {
                    row.addView(card, LinearLayout.LayoutParams(if (liveCard) 190 else posterCardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        // Consistent TV-friendly breathing room between poster cards.
                        setMargins(dp(5), 0, if (weeklyMovieFeature) dp(20) else dp(14), dp(10))
                    })
                }
            }
            if (showingExpandedCategory) {
                content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                content.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    isFillViewport = true
                    setPadding(dp(18), 0, dp(30), 0)
                    // Keep poster rows anchored to the left, even when there is only one item.
                    row.gravity = Gravity.START or Gravity.TOP
                    addView(row)
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
                // Xtream providers vary: episodes may be grouped arrays, nested objects,
                // or a flat array. Read each shape and keep only seasons with playable episodes.
                val episodesValue = data.opt("episodes")
                val episodesObject = episodesValue as? JSONObject
                val episodesArray = episodesValue as? JSONArray
                val seasonMetadata = data.optJSONArray("seasons")
                val seasonEpisodes = linkedMapOf<String, MutableList<MediaItem>>()

                fun episodeValue(ep: JSONObject, fallbackIndex: Int, seasonNumber: String): MediaItem? {
                    val id = ep.optString("id").ifBlank {
                        ep.optString("episode_id").ifBlank { ep.optString("stream_id") }
                    }
                    if (id.isBlank()) return null
                    val ext = ep.optString("container_extension").ifBlank {
                        ep.optString("extension").ifBlank { "mp4" }
                    }
                    val name = ep.optString("title").ifBlank {
                        ep.optString("name").ifBlank {
                            ep.optString("episode_name").ifBlank { "Bölüm $fallbackIndex" }
                        }
                    }
                    val stream = ep.optString("direct_source").ifBlank {
                        "${source.address.trimEnd('/')}/series/$u/$p/$id.$ext"
                    }
                    // Xtream providers put episode artwork under different keys.
                    // Check both the episode object and its nested info object.
                    val info = ep.optJSONObject("info")
                    val artwork = listOf(
                        ep.optString("movie_image"),
                        ep.optString("cover_big"),
                        ep.optString("cover"),
                        ep.optString("stream_icon"),
                        ep.optString("movie_image_url"),
                        info?.optString("movie_image").orEmpty(),
                        info?.optString("cover_big").orEmpty(),
                        info?.optString("cover").orEmpty(),
                        info?.optString("stream_icon").orEmpty(),
                        info?.optString("movie_image_url").orEmpty()
                    ).firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
                    val posterUrl = when {
                        artwork.startsWith("https://", true) || artwork.startsWith("http://", true) -> artwork
                        artwork.startsWith("/") -> "${source.address.trimEnd('/')}$artwork"
                        artwork.isNotBlank() -> "${source.address.trimEnd('/')}/$artwork"
                        else -> ""
                    }
                    return MediaItem(id, name, stream, "${series.name} • Sezon $seasonNumber",
                        "Bölümler", posterUrl, ext, series.seriesId, source.id)
                }

                fun addEpisodeArray(seasonNumber: String, array: JSONArray) {
                    val target = seasonEpisodes.getOrPut(seasonNumber) { mutableListOf() }
                    for (i in 0 until array.length()) {
                        val ep = array.optJSONObject(i) ?: continue
                        val item = episodeValue(ep, target.size + 1, seasonNumber) ?: continue
                        if (target.none { it.id == item.id }) target.add(item)
                    }
                }

                if (episodesObject != null) {
                    val keys = episodesObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (val value = episodesObject.opt(key)) {
                            is JSONArray -> addEpisodeArray(key, value)
                            is JSONObject -> {
                                val nested = value.optJSONArray("episodes") ?: value.optJSONArray("episode")
                                if (nested != null) addEpisodeArray(key, nested)
                            }
                        }
                    }
                }

                if (episodesArray != null) {
                    for (i in 0 until episodesArray.length()) {
                        val ep = episodesArray.optJSONObject(i) ?: continue
                        val seasonNumber = ep.optString("season").ifBlank {
                            ep.optString("season_number").ifBlank { ep.optString("season_num") }
                        }
                        if (seasonNumber.isBlank()) continue
                        val target = seasonEpisodes.getOrPut(seasonNumber) { mutableListOf() }
                        val item = episodeValue(ep, target.size + 1, seasonNumber) ?: continue
                        if (target.none { it.id == item.id }) target.add(item)
                    }
                }

                // Some providers put episode arrays directly inside season metadata.
                if (seasonMetadata != null) {
                    for (i in 0 until seasonMetadata.length()) {
                        val season = seasonMetadata.optJSONObject(i) ?: continue
                        val number = season.optString("season_number").ifBlank {
                            season.optString("season_num").ifBlank {
                                season.optString("number").ifBlank {
                                    season.optString("name").removePrefix("Season ").removePrefix("Sezon ")
                                }
                            }
                        }
                        if (number.isBlank()) continue
                        val nested = season.optJSONArray("episodes") ?: season.optJSONArray("episode")
                        if (nested != null) addEpisodeArray(number, nested)
                    }
                }

                val sortedRows = seasonEpisodes.entries
                    .filter { it.value.isNotEmpty() }
                    .sortedWith(compareBy { it.key.toIntOrNull() ?: Int.MAX_VALUE })
                    .map { it.key to it.value.toList() }
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
            clipChildren = false
            clipToPadding = false
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
            clipChildren = false
            clipToPadding = false
            // Reserve enough room below the action row so its rounded lower edge stays visible.
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
        copy.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(TextView(this@MainActivity).apply {
                text = movie.plot.ifBlank { "Film bilgileri kaynakta bulunmuyorsa doğrudan izleyebilirsin." }
                textSize = 13f
                maxWidth = dp(760)
                setTextColor(Color.rgb(226, 230, 238))
            })
        }, LinearLayout.LayoutParams(-1, dp(54)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val watchButton = makeButton("▶  FİLMİ İZLE", true) { play(movie) }.apply { gravity = Gravity.CENTER }
        actions.addView(watchButton, LinearLayout.LayoutParams(dp(160), dp(46)).apply { setMargins(0, dp(8), dp(8), 0) })
        actions.addView(makeFavoriteButton(movie), LinearLayout.LayoutParams(dp(160), dp(46)).apply { setMargins(0, dp(8), 0, 0) })

        copy.addView(actions)
        watchButton.post { if (watchButton.isShown) watchButton.requestFocus() }
        hero.addView(copy, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(390)).apply { setMargins(0, 0, 0, dp(18)) })

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
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22070A11, 0xEE070A11.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), dp(42), dp(64), dp(18))
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
        copy.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(TextView(this@MainActivity).apply {
                text = series.plot.ifBlank { "Sezonları ve bölümleri aşağıdan seç." }
                textSize = 13f
                setTextColor(Color.rgb(226, 230, 238))
            })
        }, LinearLayout.LayoutParams(-1, dp(54)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val lastEpisode = recent.firstOrNull {
            it.type == "Bölümler" && it.group.startsWith(series.name + " • Sezon") && it.url.isNotBlank()
        }
        val watchSeriesButton = makeButton("▶  DİZİYİ İZLE", true) {
            val resumeEpisode = lastEpisode ?: seriesEpisodeRows
                .firstOrNull { it.first == selectedSeriesSeason }?.second?.firstOrNull()
                ?: seriesEpisodeRows.firstOrNull()?.second?.firstOrNull()
            if (resumeEpisode != null) {
                play(resumeEpisode)
            } else if (seriesEpisodesLoading) {
                Toast.makeText(this, "Bölümler yükleniyor, lütfen bekle.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, seriesEpisodesError.ifBlank { "Bu dizi için bölüm bulunamadı." }, Toast.LENGTH_SHORT).show()
                showSeriesDetail(series)
            }
        }
        actions.addView(watchSeriesButton, LinearLayout.LayoutParams(dp(160), dp(46)).apply { setMargins(0, dp(14), dp(8), 0) })
        actions.addView(makeButton("← Geri", false) {
            detailSeries = null
            seriesEpisodeRows = emptyList()
            selectedSeriesSeason = ""
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { setMargins(0, dp(14), dp(8), 0) })
        actions.addView(makeFavoriteButton(series), LinearLayout.LayoutParams(dp(160), dp(46)).apply { setMargins(0, dp(14), 0, 0) })
        copy.addView(actions)
        watchSeriesButton.post { if (watchSeriesButton.isShown) watchSeriesButton.requestFocus() }
        hero.addView(copy, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(360)).apply { setMargins(0, 0, 0, dp(18)) })

        val episodesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(Color.rgb(14, 19, 29), 16, Color.rgb(42, 50, 66))
        }
        content.addView(episodesPanel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        })
        episodesPanel.addView(TextView(this).apply {
            text = "SEZON VE BÖLÜMLER"
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
            else -> {
                if (selectedSeriesSeason.isBlank() || seriesEpisodeRows.none { it.first == selectedSeriesSeason }) {
                    selectedSeriesSeason = seriesEpisodeRows.first().first
                }
                val seasonLabels = seriesEpisodeRows.map { "Sezon ${it.first}" }
                val selectedIndex = seriesEpisodeRows.indexOfFirst { it.first == selectedSeriesSeason }.coerceAtLeast(0)
                val seasonPicker = Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, seasonLabels).also {
                        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    background = roundedDrawable(Color.rgb(28, 35, 49), 12, Color.rgb(48, 58, 76))
                    setPadding(dp(12), 0, dp(12), 0)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setSelection(selectedIndex, false)
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val newSeason = seriesEpisodeRows.getOrNull(position)?.first ?: return
                            if (newSeason != selectedSeriesSeason) {
                                selectedSeriesSeason = newSeason
                                render()
                            }
                        }
                    }
                }
                episodesPanel.addView(seasonPicker, LinearLayout.LayoutParams(dp(250), dp(48)).apply {
                    setMargins(0, 0, 0, dp(14))
                })

                val selectedEpisodes = seriesEpisodeRows.firstOrNull { it.first == selectedSeriesSeason }?.second.orEmpty()
                val episodeRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                selectedEpisodes.forEachIndexed { index, episode ->
                    val episodeCard = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.TOP
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                        background = roundedDrawable(panel, 10)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        contentDescription = episode.name
                        setOnFocusChangeListener { v, hasFocus ->
                            v.background = roundedDrawable(panel, 10, if (hasFocus) accent else null)
                            v.scaleX = if (hasFocus) 1.02f else 1f
                            v.scaleY = if (hasFocus) 1.02f else 1f
                        }
                        setOnClickListener { play(episode) }
                    }
                    val episodePoster = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = episode.name
                        background = roundedDrawable(Color.rgb(31, 37, 50), 10)
                        layoutParams = LinearLayout.LayoutParams(-1, dp(210))
                    }
                    if (episode.icon.startsWith("http://") || episode.icon.startsWith("https://")) {
                        loadPoster(episode.icon, episodePoster)
                    } else {
                        episodePoster.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                            intArrayOf(Color.rgb(43, 51, 68), Color.rgb(17, 22, 33))))
                    }
                    episodeCard.addView(episodePoster)
                    episodeCard.addView(TextView(this).apply {
                        text = "BÖLÜM ${index + 1}"
                        textSize = 10f
                        setTextColor(accent)
                        setPadding(dp(3), dp(8), dp(2), dp(4))
                        letterSpacing = 0.05f
                    })
                    episodeCard.addView(TextView(this).apply {
                        text = episode.name
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        maxLines = 2
                        minHeight = dp(40)
                        setPadding(dp(3), 0, dp(3), dp(4))
                    })
                    episodeRow.addView(episodeCard, LinearLayout.LayoutParams(dp(166), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(5), 0, dp(14), dp(10))
                    })
                }
                episodesPanel.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    setPadding(dp(4), 0, dp(18), 0)
                    addView(episodeRow)
                }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                background = roundedDrawable(panel, 14)
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = roundedDrawable(
                        if (hasFocus) accent else panel,
                        14,
                        if (hasFocus) accent else null
                    )
                    v.elevation = 0f
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
            settingsPage = "main"; prefs.edit().putString("last_settings_page", "main").apply(); rebuildNavigation(); render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { setMargins(dp(6), dp(6), 0, dp(8)) })
        when (settingsPage) {
            "main" -> {
                heading("Ayarlar", "Bir bölümü seç; ayrı ekranda açılacak.")
                option("＋  Kaynaklar", "Kaynak ekle, kayıtlı kaynakları yenile veya sil") { settingsPage = "sources"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("▤  Kategoriler", "Film ve dizi kategorilerini görüntüle") { settingsPage = "categories"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("◐  Temalar", "Uygulamanın renk görünümünü değiştir") { settingsPage = "themes"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("◷  Geçmiş", "İzlediğin dizileri ve filmleri görüntüle") { settingsPage = "history"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
            }
            "sources" -> {
                heading("Kaynaklar", "Kaynak ekle ve kayıtlı bağlantılarını buradan yönet.")
                option("＋  Yeni kaynak ekle", "M3U/M3U8 veya Xtream Codes") { showConnectionDialog() }
                if (savedSources.isEmpty()) {
                    option("Henüz kaynak eklenmedi", "Yeni kaynak ekle düğmesini kullan.") { showConnectionDialog() }
                } else {
                    savedSources.toList().forEach { source ->
                        val sourceRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(6), dp(8), dp(6))
                            background = roundedDrawable(panel, 14, Color.rgb(48, 57, 73))
                        }
                        val sourceInfo = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        sourceInfo.addView(TextView(this).apply {
                            text = source.name
                            textSize = 16f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.WHITE)
                        })
                        sourceInfo.addView(TextView(this).apply {
                            text = "${if (source.mode == "xtream") "Xtream Codes" else "M3U/M3U8"} • ${source.address}"
                            textSize = 11f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(muted)
                            setPadding(0, dp(2), 0, 0)
                        })
                        sourceRow.addView(sourceInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val shownOnHome = homeSourceIds.contains(source.id)
                        sourceRow.addView(makeButton(if (shownOnHome) "Ana sayfada ✓" else "Seç", shownOnHome) {
                            if (homeSourceIds.contains(source.id)) homeSourceIds.remove(source.id) else homeSourceIds.add(source.id)
                            persistHomeSourceIds()
                            render()
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        })
                        sourceRow.addView(makeButton("Yenile", false) {
                            if (source.mode == "xtream") loadXtream(source, true) else loadM3u(source, true)
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        })
                        sourceRow.addView(makeButton("Düzenle", false) {
                            showConnectionDialog(source)
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        })
                        sourceRow.addView(makeButton("Sil", false) {
                            AlertDialog.Builder(this)
                                .setTitle("Kaynağı sil")
                                .setMessage("${source.name} kaynağı ve bu kaynağın içerikleri silinsin mi?")
                                .setNegativeButton("İptal", null)
                                .setPositiveButton("Sil") { _, _ ->
                                    savedSources.removeAll { it.id == source.id }
                                    persistSources()
                                    items.removeAll { it.sourceId == source.id }
                                    render()
                                }.show()
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                            setMargins(dp(4), 0, 0, 0)
                        })
                        content.addView(sourceRow, LinearLayout.LayoutParams(-1, dp(70)).apply {
                            setMargins(dp(6), dp(3), dp(6), dp(3))
                        })
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
                            if (selected) accent else Color.TRANSPARENT,
                            12,
                            if (selected) accent else null
                        )
                        isFocusable = true
                        isClickable = true
                        setOnFocusChangeListener { v, hasFocus ->
                            v.background = roundedDrawable(
                                if (selected) accent else if (hasFocus) Color.rgb(27, 39, 53) else Color.TRANSPARENT,
                                12, if (hasFocus || selected) accent else null
                            )
                        }
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
                    Triple("Siyah • Mavi", Color.BLACK, Color.rgb(0, 166, 255)),
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
                        rootLayout.setBackgroundColor(bg); sidebar.setBackgroundColor(sidebarBackgroundColor()); mainContentScroll.setBackgroundColor(bg)
                        window.statusBarColor = bg; window.navigationBarColor = bg
                        settingsPage = "themes"; render()
                    }
                }
                option("▣  Buton görünümü", "Buton şekli, vurgu rengi ve seçili görünümünü düzenle") {
                    settingsPage = "button_appearance"
                    prefs.edit().putString("last_settings_page", settingsPage).apply()
                    render()
                }
                val transparencyRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = roundedDrawable(panel, 14, Color.rgb(48, 57, 73))
                }
                transparencyRow.addView(TextView(this).apply {
                    text = "Menü saydamlığı"; textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val percentControl = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedDrawable(Color.rgb(10, 13, 19), 9, Color.rgb(65, 74, 90))
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                }
                percentControl.addView(makeButton("−", false) {
                    sidebarTransparency = (sidebarTransparency - 10).coerceAtLeast(0)
                    transparentMenu = sidebarTransparency == 100
                    prefs.edit().putInt("sidebar_transparency", sidebarTransparency).putBoolean("transparent_menu", transparentMenu).apply()
                    sidebar.setBackgroundColor(sidebarBackgroundColor()); settingsPage = "themes"; render()
                }, LinearLayout.LayoutParams(dp(42), dp(40)))
                percentControl.addView(TextView(this).apply {
                    text = "$sidebarTransparency%"; textSize = 16f
                    gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(dp(68), dp(40)))
                percentControl.addView(makeButton("+", false) {
                    sidebarTransparency = (sidebarTransparency + 10).coerceAtMost(100)
                    transparentMenu = sidebarTransparency == 100
                    prefs.edit().putInt("sidebar_transparency", sidebarTransparency).putBoolean("transparent_menu", transparentMenu).apply()
                    sidebar.setBackgroundColor(sidebarBackgroundColor()); settingsPage = "themes"; render()
                }, LinearLayout.LayoutParams(dp(42), dp(40)))
                transparencyRow.addView(percentControl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                content.addView(transparencyRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(5), dp(6), dp(7)) })
            }
            "button_appearance" -> {
                heading("Buton görünümü", "Şekli, vurgu rengini ve seçili buton stilini seç.")
                heading("Buton şekli", "Tüm standart butonlarda uygulanır.")
                listOf("Yuvarlak" to 18, "Yumuşak" to 10, "Köşeli" to 3).forEach { (label, radius) ->
                    option((if (buttonRadiusDp == radius) "✓  " else "") + label, "Buton köşe biçimi") {
                        buttonRadiusDp = radius; prefs.edit().putInt("button_radius_dp", radius).apply(); render()
                    }
                }
                heading("Vurgu rengi", "Buton ve arayüz vurgusu için renk seç.")
                listOf("Mercan" to Color.rgb(255, 112, 79), "Buz mavisi" to Color.rgb(57, 198, 232), "Mavi" to Color.rgb(0, 166, 255), "Turkuaz" to Color.rgb(35, 205, 190), "Mor" to Color.rgb(174, 115, 255), "Altın" to Color.rgb(224, 184, 95), "Yeşil" to Color.rgb(70, 190, 120)).forEach { (label, color) ->
                    option((if (accent == color) "✓  " else "") + "●  " + label, "Vurgu rengini uygula") {
                        accent = color; accentBright = color; customAccentColor = color
                        prefs.edit().putInt("custom_accent_color", color).apply(); rebuildNavigation(); render()
                    }
                }
                heading("Seçili buton stili", "Menüde seçili olan bölümün görünümü.")
                listOf("Dolu", "Çerçeve", "Sol çizgi").forEach { style ->
                    option((if (buttonSelectionStyle == style) "✓  " else "") + style, "Seçili bölüm görünümü") {
                        buttonSelectionStyle = style; prefs.edit().putString("button_selection_style", style).apply(); rebuildNavigation(); render()
                    }
                }
            }
            "history" -> {
                heading("Geçmiş", "Türü seçerek izleme geçmişini görüntüle.")
                val historyOptions = listOf("Canlı TV", "Filmler", "Diziler")
                val picker = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                historyOptions.forEach { type ->
                    picker.addView(makeButton(when (type) { "Canlı TV" -> "▣  TV"; "Filmler" -> "▶  Filmler"; else -> "▤  Diziler" }, historyType == type) {
                        historyType = type; render()
                    }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(8)) })
                }
                content.addView(picker)
                val entries = recent.filter { it.type == historyType }
                // Bu türün tüm izleme geçmişini tek seferde temizle.
                content.addView(makeButton("Hepsini sil", false) {
                    if (entries.isNotEmpty()) {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("İzleme geçmişini temizle")
                            .setMessage(when (historyType) {
                                "Canlı TV" -> "İzlenen tüm TV kanalları geçmişten silinsin mi?"
                                "Filmler" -> "İzlenen tüm filmler geçmişten silinsin mi?"
                                else -> "İzlenen tüm diziler geçmişten silinsin mi?"
                            })
                            .setNegativeButton("İptal", null)
                            .setPositiveButton("Sil") { _, _ ->
                                recent.removeAll { it.type == historyType }
                                persistRecentHistory()
                                render()
                            }.show()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                    setMargins(dp(8), 0, 0, dp(8))
                })
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
                    val historyRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(6), dp(8), dp(6))
                        background = roundedDrawable(panel, 14, Color.rgb(48, 57, 73))
                    }
                    // Show the actual poster/logo on the right-side history screen.
                    val historyPoster = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = item.name
                        background = roundedDrawable(Color.rgb(28, 34, 45), 8, Color.rgb(48, 57, 73))
                    }
                    val posterUrl = item.icon
                    if (posterUrl.startsWith("http://") || posterUrl.startsWith("https://")) {
                        loadPoster(posterUrl, historyPoster)
                    } else {
                        historyPoster.setImageDrawable(GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(Color.rgb(28, 34, 45))
                        })
                    }
                    historyRow.addView(historyPoster, LinearLayout.LayoutParams(
                        dp(if (historyType == "Canlı TV") 64 else 76),
                        dp(if (historyType == "Canlı TV") 58 else 104)
                    ).apply { setMargins(0, 0, dp(10), 0) })
                    val itemInfo = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(6), dp(4), dp(8), dp(4))
                    }
                    itemInfo.addView(TextView(this).apply {
                        text = item.name
                        textSize = 16f
                        maxLines = 2
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                    })
                    itemInfo.addView(TextView(this).apply {
                        text = item.group.ifBlank { item.type }
                        textSize = 13f
                        maxLines = 1
                        setTextColor(muted)
                    })
                    historyRow.addView(itemInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    historyRow.addView(makeButton("Sil", false) {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Geçmiş kaydını sil")
                            .setMessage("${item.name} izleme geçmişinden silinsin mi?")
                            .setNegativeButton("İptal", null)
                            .setPositiveButton("Sil") { _, _ ->
                                recent.removeAll { it.favoriteKey == item.favoriteKey }
                                persistRecentHistory()
                                render()
                            }.show()
                    }, LinearLayout.LayoutParams(dp(76), dp(40)))
                    historyRow.isFocusable = true
                    content.addView(historyRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dp(8))
                    })
                    historyRow.setOnClickListener {
                        if (item.type == "Diziler" && item.url.isBlank()) showSeriesDetail(item)
                        else play(item)
                    }
                }
            }
        }
        // Apply one consistent, visible remote-focus state to every actionable
        // settings control, including the category action chips and section tabs.
        fun styleSettingsFocus(view: View) {
            if (view.isFocusable && view.isClickable && view !is EditText) {
                view.setOnFocusChangeListener { v, focused ->
                    v.background = roundedDrawable(
                        if (focused) accent else panel,
                        12,
                        if (focused) accent else Color.rgb(48, 57, 73)
                    )
                    if (v is TextView) v.setTextColor(Color.WHITE)
                    v.elevation = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) styleSettingsFocus(view.getChildAt(i))
        }
        styleSettingsFocus(content)
    }

    private fun loadPoster(address: String, target: ImageView) {
        target.tag = address
        val cached = posterCache.get(address)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }

        val waiting = CopyOnWriteArrayList<WeakReference<ImageView>>()
        waiting.add(WeakReference(target))
        val existing = posterRequests.putIfAbsent(address, waiting)
        if (existing != null) {
            existing.add(WeakReference(target))
            return
        }

        posterExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                // Fetch artwork only once, then decode the same byte array at a reduced size.
                // This avoids two sequential network requests per poster and shares concurrent requests.
                val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "KaiserTv+/Android")
                    useCaches = true
                }
                val bytes = try {
                    conn.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 8 * 1024 * 1024) throw java.io.IOException("Poster image too large")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } finally {
                    conn.disconnect()
                }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > 800 || bounds.outHeight / sampleSize > 800) {
                        sampleSize *= 2
                    }
                    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    if (bitmap != null) posterCache.put(address, bitmap)
                }
            } catch (e: Exception) {
                android.util.Log.w("KaiserTV", "Poster load failed: $address", e)
            }

            val targets = posterRequests.remove(address) ?: return@execute
            val result = bitmap
            if (result != null) {
                runOnUiThread {
                    targets.forEach { ref ->
                        val image = ref.get()
                        if (image != null && image.tag == address) image.setImageBitmap(result)
                    }
                }
            }
        }
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        previewPlayer?.release()
        previewPlayer = null
        posterExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun persistRecentHistory() {
        val arr = JSONArray()
        recent.forEach { r -> arr.put(JSONObject().put("id", r.id).put("name", r.name).put("url", r.url).put("group", r.group).put("type", r.type).put("icon", r.icon).put("extension", r.extension).put("seriesId", r.seriesId).put("sourceId", r.sourceId)) }
        getSharedPreferences("androtv", MODE_PRIVATE).edit().putString("recent", arr.toString()).apply()
    }

    private fun saveRecent(item: MediaItem) {
        recent.removeAll { it.favoriteKey == item.favoriteKey }
        recent.add(0, item)
        while (recent.size > 20) recent.removeAt(recent.lastIndex)
        persistRecentHistory()
    }

    private var openingPlayer = false

    private fun play(item: MediaItem) {
        if (openingPlayer) return
        if (item.type == "Diziler" && item.url.isBlank()) { loadSeriesEpisodes(item); return }
        if (item.url.isBlank()) {
            android.widget.Toast.makeText(this, "Bu içerik için yayın adresi bulunamadı.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        openingPlayer = true
        try {
            // Önizleme oynatıcısını kapat; tam ekran için yeni ExoPlayer açılacak.
            previewView?.player = null
            previewPlayer?.runCatching { stop() }
            previewPlayer?.release()
            previewPlayer = null
            previewView = null
            previewChannel = null
            previewUrl = ""

            saveRecent(item)
            val intent = Intent(this, PlayerActivity::class.java)
                .putExtra("name", item.name)
                .putExtra("url", item.url)
                .putExtra("isLive", item.type == "Canlı TV")
            if (item.type == "Canlı TV") {
                val channels = items.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
                val recentChannels = recent.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
                    .distinctBy { it.url }.take(20)
                // Keep large channel lists out of Intent extras to avoid Binder transaction crashes.
                PlayerActivity.pendingChannelNames = channels.map { it.name }
                PlayerActivity.pendingChannelUrls = channels.map { it.url }
                PlayerActivity.pendingChannelIndex = channels.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
                PlayerActivity.pendingRecentChannelNames = recentChannels.map { it.name }
                PlayerActivity.pendingRecentChannelUrls = recentChannels.map { it.url }
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                openingPlayer = false
                android.widget.Toast.makeText(this, "Tam ekran oynatıcı açılamadı. Tekrar deneyin.", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            openingPlayer = false
            android.widget.Toast.makeText(this, "Tam ekran oynatıcı açılamadı. Tekrar deneyin.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        openingPlayer = false
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
