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
        resumeKey = "position_" + url.hashCode().toString()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply { useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); isFocusable = true; isFocusableInTouchMode = true }
        playerView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        player = ExoPlayer.Builder(this).build().also { exo ->
            view.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.widget.Toast.makeText(this@PlayerActivity, "Akış oynatılamadı: ${error.errorCodeName}", android.widget.Toast.LENGTH_LONG).show()
                }
            })
            exo.setMediaItem(MediaItem.fromUri(url))
            val resumePosition = playbackPrefs.getLong(resumeKey, 0L)
            exo.prepare()
            if (resumePosition > 5000L) exo.seekTo(resumePosition)
            exo.playWhenReady = true
        }
        if (channelUrls.size > 1) view.requestFocus()
    }
    override fun onPause() { savePlaybackPosition(); super.onPause() }
    override fun onStop() { savePlaybackPosition(); player?.pause(); super.onStop() }
    override fun onDestroy() { savePlaybackPosition(); player?.release(); player = null; super.onDestroy() }
}
