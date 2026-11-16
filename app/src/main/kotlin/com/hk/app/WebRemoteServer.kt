package com.hk.app

import android.content.Context
import android.os.StatFs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kotlin.concurrent.thread
import kotlin.math.min
import kotlinx.coroutines.runBlocking

/** Everything the UI wants to know about the current playback state. */
data class RemoteState(
    val songs: List<Song>,
    val videos: List<VideoItem> = emptyList(),
    val currentIndex: Int,
    val currentSong: Song? = null,
    val youtubeUrl: String? = null,
    val isPlaying: Boolean,
    val positionSeconds: Int,
    val volume: Float,
    val isShuffle: Boolean,
    val isRepeat: Boolean,
    val localIp: String,
    val port: Int,
    /** Library indices in playback (queue) order; empty means "the whole library". */
    val queue: List<Int> = emptyList(),
    /** mediaId of the item that is actually playing (the queue order may differ). */
    val currentMediaId: String = "",
    /** Cast discovery state + device list, exposed for the web remote's TV panel. */
    val castAvailable: Boolean = false,
    val castConnectedName: String? = null,
    val castConnectedId: String? = null,
    val castDevices: List<CastDevice> = emptyList(),
    val isCasting: Boolean = false,
    /** Party mode: guests may add songs; each guest can only remove their own. */
    val partyMode: Boolean = false,
    /** Per library index: nickname of the guest who added it (null = host/local). */
    val owners: List<String?> = emptyList(),
    /** Video playback state for web remote control. */
    val videoActive: Boolean = false,
    val videoTitle: String = "",
    val videoPlaying: Boolean = false,
    val videoPosition: Int = 0,
    val videoDuration: Int = 0,
    val videoIndex: Int = -1
)

data class CastDevice(val id: String, val name: String)

/** Commands issued by the web remote. Always executed on the main thread. */
sealed interface RemoteCommand {
    data object Toggle : RemoteCommand
    data object Play : RemoteCommand
    data object Pause : RemoteCommand
    data object Next : RemoteCommand
    data object Previous : RemoteCommand
    data class PlayIndex(val index: Int) : RemoteCommand
    data class Seek(val seconds: Int) : RemoteCommand
    data class Volume(val value: Float) : RemoteCommand
data class Mute(val enabled: Boolean) : RemoteCommand
    data class Shuffle(val enabled: Boolean) : RemoteCommand
    data class Repeat(val enabled: Boolean) : RemoteCommand
    /** Jump inside the current queue (0-based position, not library index). */
    data class PlayQueuePos(val position: Int) : RemoteCommand
    /** Drop one entry from the queue; the file itself is untouched. */
    data class RemoveQueue(val position: Int, val who: String? = null) : RemoteCommand

    /** Reorder the playback queue: move the row at [from] so it lands at [to]. */
    data class MoveQueue(val from: Int, val to: Int) : RemoteCommand
    /** Build a fresh queue (a temporary playlist) from library indices. */
    data class PlaySelection(val indices: List<Int>) : RemoteCommand
    /** Insert library songs right after the currently playing item. */
    data class QueueNext(val indices: List<Int>) : RemoteCommand
    /** Append library songs to the end of the current queue. */
    data class QueueAdd(val indices: List<Int>) : RemoteCommand
    /** Re-scan for Chromecast / Google TV devices. */
    data object CastRescan : RemoteCommand
    /** Select and connect to a discovered cast device (route id). */
    data class CastConnect(val id: String) : RemoteCommand
    /** Disconnect from the cast device. */
    data object CastDisconnect : RemoteCommand
    /** Play a library index on the cast device (uses the phone's HTTP audio server as source). */
    data class CastPlayIndex(val index: Int) : RemoteCommand
    /** Video remote: open local video by index on the phone. */
    data class VideoPlayIndex(val index: Int) : RemoteCommand
    data object VideoToggle : RemoteCommand
    /** Stop the video being played on the phone (close the player). */
    data object VideoStop : RemoteCommand
    data class VideoSeek(val seconds: Int) : RemoteCommand
    data object VideoSeekBack10 : RemoteCommand
    data object VideoSeekFwd10 : RemoteCommand
    /** Jump to the next/previous local video. */
    data object VideoNext : RemoteCommand
    data object VideoPrev : RemoteCommand
    /** Open an arbitrary http(s) video URL (e.g. a YouTube stream) on the phone. */
    data class VideoPlayUrl(val url: String, val title: String, val audioUrl: String? = null) : RemoteCommand
    /** Cast a video to the connected TV/DLNA target. */
    data class CastPlayVideo(val index: Int) : RemoteCommand
    /** Cast an arbitrary http(s) video stream URL to the connected TV/DLNA target. */
    data class CastPlayUrl(val url: String, val title: String) : RemoteCommand
}

/**
 * Small HTTP server that powers the PC/browser remote, QR sharing and the
 * Chromecast audio source.
 *
 * The server really starts and stops (the listening socket is closed), serves
 * the current playlist, streams the audio with HTTP range support and accepts
 * chunked file uploads from the browser.
 */
