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
import androidx.media3.common.MediaItem
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
    private var resumeKey: String = ""
    private var currentStreamUrl: String = ""
    private var originalStreamUrl: String = ""
    private var triedHlsFallback: Boolean = false
    private var usingHlsFallback: Boolean = false
    private var isLiveStream: Boolean = false
    private var bufferMode: String = "normal"
    private var aspectMode: String = "fit"
    private var resumeEnabled: Boolean = true
    private var retryCount = 0
    private var bufferingSinceMs = 0L
    private var lastStablePlaybackMs = 0L
    private var lastObservedPositionMs = -1L
    private var positionStalledSinceMs = 0L
    private var reconnectPending = false
    private var idleSinceMs = 0L
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackWatchdog = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null && exo.playWhenReady && !isFinishing) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (exo.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                    if (now - bufferingSinceMs >= 12000L && !reconnectPending) {
                        restartCurrentStream("Yayın akışı 18 saniyeden uzun süre bekledi.")
                    }
                } else if (exo.playbackState == Player.STATE_READY) {
                    bufferingSinceMs = 0L
                    val position = exo.currentPosition
                    if (lastObservedPositionMs >= 0L && position <= lastObservedPositionMs) {
                        if (positionStalledSinceMs == 0L) positionStalledSinceMs = now
                        if (now - positionStalledSinceMs >= 8000L && !reconnectPending) {
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
                } else if (exo.playbackState == Player.STATE_IDLE && isLiveStream) {
                    if (idleSinceMs == 0L) idleSinceMs = now
                    if (now - idleSinceMs >= 8000L && !reconnectPending) {
                        restartCurrentStream("Canlı yayın oynatıcı boşta kaldı.")
                    }
                } else {
                    idleSinceMs = 0L
                    bufferingSinceMs = 0L
                    positionStalledSinceMs = 0L
                    lastObservedPositionMs = -1L
                }
            } else {
                idleSinceMs = 0L
                positionStalledSinceMs = 0L
                lastObservedPositionMs = -1L
            }
            playbackHandler.postDelayed(this, 5000L)
        }
    }
    private var playerView: PlayerView? = null
    private var channelOverlay: LinearLayout? = null
    private var channelRows: List<String> = emptyList()
    private var channelUrls: List<String> = emptyList()
    private var selectedChannelIndex = 0
    private var highlightedChannelIndex = 0
    private var recentChannelRows: List<String> = emptyList()
    private var recentChannelUrls: List<String> = emptyList()
    private var overlayIsRecent = false
    private var episodeNames: List<String> = emptyList()
    private var episodeUrls: List<String> = emptyList()
    private var episodeIndex = 0
    private var seriesTitle = ""
    private var seriesResumeKey = ""
    private var currentContentTitle = "Oynatıcı"
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
        if (isLiveStream || !resumeEnabled) return
        val exo = player ?: return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        if (episodeUrls.isNotEmpty() && seriesResumeKey.isNotBlank()) {
            episodeIndex = exo.currentMediaItemIndex.coerceIn(0, episodeUrls.lastIndex)
            playbackPrefs.edit().putInt("last_episode_$seriesResumeKey", episodeIndex).apply()
        }
        if (exo.duration > 0 && pos >= exo.duration - 5000L) {
            playbackPrefs.edit().remove(resumeKey).apply()
        } else if (pos > 3000L) {
            playbackPrefs.edit().putLong(resumeKey, pos).apply()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra("name") ?: "Oynatıcı"
        currentContentTitle = title
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        episodeNames = pendingEpisodeNames
        episodeUrls = pendingEpisodeUrls
        episodeIndex = pendingEpisodeIndex.coerceIn(0, (episodeUrls.size - 1).coerceAtLeast(0))
        seriesTitle = pendingSeriesTitle
        seriesResumeKey = pendingSeriesKey
        pendingEpisodeNames = emptyList()
        pendingEpisodeUrls = emptyList()
        pendingEpisodeIndex = 0
        pendingSeriesTitle = ""
        pendingSeriesKey = ""
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
        pendingChannelIndex = 0
        currentStreamUrl = url.trim()
        originalStreamUrl = currentStreamUrl
        triedHlsFallback = false
        usingHlsFallback = false
        isLiveStream = intent.getBooleanExtra("isLive", false)
        bufferMode = intent.getStringExtra("bufferMode") ?: "normal"
        aspectMode = intent.getStringExtra("aspectMode") ?: "fit"
        resumeEnabled = intent.getBooleanExtra("resumeEnabled", true)
        resumeKey = "position_" + url.hashCode().toString()
        if (isLiveStream || !resumeEnabled) playbackPrefs.edit().remove(resumeKey).apply()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            resizeMode = if (aspectMode == "fill") androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        playerView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
                        cornerRadius = dp(10).toFloat()
                        setColor(if (hasFocus) 0x553399FF else Color.TRANSPARENT)
                        if (hasFocus) setStroke(dp(3), playerAccentColor())
                    }
                    focusedView.scaleX = if (hasFocus) 1.12f else 1f
                    focusedView.scaleY = if (hasFocus) 1.12f else 1f
                    focusedView.elevation = if (hasFocus) dp(14).toFloat() else 0f
                }
            }
            (view.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? androidx.media3.ui.DefaultTimeBar)?.let { bar ->
                bar.setPlayedColor(playerAccentColor())
                bar.setScrubberColor(playerAccentColor())
                bar.setBufferedColor(0xAAFFFFFF.toInt())
                bar.setUnplayedColor(0x665B6472)
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
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(18000)
                .setAllowCrossProtocolRedirects(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpFactory)
            val mediaItem = createStreamMediaItem(currentStreamUrl)
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
            // Moderate live-TV buffering balances resilience, startup delay and memory use.
            val bufferDurations = when (bufferMode) {
                "low" -> intArrayOf(8000, 30000, 1000, 3500)
                "high" -> intArrayOf(25000, 60000, 2500, 7000)
                else -> intArrayOf(15000, 45000, 1500, 5000)
            }
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(bufferDurations[0], bufferDurations[1], bufferDurations[2], bufferDurations[3])
                .build()
            player = ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build().also { exo ->
                    view.player = exo
                    exo.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            if (episodeUrls.isNotEmpty()) {
                                episodeIndex = exo.currentMediaItemIndex.coerceIn(0, episodeUrls.lastIndex)
                                currentContentTitle = episodeNames.getOrNull(episodeIndex) ?: "Bölüm ${episodeIndex + 1}"
                                currentStreamUrl = episodeUrls.getOrNull(episodeIndex) ?: currentStreamUrl
                                originalStreamUrl = currentStreamUrl
                                resumeKey = "position_" + currentStreamUrl.hashCode().toString()
                            }
                        }
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
                                idleSinceMs = 0L
                                if (lastStablePlaybackMs == 0L) lastStablePlaybackMs = android.os.SystemClock.elapsedRealtime()
                            } else if (playbackState == Player.STATE_IDLE) {
                                if (idleSinceMs == 0L) idleSinceMs = android.os.SystemClock.elapsedRealtime()
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
                    if (!isLiveStream && episodeUrls.size > 1) {
                        val seasonPlaylist = episodeUrls.map { createStreamMediaItem(it) }
                        exo.setMediaItems(seasonPlaylist, episodeIndex, 0L)
                    } else {
                        exo.setMediaItem(mediaItem)
                    }
                    val resumePosition = if (isLiveStream) 0L else playbackPrefs.getLong(resumeKey, 0L)
                    exo.prepare()
                    if (resumePosition > 5000L) exo.seekTo(resumePosition)
                    exo.playWhenReady = true
                }
            playbackHandler.removeCallbacks(playbackWatchdog)
            playbackHandler.postDelayed(playbackWatchdog, 5000L)
            if (channelUrls.size > 1) view.requestFocus()
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
            background = GradientDrawable().apply { setColor(0xD91A2030.toInt()); cornerRadius = 0f; setStroke(dp(1), playerAccentColor()) }
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
        setColor(if (highlighted) playerAccentColor() else 0x33000000)
        cornerRadius = 0f
        if (highlighted) setStroke(dp(1), playerAccentColor())
    }

    private fun restartCurrentStream(reason: String) {
        val exo = player ?: return
        if (currentStreamUrl.isBlank() || isFinishing || !isLiveStream || !exo.playWhenReady || reconnectPending) return

        // Retry automatically with capped backoff; do not stop after three attempts.
        retryCount = (retryCount + 1).coerceAtMost(10)
        reconnectPending = true
        bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
        idleSinceMs = 0L
        positionStalledSinceMs = 0L
        lastObservedPositionMs = -1L
        val delayMs = (1500L * (1L shl (retryCount - 1).coerceAtMost(4))).coerceAtMost(20000L)
        android.util.Log.w("KaiserTVPlayer", "$reason Otomatik yeniden bağlanma denemesi $retryCount")
        if (retryCount == 1 || retryCount % 3 == 0) {
            android.widget.Toast.makeText(this, "Yayın yeniden bağlanıyor…", android.widget.Toast.LENGTH_SHORT).show()
        }
        playbackHandler.postDelayed({
            reconnectPending = false
            if (!isFinishing && player === exo && exo.playWhenReady && isLiveStream) {
                try {
                    exo.stop()
                    exo.setMediaItem(createStreamMediaItem(currentStreamUrl))
                    exo.prepare()
                    exo.playWhenReady = true
                } catch (e: Exception) {
                    android.util.Log.e("KaiserTVPlayer", "Reconnect failed", e)
                    restartCurrentStream("Yeniden bağlantı başlatılamadı.")
                }
            }
        }, delayMs)
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
        reconnectPending = false
        bufferingSinceMs = 0L
        idleSinceMs = 0L
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

    private fun playerAccentColor(): Int {
        return when (getSharedPreferences("androtv", Context.MODE_PRIVATE).getString("theme_name", "Dark Cinematic")) {
            "Gece • Altın" -> Color.rgb(224, 184, 95)
            "Lacivert • Turkuaz" -> Color.rgb(35, 205, 190)
            "Koyu • Mor" -> Color.rgb(174, 115, 255)
            else -> Color.rgb(0, 145, 255)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() { savePlaybackPosition(); super.onPause() }
    override fun onStop() { savePlaybackPosition(); player?.pause(); super.onStop() }
    override fun onDestroy() { playbackHandler.removeCallbacksAndMessages(null); savePlaybackPosition(); player?.release(); player = null; super.onDestroy() }
}
