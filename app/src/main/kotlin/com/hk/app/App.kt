package com.hk.app

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import androidx.compose.ui.layout.ContentScale
import android.app.PictureInPictureParams
import android.util.Rational
import android.view.WindowManager
import android.app.Activity
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

@Composable
fun MusicPlayerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ------------------------------------------------------- permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // ------------------------------------------------------- library
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryRefreshKey by remember { mutableIntStateOf(0) }
    var libraryReady by remember { mutableStateOf(false) }

    LaunchedEffect(libraryRefreshKey) {
        libraryLoading = true
        songs = LibraryRepository.load(context)
        libraryLoading = false
        libraryReady = true
    }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                libraryRefreshKey++
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            true
        }.getOrDefault(false)
        onDispose {
            if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    // ------------------------------------------------------- player
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { controller = future.get() }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            try { future.get().release() } catch (_: Exception) {}
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionSeconds by remember { mutableIntStateOf(0) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var volume by remember { mutableFloatStateOf(0.8f) }
    var isShuffle by remember { mutableStateOf(false) }
    var isRepeat by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var activeTab by remember { mutableIntStateOf(1) }
    var showFolders by remember { mutableStateOf(false) }
    var showTagEditor by remember { mutableStateOf(false) }
    var scrollStyle by remember { mutableIntStateOf(0) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyricFetching by remember { mutableStateOf(false) }
    var localMuted by remember { mutableStateOf(false) }
    var showDownloadsDialog by remember { mutableStateOf(false) }
    var downloadsTick by remember { mutableIntStateOf(0) }
    var playlistUrl by remember { mutableStateOf("") }
    var bulkRunning by remember { mutableStateOf(false) }
    var bulkTotal by remember { mutableIntStateOf(0) }
    var bulkDone by remember { mutableIntStateOf(0) }
    var bulkFailed by remember { mutableIntStateOf(0) }
    var bulkCurrent by remember { mutableStateOf("") }
    var downloadQualityPicker by remember { mutableStateOf<LibraryRepository.YouTubeResult?>(null) }
    var headsetAutoPlayArmed by remember { mutableStateOf(false) }
    val armedState = rememberUpdatedState(headsetAutoPlayArmed)
    val foldersList = remember(songs) {
        songs.map { it.folderPath }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // Audio FX: the playback service publishes its pinned session so the UI can
    // attach the equalizer/bass/virtualizer to the exact stream.
    val audioSessionId = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            audioSessionId.intValue = MusicService.audioSessionId
            kotlinx.coroutines.delay(500)
        }
    }
    val fx = remember(audioSessionId.intValue) {
        if (audioSessionId.intValue > 0) AudioFxEngine(audioSessionId.intValue) else null
    }
    val pipActivity = LocalContext.current as? Activity
    var pipMode by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            pipMode = pipActivity?.isInPictureInPictureMode == true
            delay(150)
        }
    }

    val recordAudioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val audioLevels = rememberAudioLevels(audioSessionId.intValue, enabled = recordAudioGranted, bars = 20)
    val prefs = remember { context.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE) }
    var bassBoostEnabled by remember { mutableStateOf(prefs.getBoolean("bassBoost", false)) }
    var virtualizerEnabled by remember { mutableStateOf(prefs.getBoolean("virtualizer", false)) }
    var eqBands by remember { mutableStateOf<List<AudioFxEngine.Band>>(emptyList()) }
    var bassGain by remember { mutableFloatStateOf(prefs.getFloat("bassGain", 0f)) }
    var volGain by remember { mutableFloatStateOf(prefs.getFloat("volGain", 0f)) }
    var trebGain by remember { mutableFloatStateOf(prefs.getFloat("trebGain", 0f)) }
    var vizMode by remember { mutableIntStateOf(prefs.getInt("vizMode", 0)) }
    LaunchedEffect(vizMode) { prefs.edit().putInt("vizMode", vizMode).apply() }
    var lyricOffsetMs by remember { mutableIntStateOf(prefs.getInt("lyricOffset", 0)) }

    LaunchedEffect(lyricOffsetMs) { prefs.edit().putInt("lyricOffset", lyricOffsetMs).apply() }
    LaunchedEffect(bassGain) { prefs.edit().putFloat("bassGain", bassGain).apply() }
    LaunchedEffect(volGain) { prefs.edit().putFloat("volGain", volGain).apply() }
    LaunchedEffect(trebGain) { prefs.edit().putFloat("trebGain", trebGain).apply() }
    LaunchedEffect(bassBoostEnabled) { prefs.edit().putBoolean("bassBoost", bassBoostEnabled).apply() }
    LaunchedEffect(virtualizerEnabled) { prefs.edit().putBoolean("virtualizer", virtualizerEnabled).apply() }

    LaunchedEffect(fx) {
        eqBands = fx?.bands() ?: emptyList()
        fx?.setBassBoost(bassBoostEnabled)
        fx?.setVirtualizer(virtualizerEnabled)
        eqBands.forEach { band ->
            val gain = when {
                band.centerHz < 500 -> bassGain
                band.centerHz >= 4000 -> trebGain
                else -> volGain
            }
            val r = (band.maxMb - band.minMb).toFloat().coerceAtLeast(1f)
            val gainMb = (gain * 100f).toInt().coerceIn(band.minMb.toInt(), band.maxMb.toInt())
            fx?.setBandFraction(band.index, ((gainMb - band.minMb) / r).coerceIn(0f, 1f))
        }
        eqBands = fx?.bands() ?: emptyList()
    }
    LaunchedEffect(fx, bassBoostEnabled) { fx?.setBassBoost(bassBoostEnabled) }
    LaunchedEffect(fx, virtualizerEnabled) { fx?.setVirtualizer(virtualizerEnabled) }
    val castManager = remember { CastManager(context) }
    LaunchedEffect(Unit) {
        castManager.initialize()
        castManager.startDiscovery()
    }
    DisposableEffect(castManager) {
        onDispose { castManager.release() }
    }
    val dlnaManager = remember { DlnaManager() }
    LaunchedEffect(Unit) { dlnaManager.startDiscovery() }
    DisposableEffect(dlnaManager) {
        onDispose { dlnaManager.release() }
    }
    var youtubeSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var youtubeResults by remember { mutableStateOf<List<LibraryRepository.YouTubeResult>>(emptyList()) }
    var youtubeLoading by remember { mutableStateOf(false) }
    var youtubePage by remember { mutableIntStateOf(1) }
    var youtubeHasMore by remember { mutableStateOf(true) }
    var youtubeLoadingMore by remember { mutableStateOf(false) }
    var youtubePlayingSong by remember { mutableStateOf<Song?>(null) }
    var youtubePlayingVideoId by remember { mutableStateOf<String?>(null) }

    // Lyrics (.lrc) import launcher, target song tracked in lyricImportSongId
    var lyricImportSongId by remember { mutableStateOf<Long?>(null) }
    val lyricImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val songId = lyricImportSongId
        lyricImportSongId = null
        if (uri != null && songId != null) {
            scope.launch {
                val ok = LibraryRepository.importLyrics(context, songId, uri)
                if (ok) {
                    val raw = LibraryRepository.reloadLyrics(context, songId)
                    songs = songs.map { if (it.id == songId) it.copy(lyrics = raw) else it }
                    youtubePlayingSong = youtubePlayingSong?.let {
                        if (it.id == songId) it.copy(lyrics = raw) else it
                    }
                }
                Toast.makeText(
                    context,
                    if (ok) "Đã nhập lời bài hát" else "Không đọc được file .lrc",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Resolve the active player at call time (not at composition time)
    fun resolveActivePlayer(): Player? = when {
        castManager.isCasting -> castManager.castPlayer
        dlnaManager.isCasting -> null
        else -> controller
    }

    // Playback control that works for every output: phone, Chromecast, DLNA TV.
    fun activeTogglePlayback() {
        if (dlnaManager.isCasting) {
            if (dlnaManager.transportPlaying) dlnaManager.pause() else dlnaManager.play()
            return
        }
        val p = resolveActivePlayer()
        if (p != null) {
            if (p.isPlaying) p.pause() else p.play()
        } else {
            isPlaying = !isPlaying
        }
    }

    fun activePlay() {
        if (dlnaManager.isCasting) dlnaManager.play() else resolveActivePlayer()?.play()
        isPlaying = true
    }

    fun activePause() {
        if (dlnaManager.isCasting) dlnaManager.pause() else resolveActivePlayer()?.pause()
        isPlaying = false
    }

    fun activeSeek(seconds: Int) {
        if (dlnaManager.isCasting) {
            dlnaManager.seekTo(seconds.toLong() * 1000L)
        } else {
            resolveActivePlayer()?.seekTo(seconds.toLong() * 1000L)
        }
    }

    // Pending web-remote cast requests, fulfilled after the server exists.
    var castConnectRequest by remember { mutableStateOf<CastTarget?>(null) }
    var castPlayIndexRequest by remember { mutableStateOf<Int?>(null) }
    // Device id the web asked for before it showed up in the discovered list.
    var pendingCastConnectId by remember { mutableStateOf<String?>(null) }
    // Track of the exact song we already handed to the TV. Re-running the push
    // effect must not restart/resume playback while the user has it paused.
    var castPushedKey by remember { mutableStateOf<String?>(null) }

    // Web remote state
    var serverRunning by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var connectedClients by remember { mutableIntStateOf(0) }
    var localIp by remember { mutableStateOf(localIpv4(context) ?: "") }
    var playlistShareUrl by remember { mutableStateOf<String?>(null) }

    // Party mode: phone is the host. Guests (web) may add songs and may only
    // remove the ones they added; the host can remove everything.
    var partyMode by remember { mutableStateOf(prefs.getBoolean("partyMode", false)) }
    var songOwners by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(partyMode) { prefs.edit().putBoolean("partyMode", partyMode).commit() }

    val server = remember {
        WebRemoteServer(
            context = context,
            port = 8080,
            stateProvider = {
                val phoneIp = localIpv4(context) ?: ""
                val liveCastTargets: List<CastTarget> = buildList {
                    addAll(castManager.devices.distinctBy { it.name }.map { CastTarget(it.id, it.name, false) })
                    addAll(
                        dlnaManager.devices
                            .filter { phoneIp.isEmpty() || !it.location.contains("//$phoneIp") }
                            .distinctBy { it.name }
                            .map { CastTarget("dlna:" + it.id, it.name, true) }
                    )
                    val connectedDlna = dlnaManager.connectedName
                    if (connectedDlna != null && none { it.isDlna && it.name == connectedDlna }) {
                        add(CastTarget("dlna:" + (dlnaManager.connectedId ?: connectedDlna), connectedDlna, true))
                    }
                }
                RemoteState(
                    songs = songs,
                    currentIndex = currentIndex,
                    currentSong = youtubePlayingSong,
                    youtubeUrl = youtubePlayingSong?.audioUri?.toString(),
                    isPlaying = isPlaying,
                    positionSeconds = positionSeconds,
                    volume = volume,
                    isShuffle = isShuffle,
                    isRepeat = isRepeat,
                    localIp = localIp,
                    port = 8080,
                    queue = emptyList(),
                    currentMediaId = songs.getOrNull(currentIndex)?.id?.toString() ?: "",
                    castAvailable = castManager.available || liveCastTargets.isNotEmpty(),
                    castConnectedName = castManager.connectedName ?: dlnaManager.connectedName,
                    castConnectedId = when {
                        dlnaManager.isCasting ->
                            liveCastTargets.firstOrNull { it.isDlna && (dlnaManager.connectedId == null || it.id == "dlna:" + dlnaManager.connectedId) }?.id
                                ?: dlnaManager.connectedId?.let { "dlna:$it" }
                        else -> liveCastTargets.firstOrNull { !it.isDlna && it.name == castManager.connectedName }?.id
                    },
                    castDevices = liveCastTargets.map { CastDevice(it.id, if (it.isDlna) it.name + " (DLNA)" else it.name) },
                    isCasting = castManager.isCasting || dlnaManager.isCasting,
                    partyMode = partyMode,
                    owners = songs.map { songOwners[it.id] }
                )
            },
            onCommand = { command ->
                // Commands arrive on the HTTP server thread – MediaController calls must run on main.
                Handler(Looper.getMainLooper()).post {
                    when (command) {
                    RemoteCommand.Toggle -> activeTogglePlayback()
                    RemoteCommand.Play -> activePlay()
                    RemoteCommand.Pause -> activePause()
                    RemoteCommand.Next -> { if (songs.isNotEmpty()) { currentIndex = (currentIndex + 1) % songs.size; isPlaying = true } }
                    RemoteCommand.Previous -> { if (songs.isNotEmpty()) { currentIndex = (currentIndex - 1 + songs.size) % songs.size; isPlaying = true } }
                    is RemoteCommand.PlayIndex -> { currentIndex = command.index; isPlaying = true }
                    is RemoteCommand.Seek -> { positionSeconds = command.seconds; activeSeek(command.seconds) }
                    is RemoteCommand.Volume -> {
                        volume = command.value
                        if (dlnaManager.isCasting) {
                            dlnaManager.setTvVolume((command.value * 100f).toInt())
                        } else {
                            resolveActivePlayer()?.volume = command.value
                        }
                    }
                    is RemoteCommand.Mute -> {
                        if (dlnaManager.isCasting) dlnaManager.setTvMute(command.enabled)
                    }
                    is RemoteCommand.Shuffle -> { isShuffle = command.enabled; resolveActivePlayer()?.shuffleModeEnabled = command.enabled }
                    is RemoteCommand.Repeat -> { isRepeat = command.enabled; resolveActivePlayer()?.repeatMode = if (command.enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }
                    is RemoteCommand.PlayQueuePos -> { if (command.position in songs.indices) { currentIndex = command.position; isPlaying = true } }
                    is RemoteCommand.RemoveQueue -> {
                        val pos = command.position
                        val victim = songs.getOrNull(pos)
                        if (victim != null) {
                            val who = command.who?.trim().orEmpty()
                            val owner = songOwners[victim.id]
                            // Host (phone / no nickname) can remove anything; a guest
                            // only the tracks they queued while party mode is on.
                            val allowed = !partyMode || who.isEmpty() ||
                                (owner != null && owner.equals(who, ignoreCase = true))
                            if (allowed) {
                                val wasCurrent = pos == currentIndex
                                songs = songs.filterIndexed { index, _ -> index != pos }
                                songOwners = songOwners - victim.id
                                currentIndex = when {
                                    songs.isEmpty() -> 0
                                    pos < currentIndex -> currentIndex - 1
                                    currentIndex >= songs.size -> 0
                                    wasCurrent -> currentIndex.coerceAtMost(songs.size - 1)
                                    else -> currentIndex
                                }
                                if (wasCurrent && songs.isNotEmpty()) isPlaying = true
                            }
                        }
                    }

                    is RemoteCommand.PlaySelection -> { val picked = command.indices.distinct().firstOrNull { it in songs.indices }; if (picked != null) { currentIndex = picked; isPlaying = true } }
                    RemoteCommand.CastRescan -> { castManager.startDiscovery(); dlnaManager.startDiscovery() }
                    is RemoteCommand.CastConnect -> {
                        // Must run after `server` is ready: delegate through state.
                        val match = castManager.devices
                            .map { CastTarget(it.id, it.name, false) }
                            .plus(dlnaManager.devices.map { CastTarget("dlna:" + it.id, it.name, true) })
                            .firstOrNull { it.id == command.id }
                        if (match != null) {
                            castConnectRequest = match
                            isPlaying = true
                        } else {
                            // Device list went stale — rescan and connect when it appears.
                            pendingCastConnectId = command.id
                            castManager.startDiscovery()
                            dlnaManager.startDiscovery()
                        }
                    }
                    RemoteCommand.CastDisconnect -> { val p = resolveActivePlayer(); castManager.disconnect(); dlnaManager.disconnect(); castPushedKey = null; p?.pause() }
                    is RemoteCommand.CastPlayIndex -> {
                        if (command.index in songs.indices) castPlayIndexRequest = command.index
                    }
                    }
                }
            },
            onUpload = { file, who ->
                scope.launch {
                    val song = LibraryRepository.songFromFile(context, file)
                    songs = songs + song
                    if (!who.isNullOrBlank()) {
                        songOwners = songOwners + (song.id to who.trim())
                    }
                }
            },
            onClientCountChanged = { count -> connectedClients = count }
        )
    }

    fun toggleServer() {
        if (serverRunning) {
            server.stop()
            serverRunning = false
            prefs.edit().putBoolean("webRemote", false).apply()
        } else {
            localIp = localIpv4(context) ?: ""
            if (localIp.isNotEmpty()) {
                serverRunning = server.start()
                prefs.edit().putBoolean("webRemote", serverRunning).apply()
                if (!serverRunning) {
                    serverError = "Không mở được cổng 8080 (đã bị ứng dụng khác chiếm?)"
                } else {
                    serverError = null
                }
            } else {
                serverError = "Chưa kết nối Wi-Fi"
            }
        }
    }

    // Restore the web remote on launch so a phone/app restart does not break the
    // browser tab the user already has open.
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("webRemote", true)) return@LaunchedEffect
        val ip = localIpv4(context) ?: ""
        if (ip.isEmpty()) return@LaunchedEffect
        localIp = ip
        if (!serverRunning) serverRunning = server.start()
    }

    DisposableEffect(Unit) {
        onDispose { server.stop() }
    }

    // Fulfil web-remote cast requests now that the server object exists.
    LaunchedEffect(castManager.devices, dlnaManager.devices, pendingCastConnectId) {
        val wanted = pendingCastConnectId ?: return@LaunchedEffect
        val match = castManager.devices
            .map { CastTarget(it.id, it.name, false) }
            .plus(dlnaManager.devices.map { CastTarget("dlna:" + it.id, it.name, true) })
            .firstOrNull { it.id == wanted } ?: return@LaunchedEffect
        pendingCastConnectId = null
        castConnectRequest = match
        isPlaying = true
    }

    LaunchedEffect(castConnectRequest) {
        val target = castConnectRequest ?: return@LaunchedEffect
        castConnectRequest = null
        if (!serverRunning) {
            localIp = localIpv4(context) ?: ""
            if (localIp.isNotEmpty()) serverRunning = server.start()
        }
        controller?.pause()
        if (target.isDlna) {
            dlnaManager.devices.firstOrNull { "dlna:" + it.id == target.id }
                ?.let { dlnaManager.connect(it) }
        } else {
            castManager.devices.firstOrNull { it.id == target.id }
                ?.let { castManager.connect(it) }
        }
        isPlaying = true
    }

    LaunchedEffect(castPlayIndexRequest) {
        val index = castPlayIndexRequest ?: return@LaunchedEffect
        castPlayIndexRequest = null
        val dev = castManager.devices.firstOrNull { it.name == castManager.connectedName }
            ?: castManager.devices.firstOrNull()
        if (dev != null && !castManager.isCasting) {
            if (!serverRunning) {
                localIp = localIpv4(context) ?: ""
                if (localIp.isNotEmpty()) serverRunning = server.start()
            }
            castManager.connect(dev)
        }
        currentIndex = index
        isPlaying = true
    }

    LaunchedEffect(searchQuery, youtubeSearchMode) {
        if (youtubeSearchMode && searchQuery.length >= 3) {
            youtubeResults = emptyList()
            youtubePage = 1
            youtubeHasMore = true
            youtubeLoading = true
            youtubeLoadingMore = false
            val found = LibraryRepository.searchYouTube(searchQuery, 1) { r ->
                Handler(Looper.getMainLooper()).post { youtubeResults = youtubeResults + r }
            }
            youtubeLoading = false
            youtubeHasMore = found.isNotEmpty()
        } else if (!youtubeSearchMode) {
            youtubeResults = emptyList()
            youtubePage = 1
            youtubeHasMore = true
        }
    }

    var castPushKey by remember { mutableIntStateOf(0) }

    val castActive = castManager.isCasting || dlnaManager.isCasting
    val phoneIp = remember(dlnaManager.devices, localIp) { localIpv4(context) ?: localIp }
    val castTargets: List<CastTarget> = buildList {
        addAll(castManager.devices.distinctBy { it.name }.map { CastTarget(it.id, it.name, false) })
        addAll(
            dlnaManager.devices
                .filter { phoneIp.isEmpty() || !it.location.contains("//$phoneIp") }
                .distinctBy { it.name }
                .map { CastTarget("dlna:" + it.id, it.name, true) }
        )
        // Keep the renderer we are casting to in the list even when a scan missed it.
        val connectedDlna = dlnaManager.connectedName
        if (connectedDlna != null && none { it.isDlna && it.name == connectedDlna }) {
            add(CastTarget("dlna:" + (dlnaManager.connectedId ?: connectedDlna), connectedDlna, true))
        }
    }
    val castConnectedName = castManager.connectedName ?: dlnaManager.connectedName
    val loadMoreYoutube: () -> Unit = {
        if (youtubeSearchMode && searchQuery.length >= 3 &&
            !youtubeLoading && !youtubeLoadingMore && youtubeHasMore
        ) {
            youtubeLoadingMore = true
            val nextPage = youtubePage + 1
            scope.launch {
                val found = LibraryRepository.searchYouTube(searchQuery, nextPage) { r ->
                    Handler(Looper.getMainLooper()).post { youtubeResults = youtubeResults + r }
                }
                youtubePage = nextPage
                youtubeLoadingMore = false
                if (found.isEmpty()) youtubeHasMore = false
            }
        }
    }

    val song = songs.getOrNull(currentIndex)

    // Bridge the system's PiP action buttons (prev/play/next) into the app on Android 12+.
    DisposableEffect(Unit) {
        MainActivity.pipActionHandler = { action ->
            when (action) {
                MainActivity.PIP_PREV -> {
                    if (songs.isNotEmpty()) {
                        val idx = songs.indexOfFirst { it.id == (youtubePlayingSong ?: song)?.id }
                        if (idx >= 0) LibraryRepository.recordSkip(context, songs[idx].id)
                        currentIndex = (currentIndex - 1 + songs.size) % songs.size
                    }
                }
                MainActivity.PIP_NEXT -> {
                    if (songs.isNotEmpty()) {
                        val idx = songs.indexOfFirst { it.id == (youtubePlayingSong ?: song)?.id }
                        if (idx >= 0) LibraryRepository.recordSkip(context, songs[idx].id)
                        currentIndex = (currentIndex + 1) % songs.size
                    }
                }
                MainActivity.PIP_TOGGLE -> {
                    activeTogglePlayback()
                }
            }
        }
        onDispose { MainActivity.pipActionHandler = null }
    }

    LaunchedEffect(castManager.isCasting, dlnaManager.isCasting, dlnaManager.connectedName, controller, song, castPushKey) {
        if (song == null || song.audioUri == null) return@LaunchedEffect

        // DLNA: the TV decodes the stream itself, so just hand it our URL.
        if (dlnaManager.isCasting) {
            val ip = localIpv4(context) ?: localIp
            if (ip.isEmpty()) return@LaunchedEffect
            if (!serverRunning) serverRunning = server.start()
            if (!serverRunning) return@LaunchedEffect
            val pushKey = "${dlnaManager.connectedId}:${song.id}:$castPushKey"
            if (pushKey == castPushedKey) {
                // Same song already on the TV — do not resend (that would resume
                // playback after the user paused it).
                controller?.pause()
                return@LaunchedEffect
            }
            castPushedKey = pushKey
            dlnaManager.playTrack(
                trackId = song.id.toString(),
                url = "http://$ip:8080/audio?index=$currentIndex",
                title = song.title,
                artist = song.artist,
                album = song.album,
                artworkUrl = "http://$ip:8080/artwork?index=$currentIndex"
            )
            controller?.pause()
            return@LaunchedEffect
        }

        val player = resolveActivePlayer() ?: return@LaunchedEffect
        runCatching {
            val ip = localIpv4(context) ?: ""
            val uri = if (castActive) {
                "http://$ip:8080/audio?index=$currentIndex"
            } else {
                song.audioUri.toString()
            }
            val artworkUrl = if (castActive && ip.isNotEmpty()) {
                "http://$ip:8080/artwork?index=$currentIndex"
            } else null
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(song.id.toString())
                .setMimeType("audio/mpeg")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .apply { if (artworkUrl != null) setArtworkUri(Uri.parse(artworkUrl)) }
                        .build()
                )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            if (isPlaying) player.play()
        }
        // Keep the phone-side media session in sync so the notification / lock
        // screen controls show and play the CURRENT track while we cast to the TV.
        // It stays paused — during cast the phone speaker stays silent.
        if (castActive && controller != null && controller !== player) {
            val localItem = MediaItem.Builder()
                .setUri(song.audioUri)
                .setMediaId(song.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build())
                .build()
            val localPlayer = controller ?: return@LaunchedEffect
            runCatching {
                localPlayer.setMediaItem(localItem)
                localPlayer.prepare()
                localPlayer.pause()
            }
        }
    }

    // Silence the phone speaker for the whole cast session; the TV/web keep
    // their own volume control. Rebuild from scratch on every cast transition.
    LaunchedEffect(castActive) {
        controller?.volume = if (castActive || localMuted) 0f else volume
    }

    // Poll actual player state to keep UI in sync
    var endedSongId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(castManager.isCasting, dlnaManager.isCasting, dlnaManager.connectedName, controller) {
        if (dlnaManager.isCasting) {
            while (true) {
                delay(900)
                dlnaManager.poll()
                isPlaying = dlnaManager.transportPlaying
                if (dlnaManager.positionSeconds > 0) positionSeconds = dlnaManager.positionSeconds
                val total = dlnaManager.durationSeconds
                val current = dlnaManager.positionSeconds
                if (total > 5 && current >= total - 1) {
                    val endedId = songs.getOrNull(currentIndex)?.id
                    if (songs.isNotEmpty() && endedId != null && endedSongId != endedId) {
                        endedSongId = endedId
                        currentIndex = (currentIndex + 1) % songs.size
                        isPlaying = true
                        youtubePlayingSong = null
                    }
                } else {
                    endedSongId = null
                }
            }
        }
        val p = resolveActivePlayer() ?: return@LaunchedEffect
        while (true) {
            delay(500)
            isPlaying = p.isPlaying
            positionSeconds = (p.currentPosition / 1000L).toInt()
            if (p.playbackState == Player.STATE_ENDED) {
                val endedId = songs.getOrNull(currentIndex)?.id
                if (songs.isNotEmpty() && endedId != null && endedSongId != endedId) {
                    endedSongId = endedId
                    currentIndex = (currentIndex + 1) % songs.size
                    isPlaying = true
                    youtubePlayingSong = null
                }
            } else {
                endedSongId = null
            }
        }
    }

    // Arm the headset auto-play shortly after launch so it does not blast
    // music the moment the app opens while a headset is plugged in.
    LaunchedEffect(Unit) {
        delay(1500)
        headsetAutoPlayArmed = true
    }

    // Auto-play as soon as a wired or Bluetooth audio device connects
    DisposableEffect(Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val headsetish = addedDevices.any { device ->
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        device.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
                if (headsetish && armedState.value && !castManager.isCasting) {
                    Handler(Looper.getMainLooper()).post {
                        if (!isPlaying) {
                            val p = resolveActivePlayer()
                            if (p != null) p.play() else isPlaying = true
                            Toast.makeText(context, "Tai nghe kết nối — phát tiếp", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    /** Fetch synced lyrics for the current song from LRCLIB and store them. */
    fun fetchLyricsOnline() {
        val target = youtubePlayingSong ?: song ?: return
        val title = target.title
        val artist = target.artist
        val duration = target.durationSeconds
        lyricFetching = true
        scope.launch {
            val raw = LibraryRepository.fetchLyrics(context, title, artist, duration)
            if (raw != null) {
                LibraryRepository.saveFetchedLyrics(context, target.id, raw)
                val reloaded = LibraryRepository.reloadLyrics(context, target.id)
                songs = songs.map { if (it.id == target.id) it.copy(lyrics = reloaded) else it }
                youtubePlayingSong = youtubePlayingSong?.let {
                    if (it.id == target.id) it.copy(lyrics = reloaded) else it
                }
                Toast.makeText(context, "Đã tải lời bài hát", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Không tìm thấy lời bài hát online", Toast.LENGTH_SHORT).show()
            }
            lyricFetching = false
        }
    }

    /** Starts a YouTube download with an optional bitrate cap (bits per second). */
    fun startYoutubeDownload(result: LibraryRepository.YouTubeResult, maxBitrateBps: Int?) {
        scope.launch {
            val file = LibraryRepository.downloadYouTubeAudio(context, result.videoId, result.title, maxBitrateBps)
            if (file != null) {
                Toast.makeText(context, "\u0110\u00e3 t\u1ea3i: ${result.title}", Toast.LENGTH_LONG).show()
                val song = LibraryRepository.songFromFile(context, file, folderLabel = "YouTube")
                songs = songs + song
            } else {
                Toast.makeText(context, "T\u1ea3i th\u1ea5t b\u1ea1i", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Downloads every audio item in a YouTube playlist into offline storage. */
    fun startBulkPlaylistDownload() {
        val target = playlistUrl.trim()
        if (target.isBlank() || bulkRunning) return
        bulkRunning = true
        bulkTotal = 0
        bulkDone = 0
        bulkFailed = 0
        bulkCurrent = ""

        scope.launch {
            val items = LibraryRepository.getYouTubePlaylist(target)
            if (items.isEmpty()) {
                Toast.makeText(context, "Không tìm thấy playlist (link sai hoặc playlist rỗng)", Toast.LENGTH_LONG).show()
                bulkRunning = false
                return@launch
            }
            bulkTotal = items.size
            for (item in items) {
                if (!bulkRunning) break
                bulkCurrent = item.title
                val file = LibraryRepository.downloadYouTubeAudio(context, item.videoId, item.title, null)
                if (file != null) {
                    val downloaded = LibraryRepository.songFromFile(context, file, folderLabel = "YouTube")
                    songs = songs + downloaded
                } else {
                    bulkFailed++
                }
                bulkDone++
            }
            downloadsTick++
            if (!bulkRunning) {
                Toast.makeText(context, "Đã hủy (${bulkDone}/$bulkTotal bài đã tải xong)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Đã tải ${bulkDone - bulkFailed}/$bulkTotal bài từ playlist", Toast.LENGTH_LONG).show()
            }
            bulkRunning = false
            bulkCurrent = ""
        }
    }

    fun cancelBulkPlaylistDownload() { bulkRunning = false }

    fun formatFileSize(bytes: Long): String =
        if (bytes >= 1024L * 1024L) {
            String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        } else {
            "${bytes / 1024L} KB"
        }

    val totalSeconds = (youtubePlayingSong ?: song)?.durationSeconds?.coerceAtLeast(1) ?: 0

    val bgBreath by rememberInfiniteTransition(label = "bgBreath").animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgBreath"
    )

    Box(modifier = Modifier.fillMaxSize().background(
        Brush.radialGradient(
            colors = listOf(
                (song?.color1 ?: Color(0xFF4FACFE)).copy(alpha = bgBreath),
                (song?.color2 ?: Color(0xFF00F2FE)).copy(alpha = bgBreath * 0.5f),
                Color(0xFF070A12)
            ),
            radius = 1100f
        )
    )) {
        // Picture-in-Picture: render ONLY the compact player surface — no tabs, no mini bar, no controls.
        if (pipMode) {
            val pipSong = youtubePlayingSong ?: song ?: songs.firstOrNull()
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (pipSong?.albumArtBitmap != null) {
                    Image(
                        bitmap = pipSong.albumArtBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Subtle bottom scrim so transport buttons stay legible on any artwork
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .height(92.dp)
                            .background(Color.Black.copy(alpha = 0.55f))
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = pipSong?.color1 ?: Color.White, modifier = Modifier.size(96.dp))
                }
                // Bottom legible area: title/artist + transport
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    pipSong?.let {
                        Text(it.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(it.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        // Android 8–11: no system PiP action buttons, draw our own transport row.
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentIndex = (currentIndex - 1 + songs.size) % songs.size }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        IconButton(
                            onClick = {
                                activeTogglePlayback()
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = { currentIndex = (currentIndex + 1) % songs.size }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
            }
            return@Box
        }
        if (libraryLoading && songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Đang tải thư viện...", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            return@Box
        }

        if (songs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text("Không tìm thấy bài hát", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Hãy copy nhạc vào bộ nhớ máy (thư mục Music) rồi quét lại.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { libraryRefreshKey++ }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F2FE))) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Quét lại", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            return@Box
        }

        val displaySong = youtubePlayingSong ?: song ?: songs.first()

        // ------------------------------------------------------- main layout
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 1.dp).padding(top = 35.dp, bottom = 30.dp)) {

            // Tab bar
            Row(modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tabs = listOf(0 to "Full Player", 1 to "Nhạc", 2 to "Share", 3 to "EQ")
                tabs.forEach { (tab, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable { activeTab = tab; if (tab == 0) showFullPlayer = true }
                            .background(if (activeTab == tab) displaySong.color1.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                            .border(1.dp, if (activeTab == tab) displaySong.color1 else Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (activeTab == tab) displaySong.color1 else Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            if (activeTab == 1) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(if (youtubeSearchMode) "Tìm trên YouTube..." else "Tìm trong thư viện...", color = Color.White.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.weight(1f),

                        trailingIcon = {
                            IconButton(onClick = { youtubeSearchMode = !youtubeSearchMode; searchQuery = "" }) {
                                Icon(
                                    if (youtubeSearchMode) Icons.Default.Search else Icons.Filled.SmartDisplay,
                                    contentDescription = "Chuyển giữa thư viện và YouTube",
                                    tint = if (youtubeSearchMode) Color(0xFFFF0000) else Color(0xFF00F2FE)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00F2FE),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF00F2FE)
                        )
                    )
                    CustomChip(
                        selected = showFolders,
                        text = if (showFolders) "Dir" else "Lib",
                        onClick = { showFolders = !showFolders }
                    )
                    // Scroll effect style
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { scrollStyle = (scrollStyle + 1) % 3 }
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                            .border(1.dp, displaySong.color1.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (scrollStyle) {
                                0 -> "3D"; 1 -> "C"; else -> "Z"
                            },
                            color = displaySong.color1,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Offline YouTube downloads manager
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { showDownloadsDialog = true }
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Tải YouTube offline",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content area — infinite horizontal swipe + 3D cube transition
            var displayedTab by remember { mutableIntStateOf(activeTab) }
            val cubeRotation = remember { Animatable(0f) }
            var dragAccum by remember { mutableFloatStateOf(0f) }
            val tabDensity = LocalDensity.current
            val tabCount = 4

            LaunchedEffect(activeTab) {
                if (displayedTab != activeTab) {
                    val forward = (activeTab - displayedTab + tabCount) % tabCount == 1
                    val dir = if (forward) -1f else 1f
                    cubeRotation.animateTo(90f * dir, animationSpec = tween(170, easing = FastOutLinearInEasing))
                    displayedTab = activeTab
                    cubeRotation.snapTo(-90f * dir)
                    cubeRotation.animateTo(0f, animationSpec = tween(240, easing = LinearOutSlowInEasing))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragAccum = 0f },
                            onDragCancel = { dragAccum = 0f },
                            onDragEnd = {
                                val threshold = 80f
                                if (dragAccum < -threshold) activeTab = (activeTab + 1) % tabCount
                                else if (dragAccum > threshold) activeTab = (activeTab + tabCount - 1) % tabCount
                                dragAccum = 0f
                            }
                        ) { _, dragAmount -> dragAccum += dragAmount }
                    }
                    .graphicsLayer {
                        rotationY = cubeRotation.value
                        cameraDistance = with(tabDensity) { 440.dp.toPx() }
                        transformOrigin = TransformOrigin(if (cubeRotation.value >= 0f) 1f else 0f, 0.5f)
                        alpha = 1f - (abs(cubeRotation.value) / 90f) * 0.35f
                    }
            ) {
                when (displayedTab) {
                    3 -> EqualizerView(
                        song = displaySong,
                        bands = eqBands,
                        eqSupported = fx?.eqAvailable == true,
                        bassSupported = fx?.bassAvailable == true,
                        virtualizerSupported = fx?.virtualizerAvailable == true,
                        bassBoostEnabled = bassBoostEnabled,
                        onBassBoostToggle = { bassBoostEnabled = !bassBoostEnabled },
                        virtualizerEnabled = virtualizerEnabled,
                        onVirtualizerToggle = { virtualizerEnabled = !virtualizerEnabled },
                        onBandChange = { band, fraction ->
                            fx?.setBandFraction(band, fraction)
                            eqBands = fx?.bands() ?: emptyList()
                        },
                        audioSessionId = audioSessionId.intValue,
                        bassGain = bassGain,
                        volGain = volGain,
                        trebGain = trebGain,
                        onBassGainChange = { bassGain = it },
                        onVolGainChange = { volGain = it },
                        onTrebGainChange = { trebGain = it }
                    )

                    2 -> ShareHubView(
                        currentSong = displaySong,
                        localIp = localIp.ifEmpty { null },
                        port = 8080,
                        serverRunning = serverRunning,
                        serverError = serverError,
                        clients = connectedClients,
                        onServerToggle = { toggleServer() },
                        castAvailable = castManager.available || dlnaManager.devices.isNotEmpty(),
                        castDevices = castTargets,
                        castConnectedName = castConnectedName,
                        castScanning = dlnaManager.scanning,
                        onCastRescan = {
                            castManager.startDiscovery()
                            dlnaManager.startDiscovery()
                        },
                        onCastConnect = { target ->
                            // Make sure the local audio server is up before handing the URL to the TV
                            if (!serverRunning) {
                                localIp = localIpv4(context) ?: ""
                                if (localIp.isNotEmpty()) {
                                    serverRunning = server.start()
                                }
                            }
                            controller?.pause()
                            if (target.isDlna) {
                                dlnaManager.devices.firstOrNull { "dlna:" + it.id == target.id }
                                    ?.let { dlnaManager.connect(it) }
                            } else {
                                castManager.devices.firstOrNull { it.id == target.id }
                                    ?.let { castManager.connect(it) }
                            }
                            isPlaying = true
                        },
                        onCastDisconnect = { activePause(); castManager.disconnect(); dlnaManager.disconnect(); castPushedKey = null },
                        onCastPlayCurrent = { isPlaying = true; castPushKey++ },
                        context = context,
                        playlistShareUrl = playlistShareUrl,
                        partyMode = partyMode,
                        partySongCount = songOwners.size,
                        onPartyToggle = { on ->
                            partyMode = on
                            prefs.edit().putBoolean("partyMode", on).commit()
                        }
                    )

                    1 -> {
                        if (youtubeSearchMode) {
                            if (youtubeResults.isEmpty() && !youtubeLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Nhập từ khoá để tìm trên YouTube", color = Color.White.copy(alpha = 0.5f))
                                }
                            } else {
                                YouTubeResultsView(
                                    results = youtubeResults,
                                    isLoading = youtubeLoading,
                                    isLoadingMore = youtubeLoadingMore,
                                    hasMore = youtubeHasMore,
                                    onLoadMore = loadMoreYoutube,
                                    song = displaySong,
                                    onPlay = { result ->
                                        scope.launch {
                                            val audioUrl = LibraryRepository.getYouTubeAudioUrl(result.videoId)
                                            if (audioUrl != null) {
                                                val ytSong = Song(
                                                    id = result.videoId.hashCode().toLong(),
                                                    title = result.title,
                                                    artist = result.author,
                                                    album = "YouTube",
                                                    durationSeconds = result.duration,
                                                    genre = "",
                                                    color1 = Color(0xFFFF0000),
                                                    color2 = Color(0xFFCC0000),
                                                    audioUri = Uri.parse(audioUrl)
                                                )
                                                youtubePlayingSong = ytSong
                                                youtubePlayingVideoId = result.videoId
                                                val mediaItem = MediaItem.Builder()
                                                    .setUri(audioUrl)
                                                    .setMediaMetadata(MediaMetadata.Builder().setTitle(result.title).setArtist(result.author).build())
                                                    .build()
                                                resolveActivePlayer()?.setMediaItem(mediaItem)
                                                resolveActivePlayer()?.prepare()
                                                resolveActivePlayer()?.play()
                                            }
                                        }
                                    },
                                    onDownload = { result -> downloadQualityPicker = result }
                                )
                            }
                        } else {
                            val filtered = if (searchQuery.isBlank()) songs else {
                                songs.filter {
                                    it.title.contains(searchQuery, ignoreCase = true) ||
                                        it.artist.contains(searchQuery, ignoreCase = true) ||
                                        it.album.contains(searchQuery, ignoreCase = true)
                                }
                            }
                            if (showFolders) {
                                FolderBrowserView(
                                    folders = foldersList,
                                    songs = filtered,
                                    currentSong = displaySong,
                                    isPlaying = isPlaying,
                                    onSongSelected = { selected ->
                                        val index = songs.indexOfFirst { it.id == selected.id }
                                        if (index >= 0) {
                                            currentIndex = index
                                            isPlaying = true
                                            youtubePlayingSong = null
                                        }
                                    }
                                )
                            } else {
                                SongList3DWithGestures(
                                    filteredSongs = filtered,
                                    currentSong = displaySong,
                                    isPlaying = isPlaying,
                                    scrollStyle = scrollStyle,
                                    onSongSelected = { selected ->
                                        val index = songs.indexOfFirst { it.id == selected.id }
                                        if (index >= 0) {
                                            currentIndex = index
                                            isPlaying = true
                                            youtubePlayingSong = null
                                        }
                                    },
                                onSwipeRightToShare = {},
                                onSwipeLeftToRemote = { activeTab = 2 }
                            )
                        }
                    }
                    }

                    0 -> {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                                if (showLyrics) {
                                    KaraokeLyricsView(
                                        song = displaySong,
                                        positionSeconds = positionSeconds,
                                        accentColor = displaySong.color1,
                                        onSeekSeconds = { sec ->
                                            positionSeconds = sec
                                            resolveActivePlayer()?.seekTo(sec * 1000L)
                                        },
                                        onFetchOnline = { fetchLyricsOnline() },
                                        onImportLrc = {
                                            lyricImportSongId = displaySong.id
                                            lyricImportLauncher.launch(arrayOf("*/*"))
                                        },
                                        isFetching = lyricFetching,
                                        offsetMs = lyricOffsetMs,
                                        onOffsetChange = { lyricOffsetMs = it },
                                        modifier = Modifier.fillMaxWidth().height(260.dp)
                                            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(20.dp))
                                            .padding(4.dp)
                                    )
                                } else {
                                Box(modifier = Modifier.size(220.dp).background(Brush.linearGradient(listOf(displaySong.color1.copy(alpha = 0.3f), displaySong.color2.copy(alpha = 0.3f))), CircleShape).border(2.dp, displaySong.color1, CircleShape), contentAlignment = Alignment.Center) {
                                    if (displaySong.albumArtBitmap != null) {
                                        Image(bitmap = displaySong.albumArtBitmap, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
                                    } else {
                                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = displaySong.color1, modifier = Modifier.size(96.dp))
                                    }
                                }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(displaySong.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxWidth().basicMarquee())
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(displaySong.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.15f), CircleShape)) {
                                        val progress = if (totalSeconds > 0) positionSeconds.toFloat() / totalSeconds.toFloat() else 0f
                                        Box(modifier = Modifier.fillMaxWidth(progress).height(4.dp).background(displaySong.color1, CircleShape))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(formatTime(positionSeconds), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                        Text(formatTime(totalSeconds), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                    }
                                }
                                if (recordAudioGranted) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(64.dp)
                                                .background(
                                                    Color.White.copy(alpha = 0.04f),
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                                .pointerInput(Unit) {
                                                    detectTapGestures {
                                                        vizMode = (vizMode + 1) % 5
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            arrayOf(
                                                                "Bars", "Wave", "Radial", "Mirror", "Constellation"
                                                            )[vizMode] + " visualizer",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                        ) {
                                            AudioVisualizerView(
                                                levels = audioLevels.waveform,
                                                isPlaying = isPlaying,
                                                mode = vizMode,
                                                primaryColor = displaySong.color1,
                                                spectrum = audioLevels.spectrum
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFFFB199).copy(alpha = 0.12f))
                                            .clickable { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Visualizer cần quyền micro — bấm để bật",
                                            color = Color(0xFFFFB199),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { currentIndex = (currentIndex - 1 + songs.size) % songs.size }) { Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                                    IconButton(onClick = { val p = resolveActivePlayer(); if (p != null) { if (p.isPlaying) p.pause() else p.play() } else { isPlaying = !isPlaying } }) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                                    IconButton(onClick = { currentIndex = (currentIndex + 1) % songs.size }) { Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { isShuffle = !isShuffle }, modifier = Modifier.size(44.dp)) { Icon(Icons.Filled.Shuffle, contentDescription = null, tint = if (isShuffle) displaySong.color1 else Color.White.copy(alpha = 0.6f)) }
                                    IconButton(onClick = { isRepeat = !isRepeat }, modifier = Modifier.size(44.dp)) { Icon(Icons.Filled.Repeat, contentDescription = null, tint = if (isRepeat) displaySong.color1 else Color.White.copy(alpha = 0.6f)) }
                                    IconButton(onClick = { showLyrics = !showLyrics }, modifier = Modifier.size(44.dp)) { Icon(Icons.Filled.Mic, contentDescription = "Lời bài hát", tint = if (showLyrics) displaySong.color1 else Color.White.copy(alpha = 0.6f)) }
                                    IconButton(onClick = { showTagEditor = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Filled.Create, contentDescription = null, tint = Color.White.copy(alpha = 0.6f)) }
                                    IconButton(
                                        onClick = {
                                            val a = pipActivity
                                            if (a != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                runCatching { a.enterPictureInPictureMode(MainActivity.buildPipParams(a)) }
                                            } else {
                                                Toast.makeText(context, "Cần Android 8.0 trở lên", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) { Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White.copy(alpha = 0.6f)) }
                                    // Local mute only — never silences the TV/web outputs, just the phone speaker
                                    IconButton(
                                        onClick = {
                                            localMuted = !localMuted
                                            controller?.volume = if (castActive || localMuted) 0f else volume
                                            Toast.makeText(
                                                context,
                                                if (localMuted) "Đã tắt tiếng máy" else "Đã bật lại tiếng máy",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(if (localMuted) displaySong.color1.copy(alpha = 0.22f) else Color.Transparent)
                                            .border(if (localMuted) BorderStroke(1.dp, displaySong.color1) else BorderStroke(0.dp, Color.Transparent), CircleShape)
                                    ) {
                                        Icon(
                                            if (localMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Tắt tiếng máy",
                                            tint = if (localMuted) displaySong.color1 else Color.White.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(
                visible = activeTab != 0,
                enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }, animationSpec = tween(340, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(320)),
                exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }, animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(180))
            ) {
                MiniPlayer(
                    song = displaySong,
                    isPlaying = isPlaying,
                    currentPositionSeconds = positionSeconds.coerceIn(0, totalSeconds),
                    totalSeconds = totalSeconds,
                    playbackSpeed = playbackSpeed,
                    onPlayPauseToggle = {
                        activeTogglePlayback()
                    },
                    onNext = {
                        // Player queue only ever holds one item, so jump the library index directly
                        val idx = songs.indexOfFirst { it.id == displaySong.id }
                        if (idx >= 0) LibraryRepository.recordSkip(context, songs[idx].id)
                        if (songs.isNotEmpty()) {
                            currentIndex = (currentIndex + 1) % songs.size
                            isPlaying = true
                        }
                    },
                    onPrevious = {
                        val idx = songs.indexOfFirst { it.id == displaySong.id }
                        if (idx >= 0) LibraryRepository.recordSkip(context, songs[idx].id)
                        if (songs.isNotEmpty()) {
                            currentIndex = (currentIndex - 1 + songs.size) % songs.size
                            isPlaying = true
                        }
                    },
                    onSeek = { positionSeconds = it; resolveActivePlayer()?.seekTo(it * 1000L) },
                    onSpeedChange = { playbackSpeed = it },
                    onExpand = { activeTab = 0; showFullPlayer = true },
                    localMuted = localMuted,
                    onLocalMuteToggle = {
                        localMuted = !localMuted
                        controller?.volume = if (castActive || localMuted) 0f else volume
                        Toast.makeText(
                            context,
                            if (localMuted) "Đã tắt tiếng máy" else "Đã bật lại tiếng máy",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

        }
    }

    // ------------------------------------------------------- YouTube offline manager
    if (showDownloadsDialog) {
        val downloads = remember(downloadsTick) { LibraryRepository.youtubeDownloads(context) }
        val totalBytes = downloads.sumOf { it.length() }
        Dialog(onDismissRequest = { showDownloadsDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF101422),
                border = BorderStroke(1.dp, Color(0xFF00F2FE).copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(18.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = Color(0xFF00F2FE))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Tải YouTube offline",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "${downloads.size} bài · ${formatFileSize(totalBytes)}",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // Bulk import: paste a YouTube playlist link to download every track
                    OutlinedTextField(
                        value = playlistUrl,
                        onValueChange = { playlistUrl = it },
                        placeholder = { Text("Dán link playlist YouTube…", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00F2FE),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                            cursorColor = Color(0xFF00F2FE),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = { startBulkPlaylistDownload() },
                                enabled = !bulkRunning && playlistUrl.isNotBlank()
                            ) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = "Tải tất cả",
                                    tint = if (!bulkRunning && playlistUrl.isNotBlank()) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.3f)
                                )
                            }
                        }
                    )
                    if (bulkRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = Color(0xFF00F2FE),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (bulkTotal > 0) "$bulkDone/$bulkTotal bài · ${bulkCurrent.take(40)}" else "Đang lấy danh sách…",
                                color = Color(0xFF00F2FE),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { cancelBulkPlaylistDownload() }) {
                                Text("Hủy", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    if (downloads.isEmpty()) {
                        Text(
                            "Chưa tải bài nào. Bấm nút tải ở kết quả YouTube (chế độ YouTube trong ô tìm kiếm) để tải về nghe offline.",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 13.sp
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                            items(downloads.size) { index ->
                                val file = downloads[index]
                                val songId = file.name.hashCode().toLong() and 0xFFFFFFFFL
                                val currentIndexOf = songs.indexOfFirst { it.id == songId }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (currentIndexOf >= 0) {
                                                currentIndex = currentIndexOf
                                                isPlaying = true
                                                youtubePlayingSong = null
                                                showDownloadsDialog = false
                                            }
                                        },
                                        enabled = currentIndexOf >= 0
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Phát",
                                            tint = if (currentIndexOf >= 0) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.2f)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.nameWithoutExtension,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            formatFileSize(file.length()),
                                            color = Color.White.copy(alpha = 0.45f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (LibraryRepository.deleteYoutubeDownload(file)) {
                                                songs = songs.filterNot { it.id == songId }
                                                if (youtubePlayingSong?.id == songId) youtubePlayingSong = null
                                                Toast.makeText(
                                                    context,
                                                    "Đã xóa: ${file.nameWithoutExtension.take(40)}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                downloadsTick++
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Xóa",
                                            tint = Color(0xFFFF6B6B)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showDownloadsDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Đóng", color = Color(0xFF00F2FE))
                    }
                }
            }
        }
    }
    // ------------------------------------------------------- Download quality picker
    if (downloadQualityPicker != null) {
        val target = downloadQualityPicker!!
        Dialog(onDismissRequest = { downloadQualityPicker = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF101422),
                border = BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(18.dp).fillMaxWidth()) {
                    Text(
                        "Chất lượng tải về",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        target.title,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { startYoutubeDownload(target, 64_000); downloadQualityPicker = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                            Text("Tiết kiệm", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("≤ 64 kbps — file nhỏ, tải nhanh", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                    }
                    TextButton(
                        onClick = { startYoutubeDownload(target, 128_000); downloadQualityPicker = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                            Text("Cân bằng", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("≤ 128 kbps — đủ nghe tốt", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                    }
                    TextButton(
                        onClick = { startYoutubeDownload(target, null); downloadQualityPicker = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                            Text("Cao nhất", color = Color(0xFFFF0000), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Bitrate cao nhất có sẵn — file lớn", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                    }
                    TextButton(
                        onClick = { downloadQualityPicker = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Hủy", color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    // Tag editor dialog
    if (showTagEditor) {
        val displaySong = songs.getOrNull(currentIndex) ?: songs.first()
        TagEditorDialog(
            song = displaySong,
            onDismiss = { showTagEditor = false },
            onSave = { title, artist, album, genre ->
                showTagEditor = false
                scope.launch {
                    val result = LibraryRepository.writeTags(context, displaySong, title, artist, album, genre)
                    when (result) {
                        is LibraryRepository.TagWriteResult.Written -> {
                            Toast.makeText(context, "Đã lưu thẻ nhạc", Toast.LENGTH_SHORT).show()
                            songs = songs.map { if (it.id == displaySong.id) it.copy(title = title, artist = artist, album = album, genre = genre) else it }
                        }
                        is LibraryRepository.TagWriteResult.NeedsPermission -> {
                            Toast.makeText(context, "Đã lưu trong app", Toast.LENGTH_SHORT).show()
                            songs = songs.map { if (it.id == displaySong.id) it.copy(title = title, artist = artist, album = album, genre = genre) else it }
                        }
                        is LibraryRepository.TagWriteResult.Failed -> {
                            Toast.makeText(context, "Lỗi: ${result.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }
}
