package blbl.cat3399.feature.player

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.ui.CaptionStyleCompat
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.DanmakuShield
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var debugJob: kotlinx.coroutines.Job? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var autoHideJob: kotlinx.coroutines.Job? = null
    private var holdSeekJob: kotlinx.coroutines.Job? = null
    private var seekHintJob: kotlinx.coroutines.Job? = null
    private var keyScrubEndJob: kotlinx.coroutines.Job? = null
    private var scrubbing: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var lastBackAtMs: Long = 0L
    private var holdPrevSpeed: Float = 1.0f
    private var holdPrevPlayWhenReady: Boolean = false

    private var smartSeekDirection: Int = 0
    private var smartSeekStreak: Int = 0
    private var smartSeekLastAtMs: Long = 0L
    private var smartSeekTotalMs: Long = 0L
    private var tapSeekActiveDirection: Int = 0
    private var tapSeekActiveUntilMs: Long = 0L

    private var currentBvid: String = ""
    private var currentCid: Long = -1L
    private lateinit var session: PlayerSessionSettings
    private var subtitleAvailable: Boolean = false
    private var subtitleConfig: MediaItem.SubtitleConfiguration? = null
    private var subtitleItems: List<SubtitleItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.tvSeekHint.visibility = View.GONE
        binding.btnBack.setOnClickListener { finish() }

        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val cidExtra = intent.getLongExtra(EXTRA_CID, -1L).takeIf { it > 0 }
        if (bvid.isBlank()) {
            Toast.makeText(this, "缺少 bvid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentBvid = bvid

        val prefs = BiliClient.prefs
        session = PlayerSessionSettings(
            playbackSpeed = prefs.playerSpeed,
            preferCodec = prefs.playerPreferredCodec,
            preferAudioId = prefs.playerPreferredAudioId,
            subtitleEnabled = prefs.subtitleEnabledDefault,
            subtitleLangOverride = null,
            danmaku = DanmakuSessionSettings(
                enabled = prefs.danmakuEnabled,
                opacity = prefs.danmakuOpacity,
                textSizeSp = prefs.danmakuTextSizeSp,
                speedLevel = prefs.danmakuSpeed,
                area = prefs.danmakuArea,
            ),
            debugEnabled = prefs.playerDebugEnabled,
        )

        val okHttpFactory = OkHttpDataSource.Factory(BiliClient.cdnOkHttp)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        binding.danmakuView.setPositionProvider { exo.currentPosition }
        binding.danmakuView.setConfigProvider { session.danmaku.toConfig() }
        configureSubtitleView()
        exo.setPlaybackSpeed(session.playbackSpeed)
        // Subtitle enabled state follows session (default from global prefs).
        applySubtitleEnabled(exo)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("Player", "onPlayerError", error)
                Toast.makeText(this@PlayerActivity, "播放失败：${error.errorCodeName}", Toast.LENGTH_SHORT).show()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                restartAutoHideTimer()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgressUi()
            }
        })

        val settingsAdapter = PlayerSettingsAdapter { item ->
            when (item.title) {
                "视频编码" -> showCodecDialog()
                "播放速度" -> showSpeedDialog()
                "字幕语言" -> showSubtitleLangDialog()
                "弹幕透明度" -> showDanmakuOpacityDialog()
                "弹幕字号" -> showDanmakuTextSizeDialog()
                "弹幕速度" -> showDanmakuSpeedDialog()
                "弹幕区域" -> showDanmakuAreaDialog()
                "调试信息" -> {
                    session = session.copy(debugEnabled = !session.debugEnabled)
                    updateDebugOverlay()
                    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                }
                else -> Toast.makeText(this, "暂未实现：${item.title}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.recyclerSettings.adapter = settingsAdapter
        binding.recyclerSettings.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        refreshSettings(settingsAdapter)
        updateDebugOverlay()

        initControls(exo)

        lifecycleScope.launch {
            try {
                val viewJson = async(Dispatchers.IO) { BiliApi.view(bvid) }
                val viewData = viewJson.await().optJSONObject("data") ?: JSONObject()
                val title = viewData.optString("title", "")
                if (title.isNotBlank()) binding.tvTitle.text = title

                val cid = cidExtra ?: viewData.optLong("cid").takeIf { it > 0 } ?: error("cid missing")
                val aid = viewData.optLong("aid").takeIf { it > 0 }
                currentCid = cid
                AppLog.i("Player", "start bvid=$bvid cid=$cid")

                val playJob = async { BiliApi.playUrlDash(bvid, cid, fnval = 16) }
                val dmJob = async(Dispatchers.IO) { loadDanmaku(cid, aid) }
                val subJob = async(Dispatchers.IO) { prepareSubtitleConfig(viewData, bvid, cid) }

                val playJson = playJob.await()
                val playable = pickPlayable(playJson)
                subtitleConfig = subJob.await()
                subtitleAvailable = subtitleConfig != null
                (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                applySubtitleEnabled(exo)
                when (playable) {
                    is Playable.Dash -> {
                        AppLog.i("Player", "picked DASH video=${playable.videoUrl.take(40)} audio=${playable.audioUrl.take(40)}")
                        exo.setMediaSource(buildMerged(okHttpFactory, playable.videoUrl, playable.audioUrl, subtitleConfig))
                    }
                    is Playable.Progressive -> {
                        AppLog.i("Player", "picked Progressive url=${playable.url.take(60)}")
                        exo.setMediaSource(buildProgressive(okHttpFactory, playable.url, subtitleConfig))
                    }
                }
                exo.prepare()
                exo.playWhenReady = true
                updateSubtitleButton()

                val danmakus = dmJob.await()
                binding.danmakuView.setDanmakus(danmakus)
            } catch (t: Throwable) {
                AppLog.e("Player", "start failed", t)
                Toast.makeText(this@PlayerActivity, "加载播放信息失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (holdSeekJob != null) {
                    stopHoldSeek()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (isInteractionKey(keyCode)) noteUserInteraction()

        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return true
                setControlsVisible(true)
                focusFirstControl()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) {
                    binding.settingsPanel.visibility = View.GONE
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (controlsVisible) {
                    setControlsVisible(false)
                    return true
                }
                return handleBackExitOrDismiss()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                setControlsVisible(true)
                if (!binding.seekProgress.isFocused) {
                    focusSeekBar()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (binding.seekProgress.isFocused) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (!controlsVisible) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                if (binding.settingsPanel.visibility != View.VISIBLE && !hasControlsFocus()) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (!controlsVisible && binding.settingsPanel.visibility != View.VISIBLE) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> {
                binding.btnPlayPause.performClick()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (binding.seekProgress.isFocused) return super.dispatchKeyEvent(event)
                if (binding.topBar.hasFocus() || binding.bottomBar.hasFocus()) return super.dispatchKeyEvent(event)

                if (event.repeatCount > 0) {
                    startHoldSeek(direction = -1, showControls = false)
                    return true
                }

                smartSeek(direction = -1, showControls = false, hintKind = SeekHintKind.Step)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (binding.seekProgress.isFocused) return super.dispatchKeyEvent(event)
                if (binding.topBar.hasFocus() || binding.bottomBar.hasFocus()) return super.dispatchKeyEvent(event)

                if (event.repeatCount > 0) {
                    startHoldSeek(direction = +1, showControls = false)
                    return true
                }

                smartSeek(direction = +1, showControls = false, hintKind = SeekHintKind.Step)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        debugJob?.cancel()
        progressJob?.cancel()
        autoHideJob?.cancel()
        holdSeekJob?.cancel()
        seekHintJob?.cancel()
        keyScrubEndJob?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun initControls(exo: ExoPlayer) {
        val detector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (binding.settingsPanel.visibility == View.VISIBLE) return true
                        if (controlsVisible) {
                            setControlsVisible(false)
                            return true
                        }
                        val w = binding.playerView.width.toFloat()
                        if (w <= 0f) return true
                        val dir = edgeDirection(e.x, w)
                        if (dir == 0) return true

                        smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                        tapSeekActiveDirection = dir
                        tapSeekActiveUntilMs = SystemClock.uptimeMillis() + TAP_SEEK_ACTIVE_MS
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (binding.settingsPanel.visibility == View.VISIBLE) {
                            binding.settingsPanel.visibility = View.GONE
                            setControlsVisible(true)
                            focusFirstControl()
                            return true
                        }
                        if (controlsVisible) {
                            setControlsVisible(false)
                            return true
                        }

                        val now = SystemClock.uptimeMillis()
                        val w = binding.playerView.width.toFloat()
                        if (w > 0f && now <= tapSeekActiveUntilMs) {
                            val dir = edgeDirection(e.x, w)
                            if (dir != 0 && dir == tapSeekActiveDirection) {
                                smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                                tapSeekActiveUntilMs = now + TAP_SEEK_ACTIVE_MS
                                return true
                            }
                        }

                        setControlsVisible(true)
                        return true
                    }
                },
            )
        binding.playerView.setOnTouchListener { v, event ->
            val handled = detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && handled) v.performClick()
            handled
        }

        binding.btnAdvanced.setOnClickListener {
            val willShow = binding.settingsPanel.visibility != View.VISIBLE
            binding.settingsPanel.visibility = if (willShow) View.VISIBLE else View.GONE
            setControlsVisible(true)
        }

        binding.btnPlayPause.setOnClickListener {
            if (exo.isPlaying) exo.pause() else exo.play()
            setControlsVisible(true)
        }
        binding.btnRew.setOnClickListener {
            smartSeek(direction = -1, showControls = true, hintKind = SeekHintKind.Step)
        }
        binding.btnFfwd.setOnClickListener {
            smartSeek(direction = +1, showControls = true, hintKind = SeekHintKind.Step)
        }

        binding.btnDanmaku.setOnClickListener {
            session = session.copy(danmaku = session.danmaku.copy(enabled = !session.danmaku.enabled))
            binding.danmakuView.invalidate()
            updateDanmakuButton()
            setControlsVisible(true)
        }

        binding.btnSubtitle.setOnClickListener {
            toggleSubtitles(exo)
            setControlsVisible(true)
        }

        binding.seekProgress.max = SEEK_MAX
        binding.seekProgress.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    scrubbing = true
                    noteUserInteraction()
                    scheduleKeyScrubEnd()

                    if (binding.seekProgress.isFocused) {
                        val duration = exo.duration.takeIf { it > 0 } ?: return
                        val seekTo = duration * progress / SEEK_MAX
                        exo.seekTo(seekTo)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    scrubbing = true
                    setControlsVisible(true)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = exo.duration.takeIf { it > 0 } ?: return
                    val progress = seekBar?.progress ?: return
                    val seekTo = duration * progress / SEEK_MAX
                    exo.seekTo(seekTo)
                    scrubbing = false
                    setControlsVisible(true)
                }
            },
        )

        updatePlayPauseIcon(exo.isPlaying)
        updateSubtitleButton()
        updateDanmakuButton()
        setControlsVisible(true)
        startProgressLoop()
    }

    private fun seekRelative(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val next = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(next)
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                updateProgressUi()
                delay(250)
            }
        }
    }

    private fun toggleControls() {
        val willShow = !controlsVisible
        if (!willShow) binding.settingsPanel.visibility = View.GONE
        setControlsVisible(willShow)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val show = visible || binding.settingsPanel.visibility == View.VISIBLE
        binding.topBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        if (visible) noteUserInteraction() else autoHideJob?.cancel()
    }

    private fun restartAutoHideTimer() {
        autoHideJob?.cancel()
        val exo = player ?: return
        if (!controlsVisible) return
        if (binding.settingsPanel.visibility == View.VISIBLE) return
        if (scrubbing) return
        if (!exo.isPlaying) return
        val token = lastInteractionAtMs
        autoHideJob = lifecycleScope.launch {
            delay(AUTO_HIDE_MS)
            if (token != lastInteractionAtMs) return@launch
            setControlsVisible(false)
        }
    }

    private fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.uptimeMillis()
        restartAutoHideTimer()
    }

    private fun scheduleKeyScrubEnd() {
        keyScrubEndJob?.cancel()
        keyScrubEndJob =
            lifecycleScope.launch {
                delay(KEY_SCRUB_END_DELAY_MS)
                scrubbing = false
                restartAutoHideTimer()
            }
    }

    private fun hasControlsFocus(): Boolean =
        binding.topBar.hasFocus() || binding.bottomBar.hasFocus() || binding.settingsPanel.hasFocus()

    private fun focusFirstControl() {
        binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
    }

    private fun focusSeekBar() {
        binding.seekProgress.post { binding.seekProgress.requestFocus() }
    }

    private fun handleBackExitOrDismiss(): Boolean {
        val now = SystemClock.uptimeMillis()
        val isSecond = now - lastBackAtMs <= BACK_DOUBLE_PRESS_WINDOW_MS
        if (isSecond) {
            finish()
            return true
        }
        lastBackAtMs = now
        Toast.makeText(this, "再按一次退出播放器", Toast.LENGTH_SHORT).show()
        if (controlsVisible) setControlsVisible(false)
        return true
    }

    private fun smartSeek(direction: Int) {
        smartSeek(direction, showControls = true, hintKind = SeekHintKind.Step)
    }

    private enum class SeekHintKind {
        Step,
        Hold,
    }

    private fun smartSeek(direction: Int, showControls: Boolean, hintKind: SeekHintKind) {
        val now = SystemClock.uptimeMillis()
        val sameDir = direction == smartSeekDirection
        val within = now - smartSeekLastAtMs <= SMART_SEEK_WINDOW_MS
        val continued = sameDir && within
        smartSeekStreak = if (continued) (smartSeekStreak + 1) else 1
        smartSeekDirection = direction
        smartSeekLastAtMs = now

        if (showControls) {
            if (!controlsVisible && binding.settingsPanel.visibility != View.VISIBLE) setControlsVisible(true) else noteUserInteraction()
        } else {
            noteUserInteraction()
        }

        val step = smartSeekStepMs(smartSeekStreak)
        seekRelative(step * direction)
        smartSeekTotalMs = if (continued) (smartSeekTotalMs + step) else step
        if (hintKind == SeekHintKind.Step) showSeekStepHint(direction, smartSeekTotalMs)
    }

    private fun smartSeekStepMs(streak: Int): Long {
        val baseStepsSec = intArrayOf(2, 3, 5, 7, 10, 15, 25, 35, 45)
        val idx = (streak - 1).coerceAtLeast(0)
        val sec =
            if (idx < baseStepsSec.size) {
                baseStepsSec[idx]
            } else {
                val extra = idx - (baseStepsSec.size - 1)
                (baseStepsSec.last() + (10 * extra)).coerceAtMost(300)
            }
        return sec * 1000L
    }

    private fun startHoldSeek(direction: Int, showControls: Boolean) {
        if (holdSeekJob?.isActive == true) return
        val exo = player ?: return

        if (showControls) {
            if (!controlsVisible && binding.settingsPanel.visibility != View.VISIBLE) setControlsVisible(true) else noteUserInteraction()
        } else {
            noteUserInteraction()
        }

        holdPrevSpeed = exo.playbackParameters.speed
        holdPrevPlayWhenReady = exo.playWhenReady
        if (direction > 0) {
            showSeekHoldHint(direction)
            exo.setPlaybackSpeed(HOLD_SPEED)
            exo.playWhenReady = true
            holdSeekJob = lifecycleScope.launch { kotlinx.coroutines.awaitCancellation() }
            return
        }

        // Rewind: ExoPlayer has no negative playback speed; do stepped rewind while paused.
        showSeekHoldHint(direction)
        exo.pause()
        holdSeekJob =
            lifecycleScope.launch {
                while (isActive) {
                    seekRelative(-HOLD_REWIND_STEP_MS)
                    delay(HOLD_REWIND_TICK_MS)
                }
            }
    }

    private fun stopHoldSeek() {
        val exo = player
        holdSeekJob?.cancel()
        holdSeekJob = null
        if (exo != null) {
            exo.setPlaybackSpeed(holdPrevSpeed)
            exo.playWhenReady = holdPrevPlayWhenReady
        }
        scheduleHideSeekHint()
    }

    private fun showSeekStepHint(direction: Int, totalMs: Long) {
        val sec = (kotlin.math.abs(totalMs) / 1000L).coerceAtLeast(1L)
        val text = if (direction > 0) "快进 ${sec}s" else "后退 ${sec}s"
        showSeekHint(text, hold = false)
    }

    private fun showSeekHoldHint(direction: Int) {
        val text = if (direction > 0) "快进 x2" else "后退 x2"
        showSeekHint(text, hold = true)
    }

    private fun showSeekHint(text: String, hold: Boolean) {
        binding.tvSeekHint.text = text
        binding.tvSeekHint.visibility = View.VISIBLE
        seekHintJob?.cancel()
        if (!hold) scheduleHideSeekHint()
    }

    private fun scheduleHideSeekHint() {
        seekHintJob?.cancel()
        seekHintJob =
            lifecycleScope.launch {
                delay(SEEK_HINT_HIDE_DELAY_MS)
                binding.tvSeekHint.visibility = View.GONE
            }
    }

    private fun edgeDirection(x: Float, width: Float): Int {
        return when {
            x < width * EDGE_TAP_THRESHOLD -> -1
            x > width * (1f - EDGE_TAP_THRESHOLD) -> +1
            else -> 0
        }
    }

    private fun isInteractionKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MENU,
            -> true

            else -> false
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) blbl.cat3399.R.drawable.ic_player_pause else blbl.cat3399.R.drawable.ic_player_play,
        )
    }

    private fun updateProgressUi() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        val pos = exo.currentPosition.coerceAtLeast(0L)

        binding.tvTime.text = "${formatHms(pos)} / ${formatHms(duration)}"

        val enabled = duration > 0
        binding.seekProgress.isEnabled = enabled
        if (enabled && !scrubbing) {
            val p = ((pos.toDouble() / duration.toDouble()) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
            binding.seekProgress.progress = p
        }
    }

    private fun updateDanmakuButton() {
        val colorRes = if (session.danmaku.enabled) blbl.cat3399.R.color.blbl_blue else blbl.cat3399.R.color.blbl_text_secondary
        binding.btnDanmaku.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun updateSubtitleButton() {
        val colorRes =
            if (!subtitleAvailable) {
                blbl.cat3399.R.color.blbl_text_secondary
            } else {
                if (session.subtitleEnabled) blbl.cat3399.R.color.blbl_blue else blbl.cat3399.R.color.blbl_text_secondary
            }
        binding.btnSubtitle.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun toggleSubtitles(exo: ExoPlayer) {
        if (!subtitleAvailable) {
            Toast.makeText(this, "该视频暂无字幕", Toast.LENGTH_SHORT).show()
            return
        }
        session = session.copy(subtitleEnabled = !session.subtitleEnabled)
        applySubtitleEnabled(exo)
        updateSubtitleButton()
    }

    private fun applySubtitleEnabled(exo: ExoPlayer) {
        val disable = (!subtitleAvailable) || (!session.subtitleEnabled)
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disable)
                .build()
    }

    private sealed interface Playable {
        data class Dash(val videoUrl: String, val audioUrl: String) : Playable
        data class Progressive(val url: String) : Playable
    }

    private fun refreshSettings(adapter: PlayerSettingsAdapter) {
        adapter.submit(
            listOf(
                PlayerSettingsAdapter.SettingItem("视频编码", session.preferCodec),
                PlayerSettingsAdapter.SettingItem("播放速度", String.format(Locale.US, "%.2fx", session.playbackSpeed)),
                PlayerSettingsAdapter.SettingItem("字幕语言", subtitleLangSubtitle()),
                PlayerSettingsAdapter.SettingItem("弹幕透明度", String.format(Locale.US, "%.2f", session.danmaku.opacity)),
                PlayerSettingsAdapter.SettingItem("弹幕字号", session.danmaku.textSizeSp.toInt().toString()),
                PlayerSettingsAdapter.SettingItem("弹幕速度", session.danmaku.speedLevel.toString()),
                PlayerSettingsAdapter.SettingItem("弹幕区域", areaText(session.danmaku.area)),
                PlayerSettingsAdapter.SettingItem("调试信息", if (session.debugEnabled) "开" else "关"),
            ),
        )
    }

    private suspend fun pickPlayable(json: JSONObject): Playable {
        val data = json.optJSONObject("data") ?: JSONObject()
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = dash.optJSONArray("video") ?: JSONArray()
            val audios = dash.optJSONArray("audio") ?: JSONArray()

            fun baseUrl(obj: JSONObject): String =
                obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))

            val prefs = BiliClient.prefs
            val maxHeight = prefs.playerMaxHeight
            val preferCodecid = when (session.preferCodec) {
                "HEVC" -> 12
                "AV1" -> 13
                else -> 7
            }

            var bestVideo: JSONObject? = null
            var bestScore = -1L
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val codecid = v.optInt("codecid", 0)
                val height = v.optInt("height", 0)
                val bandwidth = v.optLong("bandwidth", 0L)
                val okCodec = (codecid == preferCodecid)
                val okRes = height in 1..maxHeight
                val score =
                    bandwidth +
                        (if (okCodec) 1_000_000_000L else 0L) +
                        (if (okRes) 200_000_000L else -200_000_000L)
                if (score > bestScore && baseUrl(v).isNotBlank()) {
                    bestScore = score
                    bestVideo = v
                }
            }
            val videoUrl = baseUrl(bestVideo ?: error("no video"))

            var bestAudio: JSONObject? = null
            var bestAudioScore = -1L
            for (i in 0 until audios.length()) {
                val a = audios.optJSONObject(i) ?: continue
                val id = a.optInt("id", 0)
                val bw = a.optLong("bandwidth", 0L)
                val score = bw + if (id == session.preferAudioId) 10_000_000L else 0L
                if (score > bestAudioScore && baseUrl(a).isNotBlank()) {
                    bestAudioScore = score
                    bestAudio = a
                }
            }
            val audioUrl = baseUrl(bestAudio ?: error("no audio"))
            return Playable.Dash(videoUrl, audioUrl)
        }

        // Fallback: try durl (progressive) if dash missing.
        val url = data.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
        if (url.isNotBlank()) return Playable.Progressive(url)

        val cid = intent.getLongExtra(EXTRA_CID, -1L).takeIf { it > 0 } ?: error("cid missing for fallback")
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val fallbackJson = BiliApi.playUrlDash(bvid, cid, fnval = 0)
        val fallbackData = fallbackJson.optJSONObject("data") ?: JSONObject()
        val fallbackUrl = fallbackData.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
        if (fallbackUrl.isNotBlank()) return Playable.Progressive(fallbackUrl)

        error("No playable url in playurl response")
    }

    private fun buildMerged(
        factory: OkHttpDataSource.Factory,
        videoUrl: String,
        audioUrl: String,
        subtitle: MediaItem.SubtitleConfiguration?,
    ): MediaSource {
        val subs = listOfNotNull(subtitle)
        val videoSource = ProgressiveMediaSource.Factory(factory).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(videoUrl)).setSubtitleConfigurations(subs).build(),
        )
        val audioSource = ProgressiveMediaSource.Factory(factory).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(audioUrl)).build(),
        )
        val subtitleSource = subtitle?.let { buildSubtitleSource(it) }
        return if (subtitleSource != null) MergingMediaSource(videoSource, audioSource, subtitleSource) else MergingMediaSource(videoSource, audioSource)
    }

    private fun buildProgressive(factory: OkHttpDataSource.Factory, url: String, subtitle: MediaItem.SubtitleConfiguration?): MediaSource {
        val subs = listOfNotNull(subtitle)
        val main = ProgressiveMediaSource.Factory(factory).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(url)).setSubtitleConfigurations(subs).build(),
        )
        val subtitleSource = subtitle?.let { buildSubtitleSource(it) }
        return if (subtitleSource != null) MergingMediaSource(main, subtitleSource) else main
    }

    private fun buildSubtitleSource(subtitle: MediaItem.SubtitleConfiguration): MediaSource {
        val ds = DefaultDataSource.Factory(this)
        return SingleSampleMediaSource.Factory(ds).createMediaSource(subtitle, C.TIME_UNSET)
    }

    private fun showCodecDialog() {
        val options = arrayOf("AVC", "HEVC", "AV1")
        val current = options.indexOf(session.preferCodec).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("视频编码")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val selected = options.getOrNull(which) ?: "AVC"
                session = session.copy(preferCodec = selected)
                refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                dialog.dismiss()
                reloadStream(keepPosition = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSpeedDialog() {
        val options = arrayOf("0.50x", "0.75x", "1.00x", "1.25x", "1.50x", "2.00x")
        val current = options.indexOf(String.format(Locale.US, "%.2fx", session.playbackSpeed)).let { if (it >= 0) it else 2 }
        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val selected = options.getOrNull(which) ?: "1.00x"
                val v = selected.removeSuffix("x").toFloatOrNull() ?: 1.0f
                session = session.copy(playbackSpeed = v)
                player?.setPlaybackSpeed(v)
                refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun reloadStream(keepPosition: Boolean) {
        val exo = player ?: return
        val cid = currentCid
        val bvid = currentBvid
        if (cid <= 0 || bvid.isBlank()) return
        val pos = exo.currentPosition
        lifecycleScope.launch {
            try {
                val playJson = BiliApi.playUrlDash(bvid, cid, fnval = 16)
                val playable = pickPlayable(playJson)
                val okHttpFactory = OkHttpDataSource.Factory(BiliClient.cdnOkHttp)
                when (playable) {
                    is Playable.Dash -> exo.setMediaSource(buildMerged(okHttpFactory, playable.videoUrl, playable.audioUrl, subtitleConfig))
                    is Playable.Progressive -> exo.setMediaSource(buildProgressive(okHttpFactory, playable.url, subtitleConfig))
                }
                exo.prepare()
                applySubtitleEnabled(exo)
                if (keepPosition) exo.seekTo(pos)
                exo.playWhenReady = true
            } catch (t: Throwable) {
                AppLog.e("Player", "reloadStream failed", t)
                Toast.makeText(this@PlayerActivity, "切换失败：${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDebugOverlay() {
        val enabled = session.debugEnabled
        binding.tvDebug.visibility = if (enabled) View.VISIBLE else View.GONE
        debugJob?.cancel()
        if (!enabled) return
        val exo = player ?: return
        debugJob = lifecycleScope.launch {
            while (isActive) {
                binding.tvDebug.text = buildDebugText(exo)
                delay(500)
            }
        }
    }

    private fun buildDebugText(exo: ExoPlayer): String {
        val sb = StringBuilder()
        sb.append(
            when (exo.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFER"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> exo.playbackState.toString()
            },
        )
        sb.append(" playing=").append(exo.isPlaying)
        sb.append('\n')
        sb.append("pos=").append(exo.currentPosition).append("ms")
        sb.append(" buf=").append(exo.bufferedPosition).append("ms")
        sb.append(" spd=").append(String.format(Locale.US, "%.2f", exo.playbackParameters.speed))
        sb.append('\n')
        val vs = exo.videoSize
        if (vs.width > 0 && vs.height > 0) {
            sb.append("size=").append(vs.width).append("x").append(vs.height)
            sb.append(" par=").append(String.format(Locale.US, "%.2f", vs.pixelWidthHeightRatio))
            sb.append('\n')
        }
        val tracks = exo.currentTracks
        var videoLine: String? = null
        var audioLine: String? = null
        for (g in tracks.groups) {
            if (!g.isSelected) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSelected(i)) continue
                val f = g.getTrackFormat(i)
                val mime = f.sampleMimeType ?: ""
                val codecs = f.codecs ?: ""
                if (mime.startsWith("video/") && videoLine == null) {
                    videoLine = "v=$mime ${f.width}x${f.height} br=${f.bitrate} $codecs"
                }
                if (mime.startsWith("audio/") && audioLine == null) {
                    audioLine = "a=$mime br=${f.bitrate} ch=${f.channelCount} $codecs"
                }
            }
        }
        if (videoLine != null) sb.append(videoLine).append('\n')
        if (audioLine != null) sb.append(audioLine).append('\n')
        sb.append("ua=").append(BiliClient.prefs.userAgent.take(28)).append("…")
        return sb.toString()
    }

    private fun showDanmakuOpacityDialog() {
        val options = listOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f)
        val items = options.map { String.format(Locale.US, "%.2f", it) }.toTypedArray()
        val current = options.indexOfFirst { kotlin.math.abs(it - session.danmaku.opacity) < 0.01f }.let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle("弹幕透明度")
            .setSingleChoiceItems(items, current) { dialog, which ->
                val v = options.getOrNull(which) ?: session.danmaku.opacity
                session = session.copy(danmaku = session.danmaku.copy(opacity = v))
                binding.danmakuView.invalidate()
                refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDanmakuTextSizeDialog() {
        val options = listOf(14, 16, 18, 20, 24, 28, 32, 36, 40)
        val items = options.map { it.toString() }.toTypedArray()
        val current = options.indexOf(session.danmaku.textSizeSp.toInt()).let { if (it >= 0) it else 2 }
        AlertDialog.Builder(this)
            .setTitle("弹幕字号(sp)")
            .setSingleChoiceItems(items, current) { dialog, which ->
                val v = (options.getOrNull(which) ?: session.danmaku.textSizeSp.toInt()).toFloat()
                session = session.copy(danmaku = session.danmaku.copy(textSizeSp = v))
                binding.danmakuView.invalidate()
                refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDanmakuSpeedDialog() {
        val options = (1..10).toList()
        val items = options.map { it.toString() }.toTypedArray()
        val current = options.indexOf(session.danmaku.speedLevel).let { if (it >= 0) it else 3 }
        AlertDialog.Builder(this)
            .setTitle("弹幕速度(1~10)")
            .setSingleChoiceItems(items, current) { dialog, which ->
                val v = options.getOrNull(which) ?: session.danmaku.speedLevel
                session = session.copy(danmaku = session.danmaku.copy(speedLevel = v))
                binding.danmakuView.invalidate()
                refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDanmakuAreaDialog() {
        val options = listOf(
            0.25f to "1/4",
            0.50f to "1/2",
            0.75f to "3/4",
            1.00f to "不限",
        )
        val items = options.map { it.second }.toTypedArray()
        val current = options.indexOfFirst { kotlin.math.abs(it.first - session.danmaku.area) < 0.01f }.let { if (it >= 0) it else 3 }
        AlertDialog.Builder(this)
            .setTitle("弹幕区域")
            .setSingleChoiceItems(items, current) { dialog, which ->
                val v = options.getOrNull(which)?.first ?: session.danmaku.area
                session = session.copy(danmaku = session.danmaku.copy(area = v))
                binding.danmakuView.invalidate()
                refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun prepareSubtitleConfig(viewData: JSONObject, bvid: String, cid: Long): MediaItem.SubtitleConfiguration? {
        val items = fetchSubtitleItems(viewData, bvid, cid)
        subtitleItems = items
        val chosen = pickSubtitleItem(items) ?: return null
        return buildSubtitleConfigFromItem(chosen, bvid, cid)
    }

    private suspend fun buildSubtitleConfigFromItem(item: SubtitleItem, bvid: String, cid: Long): MediaItem.SubtitleConfiguration? {
        val subtitleJson = runCatching { BiliClient.getJson(item.url) }.getOrNull() ?: return null
        val body = subtitleJson.optJSONArray("body") ?: subtitleJson.optJSONObject("data")?.optJSONArray("body") ?: return null

        val vtt = buildWebVtt(body)
        if (vtt.isBlank()) return null

        val safeLan = item.lan.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(cacheDir, "sub_${bvid}_${cid}_${safeLan}.vtt")
        runCatching { file.writeText(vtt, Charsets.UTF_8) }.getOrElse { return null }

        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(item.lan)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private suspend fun fetchSubtitleItems(viewData: JSONObject, bvid: String, cid: Long): List<SubtitleItem> {
        val playerJson = runCatching { BiliApi.playerWbiV2(bvid = bvid, cid = cid) }.getOrNull()
        val data = playerJson?.optJSONObject("data")
        val needLogin = data?.optBoolean("need_login_subtitle") ?: false
        val list = data?.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
        if (list.length() == 0 && needLogin && !BiliClient.cookies.hasSessData()) {
            return emptyList()
        }
        if (list.length() == 0) {
            // Fallback: try older view payload (some responses may include it).
            val legacy = viewData.optJSONObject("subtitle")?.optJSONArray("list") ?: JSONArray()
            if (legacy.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until legacy.length()) {
                    val it = legacy.optJSONObject(i) ?: continue
                    val url = it.optString("subtitle_url", it.optString("subtitleUrl", "")).trim()
                    if (url.isBlank()) continue
                    val lan = it.optString("lan", "")
                    val doc = it.optString("lan_doc", it.optString("language", lan))
                    add(SubtitleItem(lan = lan.ifBlank { "unknown" }, lanDoc = doc.ifBlank { lan }, url = normalizeUrl(url)))
                }
            }
        }
        return buildList {
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                val url = it.optString("subtitle_url", "").trim()
                val lan = it.optString("lan", "").trim()
                val doc = it.optString("lan_doc", "").trim()
                if (url.isBlank() || lan.isBlank()) continue
                add(SubtitleItem(lan = lan, lanDoc = doc.ifBlank { lan }, url = normalizeUrl(url)))
            }
        }
    }

    private fun pickSubtitleItem(items: List<SubtitleItem>): SubtitleItem? {
        if (items.isEmpty()) return null
        val prefs = BiliClient.prefs
        val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
        if (preferred == "auto" || preferred.isBlank()) return items.first()
        return items.firstOrNull { it.lan.equals(preferred, ignoreCase = true) } ?: items.first()
    }

    private fun subtitleLangSubtitle(): String {
        if (subtitleItems.isEmpty()) return "无/未加载"
        val prefs = BiliClient.prefs
        val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
        if (session.subtitleLangOverride == null) {
            val resolved = resolveSubtitleLang(preferred)
            return "全局：$resolved"
        }
        return resolveSubtitleLang(preferred)
    }

    private fun resolveSubtitleLang(code: String): String {
        if (subtitleItems.isEmpty()) return "无"
        if (code == "auto" || code.isBlank()) {
            val first = subtitleItems.first()
            return "自动：${first.lanDoc}"
        }
        val found = subtitleItems.firstOrNull { it.lan.equals(code, ignoreCase = true) } ?: subtitleItems.first()
        return "${found.lanDoc}"
    }

    private fun showSubtitleLangDialog() {
        val exo = player ?: return
        if (subtitleItems.isEmpty()) {
            Toast.makeText(this, "该视频暂无字幕", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = BiliClient.prefs
        val global = prefs.subtitlePreferredLang
        val items = buildList {
            add("跟随全局（${resolveSubtitleLang(global)}）")
            add("自动（取第一个）")
            subtitleItems.forEach { add(it.lanDoc) }
        }
        val currentLabel =
            when (val ov = session.subtitleLangOverride) {
                null -> "跟随全局（${resolveSubtitleLang(global)}）"
                "auto" -> "自动（取第一个）"
                else -> subtitleItems.firstOrNull { it.lan.equals(ov, ignoreCase = true) }?.lanDoc ?: subtitleItems.first().lanDoc
            }
        val checked = items.indexOf(currentLabel).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("字幕语言（本次播放）")
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                val chosen = items.getOrNull(which).orEmpty()
                session =
                    when {
                        chosen.startsWith("跟随全局") -> session.copy(subtitleLangOverride = null)
                        chosen.startsWith("自动") -> session.copy(subtitleLangOverride = "auto")
                        else -> {
                            val code = subtitleItems.firstOrNull { it.lanDoc == chosen }?.lan ?: subtitleItems.first().lan
                            session.copy(subtitleLangOverride = code)
                        }
                    }
                dialog.dismiss()
                lifecycleScope.launch {
                    subtitleConfig = buildSubtitleConfigFromCurrentSelection(bvid = currentBvid, cid = currentCid)
                    subtitleAvailable = subtitleConfig != null
                    applySubtitleEnabled(exo)
                    refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                    updateSubtitleButton()
                    reloadStream(keepPosition = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun buildSubtitleConfigFromCurrentSelection(bvid: String, cid: Long): MediaItem.SubtitleConfiguration? {
        if (bvid.isBlank() || cid <= 0) return null
        val chosen = pickSubtitleItem(subtitleItems) ?: return null
        return buildSubtitleConfigFromItem(chosen, bvid, cid)
    }

    private fun normalizeUrl(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") || u.startsWith("https://") -> u
            else -> "https://$u"
        }
    }

    private fun buildWebVtt(body: JSONArray): String {
        val sb = StringBuilder()
        sb.append("WEBVTT\n\n")
        for (i in 0 until body.length()) {
            val line = body.optJSONObject(i) ?: continue
            val from = line.optDouble("from", -1.0)
            val to = line.optDouble("to", -1.0)
            val content = line.optString("content", "").trim()
            if (from < 0 || to <= 0 || content.isBlank()) continue
            sb.append(formatVttTime(from)).append(" --> ").append(formatVttTime(to)).append('\n')
            sb.append(content.replace('\n', ' ')).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatVttTime(sec: Double): String {
        val ms = (sec * 1000.0).toLong().coerceAtLeast(0L)
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, milli)
    }

    private fun formatHms(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    private data class SubtitleItem(
        val lan: String,
        val lanDoc: String,
        val url: String,
    )

    private suspend fun loadDanmaku(cid: Long, aid: Long?): List<blbl.cat3399.core.model.Danmaku> {
        return withContext(Dispatchers.IO) {
            val prefs = BiliClient.prefs
            val followBili = prefs.danmakuFollowBiliShield
            val dmView = if (followBili && BiliClient.cookies.hasSessData()) {
                runCatching { BiliApi.dmWebView(cid, aid) }
                    .onFailure { AppLog.w("Player", "dmWebView failed: ${it.message}") }
                    .getOrNull()
            } else {
                null
            }
            val setting = dmView?.setting
            val shield = DanmakuShield(
                allowScroll = prefs.danmakuAllowScroll && (setting?.allowScroll ?: true),
                allowTop = prefs.danmakuAllowTop && (setting?.allowTop ?: true),
                allowBottom = prefs.danmakuAllowBottom && (setting?.allowBottom ?: true),
                allowColor = prefs.danmakuAllowColor && (setting?.allowColor ?: true),
                allowSpecial = prefs.danmakuAllowSpecial && (setting?.allowSpecial ?: true),
                aiEnabled = prefs.danmakuAiShieldEnabled || (setting?.aiEnabled ?: false),
                aiLevel = maxOf(prefs.danmakuAiShieldLevel, setting?.aiLevel ?: 0),
            )
            val all = ArrayList<blbl.cat3399.core.model.Danmaku>()
            // 初版：先拉 3 个分片（约 18 分钟），避免依赖 dm/web/view 登录态。
            val maxSeg = (dmView?.segmentTotal?.takeIf { it > 0 } ?: 3).coerceAtMost(3)
            for (seg in 1..maxSeg) {
                runCatching { all.addAll(BiliApi.dmSeg(cid, seg)) }
            }
            all.sortBy { it.timeMs }
            val filtered = all.filter(shield::allow)
            AppLog.i("Player", "danmaku cid=$cid raw=${all.size} filtered=${filtered.size} followBili=$followBili hasDmSetting=${setting != null}")
            filtered
        }
    }

    private fun configureSubtitleView() {
        val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
        // Move subtitles slightly up from the very bottom.
        subtitleView.setBottomPaddingFraction(0.16f)
        // Make background more transparent while keeping readability.
        subtitleView.setStyle(
            CaptionStyleCompat(
                /* foregroundColor= */ 0xFFFFFFFF.toInt(),
                /* backgroundColor= */ 0x22000000,
                /* windowColor= */ 0x00000000,
                /* edgeType= */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                /* edgeColor= */ 0xCC000000.toInt(),
                /* typeface= */ null,
            ),
        )
    }

    private fun areaText(area: Float): String = when {
        area >= 0.99f -> "不限"
        area >= 0.74f -> "3/4"
        area >= 0.49f -> "1/2"
        else -> "1/4"
    }

    private data class PlayerSessionSettings(
        val playbackSpeed: Float,
        val preferCodec: String,
        val preferAudioId: Int,
        val subtitleEnabled: Boolean,
        val subtitleLangOverride: String?,
        val danmaku: DanmakuSessionSettings,
        val debugEnabled: Boolean,
    )

    private data class DanmakuSessionSettings(
        val enabled: Boolean,
        val opacity: Float,
        val textSizeSp: Float,
        val speedLevel: Int,
        val area: Float,
    ) {
        fun toConfig(): blbl.cat3399.feature.player.danmaku.DanmakuConfig =
            blbl.cat3399.feature.player.danmaku.DanmakuConfig(
                enabled = enabled,
                opacity = opacity,
                textSizeSp = textSizeSp,
                speedLevel = speedLevel,
                area = area,
            )
    }

    companion object {
        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        private const val SEEK_MAX = 10_000
        private const val AUTO_HIDE_MS = 6_000L
        private const val EDGE_TAP_THRESHOLD = 0.28f
        private const val TAP_SEEK_ACTIVE_MS = 1_200L
        private const val SMART_SEEK_WINDOW_MS = 900L
        private const val HOLD_SPEED = 2.0f
        private const val HOLD_REWIND_TICK_MS = 260L
        private const val HOLD_REWIND_STEP_MS = 520L
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 1_500L
        private const val SEEK_HINT_HIDE_DELAY_MS = 900L
        private const val KEY_SCRUB_END_DELAY_MS = 800L
    }
}
