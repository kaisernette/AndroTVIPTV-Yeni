package com.example.androtviptv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var video: PlayerView
    private lateinit var progress: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var subtitlePanel: LinearLayout
    private lateinit var audioPanel: LinearLayout
    private lateinit var subtitleButton: TextView
    private lateinit var audioButton: TextView
    private var currentTracks = Tracks.EMPTY
    private var episodeNames = emptyArray<String>()
    private var episodeUrls = emptyArray<String>()
    private var episodeIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && ::progress.isInitialized && p.duration > 0) {
                progress.max = p.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                progress.progress = p.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                elapsed.text = clock(p.currentPosition)
                duration.text = clock(p.duration)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        episodeNames = intent.getStringArrayExtra("episode_names") ?: emptyArray()
        episodeUrls = intent.getStringArrayExtra("episode_urls") ?: emptyArray()
        episodeIndex = intent.getIntExtra("episode_index", 0).coerceIn(0, (episodeUrls.size - 1).coerceAtLeast(0))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22000000, 0x00000000, 0x99000000.toInt()))
        }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = control("◀|", 34) { playEpisode(episodeIndex - 1) }
        val back = control("↶", 38) { player?.seekBack() }
        val toggle = control("Ⅱ", 42) {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
        }
        val forward = control("↷", 38) { player?.seekForward() }
        val next = control("|▶", 34) { playEpisode(episodeIndex + 1) }
        listOf(prev, back, toggle, forward, next).forEachIndexed { index, v ->
            val size = if (index == 2) 82 else 68
            center.addView(v, LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                setMargins(dp(5), 0, dp(5), 0)
            })
        }
        root.addView(center, FrameLayout.LayoutParams(-2, dp(100), Gravity.CENTER))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val panels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        subtitlePanel = trackPanel()
        audioPanel = trackPanel()
        panels.addView(subtitlePanel, LinearLayout.LayoutParams(dp(150), dp(220)).apply { setMargins(dp(3), 0, dp(3), dp(8)) })
        panels.addView(audioPanel, LinearLayout.LayoutParams(dp(150), dp(220)).apply { setMargins(dp(3), 0, dp(3), dp(8)) })
        right.addView(panels)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        subtitleButton = panelButton("▣  Altyazı") { subtitlePanel.visibility = if (subtitlePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        audioButton = panelButton("◖  Ses Dili") { audioPanel.visibility = if (audioPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        buttons.addView(subtitleButton, LinearLayout.LayoutParams(dp(176), dp(54)).apply { setMargins(dp(3), 0, dp(3), 0) })
        buttons.addView(audioButton, LinearLayout.LayoutParams(dp(176), dp(54)).apply { setMargins(dp(3), 0, dp(3), 0) })
        right.addView(buttons)
        root.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(20), dp(20))
        })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        progress = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(0xff079be8.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xff079be8.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xffb0b0b0.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) elapsed.text = clock(value.toLong()) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { player?.seekTo(seekBar?.progress?.toLong() ?: 0L) }
            })
        }
        bottom.addView(progress, LinearLayout.LayoutParams(-1, dp(28)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsed = timeText("00:00")
        duration = timeText("00:00")
        times.addView(elapsed); times.addView(timeText("  •  ")); times.addView(duration)
        bottom.addView(times)
        root.addView(bottom, FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM).apply {
            setMargins(dp(24), 0, dp(510), dp(20))
        })
        setContentView(root)

        player = ExoPlayer.Builder(this).build().also { exo ->
            video.player = exo
            exo.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    currentTracks = tracks
                    refreshTrackPanels()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    toggle.text = if (isPlaying) "Ⅱ" else "▶"
                }
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Akış oynatılamadı: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { refreshTrackPanels() }
            })
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            exo.playWhenReady = true
        }
        refreshTrackPanels()
        handler.post(tick)
    }

    private fun control(label: String, textSize: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label; this.textSize = textSize.toFloat(); gravity = Gravity.CENTER
            setTextColor(Color.WHITE); isFocusable = true; isClickable = true
            background = circle(0x660e1724, 0xff9aa7b8.toInt())
            setOnClickListener { action() }
        }

    private fun panelButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); isFocusable = true; isClickable = true
        background = rounded(0x99101824.toInt(), 0xff079be8.toInt(), 2)
        setOnClickListener { action() }
    }

    private fun trackPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(0xdd101722.toInt(), 0xff079be8.toInt(), 1)
        visibility = View.GONE
    }

    private fun refreshTrackPanels() {
        if (!::subtitlePanel.isInitialized) return
        fillTrackPanel(subtitlePanel, C.TRACK_TYPE_TEXT)
        fillTrackPanel(audioPanel, C.TRACK_TYPE_AUDIO)
    }

    private fun fillTrackPanel(panel: LinearLayout, type: Int) {
        panel.removeAllViews()
        val heading = if (type == C.TRACK_TYPE_TEXT) "▣   Altyazı" else "◖   Ses Dili"
        panel.addView(TextView(this).apply {
            text = heading; textSize = 15f; setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })
        addTrackOption(panel, "Otomatik", type, null, null)
        if (type == C.TRACK_TYPE_TEXT) addTrackOption(panel, "Kapalı", type, null, -1)
        var count = 0
        currentTracks.groups.forEach { group ->
            if (group.type != type) return@forEach
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("tr")) }
                    ?: if (type == C.TRACK_TYPE_TEXT) "Altyazı ${count + 1}" else "Ses ${count + 1}"
                addTrackOption(panel, label, type, group, i)
                count++
            }
        }
        if (count == 0) panel.addView(TextView(this).apply {
            text = "Yayın parçası bulunamadı"; textSize = 12f; setTextColor(0xffc5ccd6.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
    }

    private fun addTrackOption(panel: LinearLayout, label: String, type: Int, group: Tracks.Group?, index: Int?) {
        val row = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            isFocusable = true; isClickable = true
            background = rounded(if (label == "Otomatik") 0xff087fca.toInt() else 0x00101824, 0x00000000, 0)
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                val builder = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, false)
                if (label == "Kapalı" && type == C.TRACK_TYPE_TEXT) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else if (group != null && index != null) {
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                }
                p.trackSelectionParameters = builder.build()
                panel.visibility = View.GONE
            }
        }
        panel.addView(row, LinearLayout.LayoutParams(-1, dp(35)))
    }

    private fun playEpisode(index: Int) {
        if (episodeUrls.isEmpty()) {
            Toast.makeText(this, "Bölüm geçişi için bölüm listesi bulunmuyor.", Toast.LENGTH_SHORT).show()
            return
        }
        if (index !in episodeUrls.indices) {
            Toast.makeText(this, "Bu sezonda başka bölüm yok.", Toast.LENGTH_SHORT).show()
            return
        }
        episodeIndex = index
        player?.setMediaItem(MediaItem.fromUri(episodeUrls[index]))
        player?.prepare()
        player?.playWhenReady = true
        Toast.makeText(this, episodeNames.getOrNull(index) ?: "Bölüm ${index + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(1), stroke)
    }
    private fun rounded(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat(); setColor(fill)
        if (width > 0) setStroke(dp(width), stroke)
    }
    private fun timeText(value: String) = TextView(this).apply {
        text = value; textSize = 15f; setTextColor(Color.WHITE)
    }
    private fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        video.player = null
        player?.release(); player = null
        super.onDestroy()
    }
}
