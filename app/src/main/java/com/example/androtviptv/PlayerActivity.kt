package com.example.androtviptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
        val centerControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun roundControl(symbol: String, big: Boolean = false, action: () -> Unit) = TextView(this).apply {
            text = symbol; textSize = if (big) 30f else 26f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); isFocusable = true; isClickable = true
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66101824); setStroke(dp(if (big) 2 else 1), if (big) 0xFF079BE8.toInt() else 0x889AA7B8.toInt()) }
            setOnClickListener { action() }
        }
        val previousControl = roundControl("|◀") {
            if (isLiveStream && channelUrls.isNotEmpty()) tuneChannel((selectedChannelIndex - 1).let { if (it < 0) channelUrls.lastIndex else it })
            else if (episodeUrls.isNotEmpty()) playEpisodeAt(selectedEpisodeIndex - 1) else player?.seekToPreviousMediaItem()
        }
        val rewindControl = roundControl("↶") { player?.seekBack() }
        val playControl = roundControl("Ⅱ", true) { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        val forwardControl = roundControl("↷") { player?.seekForward() }
        val nextControl = roundControl("▶|") {
            if (isLiveStream && channelUrls.isNotEmpty()) tuneChannel((selectedChannelIndex + 1) % channelUrls.size)
            else if (episodeUrls.isNotEmpty()) playEpisodeAt(selectedEpisodeIndex + 1) else player?.seekToNextMediaItem()
        }
        listOf(previousControl, rewindControl, playControl, forwardControl, nextControl).forEachIndexed { i, button ->
            val size = if (i == 2) 84 else 68
            centerControls.addView(button, LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        root.addView(centerControls, FrameLayout.LayoutParams(-2, dp(100), Gravity.CENTER))

        fun createTrackPanel(type: Int): LinearLayout {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { setColor(0xDD101722.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF079BE8.toInt()) }
                visibility = View.GONE
            }
            panel.addView(TextView(this).apply {
                text = if (type == C.TRACK_TYPE_TEXT) "▣   Altyazı" else "◖   Ses Dili"
                textSize = 15f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(7))
            })
            val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(options) }
            panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(174)))
            panel.tag = Pair(type, options)
            return panel
        }
        subtitlePanel = createTrackPanel(C.TRACK_TYPE_TEXT)
        audioPanel = createTrackPanel(C.TRACK_TYPE_AUDIO)
        val panels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panels.addView(subtitlePanel, LinearLayout.LayoutParams(dp(126), dp(220)).apply { setMargins(dp(3), 0, dp(3), dp(7)) })
        panels.addView(audioPanel, LinearLayout.LayoutParams(dp(154), dp(220)).apply { setMargins(dp(3), 0, dp(3), dp(7)) })
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(panels) }
        val trackButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun trackButton(label: String, panel: LinearLayout) = TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); isFocusable = true; isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x99101824.toInt()); setStroke(dp(1), 0xFF079BE8.toInt()) }
            setOnClickListener { val other = if (panel === subtitlePanel) audioPanel else subtitlePanel; other?.visibility = View.GONE; panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        trackButtons.addView(trackButton("▣  Altyazı", subtitlePanel!!), LinearLayout.LayoutParams(dp(126), dp(52)).apply { setMargins(dp(3), 0, dp(3), 0) })
        trackButtons.addView(trackButton("◖  Ses Dili", audioPanel!!), LinearLayout.LayoutParams(dp(154), dp(52)).apply { setMargins(dp(3), 0, dp(3), 0) })
        right.addView(trackButtons)
        root.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT or Gravity.BOTTOM).apply { setMargins(0, 0, dp(20), dp(18)) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progressBar = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF079BE8.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF079BE8.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) elapsedLabel?.text = formatTime(value.toLong()) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { player?.seekTo(seekBar?.progress?.toLong() ?: 0L) }
            })
        }
        bottom.addView(progressBar, LinearLayout.LayoutParams(-1, dp(28)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsedLabel = TextView(this).apply { text = "00:00"; textSize = 14f; setTextColor(Color.WHITE) }
        durationLabel = TextView(this).apply { text = " •  00:00"; textSize = 14f; setTextColor(0xFFB8BEC8.toInt()) }
        times.addView(elapsedLabel); times.addView(durationLabel); bottom.addView(times)
        root.addView(bottom, FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM).apply { setMargins(dp(24), 0, dp(24), dp(88)) })
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
                        setColor(if (hasFocus) 0x3329B6F6 else Color.TRANSPARENT)
                        if (hasFocus) setStroke(dp(2), 0xFF29B6F6.toInt())
                    }
                    focusedView.elevation = if (hasFocus) dp(6).toFloat() else 0f
                }
            }
        }
        setContentView(root)
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
            if (channelUrls.size > 1) view.requestFocus()
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
        if (event.action == KeyEvent.ACTION_DOWN && channelUrls.isNotEmpty()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (channelOverlay != null) {
                        highlightedChannelIndex = (highlightedChannelIndex +
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                            .coerceIn(0, channelUrls.lastIndex)
                        refreshChannelHighlight()
                    } else {
                        val next = (selectedChannelIndex +
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                            .let { if (it < 0) channelUrls.lastIndex else if (it > channelUrls.lastIndex) 0 else it }
                        tuneChannel(next)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (channelOverlay == null) showChannelOverlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (channelOverlay == null) showRecentOverlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (channelOverlay != null) { tuneChannel(highlightedChannelIndex); return true }
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (channelOverlay != null) { hideChannelOverlay(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
            background = GradientDrawable().apply { setColor(0xD91A2030.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xAADEB55B.toInt()) }
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
        if (index !in episodeUrls.indices) return
        selectedEpisodeIndex = index
        currentStreamUrl = episodeUrls[index]
        originalStreamUrl = currentStreamUrl
        resumeKey = "position_" + currentStreamUrl.hashCode().toString()
        triedHlsFallback = false
        usingHlsFallback = false
        player?.seekTo(index, 0L)
        player?.playWhenReady = true
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
                options.addView(TextView(this).apply {
                    text = label; textSize = 14f; gravity = Gravity.CENTER_VERTICAL; setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(7), dp(8), dp(7)); isFocusable = true; isClickable = true
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (label == "Otomatik") 0xFF087FCA.toInt() else Color.TRANSPARENT) }
                    setOnClickListener {
                        val exo = player ?: return@setOnClickListener
                        val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, disabled)
                        if (group != null && index != null) { builder.setTrackTypeDisabled(type, false); builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)) }
                        exo.trackSelectionParameters = builder.build()
                        target.visibility = View.GONE
                    }
                }, LinearLayout.LayoutParams(-1, dp(35)))
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() { savePlaybackPosition(); super.onPause() }
    override fun onStop() { savePlaybackPosition(); player?.pause(); super.onStop() }
    override fun onDestroy() { uiHandler.removeCallbacksAndMessages(null); playbackHandler.removeCallbacksAndMessages(null); savePlaybackPosition(); player?.release(); player = null; super.onDestroy() }
}
