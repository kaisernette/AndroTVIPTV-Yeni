package com.example.androtviptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private var resumeKey: String = ""
    private var playerView: PlayerView? = null
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

    private fun savePlaybackPosition() {
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
        channelRows = intent.getStringArrayListExtra("channel_names")?.toList() ?: emptyList()
        channelUrls = intent.getStringArrayListExtra("channel_urls")?.toList() ?: emptyList()
        selectedChannelIndex = intent.getIntExtra("channel_index", 0).coerceIn(0, (channelUrls.size - 1).coerceAtLeast(0))
        highlightedChannelIndex = selectedChannelIndex
        recentChannelRows = intent.getStringArrayListExtra("recent_channel_names")?.toList() ?: emptyList()
        recentChannelUrls = intent.getStringArrayListExtra("recent_channel_urls")?.toList() ?: emptyList()
        resumeKey = "position_" + url.hashCode().toString()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply { useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); isFocusable = true; isFocusableInTouchMode = true }
        playerView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        if (url.isBlank()) {
            android.widget.Toast.makeText(this, "Bu içerik için yayın adresi bulunamadı.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        try {
            val mediaItem = MediaItem.fromUri(url.trim())
            player = ExoPlayer.Builder(this).build().also { exo ->
                view.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        android.widget.Toast.makeText(this@PlayerActivity, "Akış oynatılamadı: ${error.errorCodeName}", android.widget.Toast.LENGTH_LONG).show()
                    }
                })
                exo.setMediaItem(mediaItem)
                val resumePosition = playbackPrefs.getLong(resumeKey, 0L)
                exo.prepare()
                if (resumePosition > 5000L) exo.seekTo(resumePosition)
                exo.playWhenReady = true
            }
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

    private fun tuneChannel(index: Int) {
        if (index !in channelUrls.indices) return
        val url = channelUrls[index]
        selectedChannelIndex = index
        resumeKey = "position_" + url.hashCode().toString()
        player?.let { exo -> exo.setMediaItem(MediaItem.fromUri(url)); exo.prepare(); exo.playWhenReady = true }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() { savePlaybackPosition(); super.onPause() }
    override fun onStop() { savePlaybackPosition(); player?.pause(); super.onStop() }
    override fun onDestroy() { savePlaybackPosition(); player?.release(); player = null; super.onDestroy() }
}
