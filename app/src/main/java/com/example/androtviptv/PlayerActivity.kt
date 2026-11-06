package com.example.androtviptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.SeekBar
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    companion object {
        @Volatile var pendingChannelNames: List<String> = emptyList()
        @Volatile var pendingChannelUrls: List<String> = emptyList()
        @Volatile var pendingChannelIndex: Int = 0
        @Volatile var pendingRecentChannelNames: List<String> = emptyList()
        @Volatile var pendingRecentChannelUrls: List<String> = emptyList()
        @Volatile var pendingEpisodeNames: List<String> = emptyList()
        @Volatile var pendingEpisodeUrls: List<String> = emptyList()
        @Volatile var pendingEpisodeIndex: Int = 0
        @Volatile var pendingSeriesTitle: String = ""
        @Volatile var pendingSeriesKey: String = ""
    }

    private var player: ExoPlayer? = null
    private var episodeNames: List<String> = emptyList()
    private var episodeUrls: List<String> = emptyList()
    private var selectedEpisodeIndex: Int = 0
    private var resumeKey: String = ""
    private var currentStreamUrl: String = ""
    private var originalStreamUrl: String = ""
    private var triedHlsFallback: Boolean = false
    private var usingHlsFallback: Boolean = false
    private var isLiveStream: Boolean = false
    private var retryCount = 0
    private var bufferingSinceMs = 0L
    private var lastStablePlaybackMs = 0L
    private var lastObservedPositionMs = -1L
    private var positionStalledSinceMs = 0L
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackWatchdog = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null && exo.playWhenReady && !isFinishing) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (exo.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                    if (now - bufferingSinceMs >= 18000L) {
                        restartCurrentStream("Yayın akışı 18 saniyeden uzun süre bekledi.")
                    }
                } else if (exo.playbackState == Player.STATE_READY) {
                    bufferingSinceMs = 0L
                    val position = exo.currentPosition
                    if (lastObservedPositionMs >= 0L && position <= lastObservedPositionMs) {
                        if (positionStalledSinceMs == 0L) positionStalledSinceMs = now
                        if (now - positionStalledSinceMs >= 12000L) {
                            restartCurrentStream("Oynatma konumu 12 saniyedir ilerlemiyor.")
                        }
                    } else {
                        positionStalledSinceMs = 0L
                    }
                    lastObservedPositionMs = position
                    if (lastStablePlaybackMs == 0L) lastStablePlaybackMs = now
                    if (now - lastStablePlaybackMs >= 60000L) {
                        retryCount = 0
                        lastStablePlaybackMs = now
                    }
                } else {
                    bufferingSinceMs = 0L
                    positionStalledSinceMs = 0L
                    lastObservedPositionMs = -1L
                }
            } else {
                positionStalledSinceMs = 0L
                lastObservedPositionMs = -1L
            }
            playbackHandler.postDelayed(this, 5000L)
        }
    }
    private var playerView: PlayerView? = null
    private var currentTracks: Tracks = Tracks.EMPTY
    private var subtitlePanel: LinearLayout? = null
    private var audioPanel: LinearLayout? = null
    private var progressBar: SeekBar? = null
    private var elapsedLabel: TextView? = null
    private var durationLabel: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var controlsRoot: View? = null
    private var centerControlsView: View? = null
    private var trackControlsView: View? = null
    private var progressControlsView: View? = null
    private var contentInfoView: TextView? = null
    private var subtitleButtonView: View? = null
    private var audioButtonView: View? = null
    private var episodesButtonView: View? = null
    private var episodesPanelView: LinearLayout? = null
    private var episodeOptionsView: LinearLayout? = null
    private var lastPlaybackFocusView: View? = null

    private fun playerThemeAccent(): Int {
        val theme = getSharedPreferences("androtv", Context.MODE_PRIVATE).getString("theme_name", "Antrasit • Mavi")
        return when (theme) {
            "Gece • Altın" -> 0xFFE0B85F.toInt()
            "Gece • Buzul", "Lacivert • Turkuaz" -> 0xFF54C6D9.toInt()
            "Orman • Zümrüt" -> 0xFF20C997.toInt()
            "Gece • Yakut" -> 0xFFF06479.toInt()
            "Gece • Lavanta", "Koyu • Mor" -> 0xFFA78BFA.toInt()
            else -> 0xFF00A6FF.toInt()
        }
    }

    private fun updateContentInfo() {
        val baseTitle = intent.getStringExtra("name")?.takeIf { it.isNotBlank() } ?: "Oynatıcı"
        val title = if (isLiveStream) {
            channelRows.getOrNull(selectedChannelIndex)?.takeIf { it.isNotBlank() } ?: baseTitle
        } else {
            val episode = episodeNames.getOrNull(selectedEpisodeIndex)
            if (episode != null && episode.isNotBlank() && episode != baseTitle) "$baseTitle  ·  $episode" else baseTitle
        }
        contentInfoView?.text = title
        contentInfoView?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
    }
    private var controlsHideTask = Runnable { setPlaybackControlsVisible(false) }
    private var controlsVisible = true

    private fun setPlaybackControlsVisible(visible: Boolean) {
        if (!visible) {
            val focused = currentFocus
            fun isInsideControls(view: View?, parent: View?): Boolean {
                var current = view
                while (current != null) {
                    if (current === parent) return true
                    current = current.parent as? View
                }
                return false
            }
            if (isInsideControls(focused, episodesPanelView)) {
                lastPlaybackFocusView = episodesButtonView
            } else if (isInsideControls(focused, centerControlsView) ||
                isInsideControls(focused, trackControlsView) ||
                isInsideControls(focused, progressControlsView)) {
                lastPlaybackFocusView = focused
            }
            // Episode/track popups must never remain floating after the controls disappear.
            episodesPanelView?.visibility = View.GONE
            subtitlePanel?.visibility = View.GONE
            audioPanel?.visibility = View.GONE
        }

        controlsVisible = visible
        centerControlsView?.visibility = if (visible) View.VISIBLE else View.GONE
        trackControlsView?.visibility = if (visible) View.VISIBLE else View.GONE
        progressControlsView?.visibility = if (visible) View.VISIBLE else View.GONE
        contentInfoView?.visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            // Android TV clears focus when the control row is set to GONE.
            // Restore the last focused control (CC on first launch) after it is visible again.
            controlsRoot?.post {
                if (!controlsVisible || isFinishing) return@post
                fun isInsideControls(view: View?, parent: View?): Boolean {
                    var current = view
                    while (current != null) {
                        if (current === parent) return true
                        current = current.parent as? View
                    }
                    return false
                }
                val focused = currentFocus
                val focusStillOnControls =
                    isInsideControls(focused, centerControlsView) ||
                    isInsideControls(focused, trackControlsView) ||
                    isInsideControls(focused, progressControlsView) ||
                    isInsideControls(focused, episodesPanelView)
                if (!focusStillOnControls) {
                    val restore = lastPlaybackFocusView?.takeIf { it.isShown && it.isFocusable }
                        ?: subtitleButtonView?.takeIf { it.isShown && it.isFocusable }
                        ?: playButtonView()
                    restore?.requestFocus()
                }
            }
        }
    }

    private fun showPlaybackControls() {
        uiHandler.removeCallbacks(controlsHideTask)
        setPlaybackControlsVisible(true)
        uiHandler.postDelayed(controlsHideTask, 3000L)
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            val exo = player
            val bar = progressBar
            if (exo != null && bar != null && exo.duration > 0 && !isLiveStream) {
                bar.max = exo.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                bar.progress = exo.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                elapsedLabel?.text = formatTime(exo.currentPosition)
                durationLabel?.text = " •  " + formatTime(exo.duration)
            } else if (isLiveStream) {
                elapsedLabel?.text = "CANLI"
                durationLabel?.text = ""
            }
            uiHandler.postDelayed(this, 500L)
        }
    }
    private var channelOverlay: LinearLayout? = null
    private var channelRows: List<String> = emptyList()
    private var channelUrls: List<String> = emptyList()
    private var selectedChannelIndex = 0
    private var highlightedChannelIndex = 0
    private var recentChannelRows: List<String> = emptyList()
    private var recentChannelUrls: List<String> = emptyList()
    private var overlayIsRecent = false
    private var savedChannelRows: List<String> = emptyList()
    private var savedChannelUrls: List<String> = emptyList()
    private val playbackPrefs by lazy { getSharedPreferences("androtv_playback", Context.MODE_PRIVATE) }

    private fun createStreamMediaItem(url: String): MediaItem {
        val cleanUrl = url.substringBefore("?").lowercase()
        val builder = MediaItem.Builder().setUri(url)
        if (cleanUrl.endsWith(".m3u8") || cleanUrl.endsWith(".m3u8/")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    private fun toHlsFallbackUrl(url: String): String? {
        // Replace the stream extension in the URL path only; keep any auth/query token intact.
        val suffixStart = listOf(url.indexOf('?'), url.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull() ?: url.length
        val path = url.substring(0, suffixStart)
        val suffix = url.substring(suffixStart)
        if (!path.endsWith(".ts", ignoreCase = true)) return null
        return path.dropLast(3) + ".m3u8" + suffix
    }

    private fun savePlaybackPosition() {
        if (isLiveStream) return
        val exo = player ?: return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        if (exo.duration > 0 && pos >= exo.duration - 5000L) {
            playbackPrefs.edit().remove(resumeKey).apply()
        } else if (pos > 3000L) {
            playbackPrefs.edit().putLong(resumeKey, pos).apply()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPlayerImmersiveMode()
        val title = intent.getStringExtra("name") ?: "Oynatıcı"
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        channelRows = pendingChannelNames
        channelUrls = pendingChannelUrls
        selectedChannelIndex = pendingChannelIndex.coerceIn(0, (channelUrls.size - 1).coerceAtLeast(0))
        highlightedChannelIndex = selectedChannelIndex
        recentChannelRows = pendingRecentChannelNames
        recentChannelUrls = pendingRecentChannelUrls
        pendingChannelNames = emptyList()
        pendingChannelUrls = emptyList()
        pendingRecentChannelNames = emptyList()
        pendingRecentChannelUrls = emptyList()
        episodeNames = pendingEpisodeNames
        episodeUrls = pendingEpisodeUrls
        selectedEpisodeIndex = pendingEpisodeIndex.coerceIn(0, (episodeUrls.size - 1).coerceAtLeast(0))
        pendingEpisodeNames = emptyList()
        pendingEpisodeUrls = emptyList()
        pendingEpisodeIndex = 0
        pendingChannelIndex = 0
        currentStreamUrl = url.trim()
        originalStreamUrl = currentStreamUrl
        triedHlsFallback = false
        usingHlsFallback = false
        isLiveStream = intent.getBooleanExtra("isLive", false)
        resumeKey = "position_" + url.hashCode().toString()
        if (isLiveStream) playbackPrefs.edit().remove(resumeKey).apply()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        playerView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, 0x00000000, 0x99000000.toInt()))
        }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        controlsRoot = root
        contentInfoView = TextView(this).apply {
            text = title
            textSize = 17f
            maxLines = 2
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x99101824.toInt())
                setStroke(dp(1), 0x66079BE8)
            }
        }
        root.addView(contentInfoView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(dp(24), dp(22), dp(24), 0)
        })
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            clipChildren = false
            clipToPadding = false
            background = null
            elevation = 0f
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, dp(158), Gravity.BOTTOM).apply {
            setMargins(dp(28), 0, dp(28), dp(16))
        })

        val progressLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsedLabel = TextView(this).apply { text = "00:00"; textSize = 12f; setTextColor(Color.WHITE); minWidth = dp(42) }
        durationLabel = TextView(this).apply { text = "00:00"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.RIGHT; minWidth = dp(42) }
        progressBar = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(playerThemeAccent())
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsedLabel?.text = formatTime(value.toLong())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { player?.seekTo(seekBar?.progress?.toLong() ?: 0L) }
            })
        }
        progressLine.addView(elapsedLabel)
        progressLine.addView(progressBar, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        progressLine.addView(durationLabel)
        panel.addView(progressLine, LinearLayout.LayoutParams(-1, dp(30)))
        progressControlsView = progressLine

        val centerControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(5))
        }
        fun roundControl(symbol: String, label: String, big: Boolean = false, action: () -> Unit) = TextView(this).apply {
            text = if (label.isBlank()) symbol else "$symbol\n$label"
            textSize = if (big) 20f else 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                setColor(if (big) playerThemeAccent() else 0xCC080A0E.toInt())
                setStroke(dp(if (big) 2 else 1), if (big) playerThemeAccent() else 0x665E6D7D)
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                    setColor(if (hasFocus || big) playerThemeAccent() else 0xCC080A0E.toInt())
                    setStroke(dp(if (hasFocus || big) 3 else 1), if (hasFocus || big) playerThemeAccent() else 0x665E6D7D)
                }
                v.elevation = if (hasFocus) dp(10).toFloat() else 0f
            }
            setOnClickListener { action() }
        }
        val previousControl = roundControl("◀|", "", false) {
            if (isLiveStream && channelUrls.isNotEmpty()) tuneChannel((selectedChannelIndex - 1).let { if (it < 0) channelUrls.lastIndex else it })
            else if (episodeUrls.isNotEmpty()) playEpisodeAt((player?.currentMediaItemIndex ?: selectedEpisodeIndex) - 1)
            else player?.seekToPreviousMediaItem()
        }
        val rewindControl = roundControl("≪", "10 sn") { player?.seekBack() }
        val playControl = roundControl("Ⅱ", "", true) { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        val forwardControl = roundControl("≫", "10 sn") { player?.seekForward() }
        val nextControl = roundControl("|▶", "", false) {
            if (isLiveStream && channelUrls.isNotEmpty()) tuneChannel((selectedChannelIndex + 1) % channelUrls.size)
            else if (episodeUrls.isNotEmpty()) playEpisodeAt((player?.currentMediaItemIndex ?: selectedEpisodeIndex) + 1)
            else player?.seekToNextMediaItem()
        }
        listOf(previousControl, rewindControl, playControl, forwardControl, nextControl).forEachIndexed { i, button ->
            val w = if (i == 2) 54 else 46
            val h = if (i == 2) 50 else 44
            centerControls.addView(button, LinearLayout.LayoutParams(dp(w), dp(h)).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
        val centerButtons = listOf(previousControl, rewindControl, playControl, forwardControl, nextControl)
        centerButtons.forEachIndexed { index, button ->
            button.id = View.generateViewId()
            button.nextFocusLeftId = centerButtons[(index - 1).coerceAtLeast(0)].id
            button.nextFocusRightId = centerButtons[(index + 1).coerceAtMost(centerButtons.lastIndex)].id
            button.nextFocusUpId = playControl.id
        }
        progressBar?.id = View.generateViewId()
        centerButtons.forEach { it.nextFocusUpId = progressBar!!.id }
        progressBar?.nextFocusDownId = playControl.id
        panel.addView(centerControls, LinearLayout.LayoutParams(-1, dp(70)))
        centerControlsView = centerControls

        fun createTrackPanel(type: Int): LinearLayout {
            val trackPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    setColor(0xF018202C.toInt())
                    cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                    setStroke(dp(1), playerThemeAccent())
                }
                visibility = View.GONE
                elevation = dp(18).toFloat()
            }
            trackPanel.addView(TextView(this).apply {
                text = if (type == C.TRACK_TYPE_TEXT) "Altyazı seç" else "Ses dili seç"
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(4), dp(2), dp(4), dp(7))
            })
            val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(options) }
            trackPanel.addView(scroll, LinearLayout.LayoutParams(-1, dp(174)))
            trackPanel.tag = Pair(type, options)
            return trackPanel
        }
        subtitlePanel = createTrackPanel(C.TRACK_TYPE_TEXT)
        audioPanel = createTrackPanel(C.TRACK_TYPE_AUDIO)
        val episodesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF018202C.toInt())
                cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                setStroke(dp(1), playerThemeAccent())
            }
            visibility = View.GONE
            elevation = dp(18).toFloat()
        }
        episodesPanel.addView(TextView(this).apply {
            text = "Bölümler"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(2), dp(4), dp(7))
        })
        val episodeOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        episodesPanel.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(episodeOptions)
        }, LinearLayout.LayoutParams(-1, dp(174)))
        episodesPanelView = episodesPanel
        episodeOptionsView = episodeOptions

        val optionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun optionButton(label: String, sub: String, action: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                setColor(0xCC080A0E.toInt())
                setStroke(dp(1), 0x554F6072)
            }
            addView(TextView(this@PlayerActivity).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            if (sub.isNotBlank()) addView(TextView(this@PlayerActivity).apply {
                text = sub
                textSize = 10f
                setTextColor(0xFFB9C4D0.toInt())
                gravity = Gravity.CENTER
            })
            setOnFocusChangeListener { v, hasFocus ->
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                    setColor(if (hasFocus) playerThemeAccent() else 0xCC080A0E.toInt())
                    setStroke(dp(if (hasFocus) 2 else 1), if (hasFocus) playerThemeAccent() else 0x554F6072)
                }
                v.elevation = if (hasFocus) dp(8).toFloat() else 0f
            }
            setOnClickListener { action() }
        }
        fun trackOption(symbol: String, panelView: LinearLayout) = optionButton(symbol, "") {
            episodesPanel.visibility = View.GONE
            val other = if (panelView === subtitlePanel) audioPanel else subtitlePanel
            other?.visibility = View.GONE
            if (panelView.visibility == View.VISIBLE) {
                panelView.visibility = View.GONE
            } else {
                refreshTrackChoices()
                panelView.visibility = View.VISIBLE
                panelView.bringToFront()
                val opts = (panelView.tag as? Pair<*, *>)?.second as? LinearLayout
                opts?.post {
                    val first = opts.getChildAt(0)
                    if (first != null) first.requestFocus() else panelView.requestFocus()
                }
            }
            showPlaybackControls()
        }
        val subtitleButton = trackOption("CC", subtitlePanel!!)
        val audioButton = trackOption("♫", audioPanel!!)
        val episodesButton = optionButton("☷", "") {
            subtitlePanel?.visibility = View.GONE
            audioPanel?.visibility = View.GONE
            if (episodeNames.isEmpty()) {
                android.widget.Toast.makeText(this, "Bölüm listesi bulunamadı.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                episodeOptions.removeAllViews()
                episodeNames.forEachIndexed { index, episodeName ->
                    val item = TextView(this).apply {
                        text = episodeName
                        textSize = 14f
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(Color.WHITE)
                        setPadding(dp(10), dp(7), dp(10), dp(7))
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        background = GradientDrawable().apply {
                            cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                            setColor(Color.TRANSPARENT)
                        }
                        setOnFocusChangeListener { focused, hasFocus ->
                            focused.background = GradientDrawable().apply {
                                cornerRadius = dp(if (getSharedPreferences("androtv", Context.MODE_PRIVATE).getBoolean("oval_buttons", false)) 40 else 0).toFloat()
                                setColor(if (hasFocus) playerThemeAccent() else Color.TRANSPARENT)
                                if (hasFocus) setStroke(dp(2), playerThemeAccent())
                            }
                        }
                        setOnClickListener {
                            episodesPanel.visibility = View.GONE
                            playEpisodeAt(index)
                            showPlaybackControls()
                        }
                    }
                    episodeOptions.addView(item, LinearLayout.LayoutParams(-1, dp(40)))
                }
                episodesPanel.visibility = View.VISIBLE
                episodesPanel.bringToFront()
                episodeOptions.post { episodeOptions.getChildAt(0)?.requestFocus() }
                showPlaybackControls()
            }
        }
        listOf(subtitleButton, audioButton, episodesButton).forEach { button ->
            optionRow.addView(button, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        subtitleButtonView = subtitleButton
        audioButtonView = audioButton
        episodesButtonView = episodesButton
        val optionButtons = listOf(subtitleButton, audioButton, episodesButton)
        optionButtons.forEachIndexed { index, button ->
            button.id = View.generateViewId()
            button.nextFocusLeftId = optionButtons[(index - 1).coerceAtLeast(0)].id
            button.nextFocusRightId = optionButtons[(index + 1).coerceAtMost(optionButtons.lastIndex)].id
            button.nextFocusUpId = progressBar?.id ?: View.NO_ID
        }
        centerButtons.forEach { it.nextFocusDownId = subtitleButton.id }
        optionButtons.forEach { it.nextFocusUpId = playControl.id }
        val optionStack = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        optionStack.addView(optionRow, FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.END))
        root.addView(subtitlePanel!!, FrameLayout.LayoutParams(dp(280), dp(210), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(28); bottomMargin = dp(116) })
        root.addView(audioPanel!!, FrameLayout.LayoutParams(dp(280), dp(210), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(28); bottomMargin = dp(116) })
        root.addView(episodesPanel, FrameLayout.LayoutParams(dp(320), dp(210), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(28); bottomMargin = dp(116) })
        panel.addView(optionStack, LinearLayout.LayoutParams(-1, dp(52)))
        trackControlsView = optionStack

        view.post {
            val controlIds = intArrayOf(
                androidx.media3.ui.R.id.exo_play,
                androidx.media3.ui.R.id.exo_pause,
                androidx.media3.ui.R.id.exo_ffwd,
                androidx.media3.ui.R.id.exo_rew,
                androidx.media3.ui.R.id.exo_prev,
                androidx.media3.ui.R.id.exo_next,
                androidx.media3.ui.R.id.exo_repeat_toggle,
                androidx.media3.ui.R.id.exo_settings,
                androidx.media3.ui.R.id.exo_subtitle
            )
            controlIds.forEach { controlId ->
                val control = view.findViewById<View>(controlId) ?: return@forEach
                control.isFocusable = true
                control.isFocusableInTouchMode = true
                control.setOnFocusChangeListener { focusedView, hasFocus ->
                    focusedView.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(if (hasFocus) playerThemeAccent() else Color.TRANSPARENT)
                        if (hasFocus) setStroke(dp(2), playerThemeAccent())
                    }
                    focusedView.elevation = if (hasFocus) dp(6).toFloat() else 0f
                }
            }
        }
        setContentView(root)
        updateContentInfo()
        root.isClickable = true
        root.setOnClickListener { showPlaybackControls() }
        showPlaybackControls()
        if (url.isBlank()) {
            android.widget.Toast.makeText(this, "Bu içerik için yayın adresi bulunamadı.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        try {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("KaiserTv+/3.0 (Android TV)")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpFactory)
            val mediaItem = createStreamMediaItem(currentStreamUrl)
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 90000, 2500, 7000)
                .build()
            player = ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build().also { exo ->
                    view.player = exo
                    exo.addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) { currentTracks = tracks; refreshTrackChoices() }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            if (!isLiveStream && episodeUrls.isNotEmpty()) {
                                val index = exo.currentMediaItemIndex
                                if (index in episodeUrls.indices) {
                                    selectedEpisodeIndex = index
                                    updateContentInfo()
                                    currentStreamUrl = episodeUrls[index]
                                    originalStreamUrl = currentStreamUrl
                                    resumeKey = "position_" + currentStreamUrl.hashCode().toString()
                                    triedHlsFallback = false
                                    usingHlsFallback = false
                                }
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) { playControl.text = if (isPlaying) "Ⅱ" else "▶" }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED && isLiveStream) {
                                val cleanUrl = currentStreamUrl.substringBefore("?").lowercase()
                                if (!triedHlsFallback && cleanUrl.endsWith(".ts")) {
                                    triedHlsFallback = true
                                    usingHlsFallback = true
                                    currentStreamUrl = toHlsFallbackUrl(currentStreamUrl) ?: currentStreamUrl
                                    retryCount = 0
                                    android.util.Log.w("KaiserTVPlayer", "Live TS stream ended; trying HLS URL")
                                    restartCurrentStream("Canlı yayın için alternatif akış deneniyor.")
                                } else {
                                    android.util.Log.w("KaiserTVPlayer", "Live stream ended unexpectedly; reconnecting")
                                    restartCurrentStream("Canlı yayın sona erdi gibi görünüyor.")
                                }
                                return
                            }
                            if (playbackState == Player.STATE_BUFFERING) {
                                if (bufferingSinceMs == 0L) bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
                            } else if (playbackState == Player.STATE_READY) {
                                bufferingSinceMs = 0L
                                if (lastStablePlaybackMs == 0L) lastStablePlaybackMs = android.os.SystemClock.elapsedRealtime()
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.e("KaiserTVPlayer", "Playback error: ${error.errorCodeName}", error)
                            if (isLiveStream && usingHlsFallback) {
                                usingHlsFallback = false
                                currentStreamUrl = originalStreamUrl
                                retryCount = 0
                                android.util.Log.w("KaiserTVPlayer", "HLS fallback failed; returning to original stream")
                            }
                            restartCurrentStream("Akış hatası: ${error.errorCodeName}")
                        }
                    })
                    val resumePosition = if (isLiveStream) 0L else playbackPrefs.getLong(resumeKey, 0L)
                    if (episodeUrls.isNotEmpty()) {
                        val queue = episodeUrls.map { createStreamMediaItem(it) }
                        exo.setMediaItems(queue, selectedEpisodeIndex, resumePosition)
                    } else {
                        exo.setMediaItem(mediaItem)
                    }
                    exo.prepare()
                    exo.playWhenReady = true
                }
            playbackHandler.removeCallbacks(playbackWatchdog)
            playbackHandler.postDelayed(playbackWatchdog, 5000L)
            // Start remote navigation on an actual control, not on PlayerView,
            // whose default key handling can swallow DPAD up/down.
            subtitleButton.post { subtitleButton.requestFocus() }
            refreshTrackChoices()
            uiHandler.post(uiTicker)
        } catch (e: Exception) {
            player?.release()
            player = null
            android.widget.Toast.makeText(this, "Video başlatılamadı. Yayın adresini kontrol et.", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val key = event.keyCode
        val focused = currentFocus
        val episodeMenuOpen = episodesPanelView?.visibility == View.VISIBLE
        val trackMenuOpen = subtitlePanel?.visibility == View.VISIBLE || audioPanel?.visibility == View.VISIBLE
        val centerRow = centerControlsView as? ViewGroup

        fun requestCenter(index: Int = 2): Boolean {
            val target = centerRow?.getChildAt(index) ?: return false
            showPlaybackControls()
            return target.requestFocus()
        }

        fun activeTrackPanel(): LinearLayout? =
            if (audioPanel?.visibility == View.VISIBLE) audioPanel else subtitlePanel

        fun trackOptions(panel: LinearLayout?): ViewGroup? =
            (panel?.tag as? Pair<*, *>)?.second as? ViewGroup

        fun isInside(view: View?, parent: ViewGroup?): Boolean {
            if (view == null || parent == null) return false
            return generateSequence(view as View?) { it.parent as? View }.any { it === parent }
        }

        when (key) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (channelOverlay != null) { hideChannelOverlay(); return true }
                if (episodesPanelView?.visibility == View.VISIBLE) {
                    episodesPanelView?.visibility = View.GONE
                    episodesButtonView?.requestFocus()
                    showPlaybackControls()
                    return true
                }
                if (audioPanel?.visibility == View.VISIBLE) {
                    audioPanel?.visibility = View.GONE
                    audioButtonView?.requestFocus()
                    return true
                }
                if (subtitlePanel?.visibility == View.VISIBLE) {
                    subtitlePanel?.visibility = View.GONE
                    subtitleButtonView?.requestFocus()
                    return true
                }
                // First BACK dismisses the visible playback controls; a second BACK exits.
                if (controlsVisible) {
                    setPlaybackControlsVisible(false)
                    return true
                }
                finish()
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (channelOverlay != null) { tuneChannel(highlightedChannelIndex); return true }
                if (episodeMenuOpen && focused?.isClickable == true) {
                    focused.performClick()
                    showPlaybackControls()
                    return true
                }
                if (focused === subtitleButtonView || focused === audioButtonView || focused === episodesButtonView) {
                    focused?.performClick()
                    showPlaybackControls()
                    return true
                }
                if (trackMenuOpen && isInside(focused, trackOptions(activeTrackPanel()))) {
                    val panel = activeTrackPanel()
                    val options = trackOptions(panel)
                    if (isInside(focused, options)) {
                        focused?.performClick()
                        showPlaybackControls()
                        return true
                    }
                    val first = options?.getChildAt(0)
                    if (first != null) {
                        first.requestFocus()
                        showPlaybackControls()
                        return true
                    }
                }
                when {
                    focused === subtitleButtonView || focused === audioButtonView ||
                        (trackControlsView as? FrameLayout)?.getChildAt(0)?.let { row ->
                            row is ViewGroup && (0 until row.childCount).any { row.getChildAt(it) === focused }
                        } == true -> { focused?.performClick(); showPlaybackControls(); return true }
                    focused === playButtonView() -> {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        showPlaybackControls()
                        return true
                    }
                    focused is View && centerRow != null &&
                        (0 until centerRow.childCount).any { centerRow.getChildAt(it) === focused } -> {
                        focused.performClick()
                        showPlaybackControls()
                        return true
                    }
                    focused is View && focused !is SeekBar -> {
                        if (focused.isClickable && focused !== playerView) { focused.performClick(); showPlaybackControls(); return true }
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        showPlaybackControls()
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (channelOverlay != null) return super.dispatchKeyEvent(event)
                if (episodeMenuOpen) {
                    // Episode entries form a vertical list; keep horizontal input inside the popup.
                    showPlaybackControls()
                    return true
                }

                val centerIndex = centerRow?.let { row ->
                    (0 until row.childCount).firstOrNull { row.getChildAt(it) === focused }
                }
                if (centerIndex != null && centerRow != null) {
                    val next = (centerIndex + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                        .coerceIn(0, centerRow.childCount - 1)
                    if (next != centerIndex) centerRow.getChildAt(next)?.requestFocus()
                    showPlaybackControls()
                    return true
                }

                if (trackMenuOpen) {
                    val panel = activeTrackPanel()
                    val options = trackOptions(panel)
                    if (isInside(focused, options)) return true
                    if (key == KeyEvent.KEYCODE_DPAD_RIGHT && panel === subtitlePanel) {
                        audioButtonView?.requestFocus(); showPlaybackControls(); return true
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT && panel === audioPanel) {
                        subtitleButtonView?.requestFocus(); showPlaybackControls(); return true
                    }
                    return true
                }

                if (focused === subtitleButtonView && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    audioButtonView?.requestFocus(); showPlaybackControls(); return true
                }
                if (focused === audioButtonView && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    subtitleButtonView?.requestFocus(); showPlaybackControls(); return true
                }
                val optionRowView = (trackControlsView as? FrameLayout)?.getChildAt(0) as? ViewGroup
                val optionIndex = optionRowView?.let { row -> (0 until row.childCount).firstOrNull { row.getChildAt(it) === focused } }
                if (optionIndex != null && optionRowView != null) {
                    val next = (optionIndex + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, optionRowView.childCount - 1)
                    optionRowView.getChildAt(next)?.requestFocus()
                    return true
                }

                if (progressBar?.hasFocus() == true) {
                    val exo = player
                    if (exo != null && !isLiveStream && exo.duration > 0) {
                        val delta = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 10000L else -10000L
                        exo.seekTo((exo.currentPosition + delta).coerceIn(0L, exo.duration))
                        showPlaybackControls()
                        return true
                    }
                    return true
                }

                val exo = player
                if (exo != null && !isLiveStream && exo.duration > 0) {
                    val delta = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 10000L else -10000L
                    exo.seekTo((exo.currentPosition + delta).coerceIn(0L, exo.duration))
                    showPlaybackControls()
                    return true
                }
                if (isLiveStream && channelUrls.isNotEmpty()) {
                    val next = if (key == KeyEvent.KEYCODE_DPAD_RIGHT)
                        (selectedChannelIndex + 1) % channelUrls.size
                    else (selectedChannelIndex - 1).let { if (it < 0) channelUrls.lastIndex else it }
                    tuneChannel(next)
                    showPlaybackControls()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (channelOverlay != null && channelUrls.isNotEmpty()) {
                    highlightedChannelIndex = (highlightedChannelIndex +
                        if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                        .coerceIn(0, channelUrls.lastIndex)
                    refreshChannelHighlight()
                    return true
                }

                showPlaybackControls()
                if (episodeMenuOpen) {
                    val options = episodeOptionsView
                    val count = options?.childCount ?: 0
                    if (count == 0) return true
                    val index = (0 until count).firstOrNull { options?.getChildAt(it) === focused }
                    val next = when {
                        index == null && key == KeyEvent.KEYCODE_DPAD_DOWN -> 0
                        index == null -> return true
                        key == KeyEvent.KEYCODE_DPAD_DOWN -> (index + 1).coerceAtMost(count - 1)
                        index == 0 -> { episodesButtonView?.requestFocus(); return true }
                        else -> index - 1
                    }
                    val target = options?.getChildAt(next)
                    target?.requestFocus()
                    val scroll = episodesPanelView?.getChildAt(1) as? ScrollView
                    if (target != null) scroll?.post { scroll.smoothScrollTo(0, target.top) }
                    return true
                }
                if (trackMenuOpen) {
                    val options = trackOptions(activeTrackPanel())
                    val count = options?.childCount ?: 0
                    if (count == 0) return true
                    val index = (0 until count).firstOrNull { options?.getChildAt(it) === focused }
                    val next = when {
                        index == null -> 0
                        key == KeyEvent.KEYCODE_DPAD_DOWN -> (index + 1).coerceAtMost(count - 1)
                        else -> (index - 1).coerceAtLeast(0)
                    }
                    options?.getChildAt(next)?.requestFocus()
                    return true
                }

                val centerIndex = centerRow?.let { row ->
                    (0 until row.childCount).firstOrNull { row.getChildAt(it) === focused }
                }
                val optionRowView = (trackControlsView as? FrameLayout)?.getChildAt(0) as? ViewGroup
                val onOptions = optionRowView?.let { row -> (0 until row.childCount).any { row.getChildAt(it) === focused } } == true
                when {
                    key == KeyEvent.KEYCODE_DPAD_UP && centerIndex != null -> {
                        progressBar?.requestFocus(); return true
                    }
                    key == KeyEvent.KEYCODE_DPAD_DOWN && focused === progressBar -> {
                        return requestCenter(2)
                    }
                    key == KeyEvent.KEYCODE_DPAD_DOWN && centerIndex != null -> {
                        subtitleButtonView?.requestFocus(); return true
                    }
                    key == KeyEvent.KEYCODE_DPAD_UP && onOptions -> {
                        return requestCenter(2)
                    }
                    key == KeyEvent.KEYCODE_DPAD_UP && (focused == null || focused === playerView) -> {
                        progressBar?.requestFocus(); return true
                    }
                    key == KeyEvent.KEYCODE_DPAD_DOWN && focused === playerView -> {
                        progressBar?.requestFocus(); return true
                    }
                    else -> return true
                }
            }
        }

        if (key !in intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) showPlaybackControls()
        return super.dispatchKeyEvent(event)
    }

    private fun playButtonView(): View? = centerControlsView?.let { container ->
        (container as? ViewGroup)?.getChildAt(2)
    }

    private fun showRecentOverlay() {
        if (recentChannelUrls.isEmpty()) {
            android.widget.Toast.makeText(this, "Henüz son izlenen kanal yok.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        savedChannelRows = channelRows
        savedChannelUrls = channelUrls
        val currentUrl = channelUrls.getOrNull(selectedChannelIndex)
        channelRows = recentChannelRows
        channelUrls = recentChannelUrls
        selectedChannelIndex = channelUrls.indexOf(currentUrl).coerceAtLeast(0)
        highlightedChannelIndex = selectedChannelIndex
        overlayIsRecent = true
        showChannelOverlay()
        val title = (channelOverlay?.getChildAt(0) as? TextView)
        title?.text = "SON İZLENEN KANALLAR"
    }

    private fun showChannelOverlay() {
        if (channelOverlay != null || channelUrls.isEmpty()) return
        val root = findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as? FrameLayout ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(0xD9080A0E.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xAADEB55B.toInt()) }
            elevation = dp(12).toFloat()
        }
        panel.addView(TextView(this).apply { text = "CANLI KANALLAR"; textSize = 17f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(10)) })
        val scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelRows.forEachIndexed { index, name ->
            list.addView(TextView(this).apply {
                text = (if (index == selectedChannelIndex) "▶  " else "") + name
                textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9)); background = channelRowBackground(index == highlightedChannelIndex)
                minHeight = dp(40); tag = index
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(4)) })
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(400)))
        val lp = FrameLayout.LayoutParams(dp(310), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(24); topMargin = dp(12); bottomMargin = dp(12) }
        root.addView(panel, lp)
        channelOverlay = panel
        refreshChannelHighlight()
    }

    private fun refreshChannelHighlight() {
        val panel = channelOverlay ?: return
        val scroll = panel.getChildAt(1) as? ScrollView ?: return
        val list = scroll.getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? TextView ?: continue
            val index = row.tag as? Int ?: continue
            row.text = (if (index == selectedChannelIndex) "▶  " else "") + channelRows[index]
            row.background = channelRowBackground(index == highlightedChannelIndex)
        }
        val target = list.getChildAt(highlightedChannelIndex)
        if (target != null) { val y = target.top; scroll.post { scroll.smoothScrollTo(0, (y - scroll.height / 2 + target.height / 2).coerceAtLeast(0)) } }
    }

    private fun channelRowBackground(highlighted: Boolean) = GradientDrawable().apply {
        setColor(if (highlighted) 0xAADEB55B.toInt() else 0x33000000)
        cornerRadius = dp(8).toFloat()
        if (highlighted) setStroke(dp(1), 0xFFDEB55B.toInt())
    }

    private fun restartCurrentStream(reason: String) {
        val exo = player ?: return
        if (currentStreamUrl.isBlank() || isFinishing || retryCount >= 3) {
            if (retryCount >= 3) {
                android.widget.Toast.makeText(this, "Yayın birkaç kez yeniden bağlanamadı. Kanalı tekrar seçmeyi dene.", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        retryCount++
        bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
        positionStalledSinceMs = 0L
        lastObservedPositionMs = -1L
        android.util.Log.w("KaiserTVPlayer", "$reason Yeniden bağlanma denemesi $retryCount/3")
        android.widget.Toast.makeText(this, "Yayın yeniden bağlanıyor… ($retryCount/3)", android.widget.Toast.LENGTH_SHORT).show()
        playbackHandler.postDelayed({
            if (!isFinishing && player === exo) {
                try {
                    exo.setMediaItem(createStreamMediaItem(currentStreamUrl))
                    exo.prepare()
                    exo.playWhenReady = true
                } catch (e: Exception) {
                    android.util.Log.e("KaiserTVPlayer", "Reconnect failed", e)
                }
            }
        }, 1200L)
    }

    private fun tuneChannel(index: Int) {
        if (index !in channelUrls.indices) return
        val url = channelUrls[index]
        selectedChannelIndex = index
        currentStreamUrl = url
        originalStreamUrl = url
        triedHlsFallback = false
        usingHlsFallback = false
        retryCount = 0
        bufferingSinceMs = 0L
        lastStablePlaybackMs = 0L
        resumeKey = "position_" + url.hashCode().toString()
        player?.let { exo -> exo.setMediaItem(createStreamMediaItem(url)); exo.prepare(); exo.playWhenReady = true }
        refreshChannelHighlight()
        updateContentInfo()
        hideChannelOverlay()
    }

    private fun hideChannelOverlay() {
        val panel = channelOverlay ?: return
        (panel.parent as? ViewGroup)?.removeView(panel)
        channelOverlay = null
        if (overlayIsRecent) {
            val currentUrl = channelUrls.getOrNull(selectedChannelIndex)
            channelRows = savedChannelRows
            channelUrls = savedChannelUrls
            selectedChannelIndex = channelUrls.indexOf(currentUrl).coerceAtLeast(0)
            highlightedChannelIndex = selectedChannelIndex
            overlayIsRecent = false
        }
        playerView?.requestFocus()
    }


    private fun playEpisodeAt(index: Int) {
        val exo = player ?: return
        if (episodeUrls.isEmpty() || index !in episodeUrls.indices) {
            android.widget.Toast.makeText(
                this,
                if (index < 0) "Bu sezonun ilk bölümündesin." else "Bu sezonun son bölümündesin.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (exo.mediaItemCount != episodeUrls.size) {
            val queue = episodeUrls.map { createStreamMediaItem(it) }
            exo.setMediaItems(queue, index, 0L)
            exo.prepare()
        } else {
            exo.seekTo(index, 0L)
        }
        selectedEpisodeIndex = index
        currentStreamUrl = episodeUrls[index]
        originalStreamUrl = currentStreamUrl
        resumeKey = "position_" + currentStreamUrl.hashCode().toString()
        triedHlsFallback = false
        usingHlsFallback = false
        exo.playWhenReady = true
        exo.play()
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        return String.format(java.util.Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun refreshTrackChoices() {
        fun fill(panel: LinearLayout?, type: Int) {
            val target = panel ?: return
            val options = (target.tag as? Pair<*, *>)?.second as? LinearLayout ?: return
            options.removeAllViews()
            fun add(label: String, group: Tracks.Group? = null, index: Int? = null, disabled: Boolean = false) {
                val option = TextView(this).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(Color.WHITE)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    id = View.generateViewId()
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(if (label == "Otomatik") 0xFF123D60.toInt() else Color.TRANSPARENT)
                    }
                    setOnFocusChangeListener { focusedView, hasFocus ->
                        focusedView.background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(if (hasFocus) 0xFF087FCA.toInt() else if (label == "Otomatik") 0xFF123D60.toInt() else Color.TRANSPARENT)
                            if (hasFocus) setStroke(dp(2), 0xFF8CCBFF.toInt())
                        }
                        focusedView.elevation = if (hasFocus) dp(5).toFloat() else 0f
                    }
                    setOnClickListener {
                        val exo = player ?: return@setOnClickListener
                        val builder = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, disabled)
                        if (group != null && index != null) {
                            builder.setTrackTypeDisabled(type, false)
                            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        }
                        exo.trackSelectionParameters = builder.build()
                        target.visibility = View.GONE
                        if (type == C.TRACK_TYPE_TEXT) subtitleButtonView?.requestFocus()
                        else audioButtonView?.requestFocus()
                        showPlaybackControls()
                    }
                }
                options.addView(option, LinearLayout.LayoutParams(-1, dp(40)))
            }
            add("Otomatik")
            if (type == C.TRACK_TYPE_TEXT) add("Kapalı", disabled = true)
            var count = 0
            currentTracks.groups.forEach { group ->
                if (group.type != type) return@forEach
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                    val label = format.label?.takeIf { it.isNotBlank() } ?: language?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale("tr")) } ?: if (type == C.TRACK_TYPE_TEXT) "Altyazı ${count + 1}" else "Ses ${count + 1}"
                    add(label, group, i); count++
                }
            }
            if (count == 0) options.addView(TextView(this).apply { text = "Yayın parçası bulunamadı"; textSize = 12f; setTextColor(0xFFC5CCD6.toInt()); setPadding(dp(6), dp(8), dp(6), dp(8)) })
        }
        fill(subtitlePanel, C.TRACK_TYPE_TEXT)
        fill(audioPanel, C.TRACK_TYPE_AUDIO)
    }

    private fun applyPlayerImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyPlayerImmersiveMode()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() { savePlaybackPosition(); super.onPause() }
    override fun onStop() { savePlaybackPosition(); player?.pause(); super.onStop() }
    override fun onDestroy() { uiHandler.removeCallbacksAndMessages(null); playbackHandler.removeCallbacksAndMessages(null); savePlaybackPosition(); player?.release(); player = null; super.onDestroy() }
}
