package com.asmarter.iptv

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: RelativeLayout
    private lateinit var tvGestureHud: TextView
    private lateinit var btnUnlock: ImageButton
    private lateinit var btnPlayPause: Button
    private lateinit var btnAspect: Button
    private lateinit var btnSpeed: Button
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView

    private var player: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var isLocked = false
    private var controlsVisible = true
    private var aspectIdx = 0
    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "16:9"
    )
    private var speedIdx = 1
    private val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

    private var gestureMode = 0 // 0=none, 1=brightness, 2=volume, 3=seek
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var maxVolume = 15
    private var seekTargetMs = 0L

    private val hideControlsRunnable = Runnable {
        if (controlsVisible && !isLocked) toggleControls(false)
    }
    private val hideHudRunnable = Runnable { tvGestureHud.visibility = View.GONE }

    private val progressRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                val dur = p.duration.coerceAtLeast(0L)
                val pos = p.currentPosition.coerceAtLeast(0L)
                if (dur > 0) {
                    seekBar.max = (dur / 1000).toInt()
                    seekBar.progress = (pos / 1000).toInt()
                    tvDuration.text = formatMs(dur)
                } else {
                    tvDuration.text = "LIVE"
                }
                tvPosition.text = formatMs(pos)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_player)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        playerView = findViewById(R.id.playerView)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        tvGestureHud = findViewById(R.id.tvGestureHud)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnAspect = findViewById(R.id.btnAspect)
        btnSpeed = findViewById(R.id.btnSpeed)
        seekBar = findViewById(R.id.seekBar)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)

        val streamUrl = intent.getStringExtra("url") ?: ""
        findViewById<TextView>(R.id.tvTitle).text = intent.getStringExtra("title") ?: "Asmarter Player"
        findViewById<TextView>(R.id.tvSubtitle).text = intent.getStringExtra("subtitle") ?: ""

        initPlayer(streamUrl)
        setupButtons()
        setupMxGestures()
        scheduleHideControls()
    }

    private fun initPlayer(url: String) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    btnPlayPause.text = if (isPlaying) "Pause" else "Play"
                }
            })
        }
        handler.post(progressRunnable)
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            scheduleHideControls()
        }

        findViewById<Button>(R.id.btnRewind).setOnClickListener {
            seekRelative(-10_000)
        }
        findViewById<Button>(R.id.btnForward).setOnClickListener {
            seekRelative(10_000)
        }

        btnAspect.setOnClickListener {
            aspectIdx = (aspectIdx + 1) % aspectModes.size
            val (mode, label) = aspectModes[aspectIdx]
            playerView.resizeMode = mode
            btnAspect.text = label
            showHud("Aspect: $label")
            scheduleHideControls()
        }

        btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val spd = speeds[speedIdx]
            player?.playbackParameters = PlaybackParameters(spd)
            btnSpeed.text = "${spd}x"
            showHud("Speed: ${spd}x")
            scheduleHideControls()
        }

        findViewById<Button>(R.id.btnLock).setOnClickListener {
            isLocked = true
            controlsOverlay.visibility = View.GONE
            btnUnlock.visibility = View.VISIBLE
            showHud("Screen Locked")
        }

        btnUnlock.setOnClickListener {
            isLocked = false
            btnUnlock.visibility = View.GONE
            toggleControls(true)
            showHud("Screen Unlocked")
        }

        findViewById<Button>(R.id.btnPip).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }
        }

        findViewById<Button>(R.id.btnTracks).setOnClickListener {
            showTrackSelectorDialog()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(progress * 1000L)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                scheduleHideControls()
            }
        })
    }

    private fun setupMxGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    btnUnlock.visibility = if (btnUnlock.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    toggleControls(!controlsVisible)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return true
                val isLeft = e.x < playerView.width / 2f
                seekRelative(if (isLeft) -10_000 else 10_000)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                gestureMode = 0
                val lp = window.attributes
                startBrightness = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                seekTargetMs = player?.currentPosition ?: 0L
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (isLocked || e1 == null) return false
                val totalDx = e2.x - e1.x
                val totalDy = e1.y - e2.y
                if (gestureMode == 0) {
                    gestureMode = if (abs(totalDx) > abs(totalDy) && abs(totalDx) > 25) 3
                    else if (e1.x < playerView.width / 2f) 1 else 2
                }
                when (gestureMode) {
                    1 -> {
                        val delta = totalDy / (playerView.height * 0.7f)
                        val nextB = (startBrightness + delta).coerceIn(0.05f, 1.0f)
                        val lp = window.attributes
                        lp.screenBrightness = nextB
                        window.attributes = lp
                        showHud("☀ Brightness: ${(nextB * 100).toInt()}%")
                    }
                    2 -> {
                        val delta = (totalDy / (playerView.height * 0.7f) * maxVolume).toInt()
                        val nextV = (startVolume + delta).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextV, 0)
                        showHud("🔊 Volume: ${(nextV * 100) / maxVolume}%")
                    }
                    3 -> {
                        val p = player ?: return true
                        val dur = p.duration
                        if (dur > 0) {
                            val offsetMs = ((totalDx / playerView.width) * 90_000).toLong()
                            seekTargetMs = (p.currentPosition + offsetMs).coerceIn(0L, dur)
                            val sign = if (offsetMs >= 0) "+" else "-"
                            showHud("⏩ ${formatMs(seekTargetMs)} ($sign${abs(offsetMs / 1000)}s)")
                        }
                    }
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            val handled = detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && gestureMode == 3) {
                player?.seekTo(seekTargetMs)
            }
            handled
        }
    }

    private fun showTrackSelectorDialog() {
        val p = player ?: return
        val groups = p.currentTracks.groups
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        for (g in groups) {
            if (g.type == C.TRACK_TYPE_AUDIO || g.type == C.TRACK_TYPE_TEXT) {
                val prefix = if (g.type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle"
                for (i in 0 until g.length) {
                    val fmt = g.getTrackFormat(i)
                    val lang = fmt.language ?: "Track ${i + 1}"
                    val selected = if (g.isTrackSelected(i)) " ✓" else ""
                    labels.add("$prefix: $lang$selected")
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
        }
        if (labels.isEmpty()) {
            showHud("No alternate tracks in stream")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Audio & Subtitles")
            .setItems(labels.toTypedArray()) { _, idx -> actions[idx]() }
            .show()
    }

    private fun seekRelative(deltaMs: Long) {
        player?.let { p ->
            val dur = p.duration
            val target = if (dur > 0) (p.currentPosition + deltaMs).coerceIn(0L, dur) else (p.currentPosition + deltaMs).coerceAtLeast(0L)
            p.seekTo(target)
            showHud(if (deltaMs < 0) "↺ -10s" else "+10s ↻")
        }
        scheduleHideControls()
    }

    private fun toggleControls(show: Boolean) {
        controlsVisible = show
        controlsOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun showHud(msg: String) {
        tvGestureHud.text = msg
        tvGestureHud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, 1200)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
