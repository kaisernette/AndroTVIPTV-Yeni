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
    private val posterExecutor = Executors.newFixedThreadPool(3)
    private val posterCache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val items = mutableListOf<MediaItem>()
    private val posterRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>>()
    private val savedSources = mutableListOf<SavedSource>()
    private lateinit var sourceName: EditText
    private val favorites = mutableSetOf<String>()
    private val recent = mutableListOf<MediaItem>()
    private var selectedGroup = "Tüm kategoriler"
    private var selectedCategorySection = "Canlı TV"
    private var categoryFocusRestoreKey: String? = null
    // Render live channels in small batches to avoid first-open freezes on Android TV.
    private var liveChannelsLimit = 72
    private var expandedCategory: Pair<String, List<MediaItem>>? = null
    private lateinit var categoryPanel: LinearLayout
    private lateinit var categoryListScroll: ScrollView
    private lateinit var categoryBar: LinearLayout
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var mainHeader: LinearLayout
    private lateinit var dateClockRow: LinearLayout
    private lateinit var content: LinearLayout
    private var detailSeries: MediaItem? = null
    private var detailMovie: MediaItem? = null
    private var seriesEpisodeRows: List<Pair<String, List<MediaItem>>> = emptyList()
    private var seriesEpisodesLoading = false
    private var selectedEpisodeSeason = ""
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
    // Focus styling is independent from the selectable app theme.
    private val focusColor = Color.rgb(112, 205, 255)
    private var muted = Color.rgb(190, 198, 210)
    // Sidebar transparency is independent from the selected color theme.
    private var transparentMenu = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        transparentMenu = prefs.getBoolean("transparent_menu", true)
        // Apply Dark Cinematic once as the new demo baseline; later manual theme choices are preserved.
        if (!prefs.getBoolean("dark_cinematic_applied", false)) {
            prefs.edit().putString("theme_name", "Dark Cinematic").putBoolean("dark_cinematic_applied", true).apply()
        }
        // Restore the selected theme before constructing any views.
        when (prefs.getString("theme_name", "Dark Cinematic")) {
            "Dark Cinematic" -> {
                bg = Color.rgb(10, 13, 19)
                panel = Color.rgb(20, 26, 37)
                accent = Color.rgb(0, 145, 255)
                accentBright = Color.rgb(75, 195, 255)
                muted = Color.rgb(174, 187, 204)
            }
            "Gece • Altın" -> { bg = Color.rgb(15, 16, 20); accent = Color.rgb(224, 184, 95); accentBright = accent }
            "Lacivert • Turkuaz" -> { bg = Color.rgb(10, 22, 38); accent = Color.rgb(35, 205, 190); accentBright = accent }
            "Koyu • Mor" -> { bg = Color.rgb(20, 16, 29); accent = Color.rgb(174, 115, 255); accentBright = accent }
            else -> { bg = Color.rgb(17, 20, 25); accent = Color.rgb(0, 166, 255); accentBright = accent }
        }
        panel = if (prefs.getString("theme_name", "Dark Cinematic") == "Dark Cinematic") Color.rgb(20, 26, 37) else Color.rgb((Color.red(bg) + 8).coerceAtMost(255), (Color.green(bg) + 10).coerceAtMost(255), (Color.blue(bg) + 14).coerceAtMost(255))
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
        // Start on Home every time; the user's last section remains saved for history only.
        selectedType = "Tümü"
        selectedGroup = "Tüm kategoriler"
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
    private lateinit var heroPanel: LinearLayout
    private lateinit var quickAccessScroll: HorizontalScrollView
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
    private val selectedSearchHistory = mutableSetOf<String>()
    private var searchInputFocusPending = false
    private var focusFirstContentOnRender = false
    private var selectedSidebarFocusView: View? = null
    private var firstRightContentFocusView: View? = null
    private var initialSidebarFocusPending = true
    private var isLoadingSource = false
    private var loadProgress = 0
    private var lastBackPressAt = 0L
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

        root.clipChildren = false
        root.clipToPadding = false
        rootLayout = root
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 12, 6, 10)
            background = android.graphics.drawable.ColorDrawable(if (transparentMenu) Color.TRANSPARENT else panel)
            clipChildren = false
        }
        root.addView(sidebar, LinearLayout.LayoutParams(dp(270), -1).apply { setMargins(0, 0, dp(8), 0) })

        val primaryMenuColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(66), -1)
        }
        sidebar.addView(primaryMenuColumn)

        brandView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        brandView.addView(TextView(this).apply {
            val logo = android.text.SpannableString("▶ KAISER TV+")
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 9, 11, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 11, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = logo
            textSize = if (prefs.getBoolean("compact_logo", true)) 15f else 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "KAISER TV+"
        }, LinearLayout.LayoutParams(-1, dp(54)))
        primaryMenuColumn.addView(brandView, LinearLayout.LayoutParams(-1, dp(76)))
        navBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sidebarMenuScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(navBar)
        }
        primaryMenuColumn.addView(sidebarMenuScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        categoryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(78), dp(4), dp(8))
            clipChildren = false
        }
        categoryListScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            clipToPadding = false
            visibility = View.GONE
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(categoryPanel, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sidebar.addView(categoryListScroll, LinearLayout.LayoutParams(dp(190), -1))
        rebuildNavigation()

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
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
        // Fixed date and live clock: remains visible while the content below scrolls.
        dateClockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(4), 0, dp(10), dp(8))
            // Keep the fixed search/date header visually above every scrolling poster,
            // including cards temporarily raised by their focus animation.
            setBackgroundColor(bg)
            clipChildren = true
            clipToPadding = true
            elevation = dp(48).toFloat()
            translationZ = dp(48).toFloat()
        }
        // Top search bar removed; search is opened from the navigation icon.
        main.addView(dateClockRow, 0, LinearLayout.LayoutParams(-1, 0))

        main.addView(mainHeader)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 18, 18, 18)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(33, 42, 59), Color.rgb(12, 17, 28))).apply {
                cornerRadius = 0f; setStroke(1, Color.rgb(54, 64, 82))
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
            selectedType = "Tümü"; selectedGroup = "Tüm kategoriler"; render()
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
        heroPanel = hero
        main.addView(hero, LinearLayout.LayoutParams(-1, 220).apply { setMargins(0, 0, 0, 10) })
        hero.visibility = View.VISIBLE

        quickAccessScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val quickAccess = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val quickItems = listOf(
            Triple("◉\nCanlı TV", Color.rgb(0, 112, 230), "Canlı TV"),
            Triple("▣\nFilmler", Color.rgb(196, 39, 65), "Filmler"),
            Triple("▱\nDiziler", Color.rgb(123, 47, 190), "Diziler"),
            Triple("☺\nÇocuk", Color.rgb(0, 145, 95), "çocuk"),
            Triple("♧\nBelgeseller", Color.rgb(190, 104, 0), "belgesel"),
            Triple("♜\nSpor", Color.rgb(0, 112, 135), "spor")
        )
        quickItems.forEach { (label, tileColor, target) ->
            val tile = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundedDrawable(tileColor, 14, tileColor)
                stateListAnimator = null
                isFocusable = true
                setOnFocusChangeListener { v, focused ->
                    v.background = roundedDrawable(if (focused) accent else tileColor, 14)
                    v.elevation = if (focused) dp(5).toFloat() else 0f
                    v.scaleX = if (focused) 1.03f else 1f
                    v.scaleY = if (focused) 1.03f else 1f
                }
                setOnClickListener {
                    detailMovie = null; detailSeries = null
                    if (target in listOf("Canlı TV", "Filmler", "Diziler")) {
                        selectedType = target
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = if (target == "Canlı TV" || target == "Filmler" || target == "Diziler") "context" else "main"
                        searchQuery = ""
                    } else {
                        selectedType = "Tümü"
                        sidebarMode = "search"
                        searchQuery = target
                    }
                    render()
                }
            }
            quickAccess.addView(tile, LinearLayout.LayoutParams(dp(142), dp(82)).apply { setMargins(0, 0, dp(9), 0) })
        }
        quickAccessScroll.addView(quickAccess)
        main.addView(quickAccessScroll, LinearLayout.LayoutParams(-1, dp(92)).apply { setMargins(0, 0, 0, dp(6)) })

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
        mainContentScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            // Keep scrolling posters inside the content viewport. Cards may still
            // scale within the viewport, but must never draw over the fixed header.
            clipChildren = true
            clipToPadding = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(30), dp(20))
            clipChildren = false
            clipToPadding = false
        }
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
            val desiredWidth = dp(if (showReadableCategories) 270 else 76)
            if (params != null && params.width != desiredWidth) {
                params.width = desiredWidth
                sidebar.layoutParams = params
            }
        }
        navBar.removeAllViews()
        if (::categoryPanel.isInitialized) {
            categoryListScroll.visibility = if (sidebarMode == "context" && selectedType in listOf("Canlı TV", "Filmler", "Diziler")) View.VISIBLE else View.GONE
            categoryPanel.removeAllViews()
            categoryListScroll.scrollTo(0, 0)
        }
        selectedSidebarFocusView = null

        fun addSidebarButton(label: String, selected: Boolean = false, action: () -> Unit) {
            val iconRes = when {
                label == "Ara" || label == "⌕" -> R.drawable.nav_search
                label.contains("Ana Sayfa") || label == "⌂" -> R.drawable.nav_home
                label.contains("TV") || label == "▣" -> R.drawable.nav_tv
                label.contains("Filmler") || label == "▧" -> R.drawable.nav_movies
                label.contains("Diziler") || label == "▤" -> R.drawable.nav_series
                label.contains("Favoriler") || label == "♡" -> R.drawable.nav_favorites
                label.contains("İzlediklerim") || label.contains("Geçmiş") || label == "◷" -> R.drawable.nav_history
                label.contains("Ayarlar") || label == "⚙" -> R.drawable.nav_settings
                else -> 0
            }
            val sidebarButton = makeButton(label, selected, action).apply {
                val iconDrawable = if (iconRes != 0) getDrawable(iconRes)?.mutate() else null
                iconDrawable?.setBounds(0, 0, dp(29), dp(29))
                // Sidebar rests flat with no surrounding tile; focus uses a square-corner theme fill.
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnFocusChangeListener { v, hasFocus ->
                    val button = v as Button
                    button.background = if (hasFocus) roundedDrawable(accent, 0) else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    button.setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                    iconDrawable?.setTint(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                    button.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    v.elevation = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
                textSize = 14f
                val iconEnd = label.indexOf("  ").takeIf { it in 1..3 } ?: 1
                text = if (iconDrawable != null) {
                    android.text.SpannableString(" ").apply {
                        setSpan(android.text.style.ImageSpan(iconDrawable, android.text.style.ImageSpan.ALIGN_CENTER), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    android.text.SpannableString(label)
                }
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = label
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                minHeight = dp(42)
                id = View.generateViewId()
            }
            navBar.addView(sidebarButton, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(3), 0, dp(3)) })
            if (selected) selectedSidebarFocusView = sidebarButton
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
            render()
        }

        // Ana menü normal ekranlarda kalır; TV/Film/Dizi seçilince sol menü kategori listesine dönüşür.
        if (sidebarMode in listOf("main", "settings", "search")) {
            listOf("Ara", "⌂  Ana Sayfa", "▣  TV", "▧  Filmler", "▤  Diziler", "♡  Favoriler", "◷  İzlediklerim", "⚙  Ayarlar").forEach { tab ->
                val normalizedTab = when {
                    tab == "Ara" -> "Ara"
                    tab.endsWith("Ara") -> "Ara"
                    tab.contains("Ana Sayfa") -> "Ana Sayfa"
                    (tab.contains("Canlı TV") || tab.contains(" TV")) -> "TV"
                    tab.contains("Favoriler") -> "Favoriler"
                    (tab.contains("Geçmiş") || tab.contains("İzlediklerim")) -> "Geçmiş"
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
                    navBar.addView(View(this).apply {
                        setBackgroundColor(Color.rgb(76, 87, 105))
                        isFocusable = false
                    }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                        setMargins(dp(10), dp(10), dp(10), dp(10))
                    })
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
                        settingsPage = "main"
                        selectedType = "Ayarlar"
                        prefs.edit().putString("last_section", "Ayarlar").apply()
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = "settings"
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
                            expandedCategory = null
                            render()
                            return@addSidebarButton
                        }
                        detailSeries = null
                        detailMovie = null
                        seriesEpisodeRows = emptyList()
                        seriesEpisodesLoading = false
                        seriesEpisodesError = ""
                        focusFirstContentOnRender = true
                        selectedGroup = "Tüm kategoriler"
                        expandedCategory = null
                        selectedType = key
                        sidebarMode = if (normalizedTab == "TV" || normalizedTab == "Filmler" || normalizedTab == "Diziler") "context" else "main"
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
            // In TV/Movies/Series, collapse the left rail to icons only.
            // Category filters remain in the main content area on the right.
            val compactTabs = listOf(
                Triple("⌕", "Ara", "Ara"),
                Triple("⌂", "Ana Sayfa", "Tümü"),
                Triple("▣", "TV", "Canlı TV"),
                Triple("▧", "Filmler", "Filmler"),
                Triple("▤", "Diziler", "Diziler"),
                Triple("♡", "Favoriler", "Favoriler"),
                Triple("◷", "İzlediklerim", "Son İzlenenler"),
                Triple("⚙", "Ayarlar", "Ayarlar")
            )
            compactTabs.forEach { (glyph, label, key) ->
                if (key == "Ayarlar") {
                    navBar.addView(View(this).apply { setBackgroundColor(Color.rgb(76, 87, 105)); isFocusable = false },
                        LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
                }
                val isSelected = when (key) {
                    "Ara" -> false
                    "Tümü" -> selectedType == "Tümü"
                    else -> selectedType == key
                }
                addSidebarButton(glyph, isSelected) {
                    when (key) {
                        "Ara" -> {
                            detailSeries = null
                            detailMovie = null
                            sidebarMode = "search"
                            searchInputFocusPending = true
                            searchQuery = ""
                            selectedType = "Tümü"
                            selectedGroup = "Tüm kategoriler"
                            expandedCategory = null
                            render()
                        }
                        "Tümü" -> returnToMainMenu()
                        "Son İzlenenler" -> {
                            selectedType = key
                            sidebarMode = "main"
                            prefs.edit().putString("last_section", key).apply()
                            rebuildNavigation()
                        }
                        "Ayarlar" -> {
                            selectedType = key
                            settingsPage = "main"
                            sidebarMode = "settings"
                            prefs.edit().putString("last_section", key).apply()
                        }
                        else -> {
                            selectedType = key
                            selectedGroup = "Tüm kategoriler"
                            expandedCategory = null
                            sidebarMode = if (key in listOf("Canlı TV", "Filmler", "Diziler")) "context" else "main"
                            prefs.edit().putString("last_section", key).putString("last_group", selectedGroup).apply()
                            if (key in listOf("Favoriler", "Son İzlenenler")) rebuildNavigation()
                        }
                    }
                    render()
                }
            }
            if (::categoryPanel.isInitialized && selectedType in listOf("Filmler", "Diziler", "Canlı TV")) {
                // Apply the same hidden-category rules to the sidebar category column
                // as the content grid, so hidden TV/movie/series groups disappear everywhere.
                val categoryItems = items.filter {
                    it.type == selectedType &&
                    (selectedType != "Canlı TV" || it.url.isNotBlank()) &&
                    !hiddenCategories.contains(it.type + "|" + it.group.ifBlank { "Diğer" })
                }
                val grouped = categoryItems.groupBy { it.group.ifBlank { "Diğer" } }.toList()
                val selectedName = expandedCategory?.first ?: grouped.firstOrNull()?.first
                if (expandedCategory == null && selectedName != null && selectedType != "Canlı TV") {
                    val firstGroup = grouped.firstOrNull { it.first == selectedName }
                    if (firstGroup != null) {
                        expandedCategory = firstGroup.first to firstGroup.second
                        selectedGroup = firstGroup.first
                    }
                }
                categoryPanel.addView(TextView(this).apply {
                    text = if (selectedType == "Canlı TV") "KATEGORİLER" else "KATEGORİLER"
                    textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(muted); setPadding(dp(8), 0, 0, dp(10))
                })
                grouped.forEach { (name, entries) ->
                    val selected = name == (expandedCategory?.first ?: selectedGroup)
                    val button = TextView(this).apply {
                        text = name
                        textSize = 13f
                        maxLines = 2
                        setTextColor(if (selected) Color.WHITE else muted)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(6), dp(8))
                        minHeight = dp(42)
                        isFocusable = true
                        isClickable = true
                        background = if (selected) roundedDrawable(accent, 8) else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        setOnFocusChangeListener { v, hasFocus ->
                            v.background = if (hasFocus || selected) roundedDrawable(accent, 8) else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                            (v as TextView).setTextColor(if (hasFocus || selected) Color.WHITE else muted)
                            if (hasFocus) {
                                categoryListScroll.post {
                                    val rect = android.graphics.Rect(0, 0, v.width, v.height)
                                    v.getDrawingRect(rect)
                                    categoryListScroll.offsetDescendantRectToMyCoords(v, rect)
                                    categoryListScroll.requestChildRectangleOnScreen(v, rect, true)
                                }
                            }
                        }
                        setOnClickListener {
                            selectedGroup = name
                            expandedCategory = name to entries
                            focusFirstContentOnRender = true
                            render()
                        }
                    }
                    categoryPanel.addView(button, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(2), 0, dp(2))
                    })
                    if (selected && button.id == View.NO_ID) button.id = View.generateViewId()
                }
                if (selectedType == "Canlı TV") {
                    categoryPanel.addView(TextView(this).apply {
                        text = "Kanal listesi"
                        textSize = 12f; setTextColor(muted); setPadding(dp(8), dp(10), 0, 0)
                    })
                }
            }
            return
        }

        if (sidebarMode == "search") {
            sidebarMenuScroll.post { sidebarMenuScroll.scrollTo(0, 0) }
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
    }

    private fun showConnectionDialog(editSource: SavedSource? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        form.addView(TextView(this).apply {
            text = if (editSource == null) "İzleme kaynağı ekle" else "Kaynağı düzenle"
            textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6))
        })
        form.addView(TextView(this).apply {
            text = "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle."
            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(8))
        })
        sourceName = edit("Kaynak adı (ör. Ev Xtream)", false).apply { setText(editSource?.name.orEmpty()) }
        form.addView(sourceName, fieldParams())
        sourceMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val m3u = RadioButton(this).apply {
            id = View.generateViewId(); text = "M3U / M3U8"; setTextColor(Color.WHITE)
        }
        val xtream = RadioButton(this).apply {
            id = View.generateViewId(); text = "Xtream Codes"; setTextColor(Color.WHITE)
        }
        sourceMode.addView(m3u); sourceMode.addView(xtream); form.addView(sourceMode)
        playlistUrl = edit("M3U / M3U8 oynatma listesi URL'si", false).apply {
            setText(if (editSource?.mode == "m3u") editSource.address else "")
        }
        server = edit("Sunucu adresi (https://...)", false).apply {
            setText(if (editSource?.mode == "xtream") editSource.address else "")
        }
        username = edit("Kullanıcı adı", false).apply { setText(editSource?.username.orEmpty()) }
        password = edit("Şifre", true).apply { setText(editSource?.password.orEmpty()) }
        form.addView(playlistUrl, fieldParams())
        val xtreamFieldsFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedDrawable(panel, 0, Color.rgb(48, 57, 73))
        }
        fun framedInput(input: EditText): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(panel, 0, Color.rgb(48, 57, 73))
                setPadding(dp(8), 0, dp(8), 0)
                isFocusable = false
                input.background = roundedDrawable(Color.TRANSPARENT, 0)
                addView(input, LinearLayout.LayoutParams(-1, dp(48)))
                input.setOnFocusChangeListener { _, hasFocus ->
                    background = roundedDrawable(panel, 0, if (hasFocus) accent else Color.rgb(48, 57, 73))
                }
            }
        }
        val serverFrame = framedInput(server)
        val usernameFrame = framedInput(username)
        val passwordFrame = framedInput(password)
        xtreamFieldsFrame.removeAllViews()
        xtreamFieldsFrame.addView(serverFrame, LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(0, dp(3), 0, dp(5))
        })
        val creds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        creds.addView(usernameFrame, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            setMargins(0, dp(3), dp(5), dp(3))
        })
        creds.addView(passwordFrame, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            setMargins(dp(5), dp(3), 0, dp(3))
        })
        xtreamFieldsFrame.addView(creds)
        form.addView(xtreamFieldsFrame, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, dp(3))
        })
        sourceMode.setOnCheckedChangeListener { _, checked ->
            val useXtream = checked == xtream.id
            playlistUrl.visibility = if (useXtream) View.GONE else View.VISIBLE
            xtreamFieldsFrame.visibility = if (useXtream) View.VISIBLE else View.GONE
        }
        if (editSource?.mode == "xtream") sourceMode.check(xtream.id) else sourceMode.check(m3u.id)
        if (editSource?.mode == "xtream") {
            playlistUrl.visibility = View.GONE; xtreamFieldsFrame.visibility = View.VISIBLE
        } else {
            xtreamFieldsFrame.visibility = View.GONE
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(makeButton("İptal", false) { render() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { setMargins(dp(4), 0, dp(4), 0) })
        val connectButton = makeButton(if (editSource == null) "Bağlan" else "Kaydet", true) {
            val useXtream = sourceMode.checkedRadioButtonId == xtream.id
            if (useXtream) {
                val address = server.text.toString().trim()
                if (!(address.startsWith("http://") || address.startsWith("https://")) ||
                    username.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                    Toast.makeText(this, "Sunucu, kullanıcı adı ve şifreyi doldur.", Toast.LENGTH_LONG).show()
                    return@makeButton
                }
                val label = sourceName.text.toString().trim().ifBlank { "Xtream ${savedSources.size + 1}" }
                val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "xtream",
                    address.trimEnd('/'), username.text.toString().trim(), password.text.toString())
                saveSource(src); hideKeyboard(); render(); loadXtream(src, editSource != null)
            } else {
                val address = playlistUrl.text.toString().trim()
                if (!address.startsWith("http://") && !address.startsWith("https://")) {
                    Toast.makeText(this, "Geçerli bir M3U/M3U8 URL'si gir.", Toast.LENGTH_LONG).show()
                    return@makeButton
                }
                val label = sourceName.text.toString().trim().ifBlank { "M3U ${savedSources.size + 1}" }
                val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "m3u", address)
                saveSource(src); hideKeyboard(); render(); loadM3u(src, editSource != null)
            }
        }.apply {
            minWidth = dp(112)
            minHeight = dp(44)
        }
        // Android TV kumanda odak sırası:
        // Kaynak adı → M3U / M3U8 → Xtream Codes → seçilen bağlantı alanı.
        sourceMode.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        m3u.isFocusable = true
        m3u.isFocusableInTouchMode = true
        xtream.isFocusable = true
        xtream.isFocusableInTouchMode = true
        sourceName.nextFocusDownId = m3u.id
        m3u.nextFocusUpId = sourceName.id
        m3u.nextFocusDownId = xtream.id
        xtream.nextFocusUpId = m3u.id
        xtream.nextFocusDownId = if (editSource?.mode == "xtream") server.id else playlistUrl.id
        playlistUrl.nextFocusUpId = xtream.id
        playlistUrl.nextFocusDownId = connectButton.id
        server.nextFocusUpId = xtream.id
        server.nextFocusDownId = username.id
        username.nextFocusUpId = server.id
        username.nextFocusDownId = password.id
        password.nextFocusUpId = username.id
        password.nextFocusDownId = connectButton.id
        connectButton.nextFocusUpId = password.id
        actions.addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
            setMargins(dp(4), 0, 0, 0)
        })
        form.addView(actions)
        content.addView(form, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(8), dp(6), dp(10))
        })
        form.post { sourceName.requestFocus() }
    }

    private fun roundedDrawable(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = 0f
            if (strokeColor != null) setStroke((2 * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        }

    private fun edit(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText; textSize = 14f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(muted)
        setPadding(12, 0, 12, 0)
        background = roundedDrawable(panel, 10, Color.rgb(48, 57, 73))
        if (android.os.Build.VERSION.SDK_INT >= 24) imeHintLocales = android.os.LocaleList.getDefault()
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTint(accent)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun fieldParams() = LinearLayout.LayoutParams(-1, 48).apply { setMargins(0, 3, 0, 3) }
    private fun makeFavoriteButton(item: MediaItem): Button {
        fun updateFavoriteStyle(button: Button) {
            val selected = favorites.contains(item.favoriteKey)
            val yellow = Color.rgb(255, 205, 55)
            button.text = "Favori"
            button.setTextColor(if (selected) Color.rgb(25, 25, 25) else Color.WHITE)
            button.background = if (selected) {
                roundedDrawable(yellow, 0)
            } else {
                // Keep the favorite button unfilled; focus is indicated by a yellow square outline.
                roundedDrawable(Color.TRANSPARENT, 0, if (button.hasFocus()) yellow else muted)
            }
        }
        return makeButton("Favori", false) {
            if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
            render()
        }.apply {
            updateFavoriteStyle(this)
            setOnFocusChangeListener { view, _ ->
                updateFavoriteStyle(view as Button)
                view.scaleX = 1f
                view.scaleY = 1f
            }
        }
    }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; isFocusable = true; isFocusableInTouchMode = true
        setPadding(16, 0, 16, 0)
        setTextColor(Color.WHITE)
        background = roundedDrawable(panel, 0)
        stateListAnimator = null
        setOnFocusChangeListener { v, hasFocus ->
            v.background = roundedDrawable(if (hasFocus) accent else panel, 0)
            (v as Button).setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
            v.elevation = 0f
            v.scaleX = 1f; v.scaleY = 1f
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

        // Keep a built-in mock source available for the demo. If this is the
        // first version that registers it, make it the initial source once;
        // existing user sources remain saved and can still be selected later.
        val demoSource = SavedSource(
            "kaisertv-demo-222",
            "KaiserTV+ Demo",
            "xtream",
            "https://xtream-codes-mock-server.vercel.app",
            "test_user",
            "test_pass"
        )
        val demoWasAlreadySaved = savedSources.any { it.id == demoSource.id }
        if (!demoWasAlreadySaved) {
            savedSources.add(demoSource)
            persistSources()
            prefs.edit().putString("last_source_id", demoSource.id).apply()
        }

        if (savedSources.isNotEmpty()) {
            val lastId = prefs.getString("last_source_id", "") ?: ""
            val last = savedSources.firstOrNull { it.id == lastId }

            // Validate the cache against the selected source, not against items
            // belonging to any other saved source.
            val sourceToRestore = last ?: savedSources.lastOrNull()
            val restoredSourceItems = items.filter { it.sourceId == sourceToRestore?.id }
            val cacheHasLiveChannels = restoredSourceItems.any { it.type == "Canlı TV" && it.url.isNotBlank() }
            val cacheHasMovies = restoredSourceItems.any { it.type == "Filmler" }
            val cacheHasSeries = restoredSourceItems.any { it.type == "Diziler" }
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
                status.visibility = View.VISIBLE
                status.text = "${items.size} kayıtlı içerik hazır."
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
                            o.optString(if (type == "Filmler") "stream_icon" else "cover", o.optString("cover_big", o.optString("stream_icon", ""))),
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
        return super.onKeyDown(keyCode, event)
    }

    private fun handleSafeBack() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackPressAt <= 2000L) {
            lastBackPressAt = 0L
            showExitConfirmation()
        } else {
            lastBackPressAt = now
            Toast.makeText(this, "Çıkış menüsünü açmak için Geri tuşuna tekrar basın.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExitConfirmation() {
        val panelView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18), dp(16), dp(18), dp(16)); background = roundedDrawable(Color.rgb(13, 20, 33), 18, accent) }
        panelView.addView(TextView(this).apply { text = "KAISER TV+"; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(-1, dp(42)))
        panelView.addView(TextView(this).apply { text = "Uygulamadan çıkmak istiyor musunuz?"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(12)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val dialog = AlertDialog.Builder(this).setView(panelView).create()
        val exit = makeButton("Çıkış Yap", false) { dialog.dismiss(); finishAffinity() }
        val cancel = makeButton("Vazgeç", false) { dialog.dismiss() }.apply { textSize = 23f; setTypeface(null, android.graphics.Typeface.BOLD) }
        actions.addView(exit, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(0, 0, dp(10), 0) })
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(10), 0, 0, 0) })
        panelView.addView(actions)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setDimAmount(0.78f)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.56f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            cancel.post { cancel.requestFocus() }
        }
        dialog.show()
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
            setPadding(dp(18), dp(18), dp(64), dp(26))
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
        val watchButton = makeButton("FİLMİ İZLE", true) { play(movie) }.apply {
            gravity = Gravity.CENTER
            textSize = 15f
            minHeight = dp(42)
        }
        actions.addView(watchButton,
            LinearLayout.LayoutParams(dp(116), dp(38)).apply { setMargins(0, 6, dp(8), 0) })
        val favoriteButton = makeFavoriteButton(movie).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            minHeight = dp(46)
        }
        actions.addView(favoriteButton,
            LinearLayout.LayoutParams(dp(108), dp(38)).apply { setMargins(0, 6, 0, 0) })
        copy.addView(actions)
        watchButton.post { if (detailMovie?.favoriteKey == movie.favoriteKey) watchButton.requestFocus() }
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
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22070A11, 0xEE070A11.toInt()))
        }
        hero.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(18), dp(18), dp(48), dp(42))
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
        val seriesWatchButton = makeButton("DİZİYİ İZLE", true) {
            val allEpisodes = seriesEpisodeRows.flatMap { it.second }
            if (allEpisodes.isNotEmpty()) {
                pendingSeriesEpisodes = allEpisodes
                val seriesKey = series.seriesId.ifBlank { series.id }
                val savedIndex = getSharedPreferences("androtv_playback", MODE_PRIVATE)
                    .getInt("last_episode_$seriesKey", 0).coerceIn(0, allEpisodes.lastIndex)
                play(allEpisodes[savedIndex])
            } else Toast.makeText(this@MainActivity, "Bölümler henüz yüklenmedi.", Toast.LENGTH_SHORT).show()
        }.apply {
            gravity = Gravity.CENTER
            textSize = 14f
            minHeight = dp(56)
        }
        seriesWatchButton.textSize = 17f
        actions.addView(seriesWatchButton,
            LinearLayout.LayoutParams(dp(116), dp(38)).apply { setMargins(0, 8, dp(8), 0) })
        actions.addView(makeFavoriteButton(series).apply {
            textSize = 17f
            minHeight = dp(46)
        }, LinearLayout.LayoutParams(dp(108), dp(38)).apply { setMargins(0, 8, 0, 0) })
        copy.addView(actions)
        seriesWatchButton.post { if (detailSeries?.favoriteKey == series.favoriteKey) seriesWatchButton.requestFocus() }
        hero.addView(copy, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(350)).apply { setMargins(0, 0, 0, dp(18)) })

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
            else -> {
                val seasons = seriesEpisodeRows.map { it.first }
                if (selectedEpisodeSeason !in seasons) selectedEpisodeSeason = seasons.firstOrNull().orEmpty()
                val seasonSelector = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                seasons.forEach { season ->
                    seasonSelector.addView(makeButton("Sezon $season", season == selectedEpisodeSeason) {
                        selectedEpisodeSeason = season
                        renderSeriesDetail(series)
                    }.apply { textSize = 15f }, LinearLayout.LayoutParams(dp(132), dp(46)).apply {
                        setMargins(0, dp(4), dp(8), dp(8))
                    })
                }
                episodesPanel.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(seasonSelector)
                }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                val selectedEpisodes = seriesEpisodeRows.firstOrNull { it.first == selectedEpisodeSeason }?.second.orEmpty()
                selectedEpisodes.forEachIndexed { index, episode ->
                    val episodeRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(10), dp(12), dp(10))
                        // Episode row has no surrounding frame; only its own Watch button receives focus fill.
                        background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    }
                    episodeRow.addView(TextView(this).apply {
                        text = "${index + 1}."; textSize = 15f; setTextColor(accentBright)
                    }, LinearLayout.LayoutParams(38, ViewGroup.LayoutParams.WRAP_CONTENT))
                    episodeRow.addView(TextView(this).apply {
                        text = episode.name; textSize = 15f; maxLines = 2; setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    episodeRow.addView(makeButton("İzle", true) {
                        pendingSeriesEpisodes = seriesEpisodeRows.flatMap { it.second }
                        play(episode)
                    }.apply {
                        textSize = 15f; gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(116), dp(46)).apply { setMargins(12, 0, 0, 0) })
                    episodesPanel.addView(episodeRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
                }
            }
        }
    }

    private fun renderSettingsScreen() {
        // Settings is a dedicated full-screen workspace: hide the entire left navigation rail.
        if (::sidebar.isInitialized) sidebar.visibility = View.GONE
        mainHeader.visibility = View.GONE
        if (::dateClockRow.isInitialized) dateClockRow.visibility = View.GONE
        status.visibility = View.GONE
        categoryScroll.visibility = View.GONE
        mainContentScroll.visibility = View.VISIBLE
        content.removeAllViews()
        mainContentScroll.scrollTo(0, 0)
        fun heading(title: String, subtitle: String) {
            content.addView(TextView(this).apply {
                text = title; textSize = 27f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE); setPadding(dp(8), dp(16), dp(8), dp(4))
            })
            content.addView(TextView(this).apply {
                text = subtitle; textSize = 14f; setTextColor(muted); setPadding(dp(8), 0, dp(8), dp(16))
            })
        }
        var settingsGridRow: LinearLayout? = null
        var settingsGridCount = 0
        fun option(title: String, detail: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = roundedDrawable(panel, 16)
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = roundedDrawable(if (hasFocus) accent else panel, 16)
                    v.elevation = 0f
                    val cardRow = v as LinearLayout
                    val iconView = cardRow.getChildAt(0) as? TextView
                    val textColumn = cardRow.getChildAt(1) as? LinearLayout
                    iconView?.setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else accentBright)
                    textColumn?.let { column ->
                        (column.getChildAt(0) as? TextView)?.setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                        (column.getChildAt(1) as? TextView)?.setTextColor(if (hasFocus) Color.rgb(25, 38, 54) else muted)
                    }
                }
                setOnClickListener { action() }
            }
            val contentIcon = when (title) {
                "Kaynak Yönetimi" -> "▤"
                "İçerik Ayarları" -> "▦"
                "Tema ve Görünüm" -> "◈"
                "İzleme Geçmişi" -> "◷"
                else -> "◆"
            }
            card.addView(TextView(this).apply {
                text = contentIcon
                textSize = 34f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(accentBright)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, 0, dp(8)) })
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            textColumn.addView(TextView(this).apply {
                text = title; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            })
            textColumn.addView(TextView(this).apply {
                text = detail; textSize = 14f; setTextColor(muted); setPadding(0, dp(8), 0, 0)
            })
            card.addView(textColumn)
            if (settingsPage == "main") {
                if (settingsGridRow == null || settingsGridCount % 3 == 0) {
                    settingsGridRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.TOP
                    }
                    content.addView(settingsGridRow, LinearLayout.LayoutParams(-1, dp(142)).apply {
                        setMargins(dp(6), dp(7), dp(6), dp(7))
                    })
                }
                settingsGridRow?.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(5), 0, dp(5), 0)
                })
                settingsGridCount++
            } else {
                content.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(6), dp(5), dp(6), dp(7))
                })
            }
        }
        when (settingsPage) {
            "main" -> {
                heading("Ayarlar", "Bir bölümü seç; ayrı ekranda açılacak.")
                option("Kaynak Yönetimi", "M3U / Xtream kaynak ekle, düzenle") { settingsPage = "sources"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("İçerik Ayarları", "Kategoriler, gizleme ve sıralama") { settingsPage = "categories"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("Tema ve Görünüm", "Renk, arka plan ve arayüz stili") { settingsPage = "themes"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("İzleme Geçmişi", "Son izlenen içerikleri görüntüle") { settingsPage = "history"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("Hakkında", "Uygulama sürümü ve teknik bilgiler") { settingsPage = "about"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
            }
            "sources" -> {
                heading("Kaynaklar", "Kaynak ekle ve kayıtlı bağlantılarını buradan yönet.")
                // Keep the source-entry form fixed inside the Sources page; do not show a duplicate launch card.
                showConnectionDialog()
                if (savedSources.isEmpty()) {
                    content.addView(TextView(this).apply {
                        text = "Henüz kayıtlı kaynak yok."
                        textSize = 14f
                        setTextColor(muted)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    })
                } else {
                    savedSources.toList().forEach { source ->
                        val sourceRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(6), dp(8), dp(6))
                            // Transparent source container with a visible outline.
                            background = roundedDrawable(
                                Color.TRANSPARENT,
                                0,
                                Color.rgb(48, 57, 73)
                            )
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
                        val sourceSelect = CheckBox(this).apply {
                            buttonTintList = android.content.res.ColorStateList.valueOf(accentBright)
                            isFocusable = true
                            contentDescription = "${source.name} kaynağının içeriklerini göster"
                            setPadding(0, 0, dp(2), 0)
                            isChecked = prefs.getString("last_source_id", "") == source.id
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) {
                                    prefs.edit().putString("last_source_id", source.id).apply()
                                    // Display only the content belonging to the selected source.
                                    items.removeAll { it.sourceId != source.id }
                                    saveCachedItems()
                                    if (source.mode == "xtream") loadXtream(source, true) else loadM3u(source, true)
                                    render()
                                } else if (prefs.getString("last_source_id", "") == source.id) {
                                    isChecked = true
                                }
                            }
                        }
                        sourceRow.addView(sourceSelect, LinearLayout.LayoutParams(dp(38), dp(42)))
                        sourceRow.addView(makeButton("Yenile", false) {
                            if (source.mode == "xtream") loadXtream(source, true) else loadM3u(source, true)
                        }, LinearLayout.LayoutParams(dp(84), dp(42)).apply {
                            setMargins(dp(2), 0, dp(4), 0)
                        })
                        sourceRow.addView(makeButton("Düzenle", false) {
                            showConnectionDialog(source)
                        }, LinearLayout.LayoutParams(dp(92), dp(42)).apply {
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
                        }, LinearLayout.LayoutParams(dp(68), dp(42)).apply {
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
                        background = roundedDrawable(panel, 12)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        setOnFocusChangeListener { v, hasFocus ->
                            v.background = roundedDrawable(if (hasFocus) accent else panel, 12)
                            (v as TextView).setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else muted)
                        }
                        setOnClickListener {
                            selectedCategorySection = tab.first
                            render()
                            content.post { categoryTabs.getChildAt(tabInfo.indexOf(tab))?.requestFocus() }
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
                        background = roundedDrawable(panel, 10)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        setOnFocusChangeListener { v, hasFocus ->
                            v.background = roundedDrawable(if (hasFocus) accent else panel, 10)
                            (v as TextView).setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                            v.elevation = 0f
                        }
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
                            background = roundedDrawable(panel, 14)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true
                            setOnFocusChangeListener { v, hasFocus ->
                                v.background = roundedDrawable(if (hasFocus) accent else panel, 14)
                                val foreground = if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE
                                (v as ViewGroup).let { card ->
                                    (card.getChildAt(0) as? TextView)?.setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else if (isHidden) Color.WHITE else accentBright)
                                    val textColumn = card.getChildAt(1) as? LinearLayout
                                    textColumn?.let { column ->
                                        (column.getChildAt(0) as? TextView)?.setTextColor(foreground)
                                        (column.getChildAt(1) as? TextView)?.setTextColor(if (hasFocus) Color.rgb(25, 38, 54) else muted)
                                    }
                                }
                                v.elevation = 0f
                            }
                            setOnClickListener {
                                if (hiddenCategories.contains(hiddenKey)) hiddenCategories.remove(hiddenKey)
                                else hiddenCategories.add(hiddenKey)
                                categoryFocusRestoreKey = hiddenKey
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
                        if (categoryFocusRestoreKey == hiddenKey) {
                            row.post { row.requestFocus(); categoryFocusRestoreKey = null }
                        }
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
                        accent = theme.third
                        accentBright = theme.third
                        prefs.edit().putString("theme_name", theme.first).apply()
                        rootLayout.setBackgroundColor(bg); sidebar.setBackgroundColor(if (transparentMenu) Color.TRANSPARENT else panel); mainContentScroll.setBackgroundColor(bg)
                        window.statusBarColor = bg; window.navigationBarColor = bg
                        settingsPage = "themes"; render()
                    }
                }
                option("◧  Şeffaf menü: ${if (transparentMenu) "Açık" else "Kapalı"}",
                    if (transparentMenu) "Sol menü arka planı saydam; kapatmak için seç." else "Sol menü ayrı bir panel olarak görünür; saydam yapmak için seç.") {
                    transparentMenu = !transparentMenu
                    prefs.edit().putBoolean("transparent_menu", transparentMenu).apply()
                    sidebar.setBackgroundColor(if (transparentMenu) Color.TRANSPARENT else panel)
                    settingsPage = "themes"
                    render()
                }
            }
            "about" -> {
                content.setBackgroundColor(Color.rgb(10, 13, 19))
                val aboutPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(28), dp(24), dp(28), dp(24))
                    background = roundedDrawable(Color.rgb(10, 13, 19), 18)
                }
                val logo = android.text.SpannableString("▶ KAISER TV+")
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 9, 11, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.RelativeSizeSpan(0.72f), 11, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 11, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                aboutPanel.addView(TextView(this).apply {
                    text = logo
                    textSize = 34f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(34))
                })
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val version = packageInfo.versionName ?: "Bilinmiyor"
                val buildDate = try {
                    java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("tr", "TR")).format(java.util.Date(packageInfo.lastUpdateTime))
                } catch (_: Exception) { "Bilinmiyor" }
                val appSize = try {
                    val bytes = java.io.File(applicationInfo.sourceDir).length()
                    String.format(java.util.Locale("tr", "TR"), "%.1f MB", bytes / (1024.0 * 1024.0))
                } catch (_: Exception) { "Bilinmiyor" }
                listOf("Sürüm" to version, "Derleme tarihi" to buildDate, "Uygulama boyutu" to appSize).forEach { item ->
                    val infoRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                    infoRow.addView(TextView(this).apply {
                        text = item.first
                        textSize = 17f
                        setTextColor(muted)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    infoRow.addView(TextView(this).apply {
                        text = item.second
                        textSize = 17f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.END
                    })
                    aboutPanel.addView(infoRow)
                }
                content.addView(aboutPanel, LinearLayout.LayoutParams(-1, -1).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                })
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
                    connectTimeout = 12000
                    readTimeout = 12000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    setRequestProperty("Connection", "keep-alive")
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
                    while (bounds.outWidth / sampleSize > 360 || bounds.outHeight / sampleSize > 540) {
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

    private var pendingSeriesEpisodes: List<MediaItem> = emptyList()

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

            // Episode playback should appear in the series history as its parent series,
            // rather than being stored under the separate "Bölümler" type.
            val historyItem = if (item.type == "Bölümler") {
                items.firstOrNull { it.type == "Diziler" && it.seriesId == item.seriesId }
                    ?: item.copy(type = "Diziler")
            } else item
            saveRecent(historyItem)
            val intent = Intent(this, PlayerActivity::class.java)
                .putExtra("name", item.name)
                .putExtra("url", item.url)
                .putExtra("isLive", item.type == "Canlı TV")
                .putExtra("bufferMode", prefs.getString("player_buffer", "normal"))
                .putExtra("aspectMode", prefs.getString("player_aspect", "fit"))
                .putExtra("resumeEnabled", prefs.getBoolean("player_resume", true))
            if (item.type == "Bölümler") {
                // Keep episode navigation metadata in process memory, not Intent extras.
                val episodeList = pendingSeriesEpisodes
                PlayerActivity.pendingEpisodeNames = episodeList.map { it.name }
                PlayerActivity.pendingEpisodeUrls = episodeList.map { it.url }
                PlayerActivity.pendingEpisodeIndex = episodeList.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
                PlayerActivity.pendingSeriesTitle = historyItem.name
                PlayerActivity.pendingSeriesKey = item.seriesId.ifBlank { historyItem.seriesId.ifBlank { historyItem.id } }
            } else {
                PlayerActivity.pendingEpisodeNames = emptyList()
                PlayerActivity.pendingEpisodeUrls = emptyList()
                PlayerActivity.pendingEpisodeIndex = 0
                PlayerActivity.pendingSeriesTitle = ""
                PlayerActivity.pendingSeriesKey = ""
            }
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
                        AlertDialog.Builder(this).setTitle(seasonLabels[which]).setItems(chosen.map { it.name }.toTypedArray()) { _, epIndex ->
                            pendingSeriesEpisodes = chosen
                            play(chosen[epIndex])
                        }.setNegativeButton("Geri", null).show()
                    }.setNegativeButton("Kapat", null).show()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Bölümler yüklenemedi: ${e.message ?: "bağlantı hatası"}" } }
        }
    }

}