class WebRemoteServer(
    private val context: Context,
    private val port: Int,
    private val stateProvider: () -> RemoteState,
    private val onCommand: (RemoteCommand) -> Unit,
    private val onUpload: (File, String?) -> Unit,
    private val onClientCountChanged: (Int) -> Unit
) {
    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ExecutorService? = null

    private val clients = ConcurrentHashMap<String, Long>()
    private val startedAt = System.currentTimeMillis()
    private val iconCache = ConcurrentHashMap<Int, ByteArray>()
    @Volatile
    private var lastClientCount = 0

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.getOrElse {
            return false
        }

        running = true
        serverSocket = socket
        executor = Executors.newCachedThreadPool()

        acceptThread = thread(name = "web-remote-accept", isDaemon = true) {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                val pool = executor
                if (pool == null || pool.isShutdown) {
                    runCatching { client.close() }
                    break
                }
                runCatching { pool.execute { runCatching { handleClient(client) } } }
            }
        }

        thread(name = "web-remote-presence", isDaemon = true) {
            while (running) {
                runCatching { Thread.sleep(4_000L) }
                pruneClients(System.currentTimeMillis())
            }
        }

        return true
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        runCatching { executor?.shutdownNow() }
        executor = null
        clients.clear()
        lastClientCount = 0
        onClientCountChanged(0)
    }

    // ------------------------------------------------------------- HTTP layer

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val headerBlock = readHeaderBlock(input) ?: return
            val headerText = String(headerBlock, StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.trim().orEmpty()
            val tokens = requestLine.split(' ')
            if (tokens.size < 2) return

            val method = tokens[0].uppercase(Locale.US)
            val target = tokens[1]
            val path = target.substringBefore('?').ifEmpty { "/" }.lowercase(Locale.US)
            val query = parseQuery(target)
            val headers = hashMapOf<String, String>()
            for (line in lines.drop(1)) {
                val index = line.indexOf(':')
                if (index <= 0) continue
                headers[line.substring(0, index).trim().lowercase(Locale.US)] =
                    line.substring(index + 1).trim()
            }

            touchClient(client.inetAddress?.hostAddress ?: "unknown")

            try {
            when {
                method == "POST" && path == "/upload" ->
                    handleUpload(input, output, query, headers)

                method == "GET" && path == "/api/status" ->
                    respondJson(output, statusJson())

                method == "GET" && path == "/api/app-history" ->
                    respondJson(output, appHistoryJson(query))

                method == "GET" && path == "/api/lyrics" ->
                    respondJson(output, lyricsJson(query))

                method == "GET" && path == "/manifest.webmanifest" ->
                    respondBytes(
                        output,
                        "application/manifest+json; charset=utf-8",
                        manifestJson().toByteArray(StandardCharsets.UTF_8)
                    )

                method == "GET" && path == "/sw.js" ->
                    respondBytes(
                        output,
                        "application/javascript; charset=utf-8",
                        SERVICE_WORKER.toByteArray(StandardCharsets.UTF_8)
                    )

                method == "GET" && path == "/qr" -> {
                    val text = query["text"]?.takeIf { it.isNotBlank() }
                        ?: "http://${stateProvider().localIp}:$port/"
                    val size = (query["size"]?.toIntOrNull() ?: 384).coerceIn(128, 1024)
                    val png = generateQrCode(text, size)?.asAndroidBitmap()?.let { bitmap ->
                        ByteArrayOutputStream().use { bos ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            bos.toByteArray()
                        }
                    }
                    if (png == null) respondText(output, 500, "qr error")
                    else respondBytes(output, "image/png", png, cacheSeconds = 86_400)
                }

                method == "GET" && ICON_PATHS.containsKey(path) ->
                    respondBytes(
                        output,
                        "image/png",
                        iconPng(ICON_PATHS.getValue(path)),
                        cacheSeconds = 86_400
                    )

                method == "GET" && path == "/audio" ->
                    serveAudio(output, query, headers)

                method == "GET" && path == "/youtube" ->
                    serveYouTube(output, headers)

                method == "GET" && path == "/thumb" ->
                    serveThumb(output, query)

                method == "GET" && path == "/video" ->
                    serveVideo(output, query, headers)

                method == "GET" && path == "/youtube/search" -> {
                    val q = query["q"].orEmpty()
                    val page = (query["page"]?.toIntOrNull() ?: 1).coerceIn(1, 50)
                    val sortBy = query["sort_by"]
                    val date = query["date"]
                    val duration = query["duration"]
                    val features = query["features"]
                    val arr = JSONArray()
                    if (q.length >= 2) {
                        val results = runBlocking {
                            LibraryRepository.searchYouTube(q, page, sortBy, date, duration, features)
                        }
                        results.forEach { r ->
                            arr.put(
                                JSONObject()
                                    .put("videoId", r.videoId)
                                    .put("title", r.title)
                                    .put("author", r.author)
                                    .put("duration", r.duration)
                                    .put("thumbUrl", r.thumbnailUrl)
                            )
                        }
                    }
                    respondJson(output, JSONObject().put("results", arr).put("page", page).toString())
                }

                method == "GET" && path == "/youtube/stream" -> {
                    val id = query["videoId"].orEmpty()
                    val quality = query["quality"]
                    var obj = JSONObject().put("url", JSONObject.NULL)
                    if (id.isNotBlank()) {
                        val info = runBlocking { LibraryRepository.getYouTubeStreamInfo(id, quality) }
                        if (info != null) {
                            obj = JSONObject()
                                .put("url", info.videoUrl)
                                .put("audioUrl", info.audioUrl ?: JSONObject.NULL)
                                .put("title", info.title)
                                .put("duration", info.durationSec)
                                .put("thumbUrl", info.thumbUrl)
                                .put("muxed", info.audioUrl == null)
                        }
                    }
                    respondJson(output, obj.toString())
                }

                method == "GET" && path == "/youtube/watch" -> {
                    serveYouTubeWatch(output, query, headers)
                }

                method == "GET" && path == "/videoplay" -> {
                    val index = query["index"]?.toIntOrNull()
                    if (index != null) {
                        onCommand(RemoteCommand.VideoPlayIndex(index))
                        respondJson(output, "{\"ok\":true}")
                    } else {
                        val url = query["url"].orEmpty()
                        val title = query["title"]?.let {
                            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                        } ?: "YouTube"
                        val audio = query["audio"]?.let {
                            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                        }?.takeIf { it.isNotBlank() }
                        if (url.isNotBlank()) onCommand(RemoteCommand.VideoPlayUrl(url, title, audio))
                        respondJson(output, "{\"ok\":true}")
                    }
                }

                method == "GET" && path == "/videotoggle" -> {
                    onCommand(RemoteCommand.VideoToggle)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/videostop" -> {
                    onCommand(RemoteCommand.VideoStop)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/videoseek" -> {
                    val pos = query["pos"]?.toIntOrNull()
                    if (pos != null) onCommand(RemoteCommand.VideoSeek(pos))
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/videoseekback" -> {
                    onCommand(RemoteCommand.VideoSeekBack10)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/videoseekfwd" -> {
                    onCommand(RemoteCommand.VideoSeekFwd10)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/videonext" -> {
                    onCommand(RemoteCommand.VideoNext)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/videoprev" -> {
                    onCommand(RemoteCommand.VideoPrev)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/artwork" ->
                    serveArtwork(output, query)

                method == "GET" && path == "/tv" ->
                    respondHtml(output, tvPage())

                method == "GET" && path == "/cast/scan" -> {
                    onCommand(RemoteCommand.CastRescan)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/cast/connect" -> {
                    query["id"]?.let { onCommand(RemoteCommand.CastConnect(it)) }
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/cast/disconnect" -> {
                    onCommand(RemoteCommand.CastDisconnect)
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/cast/play" -> {
                    query["index"]?.toIntOrNull()?.let { onCommand(RemoteCommand.CastPlayIndex(it)) }
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/cast/play-video" -> {
                    query["index"]?.toIntOrNull()?.let { onCommand(RemoteCommand.CastPlayVideo(it)) }
                    respondJson(output, "{\"ok\":true}")
                }

                method == "GET" && path == "/cast/play-url" -> {
                    val url = query["url"]?.let {
                        runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                    }.orEmpty()
                    val title = query["title"]?.let {
                        runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                    } ?: "Video"
                    if (url.isNotBlank()) onCommand(RemoteCommand.CastPlayUrl(url, title))
                    respondJson(output, "{\"ok\":true}")
                }


                method == "GET" && (path == "/" || path == "/index.html") ->
                    respondHtml(output, remotePage())

                method == "GET" ->
                    handleCommand(path, query, output)

                else ->
                    respondText(output, 405, "method not allowed")
            }
            } catch (t: Throwable) {
                android.util.Log.e("WebRemoteServer", "handleClient failed: ${t}", t)
                runCatching { respondText(output, 500, t.toString()) }
            }
            runCatching { output.flush() }
        }
    }

    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(1024)
        val terminator = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (buffer.size() < 32_768) {
            val value = input.read()
            if (value == -1) return null
            buffer.write(value)
            matched = when (value.toByte()) {
                terminator[matched] -> matched + 1
                terminator[0] -> 1
                else -> 0
            }
            if (matched == terminator.size) return buffer.toByteArray()
        }
        return null
    }

    private fun handleCommand(path: String, query: Map<String, String>, output: OutputStream) {
        when (path) {
            "/toggle", "/playpause" -> onCommand(RemoteCommand.Toggle)
            "/play" -> {
                val index = query["index"]?.toIntOrNull()
                onCommand(if (index != null) RemoteCommand.PlayIndex(index) else RemoteCommand.Play)
            }
            "/pause" -> onCommand(RemoteCommand.Pause)
            "/next" -> onCommand(RemoteCommand.Next)
            "/prev" -> onCommand(RemoteCommand.Previous)
            "/seek" -> query["pos"]?.toIntOrNull()?.let { onCommand(RemoteCommand.Seek(it)) }
            "/volume" -> query["val"]?.toFloatOrNull()?.let {
                onCommand(RemoteCommand.Volume((it / 100f).coerceIn(0f, 1f)))
            }
            "/mute" -> query["on"]?.let { onCommand(RemoteCommand.Mute(it == "1")) }
            "/shuffle" -> {
                val state = stateProvider()
                onCommand(RemoteCommand.Shuffle(!state.isShuffle))
            }
            "/repeat" -> {
                val state = stateProvider()
                onCommand(RemoteCommand.Repeat(!state.isRepeat))
            }
            "/playqueue" -> query["pos"]?.toIntOrNull()
                ?.let { onCommand(RemoteCommand.PlayQueuePos(it)) }
            "/remove" -> query["pos"]?.toIntOrNull()
                ?.let { pos ->
                    val who = query["who"]?.let {
                        runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                    }?.trim()?.takeIf { it.isNotEmpty() }
                    onCommand(RemoteCommand.RemoveQueue(pos, who))
                }
            "/selection" -> {
                val indices = query["indices"].orEmpty()
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .distinct()
                if (indices.isEmpty()) {
                    respondText(output, 400, "empty selection")
                    return
                }
                onCommand(RemoteCommand.PlaySelection(indices))
            }

            "/movequeue" -> {
                val from = query["from"]?.toIntOrNull()
                val to = query["to"]?.toIntOrNull()
                if (from == null || to == null) {
                    respondText(output, 400, "missing from/to")
                    return
                }
                onCommand(RemoteCommand.MoveQueue(from, to))
            }

            "/queuenext" -> {
                val indices = query["indices"].orEmpty()
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .distinct()
                if (indices.isEmpty()) {
                    respondText(output, 400, "empty selection")
                    return
                }
                onCommand(RemoteCommand.QueueNext(indices))
                respondText(output, 200, "ok")
            }

            "/queueadd" -> {
                val indices = query["indices"].orEmpty()
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .distinct()
                if (indices.isEmpty()) {
                    respondText(output, 400, "empty selection")
                    return
                }
                onCommand(RemoteCommand.QueueAdd(indices))
                respondText(output, 200, "ok")
            }
            else -> {
                respondText(output, 404, "unknown endpoint")
                return
            }
        }
        respondJson(output, "{\"ok\":true}")
    }

    private fun serveAudio(
        output: OutputStream,
        query: Map<String, String>,
        headers: Map<String, String>
    ) {
        val index = query["index"]?.toIntOrNull()
        if (index == null) {
            respondText(output, 400, "missing index")
            return
        }
        val song = stateProvider().songs.getOrNull(index)
        val uri = song?.audioUri
        if (uri == null) {
            respondText(output, 404, "track not found")
            return
        }

        val descriptor = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        }.getOrNull()
        if (descriptor == null) {
            respondText(output, 404, "cannot read track")
            return
        }

        descriptor.use { afd ->
            val total = afd.length
            var start = 0L
            var end = if (total > 0) total - 1 else -1L

            val range = headers["range"]
            if (total > 0 && range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=")
                val from = spec.substringBefore('-').trim().toLongOrNull()
                val to = spec.substringAfter('-', "").trim().toLongOrNull()
                if (from != null) start = from.coerceIn(0L, total - 1)
                if (to != null) end = to.coerceIn(start, total - 1)
            }

            val length = if (total > 0) end - start + 1 else -1L
            val head = buildString {
                append(if (total > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: audio/mpeg\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (total > 0) {
                    append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Content-Length: $length\r\n")
                }
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.ISO_8859_1))

            afd.createInputStream().use { stream ->
                if (start > 0) {
                    var skipped = 0L
                    while (skipped < start) {
                        val step = stream.skip(start - skipped)
                        if (step <= 0) break
                        skipped += step
                    }
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = if (total > 0) length else Long.MAX_VALUE
                while (remaining > 0) {
                    val toRead = min(buffer.size.toLong(), remaining).toInt()
                    val read = stream.read(buffer, 0, toRead)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    // Album art cache: reuses decoded art so the TV page doesn't re-compress per poll.
    private val artCache = ConcurrentHashMap<Long, ByteArray>()

    private fun serveYouTube(output: OutputStream, headers: Map<String, String>) {
        val url = stateProvider().youtubeUrl
        if (url.isNullOrBlank()) {
            respondText(output, 404, "no youtube track")
            return
        }
        proxyRemoteStream(output, headers, url)
    }

    private fun serveYouTubeWatch(
        output: OutputStream,
        query: Map<String, String>,
        headers: Map<String, String>
    ) {
        val id = query["videoId"].orEmpty()
        if (id.isBlank()) {
            respondText(output, 400, "missing videoId")
            return
        }
        val quality = query["quality"]
        val stream = query["stream"] ?: "video"
        val info = runBlocking { LibraryRepository.getYouTubeStreamInfo(id, quality) }
        if (info == null) {
            respondText(output, 502, "cannot resolve stream")
            return
        }
        val target = if (stream == "audio") info.audioUrl else info.videoUrl
        if (target.isNullOrBlank()) {
            respondText(output, 404, "stream not available")
            return
        }
        proxyRemoteStream(output, headers, target)
    }

    private fun proxyRemoteStream(
        output: OutputStream,
        headers: Map<String, String>,
        remoteUrl: String
    ) {
        val conn = runCatching { URL(remoteUrl).openConnection() as HttpURLConnection }.getOrNull()
        if (conn == null) {
            respondText(output, 502, "cannot open stream")
            return
        }
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        headers["range"]?.let { conn.setRequestProperty("Range", it) }
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 206) {
                respondText(output, 502, "upstream $code")
                return
            }
            val mime = conn.contentType ?: "video/mp4"
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
            val contentRange = conn.getHeaderField("Content-Range")
            val acceptRanges = conn.getHeaderField("Accept-Ranges") ?: "bytes"
            val statusLine = if (code == 206 || contentRange != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val head = buildString {
                append(statusLine)
                append("Content-Type: $mime\r\n")
                append("Accept-Ranges: $acceptRanges\r\n")
                if (contentRange != null) append("Content-Range: $contentRange\r\n")
                if (contentLength != null && contentLength >= 0) append("Content-Length: $contentLength\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
            conn.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun serveArtwork(output: OutputStream, query: Map<String, String>) {
        val index = query["index"]?.toIntOrNull()
        val song = index?.let { stateProvider().songs.getOrNull(it) }
        if (song == null) {
            respondText(output, 404, "track not found")
            return
        }
        val bytes = artCache.getOrPut(song.id) {
            val embedded = song.albumArtBitmap?.asAndroidBitmap()
            if (embedded != null) {
                val out = ByteArrayOutputStream()
                embedded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            } else {
                // Solid music-note placeholder tinted with the track color
                val c1 = song.color1
                val c2 = song.color2
                val argb = Color.argb(
                    (c1.alpha * 255).toInt(), (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()
                )
                val argb2 = Color.argb(
                    (c2.alpha * 255).toInt(), (c2.red * 255).toInt(), (c2.green * 255).toInt(), (c2.blue * 255).toInt()
                )
                val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = argb
                canvas.drawRect(0f, 0f, 512f, 512f, paint)
                paint.color = argb2
                canvas.drawCircle(256f, 256f, 165f, paint)
                canvas.drawCircle(256f, 256f, 120f, paint)
                paint.color = Color.WHITE
                paint.textSize = 150f
                paint.isFakeBoldText = true
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("♪", 256f, 312f, paint)
                val out = ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                bmp.recycle()
                out.toByteArray()
            }
        }
        respondBytes(output, "image/jpeg", bytes, cacheSeconds = 86_400)
    }

    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        query: Map<String, String>,
        headers: Map<String, String>
    ) {
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length <= 0L) {
            respondJson(output, "{\"ok\":false,\"error\":\"empty body\"}")
            return
        }

        val requested = query["name"]?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
        } ?: "upload"
        val safeName = requested
            .replace(Regex("[^\\p{L}\\p{N}._\\- ]"), "_")
            .take(80)
            .ifBlank { "upload" }
        val fileName = if (safeName.contains('.')) safeName else "$safeName.mp3"
        val uploader = query["who"]?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }?.trim()?.take(24)?.takeIf { it.isNotEmpty() }
        val target = File(
            LibraryRepository.uploadDirectory(context),
            "${System.currentTimeMillis()}_$fileName"
        )

        val written = runCatching {
            var total = 0L
            target.outputStream().use { fileOutput ->
                val buffer = ByteArray(64 * 1024)
                while (total < length) {
                    val toRead = min(buffer.size.toLong(), length - total).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fileOutput.write(buffer, 0, read)
                    total += read
                }
            }
            total
        }.getOrElse {
            runCatching { target.delete() }
            0L
        }

        if (written <= 0L) {
            respondJson(output, "{\"ok\":false,\"error\":\"upload failed\"}")
            return
        }

        onUpload(target, uploader)
        respondJson(output, "{\"ok\":true,\"bytes\":$written}")
    }


    private fun serveThumb(output: OutputStream, query: Map<String, String>) {
        val index = query["index"]?.toIntOrNull() ?: -1
        val video = stateProvider().videos.getOrNull(index)
        if (video == null) {
            respondText(output, 404, "video not found")
            return
        }
        val bytes = runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, video.uri)
                val bmp = retriever.frameAtTime ?: retriever.getFrameAtTime(0)
                if (bmp != null) {
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                    bmp.recycle()
                    out.toByteArray()
                } else null
            } finally {
                retriever.release()
            }
        }.getOrNull()
        if (bytes == null) {
            respondText(output, 404, "no thumb")
            return
        }
        respondBytes(output, "image/jpeg", bytes, cacheSeconds = 3600)
    }

    private fun serveVideo(output: OutputStream, query: Map<String, String>, headers: Map<String, String>) {
        val index = query["index"]?.toIntOrNull() ?: -1
        val video = stateProvider().videos.getOrNull(index)
        if (video == null) {
            respondText(output, 404, "video not found")
            return
        }
        val uri = video.uri
        val descriptor = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor == null) {
            respondText(output, 404, "cannot read video")
            return
        }
        descriptor.use { afd ->
            val total = afd.length
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            val range = headers["range"]
            if (total > 0 && range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=")
                val from = spec.substringBefore('-').trim().toLongOrNull()
                val to = spec.substringAfter('-', "").trim().toLongOrNull()
                if (from != null) start = from.coerceIn(0L, total - 1)
                if (to != null) end = to.coerceIn(start, total - 1)
            }
            val lengthBytes = if (total > 0) end - start + 1 else -1L
            val mime = video.mimeType.ifBlank { "video/mp4" }
            val head = buildString {
                append(if (total > 0 && range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (total > 0) {
                    append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Content-Length: $lengthBytes\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1))
            afd.createInputStream().use { stream ->
                if (start > 0) {
                    var skipped = 0L
                    while (skipped < start) {
                        val sk = stream.skip(start - skipped)
                        if (sk <= 0) break
                        skipped += sk
                    }
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = lengthBytes
                while (remaining != 0L) {
                    val toRead = if (remaining > 0) minOf(buffer.size.toLong(), remaining).toInt() else buffer.size
                    val read = stream.read(buffer, 0, toRead)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    if (remaining > 0) remaining -= read
                }
            }
        }
    }

    // ------------------------------------------------------------- responses

    private fun statusJson(): String {
        val state = stateProvider()
        // The queue may be a selection in a different order than the library,
        // so resolve the playing song by media id instead of by position.
        val current = state.currentSong
            ?: (state.songs.firstOrNull { it.id.toString() == state.currentMediaId }
                ?: state.songs.getOrNull(state.currentIndex))
        val queue = state.queue.ifEmpty { state.songs.indices.toList() }

        val playlist = JSONArray()
        queue.forEachIndexed { pos, libIndex ->
            val song = state.songs.getOrNull(libIndex) ?: return@forEachIndexed
            playlist.put(
                JSONObject()
                    .put("pos", pos)
                    .put("index", libIndex)
                    .put("title", song.title)
                    .put("artist", song.artist)
                    .put("duration", song.durationSeconds)
                    .put("by", state.owners.getOrNull(libIndex) ?: JSONObject.NULL)
            )
        }

        val videoPrefs = context.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)
        val videos = JSONArray()
        state.videos.forEachIndexed { idx, v ->
            val quality = when {
                min(v.width, v.height) >= 2160 -> "4K"
                min(v.width, v.height) >= 1080 -> "1080p"
                min(v.width, v.height) >= 720 -> "720p"
                min(v.width, v.height) >= 480 -> "480p"
                else -> "SD"
            }
            videos.put(
                JSONObject()
                    .put("id", v.id)
                    .put("index", idx)
                    .put("title", v.title)
                    .put("duration", (v.durationMs / 1000L).toInt().coerceAtLeast(0))
                    .put("folder", v.folder)
                    .put("width", v.width)
                    .put("height", v.height)
                    .put("quality", quality)
                    .put("resume", (videoPrefs.getLong("video_resume_${v.id}", 0L) / 1000L).toInt())
            )
        }

        val json = JSONObject()
            .put("title", current?.title ?: "No track")
            .put("artist", current?.artist ?: "")
            .put("album", current?.album ?: "")
            .put("duration", current?.durationSeconds ?: 0)
            .put("position", state.positionSeconds)
            .put("playing", state.isPlaying)
            .put("volume", (state.volume * 100).toInt())
            .put("shuffle", state.isShuffle)
            .put("repeat", state.isRepeat)
            .put("index", state.currentIndex)
            .put("songIndex", state.songs.indexOfFirst { it.id == current?.id })
            .put("youtubeUrl", state.youtubeUrl ?: JSONObject.NULL)
            .put("clients", clients.size)
            .put("castAvailable", state.castAvailable)
            .put("castConnected", state.castConnectedName ?: JSONObject.NULL)
            .put("castConnectedId", state.castConnectedId ?: JSONObject.NULL)
            .put("isCasting", state.isCasting)
            .put("party", state.partyMode)
            .put("castDevices", JSONArray().apply {
                state.castDevices.forEach { dev ->
                    put(JSONObject().put("id", dev.id).put("name", dev.name))
                }
            })
            .put("playlist", playlist)
            .put("videos", videos)
            .put("videoCount", state.videos.size)
            .put("videoActive", state.videoActive)
            .put("videoTitle", state.videoTitle)
            .put("videoPlaying", state.videoPlaying)
            .put("videoPosition", state.videoPosition)
            .put("videoDuration", state.videoDuration)
            .put("videoIndex", state.videoIndex)
            .put("uptime", ((System.currentTimeMillis() - startedAt) / 1000L).toInt())
            .put("storageFree", runCatching {
                StatFs(context.filesDir.absolutePath).availableBytes
            }.getOrDefault(0L))
            .put("storageTotal", runCatching {
                StatFs(context.filesDir.absolutePath).totalBytes
            }.getOrDefault(0L))
            .put("url", "http://${state.localIp}:$port/")

        return json.toString()
    }

    /** Lyrics for one library index, with LRC timestamps when available. */
    private fun appHistoryJson(query: Map<String, String>): String {
        val state = stateProvider()
        val limit = (query["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
        val prefs = context.getSharedPreferences("listening_history", android.content.Context.MODE_PRIVATE)
        val all = prefs.all
        val byId = state.songs.associateBy { it.id }
        data class Row(val song: Song, val lastPlayed: Long, val playCount: Int)
        val rows = mutableListOf<Row>()
        for ((k, v) in all) {
            if (!k.startsWith("last_played_")) continue
            val id = k.removePrefix("last_played_").toLongOrNull() ?: continue
            val song = byId[id] ?: continue
            val last = (v as? Long) ?: (v as? Number)?.toLong() ?: 0L
            if (last <= 0L) continue
            val count = prefs.getInt("play_count_$id", 0)
            rows += Row(song, last, count)
        }
        rows.sortByDescending { it.lastPlayed }
        val arr = org.json.JSONArray()
        rows.take(limit).forEach { r ->
            val idx = state.songs.indexOfFirst { it.id == r.song.id }
            val o = org.json.JSONObject()
                .put("id", r.song.id)
                .put("index", idx)
                .put("title", r.song.title)
                .put("artist", r.song.artist)
                .put("album", r.song.album)
                .put("duration", r.song.durationSeconds)
                .put("lastPlayed", r.lastPlayed)
                .put("playCount", r.playCount)
            arr.put(o)
        }
        return org.json.JSONObject().put("items", arr).toString()
    }

    private fun lyricsJson(query: Map<String, String>): String {
        val index = query["index"]?.toIntOrNull() ?: -1
        val song = stateProvider().songs.getOrNull(index)
        val raw = song?.lyrics ?: emptyList()
        val parsed = parseLrc(raw)
        val synced = parsed.any { it.timeMs > 0L }
        val lines = JSONArray()
        if (synced) {
            parsed.forEach { line ->
                lines.put(JSONObject().put("t", line.timeMs).put("text", line.text))
            }
        } else {
            raw.forEach { line ->
                lines.put(JSONObject().put("t", 0L).put("text", line))
            }
        }
        return JSONObject()
            .put("title", song?.title ?: "")
            .put("synced", synced)
            .put("lines", lines)
            .toString()
    }

    private fun respondJson(output: OutputStream, body: String) =
        writeResponse(output, 200, "application/json; charset=utf-8", body)

    private fun respondHtml(output: OutputStream, body: String) =
        writeResponse(output, 200, "text/html; charset=utf-8", body)

    private fun respondText(output: OutputStream, code: Int, body: String) =
        writeResponse(output, code, "text/plain; charset=utf-8", body)

    private fun respondBytes(
        output: OutputStream,
        contentType: String,
        bytes: ByteArray,
        cacheSeconds: Int = 0
    ) {
        val cache = if (cacheSeconds > 0) "public, max-age=$cacheSeconds" else "no-store"
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: $cache\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(bytes)
    }

    // ------------------------------------------------------------- PWA assets

    private fun manifestJson(): String = JSONObject()
        .put("name", "HK Music Player")
        .put("short_name", "HK Player")
        .put("description", "Điều khiển nhạc, playlist và upload từ mọi thiết bị trong mạng LAN.")
        .put("id", "/")
        .put("start_url", "/")
        .put("scope", "/")
        .put("display", "standalone")
        .put("orientation", "portrait")
        .put("background_color", "#05070e")
        .put("theme_color", "#05070e")
        .put("lang", "vi")
        .put("dir", "ltr")
        .put(
            "icons", JSONArray()
                .put(iconJson("/icon-192.png", 192, "any"))
                .put(iconJson("/icon-512.png", 512, "any"))
                .put(iconJson("/icon-maskable-512.png", 512, "maskable"))
        )
        .toString()

    private fun iconJson(src: String, size: Int, purpose: String): JSONObject = JSONObject()
        .put("src", src)
        .put("sizes", "${size}x$size")
        .put("type", "image/png")
        .put("purpose", purpose)

    /** Rendered once per size and then served from memory. */
    private fun iconPng(size: Int): ByteArray = iconCache.getOrPut(size) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(
                Color.rgb(0, 242, 254),
                Color.rgb(79, 172, 254),
                Color.rgb(123, 47, 247)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        val corner = size * 0.22f
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), corner, corner, paint)

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.022f
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.33f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        // Simple play glyph: no font dependency, always renders the same.
        val glyph = android.graphics.Path().apply {
            moveTo(size * 0.41f, size * 0.335f)
            lineTo(size * 0.685f, size * 0.5f)
            lineTo(size * 0.41f, size * 0.665f)
            close()
        }
        canvas.drawPath(glyph, paint)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }

    private fun writeResponse(output: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ")
            append(if (code == 200) "OK" else "Error")
            append("\r\nContent-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(bytes)
    }

    // ------------------------------------------------------------- presence

    private fun touchClient(ip: String) {
        clients[ip] = System.currentTimeMillis()
        pruneClients(System.currentTimeMillis())
    }

    private fun pruneClients(now: Long) {
        val expired = clients.entries.filter { now - it.value > 15_000L }.map { it.key }
        expired.forEach { clients.remove(it) }
        val count = clients.size
        if (count != lastClientCount) {
            lastClientCount = count
            onClientCountChanged(count)
        }
    }

    // ------------------------------------------------------------- frontend

    private fun parseQuery(target: String): Map<String, String> {
        val raw = target.substringAfter('?', "")
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = decodeQueryPart(part.substringBefore('='))
            val value = if (part.contains('=')) decodeQueryPart(part.substringAfter('=')) else ""
            key to value
        }.toMap()
    }

    private fun decodeQueryPart(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun remotePage(): String {
        val state = stateProvider()
        val raw = runCatching {
            context.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { "<!DOCTYPE html><html><body><p>Web UI chưa được đóng gói.</p></body></html>" }
        val songs = state.songs
        val current = songs.getOrNull(state.currentIndex)
        return raw
            .replace("{{currentIndex}}", state.currentIndex.toString())
            .replace("{{title}}", htmlEscape(current?.title ?: "No track"))
            .replace("{{artist}}", htmlEscape(current?.artist ?: ""))
            .replace("{{localIp}}", htmlEscape(state.localIp))
            .replace("{{port}}", state.port.toString())
    }


    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    companion object {
        private val ICON_PATHS = mapOf(
            "/icon-192.png" to 192,
            "/icon-512.png" to 512,
            "/icon-maskable-512.png" to 512,
            "/apple-touch-icon.png" to 180,
            "/favicon.ico" to 64
        )
    }

    private fun tvPage(): String = """<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>HK — TV Cast Bridge</title>
<style>
:root{ --bg:#05070f; --text:#f4f7ff; --dim:rgba(244,247,255,.5); --accent:#00f2fe; --accent2:#ff0844; }
*{ margin:0; box-sizing:border-box; }
html,body{ width:100%; height:100%; overflow:hidden; background:var(--bg); color:var(--text);
  font-family:'Segoe UI',system-ui,-apple-system,sans-serif; }
#artbg{ position:fixed; inset:-70px; background-color:var(--bg); background-size:cover; background-position:center;
  filter:blur(64px) brightness(.35) saturate(1.35); transition:background-image .8s; z-index:0; }
#topbar{ position:fixed; top:0; left:0; right:0; z-index:5; display:flex; justify-content:space-between;
  align-items:center; padding:18px 30px; font-size:12px; letter-spacing:3px; color:var(--dim);
  text-transform:uppercase; transition:opacity .4s; }
#clock{ font-size:20px; color:var(--text); letter-spacing:2px; }
#stage{ position:fixed; inset:0; z-index:2; display:flex; flex-direction:column; }
#foreground{ position:relative; flex:1; display:flex; align-items:center; gap:48px; padding:5vh 5vw 2vh; min-height:0; }
#art{ width:min(46vh,46vw); aspect-ratio:1/1; object-fit:cover; border-radius:24px;
  box-shadow:0 30px 80px rgba(0,0,0,.65), 0 0 0 1px rgba(255,255,255,.08); }
#right{ flex:1; min-width:0; display:flex; flex-direction:column; gap:14px; overflow:hidden; }
#albumName{ font-size:12px; letter-spacing:4px; color:var(--dim); text-transform:uppercase; max-width:100%;
  white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
#title{ font-size:clamp(34px,5.2vh,68px); line-height:1.08; font-weight:800; letter-spacing:-1px;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
#artist{ font-size:clamp(16px,2.4vh,28px); color:var(--accent); font-weight:600;
  white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
#lyrics{ position:relative; height:26vh; overflow:hidden; }
#lyricList{ position:absolute; left:0; right:0; top:0; transition:transform .35s ease; }
.lcell{ padding:6px 0; font-size:clamp(14px,1.9vh,22px); color:var(--dim); white-space:nowrap;
  overflow:hidden; text-overflow:ellipsis; transition:all .3s; }
.lcell.now{ color:var(--text); font-weight:700; font-size:clamp(16px,2.3vh,26px); }
#progress{ position:relative; height:3px; background:rgba(255,255,255,.12); margin:0 3vw; }
#progressFill{ height:100%; background:var(--accent); width:0%; transition:width .3s linear; }
#viz{ position:relative; width:94vw; height:110px; margin:0 auto; }
#bottomBar{ position:relative; display:flex; align-items:center; gap:14px; padding:14px 4vw 22px; transition:opacity .4s; }
.ctrlBtn{ background:rgba(255,255,255,.06); color:var(--text); border:1px solid rgba(255,255,255,.14);
  border-radius:999px; min-width:64px; height:64px; padding:0 22px; font-size:20px; cursor:pointer;
  display:inline-flex; align-items:center; justify-content:center; gap:8px; letter-spacing:1px; }
.ctrlBtn:focus,.ctrlBtn:hover{ outline:2px solid var(--accent); background:rgba(0,242,254,.12); }
.ctrlBtn.big{ min-width:84px; height:84px; font-size:30px; background:var(--accent); color:#000; border-color:var(--accent); font-weight:800; }
.ctrlBtn.sm{ min-width:56px; height:56px; font-size:15px; color:var(--dim); }
.ctrlBtn.on{ border-color:var(--accent); color:var(--accent); }
.ctrlBtn.eqOn{ background:var(--accent); border-color:var(--accent); color:#000; }
#volPct{ min-width:52px; text-align:center; color:var(--dim); font-size:15px; }
.hidden{ opacity:0 !important; pointer-events:none !important; }
#start{ position:fixed; inset:0; z-index:20; background:var(--bg); display:flex; flex-direction:column;
  align-items:center; justify-content:center; gap:26px; cursor:pointer; }
#startIcon{ width:150px; height:150px; border-radius:50%; border:2px solid rgba(255,255,255,.18);
  display:flex; align-items:center; justify-content:center; font-size:64px; color:var(--accent); }
#start h1{ font-size:40px; letter-spacing:5px; }
#start p{ color:var(--dim); font-size:16px; }
#trackTime{ font-size:13px; color:var(--dim); letter-spacing:1.5px; }
#qrBox{ position:fixed; right:26px; bottom:22px; z-index:7; display:flex; flex-direction:column;
  align-items:center; gap:8px; transition:opacity .4s; }
#qrBox img{ width:118px; height:118px; border-radius:14px; background:#fff; padding:6px;
  box-shadow:0 18px 50px rgba(0,0,0,.6); }
#qrBox span{ font-size:10px; letter-spacing:2px; color:var(--dim); text-transform:uppercase; }
#qrBox.hidden{ opacity:0 !important; pointer-events:none !important; }
</style>
</head>
<body>
<div id="artbg"></div>

<div id="topbar"><span>HK · Cast Bridge</span><span id="clock">--:--</span></div>

<div id="stage">
  <div id="foreground">
    <img id="art" alt="album art">
    <div id="right">
      <div id="albumName">ALBUM</div>
      <h1 id="title">Đang tải…</h1>
      <div id="artist">—</div>
      <div id="trackTime">0:00 / 0:00</div>
      <div id="lyrics"><div id="lyricList"></div></div>
    </div>
  </div>
  <div id="progress"><div id="progressFill"></div></div>
  <canvas id="viz"></canvas>
  <div id="bottomBar">
    <button class="ctrlBtn" id="prevBtn">⏮</button>
    <button class="ctrlBtn big" id="playBtn">▶</button>
    <button class="ctrlBtn" id="nextBtn">⏭</button>
    <span style="width:8px"></span>
    <button class="ctrlBtn sm" id="shuffleBtn">Shuffle</button>
    <button class="ctrlBtn sm" id="repeatBtn">Repeat</button>
    <span style="flex:1"></span>
    <button class="ctrlBtn sm" id="bassBtn">Bass</button>
    <button class="ctrlBtn sm eqOn" id="normBtn">Normal</button>
    <button class="ctrlBtn sm" id="trebleBtn">Treble</button>
    <span style="width:8px"></span>
    <button class="ctrlBtn sm" id="muteTvBtn">🔊</button>
    <button class="ctrlBtn sm" id="volDownBtn">−</button>
    <span id="volPct">80%</span>
    <button class="ctrlBtn sm" id="volUpBtn">+</button>
  </div>
</div>

<div id="qrBox"><img id="qrImg" alt="QR điều khiển"><span>Điều khiển bằng điện thoại</span></div>

<div id="start">
  <div id="startIcon">▶</div>
  <h1>TV CAST BRIDGE</h1>
  <p>Chạm OK / Enter / Click để bắt đầu — nhạc sẽ phát qua TV</p>
</div>

<audio id="aud" preload="auto"></audio>

<script>
(function () {
  var aud = document.getElementById('aud');
  var cur = -1;
  var started = false;
  var lyricLines = [];
  var idleTimer = null;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function showChrome() {
    document.getElementById('topbar').classList.remove('hidden');
    document.getElementById('bottomBar').classList.remove('hidden');
    document.getElementById('qrBox').classList.remove('hidden');
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(function () {
      document.getElementById('topbar').classList.add('hidden');
      document.getElementById('bottomBar').classList.add('hidden');
      document.getElementById('qrBox').classList.add('hidden');
    }, 4000);
  }
  document.addEventListener('pointermove', showChrome);
  document.addEventListener('keydown', showChrome);

  // ---------------- audio engine with Biquad EQ
  var ctx = null, analyser = null, eqLow, eqMid, eqHigh;
  function buildAudio() {
    if (ctx) return;
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    ctx = new AC();
    var src = ctx.createMediaElementSource(aud);
    eqLow = ctx.createBiquadFilter(); eqLow.type = 'lowshelf'; eqLow.frequency.value = 180; eqLow.gain.value = 0;
    eqMid = ctx.createBiquadFilter(); eqMid.type = 'peaking'; eqMid.frequency.value = 1000; eqMid.Q.value = 0.9; eqMid.gain.value = 0;
    eqHigh = ctx.createBiquadFilter(); eqHigh.type = 'highshelf'; eqHigh.frequency.value = 3800; eqHigh.gain.value = 0;
    analyser = ctx.createAnalyser(); analyser.fftSize = 256; analyser.smoothingTimeConstant = 0.84;
    src.connect(eqLow); eqLow.connect(eqMid); eqMid.connect(eqHigh); eqHigh.connect(analyser);
    analyser.connect(ctx.destination);
  }
  function setEq(l, m, h, btn) {
    if (!ctx) return;
    var t = ctx.currentTime;
    eqLow.gain.setTargetAtTime(l, t, 0.08);
    eqMid.gain.setTargetAtTime(m, t, 0.08);
    eqHigh.gain.setTargetAtTime(h, t, 0.08);
    ['bassBtn', 'normBtn', 'trebleBtn'].forEach(function (id) {
      document.getElementById(id).classList.toggle('eqOn', document.getElementById(id) === btn);
    });
  }

  function fmt(s) {
    s = Math.max(0, Math.floor(s || 0));
    var m = Math.floor(s / 60), x = s % 60;
    return m + ':' + (x < 10 ? '0' + x : x);
  }

  // Pull a soft accent colour out of the cover so the whole stage matches it.
  function updateAmbient(src) {
    var img = new Image();
    img.onload = function () {
      try {
        var cv = document.createElement('canvas');
        cv.width = 24; cv.height = 24;
        var g = cv.getContext('2d');
        g.drawImage(img, 0, 0, 24, 24);
        var d = g.getImageData(0, 0, 24, 24).data;
        var r = 0, gr = 0, b = 0, n = 0;
        for (var i = 0; i < d.length; i += 4) {
          var mx = Math.max(d[i], d[i + 1], d[i + 2]);
          if (mx < 24) continue;
          r += d[i]; gr += d[i + 1]; b += d[i + 2]; n++;
        }
        if (!n) return;
        r = Math.round(r / n); gr = Math.round(gr / n); b = Math.round(b / n);
        var accent = 'rgb(' + Math.min(255, Math.round(r * 1.25 + 20)) + ',' +
          Math.min(255, Math.round(gr * 1.25 + 20)) + ',' +
          Math.min(255, Math.round(b * 1.25 + 20)) + ')';
        document.documentElement.style.setProperty('--accent', accent);
        document.documentElement.style.setProperty('--accent2', accent);
      } catch (e) {}
    };
    img.src = src;
  }

  // ---------------- track loading
  function loadTrack(index, positionSeconds) {
    cur = index;
    positionSeconds = positionSeconds || 0;
    document.getElementById('art').src = '/artwork?index=' + index + '&v=' + Date.now();
    document.getElementById('artbg').style.backgroundImage = 'url(/artwork?index=' + index + ')';
    updateAmbient('/artwork?index=' + index);
    aud.src = '/audio?index=' + index;
    aud.load();
    aud.currentTime = positionSeconds;
    aud.play().catch(function () {});

    fetch('/api/lyrics?index=' + index, { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        lyricLines = (d.lines || []).map(function (l) { return { t: l.t || 0, text: l.text || '' }; });
        var html = '';
        if (lyricLines.length) {
          lyricLines.forEach(function (l) {
            html += '<div class="lcell" data-t="' + l.t + '">' + esc(l.text) + '</div>';
          });
        } else {
          html = '<div class="lcell">♪</div>';
        }
        document.getElementById('lyricList').innerHTML = html;
      })
      .catch(function () {
        document.getElementById('lyricList').innerHTML = '';
      });
  }

  function highlightLyrics(ms) {
    var cells = document.querySelectorAll('.lcell');
    if (!cells.length) return;
    var nowIdx = -1;
    for (var i = 0; i < cells.length; i++) {
      var t = parseInt(cells[i].getAttribute('data-t'), 10) || 0;
      if (t <= ms) nowIdx = i;
    }
    for (var j = 0; j < cells.length; j++) cells[j].classList.toggle('now', j === nowIdx);
    if (nowIdx >= 0) {
      var c = cells[nowIdx];
      var offset = c.parentElement.offsetTop;
      var target = 130 - c.offsetTop;
      document.getElementById('lyricList').style.transform = 'translateY(' + target + 'px)';
    }
  }

  // ---------------- visualizer
  function drawViz() {
    requestAnimationFrame(drawViz);
    var cv = document.getElementById('viz');
    var g = cv.getContext('2d');
    var W = cv.offsetWidth, H = cv.offsetHeight;
    if (cv.width !== W * 2) { cv.width = W * 2; cv.height = H * 2; g.scale(2, 2); }
    g.clearRect(0, 0, W, H);
    if (!analyser) return;
    var data = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(data);
    var bars = 56, cell = W / bars;
    for (var i = 0; i < bars; i++) {
      var bin = Math.floor(i * data.length / bars * 0.72);
      var v = data[bin] / 255;
      var bh = H * (0.08 + Math.pow(v, 0.85) * 0.92);
      g.fillStyle = 'rgba(0,242,254,' + (0.32 + v * 0.68).toFixed(3) + ')';
      g.fillRect(i * cell + cell * 0.18, H - bh, cell * 0.64, bh);
    }
  }
  requestAnimationFrame(drawViz);

  // ---------------- status polling (TV is a dumb screen; phone drives)
  var volPct = 80;
  var tvMuted = false;
  aud.volume = 0.8;
  aud.addEventListener('play', function () {
    document.getElementById('playBtn').textContent = '⏸';
  });
  aud.addEventListener('pause', function () {
    document.getElementById('playBtn').textContent = '▶';
  });
  aud.addEventListener('ended', function () {
    document.getElementById('playBtn').textContent = '▶';
  });

  function poll() {
    fetch('/api/status', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        document.getElementById('title').textContent = d.title || '';
        document.getElementById('artist').textContent = d.artist || '';
        document.getElementById('albumName').textContent = d.album || '';
        document.getElementById('trackTime').textContent =
          fmt(d.position || 0) + ' / ' + fmt(d.duration || 0);
        // Keep the TV-side volume display in sync with the phone's reported
        // value only when the buttons weren't touched locally — simple, idempotent.
        if (typeof d.volume === 'number') {
          volPct = d.volume;
        }
        document.getElementById('volPct').textContent = volPct + '%';
        aud.volume = volPct / 100;
        aud.muted = tvMuted;
        document.getElementById('shuffleBtn').classList.toggle('on', !!d.shuffle);
        document.getElementById('repeatBtn').classList.toggle('on', !!d.repeat);
        if (typeof d.songIndex === 'number' && d.songIndex >= 0) {
          if (d.songIndex !== cur) {
            // Track changed on phone: reload TV's audio and jump to its position
            loadTrack(d.songIndex, d.position || 0);
          } else {
            // Same track: mirror play/pause and correct drift against the phone
            if (d.playing && aud.paused) aud.play().catch(function(){});
            else if (!d.playing && !aud.paused) aud.pause();
            if (d.playing && Math.abs(aud.currentTime - d.position) > 1.5) aud.currentTime = d.position;
          }
        }
        setTimeout(poll, 1500);
      })
      .catch(function () { setTimeout(poll, 2000); });
  }

  setInterval(function () {
    highlightLyrics((aud.currentTime || 0) * 1000);
    var a = aud;
    if (a && a.duration && isFinite(a.duration)) {
      document.getElementById('progressFill').style.width = ((a.currentTime / a.duration) * 100).toFixed(2) + '%';
    }
  }, 120);

  setInterval(function () {
    var d = new Date();
    document.getElementById('clock').textContent =
      (d.getHours() < 10 ? '0' + d.getHours() : d.getHours()) + ':' +
      (d.getMinutes() < 10 ? '0' + d.getMinutes() : d.getMinutes());
  }, 1000);

  // The TV page mirrors the phone: phone stays master; TV follows it.
  document.getElementById('playBtn').addEventListener('click', function () {
    fetch('/toggle', { cache: 'no-store' });
  });
  document.getElementById('prevBtn').addEventListener('click', function () {
    fetch('/prev', { cache: 'no-store' });
  });
  document.getElementById('nextBtn').addEventListener('click', function () {
    fetch('/next', { cache: 'no-store' });
  });
  document.getElementById('shuffleBtn').addEventListener('click', function () {
    fetch('/shuffle', { cache: 'no-store' });
  });
  document.getElementById('repeatBtn').addEventListener('click', function () {
    fetch('/repeat', { cache: 'no-store' });
  });
  document.getElementById('bassBtn').addEventListener('click', function () { setEq(10, 2, -4, this); });
  document.getElementById('normBtn').addEventListener('click', function () { setEq(0, 0, 0, this); });
  document.getElementById('trebleBtn').addEventListener('click', function () { setEq(-4, 3, 10, this); });
  document.getElementById('muteTvBtn').addEventListener('click', function () {
    tvMuted = !tvMuted;
    aud.muted = tvMuted;
    this.textContent = tvMuted ? '🔇' : '🔊';
  });
  document.getElementById('volDownBtn').addEventListener('click', function () {
    volPct = Math.max(0, volPct - 10);
    tvMuted = false;
    aud.muted = false;
    document.getElementById('muteTvBtn').textContent = '🔊';
    aud.volume = volPct / 100;
    document.getElementById('volPct').textContent = volPct + '%';
  });
  document.getElementById('volUpBtn').addEventListener('click', function () {
    volPct = Math.min(100, volPct + 10);
    tvMuted = false;
    aud.muted = false;
    document.getElementById('muteTvBtn').textContent = '🔊';
    aud.volume = volPct / 100;
    document.getElementById('volPct').textContent = volPct + '%';
  });

  document.getElementById('start').addEventListener('click', function () {
    buildAudio();
    if (ctx && ctx.state === 'suspended') ctx.resume();
    document.getElementById('start').style.display = 'none';
    started = true;
    aud.play().catch(function () {});
    showChrome();
  }, { once: true });

  document.getElementById('qrImg').src =
    '/qr?text=' + encodeURIComponent(location.origin + '/') + '&size=320';
  showChrome();
  poll();
})();
</script>
</body>
</html>
""".trimIndent()

        /**
         * Offline shell. The playlist/status/audio endpoints are never cached:
         * they only make sense while the phone is reachable.
         */
        private val SERVICE_WORKER = """
const CACHE = 'HK-shell-v4';
const SHELL = ['/', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', function (event) {
  event.waitUntil(
    caches.open(CACHE)
      .then(function (cache) { return cache.addAll(SHELL); })
      .then(function () { return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function (event) {
  event.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.map(function (key) {
        return key === CACHE ? null : caches.delete(key);
      }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (event) {
  var request = event.request;
  if (request.method !== 'GET') return;
  var url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname === '/api/status' || url.pathname === '/audio') return;

  if (url.pathname === '/') {
    event.respondWith(
      fetch(request).then(function (response) {
        var copy = response.clone();
        caches.open(CACHE).then(function (cache) { cache.put('/', copy); });
        return response;
      }).catch(function () {
        return caches.match('/').then(function (hit) {
          return hit || new Response(
            '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">' +
            '<body style="background:#05070e;color:#fff;font-family:system-ui;padding:32px;text-align:center">' +
            '<h2>HK Remote đang ngoại tuyến</h2><p>Kết nối lại Wi-Fi và mở lại trang để điều khiển tiếp.</p>',
            { headers: { 'Content-Type': 'text/html; charset=utf-8' } }
          );
        });
      })
    );
    return;
  }

  event.respondWith(
    caches.match(request).then(function (hit) {
      return hit || fetch(request).then(function (response) {
        if (response && response.status === 200) {
          var copy = response.clone();
          caches.open(CACHE).then(function (cache) { cache.put(request, copy); });
        }
        return response;
      });
    })
  );
});
""".trimIndent()
}
