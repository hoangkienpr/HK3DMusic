package com.hk.app

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenRotationAlt
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay

/**
 * Full-screen landscape video player for local files.
 *
 * - Own ExoPlayer instance (released when the page closes)
 * - Resume where you left off (persisted per video id in prefs)
 * - Side-strip gestures: left = brightness, right = volume
 * - ±10s skip buttons, manual PiP button, back to library
 */
@Composable
fun FullscreenVideoPlayer(
    video: VideoItem,
    pipMode: Boolean,
    onClose: () -> Unit,
    onCastRequest: (() -> Unit)? = null,
    castAvailable: Boolean = false,
    audioUri: android.net.Uri? = null,
    videoState: ((positionSeconds: Int, durationSeconds: Int, isPlaying: Boolean) -> Unit)? = null,
    videoCommand: String? = null,
    onVideoCommandConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember(context) { context.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE) }
    val resumeKey = "video_resume_${video.id}"

    val player = remember(video.id, audioUri) {
        // Generous mid-stream headroom but a small start threshold: playback
        // begins after ~2.5s buffered, then keeps up to 60s ahead.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 120_000, 2_500, 5_000)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
            if (audioUri != null) {
                // YouTube adaptive streams: merge separate video-only + audio-only tracks.
                val sourceFactory = DefaultMediaSourceFactory(DefaultHttpDataSource.Factory())
                val videoSource = sourceFactory.createMediaSource(MediaItem.fromUri(video.uri))
                val audioSource = sourceFactory.createMediaSource(MediaItem.fromUri(audioUri))
                setMediaSource(MergingMediaSource(videoSource, audioSource))
            } else {
                setMediaItem(MediaItem.fromUri(video.uri))
            }
            val saved = prefs.getLong(resumeKey, 0L)
            if (saved > 0L && saved < (video.durationMs - 3000L)) {
                seekTo(saved)
            }
            prepare()
            playWhenReady = true
        }
    }

    // Persist + restore the resume position while watching (guarded so the
    // loop never touches a released player).
    val released = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Web remote: report the current playback state (position/duration/playing)
    // so the browser page can render a "now playing" bar with seek controls.
    LaunchedEffect(player) {
        while (!released.get()) {
            delay(500)
            if (released.get()) break
            runCatching {
                val pos = (player.currentPosition / 1000L).toInt()
                val dur = (player.duration.coerceAtLeast(0L) / 1000L).toInt()
                videoState?.invoke(pos, dur, player.isPlaying)
            }
        }
    }

    // Web remote: apply commands issued by the browser (toggle/seek).
    LaunchedEffect(videoCommand) {
        val cmd = videoCommand ?: return@LaunchedEffect
        when {
            cmd == "toggle" -> if (player.isPlaying) player.pause() else player.play()
            cmd == "seek-10" -> player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
            cmd == "seek+10" -> player.seekTo(
                (player.currentPosition + 10_000L).coerceAtMost(player.duration.coerceAtLeast(0L))
            )
            cmd.startsWith("seek:") -> cmd.removePrefix("seek:").toLongOrNull()?.let { seconds ->
                player.seekTo(seconds * 1000L)
            }
        }
        onVideoCommandConsumed()
    }

    LaunchedEffect(player) {
        while (!released.get()) {
            delay(1_000)
            if (released.get()) break
            runCatching {
                val pos = player.currentPosition
                if (pos > 0) prefs.edit().putLong(resumeKey, pos).apply()
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            released.set(true)
            runCatching { prefs.edit().putLong(resumeKey, player.currentPosition).apply() }
            player.release()
        }
    }

    // Landscape by default on the video page; the rotate button on the top bar
    // flips to portrait. Restored to the previous orientation on exit.
    var isLandscape by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val previous = activity?.requestedOrientation
        onDispose {
            if (previous != null) activity?.requestedOrientation = previous
        }
    }
    LaunchedEffect(isLandscape) {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    BackHandler { onClose() }

    // ---- Gesture overlays --------------------------------------------------
    var centerIndicator by remember { mutableStateOf<Indicator?>(null) }
    LaunchedEffect(centerIndicator) {
        if (centerIndicator != null) {
            delay(1_300)
            centerIndicator = null
        }
    }

    fun adjustVolume(delta: Float): Float {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val next = (cur + (delta * max)).coerceIn(0f, max.toFloat())
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next.toInt(), 0)
        return next / max
    }

    fun adjustBrightness(delta: Float): Float {
        val window = activity?.window ?: return 0.5f
        val lp = window.attributes
        val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        val next = (cur + delta).coerceIn(0.01f, 1f)
        lp.screenBrightness = next
        window.attributes = lp
        return next
    }

    fun togglePlayPause() = if (player.isPlaying) player.pause() else player.play()

    // ---- Surface -----------------------------------------------------------
    if (pipMode) {
        // PiP: pure video surface, nothing else.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = { it.player = player }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = { it.player = player }
        )

        // Left strip: brightness
        Box(
            modifier = Modifier
                .fillMaxSize()
                .width(140.dp)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val next = adjustBrightness(-dragAmount * 0.0022f)
                        centerIndicator = Indicator(kind = IndicatorKind.Brightness, fraction = next)
                    }
                }
        )
        // Right strip: volume
        Box(
            modifier = Modifier
                .fillMaxSize()
                .width(140.dp)
                .align(Alignment.CenterEnd)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val next = adjustVolume(-dragAmount * 0.004f)
                        centerIndicator = Indicator(kind = IndicatorKind.Volume, fraction = next)
                    }
                }
        )

        // Top bar (padded below the system status bar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 18.dp, top = 4.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    video.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    video.folder,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            if (castAvailable && onCastRequest != null) {
                IconButton(
                    onClick = { player.pause(); onCastRequest() },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .padding(start = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Cast,
                        contentDescription = "Chiếu lên TV",
                        tint = Color(0xFF00F2FE)
                    )
                }
            }
            IconButton(
                onClick = { isLandscape = !isLandscape },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    Icons.Filled.ScreenRotationAlt,
                    contentDescription = "Xoay màn hình",
                    tint = if (isLandscape) Color.White else Color(0xFF00F2FE)
                )
            }
            IconButton(
                onClick = {
                    val a = context as? Activity ?: return@IconButton
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runCatching {
                            MainActivity.autoEnterPip = true
                            a.enterPictureInPictureMode(
                                MainActivity.buildPipParams(a, android.util.Rational(16, 9))
                            )
                        }
                    }
                },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
            }
        }

        // ±10s skip buttons
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
        ) {
            IconButton(
                onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) },
                modifier = Modifier.size(54.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(Icons.Filled.Replay10, contentDescription = "Lùi 10s", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            IconButton(
                onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(player.duration)) },
                modifier = Modifier.size(54.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(Icons.Filled.Forward10, contentDescription = "Tua tới 10s", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }

        // Center indicator for brightness/volume
        centerIndicator?.let { indicator ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 26.dp, vertical = 16.dp)
                ) {
                    Icon(
                        if (indicator.kind == IndicatorKind.Brightness) Icons.Filled.Brightness6 else Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(indicator.fraction.coerceIn(0f, 1f))
                                .height(5.dp)
                                .background(Color.White)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }
        }

        // The PlayerView handles tap-to-toggle its own controller.
    }
}

private enum class IndicatorKind { Brightness, Volume }
private data class Indicator(val kind: IndicatorKind, val fraction: Float)
