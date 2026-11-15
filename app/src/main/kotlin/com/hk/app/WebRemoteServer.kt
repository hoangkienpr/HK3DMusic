package com.hk.app

import android.content.Context
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
    /** Build a fresh queue (a temporary playlist) from library indices. */
    data class PlaySelection(val indices: List<Int>) : RemoteCommand
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
                    val arr = JSONArray()
                    if (q.length >= 2) {
                        val results = runBlocking { LibraryRepository.searchYouTube(q, 1) }
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
                    respondJson(output, JSONObject().put("results", arr).toString())
                }

                method == "GET" && path == "/youtube/stream" -> {
                    val id = query["videoId"].orEmpty()
                    var obj = JSONObject().put("url", JSONObject.NULL)
                    if (id.isNotBlank()) {
                        val info = runBlocking { LibraryRepository.getYouTubeStreamInfo(id) }
                        if (info != null) {
                            obj = JSONObject()
                                .put("url", info.videoUrl)
                                .put("audioUrl", info.audioUrl ?: JSONObject.NULL)
                                .put("title", info.title)
                                .put("duration", info.durationSec)
                                .put("thumbUrl", info.thumbUrl)
                        }
                    }
                    respondJson(output, obj.toString())
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
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
        if (conn == null) {
            respondText(output, 502, "cannot open stream")
            return
        }
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode !in 200..299) {
                respondText(output, 502, "upstream ${conn.responseCode}")
                return
            }
            val mime = conn.contentType ?: "audio/webm"
            val total = conn.contentLengthLong
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (total > 0) append("Content-Length: $total\r\n")
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

        val videos = JSONArray()
        state.videos.forEachIndexed { idx, v ->
            videos.put(
                JSONObject()
                    .put("index", idx)
                    .put("title", v.title)
                    .put("duration", (v.durationMs / 1000L).toInt().coerceAtLeast(0))
                    .put("folder", v.folder)
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

        return json.toString()
    }

    /** Lyrics for one library index, with LRC timestamps when available. */
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
        return """
<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="theme-color" content="#05070e">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="HK">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="icon" type="image/png" href="/icon-192.png">
<title>HK Player</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%}
body{background:radial-gradient(1200px 700px at 15% -10%,#0b1b35 0%,#05070e 45%,#04050b 100%);color:#fff;font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif;
     height:100dvh;overflow:hidden;padding:calc(6px + env(safe-area-inset-top)) 6px calc(70px + env(safe-area-inset-bottom))}
button{font-family:inherit;cursor:pointer}
*{scrollbar-width:none;-ms-overflow-style:none}
*::-webkit-scrollbar{display:none;width:0;height:0}
.card{width:100%;max-width:100%;height:100%;margin:0 auto;background:linear-gradient(180deg,rgba(18,24,40,.9),rgba(10,13,24,.94));border:1px solid rgba(255,255,255,.13);
      border-radius:18px;padding:12px;box-shadow:0 30px 80px rgba(0,0,0,.72),inset 0 1px 0 rgba(255,255,255,.06);display:flex;flex-direction:column;overflow:hidden;min-height:0;backdrop-filter:blur(10px)}
.row{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}
.brand{font-weight:800;letter-spacing:2px;font-size:13px;color:#00f2fe}
.badge{font-size:10px;font-weight:800;letter-spacing:1px;padding:4px 10px;border-radius:10px;background:rgba(0,242,254,.15);color:#00f2fe;border:1px solid rgba(0,242,254,.4)}
.badge.offline{background:rgba(255,80,80,.15);color:#ff6b6b;border-color:rgba(255,80,80,.45)}
.connBanner{display:none;align-items:center;justify-content:space-between;gap:8px;padding:8px 10px;border-radius:10px;margin:6px 0;font-size:12px}
.connBanner.show{display:flex}
.connBanner.ok{background:rgba(0,200,120,.12);border:1px solid rgba(0,200,120,.35);color:#7dffbf}
.connBanner.warn{background:rgba(255,180,0,.12);border:1px solid rgba(255,180,0,.35);color:#ffd479}
.connBanner.err{background:rgba(255,80,80,.12);border:1px solid rgba(255,80,80,.35);color:#ff8f8f}
.connBanner button{height:30px;border-radius:8px;font-size:11px;padding:0 10px;width:auto;background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.2);color:#fff}
.install{display:none;width:100%;min-height:40px;border-radius:12px;font-size:12px;margin-bottom:8px;background:rgba(0,242,254,.14);border:1px solid rgba(0,242,254,.4);color:#00f2fe}
.hint{display:none;margin-top:8px;font-size:11px;line-height:1.55;color:rgba(255,255,255,.6);background:rgba(255,255,255,.05);border-radius:12px;padding:10px 12px}
.tabs{display:flex;gap:4px;background:rgba(11,16,32,.92);border:1px solid rgba(255,255,255,.08);border-radius:14px;padding:4px;margin:6px 0 8px;flex:none;z-index:60}
.tab{flex:1;min-height:40px;border-radius:10px;font-size:12px;font-weight:700;color:rgba(255,255,255,.55);background:transparent;border:none;width:auto;padding:0 8px;transition:.15s}
.tab.active{background:linear-gradient(135deg,#00f2fe,#4facfe);color:#000;box-shadow:0 4px 14px rgba(0,242,254,.3)}
.tab:hover:not(.active){background:rgba(255,255,255,.08);color:#fff}
.tabCount{font-size:10px;opacity:.8;margin-left:3px}
.panels{flex:1;min-height:0;overflow:hidden;display:flex;flex-direction:column}
.panel{display:none}.panel.active{display:block;flex:1;min-height:0;overflow:auto;animation:fadeIn .18s ease;padding-right:2px}
#panel-video.active{display:flex;flex-direction:column;overflow:hidden}
@keyframes fadeIn{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
.nowCard{display:flex;align-items:center;gap:12px;background:rgba(0,242,254,.07);border:1px solid rgba(0,242,254,.2);border-radius:16px;padding:12px;margin-bottom:10px}
.nowCard img{width:72px;height:72px;border-radius:12px;object-fit:cover;flex:none;background:#000;cursor:pointer}
.nowInfo{flex:1;min-width:0}
.nowTitle{font-size:17px;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;cursor:pointer}
.nowArtist{font-size:12px;color:rgba(255,255,255,.6);margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.srcBadge{display:inline-block;font-size:10px;font-weight:700;padding:2px 7px;border-radius:8px;margin-left:6px;vertical-align:middle;background:rgba(255,255,255,.12);color:#dff}
.srcBadge.yt{background:rgba(255,80,80,.18);color:#ff9b9b}
.srcBadge.up{background:rgba(255,212,121,.18);color:#ffd479}
.nowSeek{flex:1;min-width:120px}
.nowSeek input{width:100%;margin:0}
.small{font-size:11px;color:rgba(255,255,255,.5);display:flex;justify-content:space-between;margin-top:4px}
input[type=range]{-webkit-appearance:none;appearance:none;width:100%;height:8px;border-radius:4px;outline:none;background:rgba(255,255,255,.14);margin-top:10px;cursor:pointer;accent-color:#00f2fe}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:18px;height:18px;border-radius:50%;background:#00f2fe;box-shadow:0 0 12px #00f2fee6}
.controls{display:flex;justify-content:center;align-items:center;gap:16px;margin:14px 0}
.controls button{width:54px;height:54px;border-radius:50%;font-size:20px}
.controls button.play{width:70px;height:70px;font-size:26px;background:linear-gradient(135deg,#00f2fe,#4facfe);color:#000;border:none;box-shadow:0 8px 24px rgba(0,242,254,.35)}
button{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.16);color:#fff;transition:.15s}
button:hover{background:rgba(255,255,255,.18)}
button.mini{width:auto;min-height:38px;border-radius:12px;font-size:12px;padding:0 14px}
.mini.eqOn,.mini.on{border-color:#00f2fe;color:#00f2fe}
.vol{display:flex;align-items:center;gap:10px;margin-top:8px}.vol input{margin-top:0}
.statsCard{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap}
.stat{flex:1;min-width:90px;background:rgba(0,0,0,.25);border:1px solid rgba(255,255,255,.06);border-radius:12px;padding:8px 10px;display:flex;flex-direction:column;gap:3px}
.statLabel{font-size:9px;letter-spacing:1px;color:rgba(255,255,255,.4);text-transform:uppercase}
.statValue{font-size:12px;font-weight:700;color:#e8feff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.acc{margin-top:10px;border:1px solid rgba(255,255,255,.08);border-radius:14px;background:rgba(0,0,0,.18);overflow:hidden}
.acc>summary{list-style:none;cursor:pointer;padding:10px 12px;font-size:11px;font-weight:800;letter-spacing:1px;color:rgba(255,255,255,.75);display:flex;align-items:center;justify-content:space-between}
.acc>summary::-webkit-details-marker{display:none}
.acc>summary::after{content:'▾';opacity:.7}
.acc[open]>summary::after{content:'▴'}
.acc .accBody{padding:0 12px 12px}
#panel-dashboard.active{display:flex;flex-direction:column;gap:12px;overflow:hidden}
#panel-dashboard{background:radial-gradient(900px 320px at 0% -10%,rgba(0,242,254,.12),transparent 55%),radial-gradient(700px 260px at 100% 0%,rgba(255,120,80,.08),transparent 50%),radial-gradient(800px 400px at 50% 120%,rgba(79,172,254,.08),transparent 60%)}
.dashHero{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}
.dashHeroItem{padding:12px 14px;border-radius:16px;border:1px solid rgba(255,255,255,.1);background:linear-gradient(180deg,rgba(255,255,255,.06),rgba(255,255,255,.02));box-shadow:0 8px 22px rgba(0,0,0,.25),inset 0 1px 0 rgba(255,255,255,.06)}
.dashHeroItem .k{font-size:10px;letter-spacing:.8px;text-transform:uppercase;color:rgba(255,255,255,.5);margin-bottom:4px}
.dashHeroItem .v{font-size:14px;font-weight:800;color:#f4ffff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dashGrid{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(320px,.85fr);gap:12px;flex:1;min-height:0;overflow:hidden}
.dashCol{display:flex;flex-direction:column;gap:12px;min-height:0}
.dashColMain,.dashColSide{overflow:hidden}
.dashCard{margin-top:0;border:1px solid rgba(255,255,255,.12);border-radius:18px;background:linear-gradient(180deg,rgba(22,30,52,.82),rgba(10,14,26,.86));overflow:hidden;box-shadow:0 12px 34px rgba(0,0,0,.38),inset 0 1px 0 rgba(255,255,255,.05);backdrop-filter:blur(10px);display:flex;flex-direction:column;min-height:0}
.dashCardFill{flex:1;min-height:0}
.dashHead{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:14px 16px;border-bottom:1px solid rgba(255,255,255,.08);background:linear-gradient(90deg,rgba(0,242,254,.1),rgba(79,172,254,.04) 40%,transparent)}
.dashHead .t{font-size:14px;font-weight:800;letter-spacing:.3px;color:#f5ffff;display:flex;align-items:center;gap:8px}
.dashHead .t .ico{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:9px;background:rgba(0,242,254,.12);border:1px solid rgba(0,242,254,.2);font-size:14px}
.dashHead .sub{font-size:11px;color:rgba(255,255,255,.55);margin-top:3px}
.dashHead .actions{display:flex;gap:6px;flex-wrap:wrap}
.dashBody{padding:12px 14px;overflow:auto;min-height:0}
.mini.accent{background:linear-gradient(135deg,rgba(0,242,254,.22),rgba(79,172,254,.18));border-color:rgba(0,242,254,.45);color:#eaffff}
.mini.ghost{background:rgba(255,255,255,.04)}
.devStatus{font-size:11px;color:rgba(255,255,255,.6);padding:8px 10px;border-radius:10px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.06)}
.eqPresets{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.histList{flex:1;min-height:120px;max-height:none;overflow-y:auto;scrollbar-width:none;-ms-overflow-style:none;padding:8px}
.histList::-webkit-scrollbar{display:none}
.histItem{display:flex;align-items:center;gap:12px;padding:10px 12px;margin:4px 2px;border:1px solid rgba(255,255,255,.06);border-radius:14px;background:rgba(255,255,255,.03);cursor:pointer;min-height:60px;transition:.18s ease}
.histItem:hover{background:rgba(255,255,255,.08);transform:translateY(-1px);border-color:rgba(255,255,255,.12)}
.histItem.playing{background:linear-gradient(90deg,rgba(0,242,254,.18),rgba(0,242,254,.06));border-color:rgba(0,242,254,.45);box-shadow:0 0 0 1px rgba(0,242,254,.15) inset,0 8px 20px rgba(0,242,254,.08)}
.histItem img{width:46px;height:46px;border-radius:10px;object-fit:cover;flex:none;background:#000;box-shadow:0 4px 12px rgba(0,0,0,.35)}
.histItem .meta{flex:1;min-width:0}
.histItem .title{font-size:13.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#f5fbff}
.histItem.playing .title{color:#cfffff}
.histItem .artist{font-size:11px;color:rgba(255,255,255,.58);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:3px}
.histItem .right{display:flex;flex-direction:column;align-items:flex-end;gap:6px;flex:none}
.histItem .time{font-size:10px;color:rgba(255,255,255,.5);background:rgba(255,255,255,.06);padding:2px 7px;border-radius:999px}
.histItem .hbtn{width:30px;height:30px;border-radius:9px;font-size:12px;padding:0;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.12);color:rgba(255,255,255,.6)}
.histItem .hbtn:hover{background:rgba(255,80,80,.15);border-color:rgba(255,80,80,.35);color:#ff9b9b}
.eqSliders{display:flex;gap:10px;margin-top:10px;padding:10px;background:rgba(255,255,255,.03);border:1px solid rgba(255,255,255,.06);border-radius:14px}
.eqSliders label{flex:1;display:flex;flex-direction:column;align-items:center;gap:5px;font-size:10px;color:rgba(255,255,255,.6);font-weight:600}
.eqSliders input{width:100%;height:8px;margin:0}
.eqSliders .db{font-size:10px;color:#bff;min-height:14px;font-weight:700;background:rgba(0,242,254,.1);padding:1px 6px;border-radius:999px}
.eqViz{display:flex;gap:8px;align-items:flex-end;height:44px;margin:6px 0 4px;padding:8px 10px;background:linear-gradient(180deg,rgba(0,242,254,.08),rgba(0,0,0,.15));border:1px solid rgba(0,242,254,.15);border-radius:12px}
.eqViz .bar{flex:1;background:linear-gradient(180deg,#6ff7ff 0%,#00f2fe 45%,#4facfe 100%);border-radius:5px;min-height:6px;transition:height .18s ease;box-shadow:0 0 10px rgba(0,242,254,.25)}
.chipBtn{min-height:36px;border-radius:999px;font-size:11px;padding:0 14px;width:auto;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.14);color:rgba(255,255,255,.75);font-weight:700}
.chipBtn:hover{background:rgba(255,255,255,.12)}
.chipBtn.on{background:linear-gradient(135deg,rgba(0,242,254,.28),rgba(79,172,254,.18));border-color:rgba(0,242,254,.55);color:#dffcff;box-shadow:0 6px 16px rgba(0,242,254,.18)}
.castList{display:flex;flex-direction:column;gap:8px;margin-top:8px}
.castItem{display:flex;align-items:center;justify-content:space-between;gap:10px;background:rgba(255,255,255,.03);border:1px solid rgba(255,255,255,.08);border-radius:14px;padding:11px 13px;min-height:52px;transition:.15s}
.castItem:hover{background:rgba(255,255,255,.07);border-color:rgba(255,255,255,.14)}
.castItem .nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:700;color:#f0f8ff}
.castItem .meta{font-size:10px;color:rgba(255,255,255,.5);margin-top:3px}
.castItem.casting{border-color:rgba(0,242,254,.45);background:rgba(0,242,254,.08)}
.castConnectedCard{border:1px solid rgba(0,242,254,.45);background:linear-gradient(135deg,rgba(0,242,254,.16),rgba(79,172,254,.08));border-radius:14px;padding:14px;margin:8px 0;box-shadow:0 10px 24px rgba(0,242,254,.12),inset 0 1px 0 rgba(255,255,255,.08)}
.castConnectedCard .name{font-size:15px;font-weight:800;color:#ffffff}
.castConnectedCard .meta{font-size:11px;color:rgba(232,254,255,.75);margin-top:3px}
.label{font-size:10px;letter-spacing:1.6px;color:rgba(255,255,255,.45);font-weight:800;margin:14px 0 8px}
.list{max-height:none;min-height:140px;flex:1;overflow-y:auto;background:rgba(0,0,0,.25);border-radius:14px;padding:6px;border:1px solid rgba(255,255,255,.06)}
.listHead{position:sticky;top:0;z-index:2;display:flex;gap:8px;padding:8px 10px;font-size:10px;color:rgba(255,255,255,.4);background:rgba(11,16,32,.96);backdrop-filter:blur(6px);border-bottom:1px solid rgba(255,255,255,.06)}
.listHead span:nth-child(1){width:28px;flex:none}.listHead span:nth-child(2){flex:1}.listHead span:nth-child(3){width:90px;flex:none}.listHead span:nth-child(4){width:40px;flex:none;text-align:right}
.item{padding:10px;border-radius:10px;font-size:13px;color:rgba(255,255,255,.78);cursor:pointer;display:flex;align-items:center;gap:8px;min-height:44px}
.item:hover{background:rgba(255,255,255,.08);color:#fff}.item.active{background:rgba(0,242,254,.15);color:#00f2fe;font-weight:700}
.item .pos{width:28px;flex:none;color:rgba(255,255,255,.4);font-size:11px}.item.active .pos{color:#00f2fe}
.item .nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.item .al{width:90px;flex:none;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px;color:rgba(255,255,255,.45)}
.item .dt{width:40px;flex:none;text-align:right;font-size:11px;color:rgba(255,255,255,.5)}
.item .by{font-size:10px;color:#ffd479;margin-left:4px;font-weight:600}
.item .castBtn,.item .del{width:34px;height:30px;border-radius:8px;font-size:13px;padding:0;flex:none}
.item .del{background:transparent;border:none;color:rgba(255,255,255,.4)}.item .del:hover{color:#ff6b6b;background:transparent}
.item input.sel{width:18px;height:18px;flex:none;accent-color:#00f2fe}
.empty{padding:18px;text-align:center;color:rgba(255,255,255,.5);font-size:13px}
.skelRow{height:40px;border-radius:10px;margin:6px 0;background:linear-gradient(90deg,rgba(255,255,255,.05),rgba(255,255,255,.1),rgba(255,255,255,.05));background-size:200% 100%;animation:shimmer 1.2s infinite}
@keyframes shimmer{0%{background-position:200% 0}100%{background-position:-200% 0}}
.chips{display:flex;gap:6px;flex-wrap:wrap}
.chip{min-height:34px;border-radius:10px;font-size:11px;padding:0 12px;width:auto;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.12);color:rgba(255,255,255,.65)}
.chip.active{background:rgba(0,242,254,.16);border-color:rgba(0,242,254,.5);color:#00f2fe}
.musicCols{display:flex;flex-direction:column;gap:12px;min-height:0}
.lyr{max-height:none;min-height:140px;flex:1;overflow-y:auto;background:rgba(0,0,0,.25);border:1px solid rgba(255,255,255,.06);border-radius:14px;padding:10px 12px;font-size:13px;line-height:1.8;color:rgba(255,255,255,.55)}
.lyr .on{color:#00f2fe;font-weight:700;font-size:14px}.lyr.expanded{min-height:40vh}
.partyBox{background:rgba(0,0,0,.3);border:1px solid rgba(255,255,255,.08);border-radius:14px;padding:10px 12px;margin-bottom:6px}
.partyBox.on{border-color:rgba(255,212,121,.55);background:rgba(255,212,121,.07)}
.partyHead{display:flex;align-items:center;gap:8px;font-size:11px;letter-spacing:1px;font-weight:800;color:rgba(255,255,255,.6);margin-bottom:8px}
.partyHead .dot{width:8px;height:8px;border-radius:50%;background:rgba(255,255,255,.25)}.partyBox.on .partyHead{color:#ffd479}.partyBox.on .dot{background:#ffd479;box-shadow:0 0 10px #ffd479}
.partyRow{display:flex;gap:6px;align-items:center}
.partyRow input[type=text]{flex:1;min-width:0;padding:8px 10px;border-radius:10px;font-size:12px;margin:0;min-height:36px;border:1px solid rgba(255,255,255,.15);background:rgba(255,255,255,.06);color:#fff;outline:none}
.partyHint{font-size:10px;color:rgba(255,255,255,.5);margin-top:6px;line-height:1.5}
.upload{border:2px dashed rgba(255,255,255,.16);border-radius:14px;padding:14px;text-align:center}
.videoLayout{display:grid;grid-template-columns:1fr 2fr;gap:12px;flex:1;min-height:0;height:100%;overflow:hidden}
.musicLayout{display:grid;grid-template-columns:1fr 2fr;gap:12px;flex:1;min-height:0;height:100%;overflow:hidden}
.musicColList{display:flex;flex-direction:column;min-height:0;height:100%;overflow-y:auto;overflow-x:hidden;gap:8px;padding-right:4px;scrollbar-width:none;-ms-overflow-style:none}
.musicColList::-webkit-scrollbar{display:none;width:0;height:0}
.musicColPlayer{display:flex;flex-direction:column;min-height:0;height:100%;overflow:hidden;gap:8px}
#panel-music.active{display:flex;flex-direction:column;overflow:hidden}
.videoColList{display:flex;flex-direction:column;min-height:0;height:100%;overflow-y:auto;overflow-x:hidden;gap:8px;padding-right:4px;scrollbar-width:none;-ms-overflow-style:none}
.videoColList::-webkit-scrollbar{display:none;width:0;height:0}
.videoColPlayer{display:flex;flex-direction:column;min-height:0;height:100%;overflow:hidden;gap:8px}
.videoGrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:8px;max-height:none;flex:none;min-height:120px;overflow:visible;border:1px solid rgba(255,255,255,.08);border-radius:14px;padding:8px;background:rgba(0,0,0,.3)}
.videoItem{position:relative;border-radius:10px;overflow:hidden;border:1px solid rgba(255,255,255,.08);background:rgba(0,0,0,.4);cursor:pointer;transition:border-color .15s,transform .15s;display:flex;flex-direction:column}
.videoItem:hover{border-color:rgba(0,242,254,.55);transform:translateY(-2px)}
.videoItem img{width:100%;aspect-ratio:16/9;object-fit:cover;display:block;background:#000}
.videoItem .playOverlay{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:28px;color:#fff;text-shadow:0 2px 12px rgba(0,0,0,.8);opacity:0;transition:.15s;background:rgba(0,0,0,.25);pointer-events:none}
.videoItem:hover .playOverlay{opacity:1}
.videoItem .vt{padding:6px 7px 2px;font-size:11px;line-height:1.3;color:rgba(255,255,255,.85);max-height:40px;overflow:hidden}
.videoItem .vd{padding:0 7px 4px;font-size:10px;color:rgba(255,255,255,.45)}
.videoItem .vrow{display:flex;gap:4px;padding:0 6px 6px;margin-top:auto;flex-wrap:wrap}
.videoItem .vbtn{flex:1;min-height:28px;border-radius:7px;font-size:10px;padding:0 4px;background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.12)}
.ytItem{display:flex;gap:10px;padding:10px;border-radius:12px;background:rgba(0,0,0,.25);border:1px solid rgba(255,255,255,.06);margin-bottom:8px}
.ytItem img{width:160px;height:90px;object-fit:cover;border-radius:8px;flex:none;background:#000}
.ytItem .ytBody{flex:1;min-width:0}.ytItem .ytTitle{font-size:13px;font-weight:700;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.ytItem .ytMeta{font-size:11px;color:rgba(255,255,255,.5);margin-top:4px}
.ytItem .ytBtn{margin-top:8px;min-height:34px;border-radius:8px;font-size:11px;padding:0 14px;width:auto;background:rgba(255,0,0,.16);border:1px solid rgba(255,0,0,.4);color:#ff8080}
/* webVideoWrap layout handled below */
#webVideoHead{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 10px;background:rgba(0,242,254,.08);flex-wrap:wrap}
#webVideoHead .ttl{font-size:13px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex:1;min-width:120px}
#webVideoHead .vtools{display:flex;gap:6px;align-items:center;flex-wrap:wrap}
#webVideoHead button,#webVideoHead select{min-height:32px;border-radius:8px;font-size:11px;padding:0 10px;width:auto}
#webVideo{width:100%;max-height:100%;flex:1;min-height:0;aspect-ratio:16/9;border-radius:0;background:#000;border:none;display:block}
#webVideoWrap{display:none;flex-direction:column;flex:1;min-height:0;max-height:100%}
#webVideoWrap.active{display:flex}
.playerPlaceholder{position:relative;min-height:220px;padding:28px 18px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;text-align:center;color:rgba(255,255,255,.7);font-size:13px;border:1px dashed rgba(0,242,254,.22);border-radius:16px;background:radial-gradient(120% 120% at 50% 0%,rgba(0,242,254,.10),rgba(255,255,255,.02) 45%,rgba(0,0,0,.15) 100%);overflow:hidden}
.playerPlaceholder::before{content:"🎬";font-size:34px;line-height:1;filter:drop-shadow(0 6px 18px rgba(0,242,254,.25));animation:vpFloat 3.2s ease-in-out infinite}
.playerPlaceholder::after{content:"";position:absolute;inset:-40%;background:conic-gradient(from 0deg,transparent,rgba(0,242,254,.10),transparent 30%);animation:vpGlow 8s linear infinite;opacity:.55}
.playerPlaceholder .hint{position:relative;z-index:1}
@keyframes vpFloat{0%,100%{transform:translateY(0);opacity:.85}50%{transform:translateY(-6px);opacity:1}}
@keyframes vpGlow{0%{transform:rotate(0deg)}100%{transform:rotate(360deg)}}
.videoNp{display:flex;gap:10px;align-items:center;background:rgba(0,242,254,.09);border:1px solid rgba(0,242,254,.35);border-radius:14px;padding:10px;margin-bottom:8px;position:sticky;top:0;z-index:40;flex-wrap:wrap}
.videoNp .vnpThumb{width:104px;height:58px;object-fit:cover;border-radius:8px;background:#000;flex:none}
.videoNp .vnpBody{flex:1;min-width:140px}.videoNp .vnpTitle{font-size:13px;font-weight:700;color:#e8feff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.videoNp .vnpMeta{font-size:10px;color:rgba(232,254,255,.6);margin:3px 0 5px}.videoNp .vnpSeek{width:100%;height:6px;margin:0;accent-color:#00f2fe}
.videoNp .vnpBtns{display:flex;gap:6px;flex:none}.videoNp .vnpBtn{width:38px;height:38px;border-radius:10px;font-size:14px;padding:0;background:rgba(255,255,255,.12);color:#e8feff;border:1px solid rgba(255,255,255,.14)}
.videoNp .vnpBtn.toggle{background:#00f2fe;color:#000;font-weight:800}
.npBar{position:fixed;left:0;right:0;bottom:0;z-index:200;display:flex;align-items:center;gap:10px;padding:8px 14px calc(8px + env(safe-area-inset-bottom));background:rgba(8,11,20,.97);border-top:1px solid rgba(255,255,255,.1);backdrop-filter:blur(12px)}
.npBar img{width:44px;height:44px;border-radius:9px;object-fit:cover;flex:none;background:#000;cursor:pointer}
.npBarInfo{flex:1;min-width:0;cursor:pointer}.npBarTitle{font-size:13px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.npBarArtist{font-size:11px;color:rgba(255,255,255,.55);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.npBarSeek{flex:1;min-width:70px;margin:0;height:6px}.npBarBtn{width:42px;height:42px;border-radius:50%;font-size:16px;padding:0;flex:none;background:rgba(255,255,255,.1);border:1px solid rgba(255,255,255,.14)}
.toastWrap{position:fixed;left:50%;bottom:calc(80px + env(safe-area-inset-bottom));transform:translateX(-50%);z-index:500;display:flex;flex-direction:column;gap:6px;align-items:center;pointer-events:none}
.toast{background:rgba(20,24,38,.96);border:1px solid rgba(255,255,255,.14);color:#fff;padding:10px 14px;border-radius:12px;font-size:12px;box-shadow:0 8px 24px rgba(0,0,0,.5);max-width:90vw}
.toast.ok{border-color:rgba(0,242,254,.4)}.toast.err{border-color:rgba(255,80,80,.5);color:#ffb0b0}
.modal{display:none;position:fixed;inset:0;z-index:600;background:rgba(0,0,0,.6);align-items:center;justify-content:center;padding:16px}
.modal.show{display:flex}.modalCard{background:#101426;border:1px solid rgba(255,255,255,.14);border-radius:16px;padding:16px;max-width:520px;width:100%;max-height:80vh;overflow:auto}
.modalCard h3{font-size:15px;margin-bottom:10px;color:#00f2fe}.modalCard li{font-size:13px;color:rgba(255,255,255,.75);margin:6px 0;line-height:1.5}
.foot{margin-top:10px;font-size:10px;color:rgba(255,255,255,.35);text-align:center;line-height:1.6}
@media (min-width:960px){
  body{padding:8px 8px calc(8px + env(safe-area-inset-bottom))}
  .card{max-width:100%;padding:16px 20px}
  .musicCols{display:grid;grid-template-columns:1.35fr .65fr;gap:16px;align-items:stretch;flex:1;min-height:0}
  .npBar{display:none}
}
@media (max-width:900px){
  .dashHero{grid-template-columns:repeat(2,minmax(0,1fr))}
  .dashGrid{grid-template-columns:1fr;grid-template-rows:auto auto;height:auto;overflow:visible}
  #panel-dashboard.active{overflow:auto}
  .dashCol,.dashColMain,.dashColSide,.dashCardFill{overflow:visible;height:auto}
  .histList{max-height:42vh}
}
@media (max-width:700px){
  #panel-video.active,#panel-music.active{overflow:auto}
  .videoLayout,.musicLayout{grid-template-columns:1fr;grid-template-rows:auto auto;height:auto;min-height:0}
  .videoColList,.videoColPlayer,.musicColList,.musicColPlayer{height:auto;overflow:visible}
  .videoColList,.musicColList{overflow-y:visible}
  .dashHero{grid-template-columns:1fr 1fr}
}
@media (prefers-reduced-motion: reduce){*{animation:none!important;transition:none!important}}

/* global upgrades */
.globalBar{display:flex;align-items:center;gap:10px;padding:8px 10px;margin:8px 0;border:1px solid rgba(255,255,255,.12);border-radius:14px;background:linear-gradient(90deg,rgba(0,242,254,.1),rgba(79,172,254,.06),rgba(255,255,255,.03));box-shadow:0 8px 24px rgba(0,0,0,.25)}
.globalBar .gThumb{width:46px;height:46px;border-radius:10px;object-fit:cover;background:#000;cursor:pointer;flex:none}
.globalBar .gInfo{flex:1;min-width:0;cursor:pointer}
.globalBar .gTitle{font-size:13px;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.globalBar .gMeta{font-size:11px;color:rgba(255,255,255,.6);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
.globalBar .gCtrls{display:flex;gap:6px;align-items:center;flex:none}
.globalBar .gBtn{width:40px;height:40px;border-radius:50%;padding:0;font-size:16px}
.globalBar .gBtn.play{width:46px;height:46px;background:linear-gradient(135deg,#00f2fe,#4facfe);color:#001;border:none}
.globalBar .gSeekWrap{flex:1.2;min-width:120px;display:flex;flex-direction:column;gap:3px}
.globalBar .gSeek{margin:0;height:6px}
.globalBar .gTime{display:flex;justify-content:space-between;font-size:10px;color:rgba(255,255,255,.55)}
.npDashCard{display:flex;gap:12px;align-items:center;padding:12px 14px}
.npDashCard img{width:72px;height:72px;border-radius:14px;object-fit:cover;background:#000;box-shadow:0 8px 20px rgba(0,0,0,.35)}
.npDashCard .info{flex:1;min-width:0}
.npDashCard .title{font-size:16px;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.npDashCard .artist{font-size:12px;color:rgba(255,255,255,.65);margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.npDashCard .meta{font-size:11px;color:rgba(255,255,255,.5);margin-top:4px}
.npDashCard .actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
.sortSel{padding:8px 10px;border-radius:10px;border:1px solid rgba(255,255,255,.15);background:rgba(255,255,255,.06);color:#fff;font-size:12px}
@media (max-width:900px){.globalBar .gSeekWrap{display:none}}
@media (max-width:700px){.globalBar{gap:8px;padding:8px}.globalBar .gInfo{display:none}}

</style>
</head>
<body>
<div class="card">
  <div class="row">
    <span class="brand">HK REMOTE</span>
    <span class="badge" id="party">REMOTE</span>
  </div>

  <div class="tabs">
    <button class="tab active" data-tab="dashboard">📊 Dashboard</button>
    <button class="tab" data-tab="music">🎵 Nhạc<span class="tabCount" id="musicCount">0</span></button>
    <button class="tab" data-tab="video">🎬 Video<span class="tabCount" id="videoCountTab">0</span></button>
  </div>

  <div class="panels">
    <div class="panel active" id="panel-dashboard">
      <button class="install" id="install" onclick="installApp()">📲 Cài đặt ứng dụng (PWA)</button>
      <div class="hint" id="hint"></div>

      <div class="dashHero">
        <div class="dashHeroItem"><div class="k">Lịch sử phiên</div><div class="v" id="dashHistCount">0 mục</div></div>
        <div class="dashHeroItem"><div class="k">Thiết bị</div><div class="v" id="dashDevState">Chưa kết nối</div></div>
        <div class="dashHeroItem"><div class="k">Preset EQ</div><div class="v" id="dashEqState">Normal</div></div>
        <div class="dashHeroItem"><div class="k">Thiết bị web</div><div class="v"><span id="statClients">0</span> · <span id="footUpd">—</span></div></div>
      </div>

      <div class="dashGrid">
        <div class="dashCol dashColMain">

          <div class="dashCard dashCardFill">
            <div class="dashHead">
              <div><div class="t"><span class="ico">🕘</span> Lịch sử phát</div><div class="sub" id="historySub">Phiên hiện tại · 0 mục</div></div>
              <div class="actions"><button class="mini ghost" onclick="clearHistory()">🗑 Xoá tất cả</button></div>
            </div>
            <div class="histList" id="historyList"><div class="empty">Chưa có lịch sử phát. Phát một bài để bắt đầu.</div></div>
            <div id="historyMore" style="display:none;padding:8px 12px;text-align:center;border-top:1px solid rgba(255,255,255,.06)"><button class="mini" onclick="showMoreHistory()">Xem thêm</button></div>
          </div>
        </div>

        <div class="dashCol dashColSide">
          <div class="dashCard">
            <div class="dashHead">
              <div><div class="t"><span class="ico">📡</span> Kết nối thiết bị</div><div class="sub" id="castSub">Đang kiểm tra…</div></div>
              <div class="actions"><button class="mini accent" id="castScanBtn" onclick="castScan()">🔍 Quét</button></div>
            </div>
            <div class="dashBody">

              <div class="vol" id="tvVolRow" style="display:none;margin:8px 0"><span>📺 Âm lượng TV</span><input type="range" id="tvVol" min="0" max="100" value="50" onchange="setTvVolume(this.value)"><button class="mini" id="muteTv" onclick="tvMuteToggle(this)">🔊 TV</button></div>
              <div class="row" style="margin:4px 0 10px;gap:6px"><button class="mini" id="muteTvCastBtn" onclick="castMuteToggle(this)">🔇 Tắt tiếng TV</button><button class="mini" onclick="window.open('http://' + location.host + '/tv', '_blank')">📺 Cast Bridge</button></div>
              <div class="devStatus" id="castStat">—</div>
              <div class="label" style="margin:10px 0 6px">THIẾT BỊ KHẢ DỤNG</div>
              <div class="castList" id="castDevices"></div>
            </div>
          </div>

          <div class="dashCard">
            <div class="dashHead">
              <div><div class="t"><span class="ico">🎚️</span> EQ trình duyệt</div><div class="sub">Preset: <b id="statEq">Normal</b></div></div>
              <div class="actions"><button class="mini ghost" onclick="eqReset()">↺ Reset</button></div>
            </div>
            <div class="dashBody">
              <div class="eqViz" id="eqViz"><div class="bar" style="height:20%"></div><div class="bar" style="height:20%"></div><div class="bar" style="height:20%"></div><div class="bar" style="height:20%"></div><div class="bar" style="height:20%"></div></div>
              <div class="eqSliders">
                <label><span>60Hz</span><input type="range" min="-10" max="10" value="0" oninput="eqSliderInput(this,0)"><span class="db" id="db0">0 dB</span></label>
                <label><span>230Hz</span><input type="range" min="-10" max="10" value="0" oninput="eqSliderInput(this,1)"><span class="db" id="db1">0 dB</span></label>
                <label><span>910Hz</span><input type="range" min="-10" max="10" value="0" oninput="eqSliderInput(this,2)"><span class="db" id="db2">0 dB</span></label>
                <label><span>3.6k</span><input type="range" min="-10" max="10" value="0" oninput="eqSliderInput(this,3)"><span class="db" id="db3">0 dB</span></label>
                <label><span>14k</span><input type="range" min="-10" max="10" value="0" oninput="eqSliderInput(this,4)"><span class="db" id="db4">0 dB</span></label>
              </div>
              <div class="eqPresets">
                <button class="chipBtn" id="eqBass" onclick="setEq(10, 2, -4, this)">Bass</button>
                <button class="chipBtn on" id="eqNorm" onclick="setEq(0, 0, 0, this)">Normal</button>
                <button class="chipBtn" id="eqTreble" onclick="setEq(-4, 3, 10, this)">Treble</button>
                <button class="chipBtn" id="eqVocal" onclick="setEq(-2, 4, 2, this)">Vocal</button>
                <button class="chipBtn" id="eqRock" onclick="setEq(4, -2, 4, this)">Rock</button>
                <button class="chipBtn" id="eqPop" onclick="setEq(2, -1, 3, this)">Pop</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel" id="panel-music">
      <div class="musicLayout">
        <div class="musicColList">
          <div class="row" style="margin-bottom:8px;gap:8px">
            <div class="chips">
              <button class="chip active" data-f="all" onclick="chipFilter(this)">Tất cả</button>
              <button class="chip" data-f="local" onclick="chipFilter(this)">Máy</button>
              <button class="chip" data-f="yt" onclick="chipFilter(this)">YouTube</button>
              <button class="chip" data-f="upload" onclick="chipFilter(this)">Uploads</button>
            </div>
            <div style="display:flex;gap:6px;flex-wrap:wrap">
              <button class="mini" onclick="goToCurrent()">🎯 Đang phát</button>
              <button class="mini" onclick="toggleSelectMode()" id="selModeBtn">☑ Chọn</button>
              <button class="mini" onclick="playAll()">▶ Tất cả</button>
              <button class="mini" onclick="playShuffle()">🔀 Ngẫu nhiên</button>
            </div>
          </div>
          <div class="row" id="selBar" style="display:none;margin-bottom:8px;gap:6px">
            <span id="selCount" style="font-size:12px;color:rgba(255,255,255,.6)">0 đã chọn</span>
            <button class="mini" onclick="selectAllVisible()">Chọn hết</button>
            <button class="mini" onclick="clearSelection()">Bỏ chọn</button>
            <button class="mini" onclick="playSelected()">▶ Phát đã chọn</button>
            <button class="mini" onclick="removeSelected()">🗑 Xoá đã chọn</button>
          </div>
          <div class="row" style="margin-bottom:6px;gap:8px"><input type="text" id="plSearch" placeholder="Tìm theo tên, nghệ sĩ, album, nguồn…" oninput="renderList(lastData)" style="flex:1;padding:9px 12px;border-radius:10px;border:1px solid rgba(255,255,255,.15);background:rgba(255,255,255,.06);color:#fff;font-size:12px"><select id="plSort" class="sortSel" onchange="renderList(lastData)" title="Sắp xếp"><option value="queue">Theo hàng đợi</option><option value="title">Tên A→Z</option><option value="artist">Nghệ sĩ</option><option value="duration">Thời lượng</option></select><span id="plCount" style="font-size:11px;color:rgba(255,255,255,.5);white-space:nowrap"></span></div>
          <div class="listHead"><span>#</span><span>Tên bài</span><span>Album/Nguồn</span><span>⏱</span><span></span></div>
          <div class="list" id="list"><div class="skelRow"></div><div class="skelRow"></div><div class="skelRow"></div><div class="skelRow"></div></div>
        </div>
        <div class="musicColPlayer">
          <div class="nowCard">
            <img id="npThumb" src="/artwork?index=${state.currentIndex}" alt="" onclick="goToCurrent()" title="Tới bài đang phát">
            <div class="nowInfo">
              <div class="nowTitle" id="title" onclick="goToCurrent()">${htmlEscape(state.songs.getOrNull(state.currentIndex)?.title ?: "No track")} <span class="srcBadge" id="srcBadge">Local</span></div>
              <div class="nowArtist" id="artist">${htmlEscape(state.songs.getOrNull(state.currentIndex)?.artist ?: "")}</div>
              <div class="small"><span id="statusExtra">—</span><span id="lastUpd">—</span></div>
            </div>
            <div class="nowSeek">
              <input type="range" id="seek" min="0" max="100" value="0" onchange="seekTrack(this.value)">
              <div class="small"><span id="cur">0:00</span><span id="dur">0:00</span></div>
            </div>
          </div>
          <div class="controls">
            <button onclick="cmd('prev')" title="Trước">⏮</button>
            <button class="play" id="play" onclick="togglePlay()" title="Phát/Tạm dừng">⏸</button>
            <button onclick="cmd('next')" title="Tiếp">⏭</button>
          </div>
          <div class="row">
            <button class="mini" id="shuffle" onclick="cmd('shuffle')">🔀 Shuffle</button>
            <button class="mini" id="repeat" onclick="cmd('repeat')">🔁 Repeat</button>
            <button class="mini" id="muteWeb" onclick="webMuteToggle(this)">🔊 Web</button>
            <button class="mini" onclick="showHelp()">❓ Phím tắt</button>
          </div>
          <div class="vol"><span>🔈</span><input type="range" id="vol" min="0" max="100" value="80" onchange="setVolume(this.value)"><span>🔊</span></div>
          <div class="row" style="margin-top:8px"><button class="mini" id="webPlay" onclick="toggleWebPlay()">🔊 Phát trên web</button><div class="stat" style="flex:0 0 auto;min-width:120px"><span class="statLabel">Đang phát</span><span class="statValue" id="statTrack">—</span></div></div>
          <details class="acc">
            <summary>PARTY & UPLOAD</summary>
            <div class="accBody">
              <div class="partyBox" id="partyBox">
                <div class="partyHead"><span class="dot"></span><span id="partyState">ĐANG TẮT</span></div>
                <div class="partyRow"><input type="text" id="nick" maxlength="24" placeholder="Tên của bạn" oninput="saveNick()"><span id="partyToggle" class="mini" style="display:inline-flex;align-items:center;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.16);border-radius:10px;min-height:36px;padding:0 12px;font-size:11px">Party</span></div>
                <div class="partyHint" id="partyHint">Party tắt: chỉ điện thoại xoá được bài. Bật party trên điện thoại để mọi người tự thêm bài (mỗi người chỉ xoá bài mình thêm).</div>
              </div>
              <div class="upload" style="margin-top:8px"><input type="file" id="file" accept="audio/*" hidden onchange="upload()"><button class="mini" onclick="document.getElementById('file').click()">📥 Tải nhạc lên</button><div class="small" id="status" style="justify-content:center;margin-top:8px"></div></div>
            </div>
          </details>
          <div style="display:flex;flex-direction:column;min-height:0;flex:1">
            <div class="row"><div class="label" style="margin:0">LỜI BÀI HÁT</div><button class="mini" onclick="toggleLyricsExpand()" id="lyrExpBtn">Mở rộng</button></div>
            <div class="lyr" id="lyrics">Chưa có lời bài hát</div>
          </div>
          <audio id="webAudio" preload="auto" style="width:100%;margin-top:6px;display:none"></audio>
        </div>
      </div>
    </div>

    <div class="panel" id="panel-video">
      <div class="videoLayout">
        <div class="videoColList">
          <div class="row" style="margin-bottom:4px"><div class="label" style="margin:0">DANH SÁCH VIDEO (<span id="videoCount">0</span>)</div><div style="display:flex;gap:6px;flex-wrap:wrap"><button class="mini" onclick="playAllVideos()">▶ Phát tất cả</button><button class="mini" onclick="toggleVideoSearch()">🔍 Tìm YouTube</button></div></div>
          <div id="youtubeSearchBox" style="display:none;margin-bottom:6px">
            <div class="row"><input type="text" id="videoSearch" placeholder="Tìm video trên YouTube…" onkeydown="if(event.key==='Enter')searchVideoYoutube()" style="flex:1;padding:9px 12px;border-radius:10px;border:1px solid rgba(255,255,255,.15);background:rgba(255,255,255,.06);color:#fff;font-size:12px"><button class="mini" onclick="searchVideoYoutube()">Tìm</button></div>
            <div id="videoYoutubeLoading" style="display:none;text-align:center;padding:10px;color:#ff8080;font-size:12px">Đang tải…</div>
            <div id="videoYoutubeResults"></div>
          </div>
          <div class="videoGrid" id="videoGrid"><div class="skelRow"></div><div class="skelRow"></div><div class="skelRow"></div></div>
          <div id="videoEmpty" class="empty" style="display:none">Chưa có video trên máy. Hãy cấp quyền đọc video hoặc thêm tệp video.</div>
        </div>
        <div class="videoColPlayer">
          <div id="webVideoWrap">
            <div id="webVideoHead">
              <div class="ttl" id="webVideoTitle">Đang phát</div>
              <div class="vtools">
                <select id="vSpeed" onchange="setVideoSpeed(this.value)" title="Tốc độ"><option value="0.5">0.5x</option><option value="0.75">0.75x</option><option value="1" selected>1x</option><option value="1.25">1.25x</option><option value="1.5">1.5x</option><option value="2">2x</option></select>
                <button class="mini" onclick="webVideoFs()">⛶ Toàn màn hình</button>
                <button class="mini" onclick="closeWebVideo()">Đóng</button>
              </div>
            </div>
            <video id="webVideo" controls playsinline preload="metadata"></video>
          </div>
          <div class="playerPlaceholder" id="videoPlaceholder"><span class="hint">Chọn một video từ danh sách để phát tại đây</span></div>
          <div class="videoNp" id="videoNp" style="display:none">
            <img class="vnpThumb" id="videoNpThumb" alt="">
            <div class="vnpBody">
              <div class="vnpTitle" id="videoNpTitle">Đang phát video (điện thoại)</div>
              <div class="vnpMeta" id="videoNpMeta">0:00 / 0:00</div>
              <input type="range" class="vnpSeek" id="videoNpSeek" min="0" max="100" value="0" onchange="videoSeekTo(this.value)">
            </div>
            <div class="vnpBtns">
              <button class="vnpBtn" title="Lùi 10s" onclick="videoSeekBack()">⏪</button>
              <button class="vnpBtn toggle" id="videoNpToggle" title="Play/Pause" onclick="videoToggle()">⏸</button>
              <button class="vnpBtn" title="Tua 10s" onclick="videoSeekFwd()">⏩</button>
              <button class="vnpBtn" title="Video trước" onclick="videoPrev()">⏮</button>
              <button class="vnpBtn" title="Video tiếp" onclick="videoNext()">⏭</button>
              <button class="vnpBtn" title="Chiếu TV" onclick="videoCastTv()">📺</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div class="npBar" id="npBar" style="display:none">
    <img id="npBarThumb" src="/artwork?index=${state.currentIndex}" alt="" onclick="goToCurrent()">
    <div class="npBarInfo" onclick="goToCurrent()"><div class="npBarTitle" id="npBarTitle">—</div><div class="npBarArtist" id="npBarArtist">—</div></div>
    <input type="range" class="npBarSeek" id="npBarSeek" min="0" max="100" value="0" onchange="seekTrack(this.value)">
    <button class="npBarBtn" onclick="cmd('prev')">⏮</button>
    <button class="npBarBtn" id="npBarPlay" onclick="togglePlay()">▶</button>
    <button class="npBarBtn" onclick="cmd('next')">⏭</button>
  </div>
  <div class="foot">HK Music Player · ${htmlEscape(state.localIp)}:${state.port} · <span id="footUpd">cập nhật: —</span><span id="pwa"></span></div>
</div>
<div class="toastWrap" id="toastWrap"></div>
<div class="modal" id="helpModal" onclick="if(event.target===this)hideHelp()"><div class="modalCard"><h3>Phím tắt & trợ giúp</h3><ul><li><b>Space</b>: Phát/Tạm dừng nhạc</li><li><b>← / →</b>: Tua lùi/tiến 5 giây</li><li><b>↑ / ↓</b>: Tăng/giảm âm lượng</li><li><b>M</b>: Tắt/bật tiếng web audio</li><li><b>Tab Nhạc</b>: lọc, tìm, chọn nhiều để phát/xoá hàng loạt, bấm “Đang phát” để cuộn tới bài hiện tại</li><li><b>Tab Video</b>: bấm video để phát mở rộng trên web, có tốc độ & toàn màn hình; nút Phone/TV để phát trên điện thoại/cast</li></ul><div style="margin-top:12px;text-align:right"><button class="mini" onclick="hideHelp()">Đóng</button></div></div></div>

<script>

function fmt(s){s=Math.max(0,Math.floor(s||0));var m=Math.floor(s/60);var x=s%60;return m+":"+(x<10?"0"+x:x);}
var webMuted=false,webPlay=false,webPlayIndex=-1,lastYtUrl=null;
var lastData=null,lastKey='',lastKeyMode='',lastVideoKey='',currentVideoIndex=-1,offline=true;
var selected=new Set(),selectMode=false,lyricFor=-1,lastSelectedPos=null,currentPlaylist=[],lastUpdTs=0,retryCount=0;
var eqCtx=null,eqLow=null,eqMid=null,eqHigh=null,eqGains=[0,0,0,0,0],eqName='Normal',musicFilter='all',currentIndexState=-1;
var playHistory=[];
var _lastHistoryKey='';
var historyLimit=20;
function renderHistory(){var box=document.getElementById('historyList');if(!box)return;var sub=document.getElementById('historySub');if(sub)sub.textContent='Phiên hiện tại · '+playHistory.length+' mục';var hc=document.getElementById('dashHistCount');if(hc)hc.textContent=playHistory.length+' mục';if(!playHistory.length){box.innerHTML='<div class="empty">Chưa có lịch sử phát. Phát một bài để bắt đầu.</div>';var m=document.getElementById('historyMore');if(m)m.style.display='none';return;}var cur=(lastData&&typeof lastData.songIndex==='number')?lastData.songIndex:(lastData?lastData.currentIndex:-1);var show=playHistory.slice(0,historyLimit);var html='';for(var i=0;i<show.length;i++){var h=show[i];var playing=(h.songIndex===cur);var src=(h.source||'Local');html+='<div class="histItem'+(playing?' playing':'')+'" onclick="playSongIndex('+h.songIndex+')"><img src="/artwork?index='+h.songIndex+'" loading="lazy" alt=""><div class="meta"><div class="title">'+esc(h.title)+' <span class="srcBadge '+(src==='YouTube'?'yt':(src==='Upload'?'up':''))+'">'+esc(src)+'</span></div><div class="artist">'+esc(h.artist||'')+(h.album?(' · '+esc(h.album)):'')+'</div></div><div class="right"><span class="time">'+esc(h.time)+'</span><button class="hbtn" title="Xoá" onclick="event.stopPropagation();removeHistory('+i+')">✕</button></div></div>'; }box.innerHTML=html;var more=document.getElementById('historyMore');if(more)more.style.display=playHistory.length>historyLimit?'block':'none';}
function playSongIndex(idx){if(typeof idx==='number'&&idx>=0){fetch('/play?index='+idx,{cache:'no-store'}).then(function(){toast('Đang phát từ lịch sử','ok');poll();}).catch(function(){toast('Lỗi phát bài','err');});}}
function trackHistory(d){if(!d||d.title==='No track')return;var idx=(d.songIndex>=0?d.songIndex:d.currentIndex);var simple=idx+':'+(d.title||''); if(simple===_lastHistoryKey)return; _lastHistoryKey=simple; var t=new Date(); var ts=('0'+t.getHours()).slice(-2)+':'+('0'+t.getMinutes()).slice(-2); var src=(d.youtubeUrl||d.album==='YouTube')?'YouTube':(d.album==='Web upload'?'Upload':'Local'); playHistory.unshift({songIndex:idx,title:d.title||'',artist:d.artist||'',album:d.album||'',source:src,time:ts}); if(playHistory.length>50)playHistory.length=50; historyLimit=20; renderHistory();}
function removeHistory(i){playHistory.splice(i,1);renderHistory();}
function clearHistory(){if(!playHistory.length)return;if(!confirm('Xoá toàn bộ lịch sử phát phiên này?'))return;playHistory=[];_lastHistoryKey='';renderHistory();toast('Đã xoá lịch sử','ok');}
function showMoreHistory(){historyLimit=Math.min(playHistory.length,historyLimit+20);renderHistory();}


function toast(msg,type){var w=document.getElementById('toastWrap');if(!w)return;var d=document.createElement('div');d.className='toast'+(type?(' '+type):'');d.textContent=msg;w.appendChild(d);setTimeout(function(){d.remove();},2200);}
function setConn(state,text){var b=document.getElementById('connBanner'),t=document.getElementById('connText');if(!b||!t)return;b.className='connBanner show '+state;t.textContent=text;}
function manualRetry(){retryCount=0;setConn('warn','Đang thử kết nối lại…');poll();}
function showHelp(){document.getElementById('helpModal').classList.add('show');}
function hideHelp(){document.getElementById('helpModal').classList.remove('show');}

function webMuteToggle(btn){webMuted=!webMuted;var a=document.getElementById('webAudio');if(a)a.muted=webMuted;btn.textContent=webMuted?'🔇 Web':'🔊 Web';}
function cmd(a){fetch('/'+a,{cache:'no-store'}).then(function(){toast('Đã gửi: '+a,'ok');poll();}).catch(function(){toast('Lỗi gửi lệnh','err');});}
function seekTrack(v){fetch('/seek?pos='+v,{cache:'no-store'}).catch(function(){});var a=document.getElementById('webAudio');if(a&&Math.abs(a.currentTime-v)>0.8)a.currentTime=+v;}
function setVolume(v){fetch('/volume?val='+v,{cache:'no-store'}).catch(function(){});var a=document.getElementById('webAudio');if(a)a.volume=(+v)/100;}
function setTvVolume(v){fetch('/volume?val='+v,{cache:'no-store'}).catch(function(){});}
function tvMuteToggle(btn){var newMuted=btn.textContent.indexOf('Bật')>=0||btn.textContent.indexOf('Tắt')<0;fetch('/mute?on='+(newMuted?1:0),{cache:'no-store'}).then(poll).catch(function(){});btn.textContent=newMuted?'🔊 Bật tiếng TV':'🔇 Tắt tiếng TV';}
var lastCastScanTs=0,castScanning=false;
function castScan(){castScanning=true;lastCastScanTs=Date.now();var b=document.getElementById('castScanBtn');if(b){b.textContent='⏳ Đang quét';b.disabled=true;}toast('Đang quét thiết bị…');fetch('/cast/scan',{cache:'no-store'}).then(function(){setTimeout(function(){castScanning=false;if(b){b.textContent='🔍 Quét';b.disabled=false;}poll();},1200);}).catch(function(){castScanning=false;if(b){b.textContent='🔍 Quét';b.disabled=false;}toast('Lỗi quét TV','err');});}
function castMuteToggle(btn){var newMuted=btn.textContent.indexOf('Bật')>=0;fetch('/mute?on='+(newMuted?1:0),{cache:'no-store'}).then(poll).catch(function(){});btn.textContent=newMuted?'🔊 Bật tiếng TV':'🔇 Tắt tiếng TV';}
function togglePlay(){fetch('/toggle',{cache:'no-store'}).then(poll).catch(function(){toast('Lỗi phát/tạm dừng','err');});}
function toggleWebPlay(){webPlay=!webPlay;var a=document.getElementById('webAudio');if(webPlay){a.play().catch(function(){toast('Trình duyệt chặn tự phát, hãy bấm lại','err');});toast('Phát nhạc trên web','ok');}else{a.pause();toast('Dừng phát trên web','ok');}document.getElementById('webPlay').textContent=webPlay?'🔇 Dừng phát web':'🔊 Phát trên web';}
function castDisconnect(){fetch('/cast/disconnect',{cache:'no-store'}).then(function(){toast('Đã ngắt kết nối TV','ok');poll();}).catch(function(){toast('Lỗi ngắt TV','err');});}
function castConnect(id){fetch('/cast/connect?id='+encodeURIComponent(id),{cache:'no-store'}).then(function(){toast('Đang kết nối TV…','ok');poll();}).catch(function(){toast('Lỗi kết nối TV','err');});}
function castPlay(idx){fetch('/cast/play?index='+idx,{cache:'no-store'}).then(function(){toast('Đang phát lên TV','ok');poll();}).catch(function(){toast('Lỗi cast bài','err');});}
function castVideoWeb(i){fetch('/cast/play-video?index='+i,{cache:'no-store'}).then(function(){toast('Đang cast video lên TV','ok');}).catch(function(){toast('Lỗi cast video','err');});}

function buildEq(){if(eqCtx)return;var AC=window.AudioContext||window.webkitAudioContext;if(!AC)return;try{var a=document.getElementById('webAudio');var ctx=new AC();var src=ctx.createMediaElementSource(a);eqLow=ctx.createBiquadFilter();eqLow.type='lowshelf';eqLow.frequency.value=180;eqMid=ctx.createBiquadFilter();eqMid.type='peaking';eqMid.frequency.value=1000;eqMid.Q.value=0.9;eqHigh=ctx.createBiquadFilter();eqHigh.type='highshelf';eqHigh.frequency.value=3800;src.connect(eqLow);eqLow.connect(eqMid);eqMid.connect(eqHigh);eqHigh.connect(ctx.destination);eqCtx=ctx;}catch(e){eqCtx=null;}}
function eqUpdateUI(){var bars=document.querySelectorAll('#eqViz .bar');for(var i=0;i<5;i++){var v=eqGains[i]||0;var d=document.getElementById('db'+i);if(d)d.textContent=(v>0?'+':'')+v+' dB';if(bars[i])bars[i].style.height=Math.max(8,(20+v*6))+'%';}var s=document.getElementById('statEq');if(s)s.textContent=eqName;var de=document.getElementById('dashEqState');if(de)de.textContent=eqName;}
function eqApplySliders(){if(!eqCtx)return;var t=eqCtx.currentTime;eqLow.gain.setTargetAtTime((eqGains[0]+eqGains[1])/2,t,0.05);eqMid.gain.setTargetAtTime(eqGains[2],t,0.05);eqHigh.gain.setTargetAtTime((eqGains[3]+eqGains[4])/2,t,0.05);eqUpdateUI();}
function eqSliderInput(el,i){buildEq();eqGains[i]=+el.value;eqApplySliders();eqName='Custom';document.querySelectorAll('#eqBass,#eqNorm,#eqTreble,#eqVocal,#eqRock,#eqPop').forEach(function(b){b.classList.remove('eqOn','on');});eqUpdateUI();toast('EQ: Custom','ok');}
function eqReset(){setEq(0,0,0,document.getElementById('eqNorm'));var sl=document.querySelectorAll('.eqSliders input');for(var i=0;i<sl.length;i++)sl[i].value=0;toast('Đã reset EQ','ok');}
function setEq(l,m,h,el){buildEq();if(eqCtx){var t=eqCtx.currentTime;eqLow.gain.setTargetAtTime(l,t,0.08);eqMid.gain.setTargetAtTime(m,t,0.08);eqHigh.gain.setTargetAtTime(h,t,0.08);}eqGains=[l,l,m,h,h];var sliders=document.querySelectorAll('.eqSliders input');for(var i=0;i<sliders.length;i++)sliders[i].value=eqGains[i];eqName=el?el.textContent:'Custom';document.querySelectorAll('#eqBass,#eqNorm,#eqTreble,#eqVocal,#eqRock,#eqPop').forEach(function(b){b.classList.remove('eqOn','on');});if(el){el.classList.add('eqOn','on');}eqUpdateUI();if(el)toast('Đã áp dụng '+eqName,'ok');}

function switchTab(name,push){if(!name)name='dashboard';document.querySelectorAll('.tab').forEach(function(t){t.classList.toggle('active',t.getAttribute('data-tab')===name);});document.querySelectorAll('.panel').forEach(function(p){p.classList.toggle('active',p.id==='panel-'+name);});try{localStorage.setItem('hkTab',name);}catch(e){}if(push!==false){try{location.hash=name;}catch(e){}}}
function goToCurrent(){if(!lastData)return;switchTab('music');setTimeout(function(){var a=document.querySelector('#list .item.active');if(a&&a.scrollIntoView)a.scrollIntoView({block:'center',behavior:'smooth'});},80);}

function chipFilter(el){document.querySelectorAll('.chip').forEach(function(c){c.classList.remove('active');});el.classList.add('active');musicFilter=el.getAttribute('data-f');renderList(lastData);}
function filteredPlaylist(d){if(!d||!d.playlist)return[];var q=(document.getElementById('plSearch').value||'').toLowerCase();var items=d.playlist.slice();if(musicFilter==='yt')items=items.filter(function(s){return s.album==='YouTube';});else if(musicFilter==='upload')items=items.filter(function(s){return s.album==='Web upload';});else if(musicFilter==='local')items=items.filter(function(s){return s.album!=='YouTube'&&s.album!=='Web upload';});if(q)items=items.filter(function(s){return (s.title||'').toLowerCase().indexOf(q)>=0||(s.artist||'').toLowerCase().indexOf(q)>=0||(s.album||'').toLowerCase().indexOf(q)>=0||(sourceLabel(s)||'').toLowerCase().indexOf(q)>=0;});var sort=(document.getElementById('plSort')&&document.getElementById('plSort').value)||'queue';if(sort==='title')items.sort(function(a,b){return (a.title||'').localeCompare(b.title||'');});else if(sort==='artist')items.sort(function(a,b){return (a.artist||'').localeCompare(b.artist||'')||(a.title||'').localeCompare(b.title||'');});else if(sort==='duration')items.sort(function(a,b){return (a.duration||0)-(b.duration||0);});return items;}
function renderList(d){var box=document.getElementById('list');if(!d||!d.playlist){box.innerHTML='<div class="empty">Chưa có dữ liệu</div>';return;}currentPlaylist=d.playlist;var items=filteredPlaylist(d);var c=document.getElementById('plCount');if(c)c.textContent=items.length+'/'+d.playlist.length+' bài';var key=items.map(function(s){return s.pos+':'+s.index;}).join('|');var mode=musicFilter+'|'+(document.getElementById('plSearch').value||'')+'|'+selectMode+'|'+items.length+'|'+((document.getElementById('plSort')&&document.getElementById('plSort').value)||'queue'); if(key===lastKey&&mode===lastKeyMode){var els=box.children;for(var i=0;i<els.length;i++){if(els[i].classList&&els[i].classList.contains('item')){var pos=parseInt(els[i].getAttribute('data-pos'),10);els[i].classList.toggle('active',pos===d.index);} }return;}lastKey=key;lastKeyMode=mode;if(!items.length){box.innerHTML='<div class="empty">Không có kết quả phù hợp</div>';return;}var html='';for(var i=0;i<items.length;i++){var s=items[i];var pos=(s.pos===undefined?i:s.pos);var checked=selected.has(s.index)?' checked':'';var src=(s.album==='YouTube')?'YouTube':(s.album==='Web upload'?'Upload':'');html+='<div class="item'+(pos===d.index?' active':'')+'" data-pos="'+pos+'" data-idx="'+s.index+'">'+(selectMode?('<input class="sel" type="checkbox"'+checked+' onclick="event.stopPropagation();toggleSel('+s.index+')">'):'')+'<span class="pos">'+(i+1)+'</span><span class="nm" onclick="playPos('+pos+',event)" title="'+esc(s.title)+'">'+esc(s.title)+(s.by?'<span class="by">'+esc(s.by)+'</span>':'')+(src?('<span class="srcBadge '+(src==='YouTube'?'yt':'up')+'">'+src+'</span>'):'')+'</span><span class="al">'+esc(s.album||'')+'</span><span class="dt">'+fmt(s.duration)+'</span><button class="castBtn" title="Chiếu lên TV" onclick="event.stopPropagation();castPlay('+s.index+')">📺</button><button class="del" title="Bỏ khỏi hàng đợi" onclick="event.stopPropagation();removeAt('+pos+')">✕</button></div>'; }box.innerHTML=html;}

function playPos(p,ev){if(ev&&ev.shiftKey&&lastSelectedPos!==null){var a=Math.min(lastSelectedPos,p),b=Math.max(lastSelectedPos,p);for(var i=a;i<=b;i++){var item=currentPlaylist[i];if(item)selected.add(item.index);}lastSelectedPos=p;toast('Đã chọn vùng','ok');renderList(lastData);return;}lastSelectedPos=p;fetch('/playqueue?pos='+p,{cache:'no-store'}).then(poll).catch(function(){toast('Lỗi phát bài','err');});}
function playAll(){if(lastData&&lastData.playlist&&lastData.playlist.length){playPos(0);toast('Phát tất cả','ok');}}
function playShuffle(){if(lastData&&lastData.playlist&&lastData.playlist.length){var p=Math.floor(Math.random()*lastData.playlist.length);playPos(p);toast('Phát ngẫu nhiên','ok');}}
function removeAt(p){if(!confirm('Bỏ bài này khỏi hàng đợi?'))return;fetch('/remove?pos='+p+whoParam(),{cache:'no-store'}).then(function(){toast('Đã xoá khỏi hàng đợi','ok');poll();}).catch(function(){toast('Không xoá được','err');});}
function whoParam(){var n=nickName();return n?('&who='+encodeURIComponent(n)):'';}
function nickName(){var el=document.getElementById('nick');return el?(el.value||'').trim():'';}
function saveNick(){try{localStorage.setItem('partyNick',nickName());}catch(e){}}

function toggleSelectMode(){selectMode=!selectMode;if(!selectMode)selected.clear();document.getElementById('selModeBtn').classList.toggle('on',selectMode);document.getElementById('selBar').style.display=selectMode?'flex':'none';updateSelCount();renderList(lastData);}
function toggleSel(idx){if(selected.has(idx))selected.delete(idx);else selected.add(idx);updateSelCount();}
function updateSelCount(){var el=document.getElementById('selCount');if(el)el.textContent=selected.size+' đã chọn';}
function selectAllVisible(){filteredPlaylist(lastData).forEach(function(s){selected.add(s.index);});updateSelCount();renderList(lastData);}
function clearSelection(){selected.clear();updateSelCount();renderList(lastData);}
function playSelected(){if(!selected.size){toast('Chưa chọn bài nào','err');return;}var ids=Array.from(selected).join(',');fetch('/selection?indices='+encodeURIComponent(ids),{cache:'no-store'}).then(function(){toast('Đang phát mục đã chọn','ok');poll();}).catch(function(){toast('Lỗi phát mục chọn','err');});}
function removeSelected(){if(!selected.size){toast('Chưa chọn bài nào','err');return;}if(!confirm('Bỏ '+selected.size+' bài đã chọn khỏi hàng đợi?'))return;var pos=[];(lastData.playlist||[]).forEach(function(s){if(selected.has(s.index))pos.push(s.pos);});pos.sort(function(a,b){return b-a;});var chain=Promise.resolve();pos.forEach(function(p){chain=chain.then(function(){return fetch('/remove?pos='+p+whoParam(),{cache:'no-store'});});});chain.then(function(){selected.clear();updateSelCount();toast('Đã xoá mục chọn','ok');poll();}).catch(function(){toast('Lỗi xoá mục chọn','err');});}

function toggleLyricsExpand(){var l=document.getElementById('lyrics');l.classList.toggle('expanded');document.getElementById('lyrExpBtn').textContent=l.classList.contains('expanded')?'Thu gọn':'Mở rộng';}
function loadLyrics(idx){lyricFor=idx;var box=document.getElementById('lyrics');fetch('/api/lyrics?index='+idx,{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){if(lyricFor!==idx)return;var lines=d.lines||[];if(lines.length===0){box.innerHTML='<div class="empty">Chưa có lời bài hát</div>';return;}var html='';for(var i=0;i<lines.length;i++)html+='<div data-t="'+(lines[i].t||0)+'">'+esc(lines[i].text)+'</div>';box.innerHTML=html;}).catch(function(){if(lyricFor===idx)box.innerHTML='<div class="empty">Lỗi tải lời bài hát</div>';});}
function highlightLyrics(ms){var box=document.getElementById('lyrics');var nodes=box.children;var active=-1;for(var i=0;i<nodes.length;i++){var t=parseInt(nodes[i].getAttribute('data-t'),10)||0;if(t<=ms)active=i;nodes[i].className='';}if(active>=0&&nodes[active]){nodes[active].className='on';if(nodes[active].scrollIntoView)nodes[active].scrollIntoView({block:'nearest'});}}

function esc(t){return String(t==null?'':t).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}

function renderCast(d){var box=document.getElementById('castDevices');var connected=d.castConnectedName||'';var connectedId=d.castConnectedId||'';var devices=d.castDevices||[];var html='';var foundConn=false;for(var i=0;i<devices.length;i++){var dev=devices[i];var isConn=(connectedId&&connectedId===dev.id)||(!connectedId&&connected===dev.name);if(isConn)foundConn=true;var k=(dev.name.indexOf('DLNA')>=0)?'DLNA':'Chromecast/Thiết bị';html+='<div class="castItem'+(isConn?' casting':'')+'"><div><div class="nm">'+esc(dev.name)+(isConn?' · Đang kết nối':'')+'</div><div class="meta">'+k+(isConn?(' · '+(d.isCasting?'Đang phát':'Sẵn sàng')):'')+'</div></div>'+(isConn?'<button class="mini" style="border-color:#ff0844;color:#ff0844" onclick="castDisconnect()">Ngắt</button>':'<button class="mini" onclick="castConnect(\''+dev.id+'\')">Kết nối</button>')+'</div>';}if(!devices.length){html='<div class="empty">Không tìm thấy thiết bị. Kiểm tra Wi-Fi/chung mạng rồi bấm Quét.</div>'; }box.innerHTML=html;var sub=document.getElementById('castSub');var ago=lastCastScanTs?(' · quét '+Math.max(0,Math.round((Date.now()-lastCastScanTs)/1000))+'s trước'):'';var connLabel=connected||((foundConn)?'Thiết bị':'');if(sub)sub.textContent=(connLabel?('Đang kết nối: '+connLabel):'Chưa kết nối')+' · '+devices.length+' thiết bị'+(castScanning?' · đang quét…':ago);var ds=document.getElementById('dashDevState');if(ds)ds.textContent=connLabel?('Đã kết nối '+connLabel):(devices.length?devices.length+' khả dụng':'Chưa kết nối');var st=document.getElementById('castStat');if(st)st.textContent=castScanning?'⏳ Đang quét thiết bị…':(devices.length?('Tìm thấy '+devices.length+' thiết bị'+(connLabel?' · đang dùng '+connLabel:'')):'Không có thiết bị · kiểm tra Wi-Fi/chung mạng rồi bấm Quét');var tvr=document.getElementById('tvVolRow');if(tvr)tvr.style.display=(connected||foundConn||d.isCasting)?'flex':'none';var b=document.getElementById('castScanBtn');if(b&&!castScanning){b.textContent='🔍 Quét';b.disabled=false;}}

function renderVideos(d){var grid=document.getElementById('videoGrid');var empty=document.getElementById('videoEmpty');if(!grid||!d||!d.videos)return;document.getElementById('videoCount').textContent=d.videos.length;if(empty)empty.style.display=d.videos.length?'none':'block';var key=d.videos.map(function(v){return v.index+':'+v.title;}).join('|');if(key===lastVideoKey)return;lastVideoKey=key;var html='';for(var i=0;i<d.videos.length;i++){var v=d.videos[i];html+='<div class="videoItem" onclick="playVideoWeb('+i+')"><img src="/thumb?index='+i+'" loading="lazy" alt=""><div class="playOverlay">▶</div><div class="vt">'+esc(v.title)+'</div><div class="vd">'+fmt(v.duration)+' · '+esc(v.folder)+'</div><div class="vrow"><button class="vbtn" onclick="event.stopPropagation();playVideoWeb('+i+')">▶ Web</button><button class="vbtn" onclick="event.stopPropagation();playVideoOnPhone('+i+')">📱 Phone</button><button class="vbtn" onclick="event.stopPropagation();castVideoWeb('+i+')">📺 TV</button></div></div>';}grid.innerHTML=html||'<div class="empty">Chưa có video</div>';}
function playVideoOnPhone(i){fetch('/videoplay?index='+i,{cache:'no-store'}).then(function(){toast('Đang phát trên điện thoại','ok');poll();}).catch(function(){toast('Lỗi phát video','err');});}
function playVideoWeb(i){var v=document.getElementById('webVideo');var wrap=document.getElementById('webVideoWrap');if(!v||!wrap)return;var item=(lastData&&lastData.videos)?lastData.videos[i]:null;document.getElementById('webVideoTitle').textContent=item?(item.title||('Video '+(i+1))):('Video '+(i+1));v.src='/video?index='+i;wrap.classList.add('active');wrap.style.display='flex';var ph=document.getElementById('videoPlaceholder');if(ph)ph.style.display='none';try{wrap.scrollIntoView({behavior:'smooth',block:'start'});}catch(e){}var p=v.play();if(p&&p.catch)p.catch(function(){toast('Bấm play để phát video','err');});toast('Đang phát video trên web','ok');}
function closeWebVideo(){var v=document.getElementById('webVideo');var wrap=document.getElementById('webVideoWrap');if(v){try{v.pause();}catch(e){}v.removeAttribute('src');v.load();}if(wrap){wrap.classList.remove('active');wrap.style.display='none';}var ph=document.getElementById('videoPlaceholder');if(ph)ph.style.display='block';}
function setVideoSpeed(v){var el=document.getElementById('webVideo');if(el)el.playbackRate=parseFloat(v)||1;}
function webVideoFs(){var el=document.getElementById('webVideo');if(!el)return;if(el.requestFullscreen)el.requestFullscreen();else if(el.webkitRequestFullscreen)el.webkitRequestFullscreen();}
function renderVideoNowPlaying(d){var np=document.getElementById('videoNp');if(!np)return;if(d&&d.videoActive){np.style.display='flex';currentVideoIndex=(typeof d.videoIndex==='number')?d.videoIndex:-1;document.getElementById('videoNpTitle').textContent=d.videoTitle||'Đang phát video (điện thoại)';document.getElementById('videoNpMeta').textContent=fmt(d.videoPosition||0)+' / '+fmt(d.videoDuration||0);var sk=document.getElementById('videoNpSeek');sk.max=d.videoDuration||0;sk.value=d.videoPosition||0;var th=document.getElementById('videoNpThumb');if(currentVideoIndex>=0){th.style.display='block';th.src='/thumb?index='+currentVideoIndex;}else{th.style.display='none';}document.getElementById('videoNpToggle').textContent=d.videoPlaying?'⏸':'▶';}else{np.style.display='none';currentVideoIndex=-1;}}
function videoToggle(){fetch('/videotoggle',{cache:'no-store'}).then(poll).catch(function(){});}
function videoPrev(){fetch('/videoprev',{cache:'no-store'}).then(poll).catch(function(){});}
function videoNext(){fetch('/videonext',{cache:'no-store'}).then(poll).catch(function(){});}
function videoSeekBack(){fetch('/videoseekback',{cache:'no-store'}).then(poll).catch(function(){});}
function videoSeekFwd(){fetch('/videoseekfwd',{cache:'no-store'}).then(poll).catch(function(){});}
function videoSeekTo(v){fetch('/videoseek?pos='+v,{cache:'no-store'}).then(poll).catch(function(){});}
function videoCastTv(){if(currentVideoIndex>=0)castVideoWeb(currentVideoIndex);else toast('Chưa có video đang phát trên điện thoại','err');}
function playAllVideos(){if(lastData&&lastData.videos&&lastData.videos.length){playVideoWeb(0);}else toast('Không có video','err');}

function toggleVideoSearch(){var box=document.getElementById('youtubeSearchBox');box.style.display=box.style.display==='none'?'block':'none';if(box.style.display==='block')document.getElementById('videoSearch').focus();}
function searchVideoYoutube(){var q=document.getElementById('videoSearch').value.trim();if(q.length<2){toast('Nhập ít nhất 2 ký tự','err');return;}document.getElementById('videoYoutubeLoading').style.display='block';document.getElementById('videoYoutubeResults').innerHTML='';fetch('/youtube/search?q='+encodeURIComponent(q),{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){document.getElementById('videoYoutubeLoading').style.display='none';var res=d.results||[];if(!res.length){document.getElementById('videoYoutubeResults').innerHTML='<div class="empty">Không tìm thấy video</div>';return;}var html='';res.forEach(function(r){html+='<div class="ytItem"><img src="'+esc(r.thumbUrl)+'" loading="lazy" alt=""><div class="ytBody"><div class="ytTitle">'+esc(r.title)+'</div><div class="ytMeta">'+esc(r.author)+' · '+fmt(r.duration)+'</div><button class="ytBtn" onclick="playYoutubeVideo(\''+r.videoId+'\',this)">▶ Phát trên điện thoại</button></div></div>';});document.getElementById('videoYoutubeResults').innerHTML=html;toast('Tìm thấy '+res.length+' kết quả','ok');}).catch(function(){document.getElementById('videoYoutubeLoading').style.display='none';document.getElementById('videoYoutubeResults').innerHTML='<div class="empty">Lỗi tải kết quả</div>';toast('Lỗi tìm YouTube','err');});}
function playYoutubeVideo(videoId,btn){if(btn)btn.disabled=true;document.getElementById('videoYoutubeLoading').style.display='block';fetch('/youtube/stream?videoId='+encodeURIComponent(videoId),{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){document.getElementById('videoYoutubeLoading').style.display='none';if(btn)btn.disabled=false;if(d&&d.url){fetch('/videoplay?url='+encodeURIComponent(d.url)+'&title='+encodeURIComponent(d.title||'YouTube')+'&audio='+encodeURIComponent(d.audioUrl||''),{cache:'no-store'}).then(function(){toast('Đang phát YouTube trên điện thoại','ok');poll();}).catch(function(){toast('Lỗi gửi lệnh phát','err');});}else{toast('Không lấy được luồng video','err');}}).catch(function(){document.getElementById('videoYoutubeLoading').style.display='none';if(btn)btn.disabled=false;toast('Lỗi tải luồng video','err');});}

function sourceLabel(d){if(d.youtubeUrl||d.album==='YouTube')return 'YouTube';if(d.album==='Web upload')return 'Upload';return 'Local';}
function updateStatusExtra(d){var extra=[];if(d.shuffle)extra.push('Shuffle');if(d.repeat)extra.push('Repeat');if(d.party)extra.push('Party');if(d.isCasting)extra.push('Casting');document.getElementById('statusExtra').textContent=extra.join(' · ')||'Sẵn sàng';var b=document.getElementById('srcBadge');if(b){var s=sourceLabel(d);b.textContent=s;b.className='srcBadge'+(s==='YouTube'?' yt':(s==='Upload'?' up':''));}}

function poll(){fetch('/api/status',{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){
  lastData=d;retryCount=0;lastUpdTs=Date.now();offline=false;
  var ts=new Date(lastUpdTs).toLocaleTimeString();
  var fu=document.getElementById('footUpd');if(fu)fu.textContent='cập nhật: '+ts;
  var lu=document.getElementById('lastUpd');if(lu)lu.textContent=ts;
  setConn('ok','Đã kết nối · cập nhật '+ts);
  document.getElementById('title').childNodes[0].nodeValue=(d.title||'')+' ';
  document.getElementById('artist').textContent=d.artist+(d.album?(' · '+d.album):'');
  document.getElementById('statTrack').textContent=d.title||'—';
  document.getElementById('statClients').textContent=d.clients||0;
  document.getElementById('musicCount').textContent=d.playlist?d.playlist.length:0;
  document.getElementById('videoCountTab').textContent=d.videoCount||0;
  document.getElementById('party').textContent=d.clients>0?('REMOTE · '+d.clients+' 📱'):'REMOTE';document.getElementById('party').classList.remove('offline');
  var wa=document.getElementById('webAudio');var playingNow=webPlay?(!wa.paused):!!d.playing;
  document.getElementById('play').textContent=playingNow?'⏸':'▶';document.getElementById('npBarPlay').textContent=playingNow?'⏸':'▶';
  var gp=document.getElementById('gPlay');if(gp)gp.textContent=playingNow?'⏸':'▶'; var dp=document.getElementById('dashNpPlay');if(dp)dp.textContent=playingNow?'⏸':'▶';
  if(webPlay){var a=document.getElementById('webAudio');if(d.youtubeUrl){if(lastYtUrl!==d.youtubeUrl){lastYtUrl=d.youtubeUrl;webPlayIndex=-2;a.src='/youtube';a.load();a.currentTime=d.position||0;if(d.playing)a.play().catch(function(){});}else{if(d.playing&&a.paused)a.play().catch(function(){});else if(!d.playing&&!a.paused)a.pause();if(d.playing&&Math.abs(a.currentTime-d.position)>1.5)a.currentTime=d.position;}}else if(typeof d.songIndex==='number'&&d.songIndex>=0){if(webPlayIndex!==d.songIndex){a.setAttribute('data-idx',d.songIndex);a.src='/audio?index='+d.songIndex;a.load();a.currentTime=d.position||0;if(d.playing)a.play().catch(function(){});webPlayIndex=d.songIndex;}else{if(d.playing&&a.paused)a.play().catch(function(){});else if(!d.playing&&!a.paused)a.pause();if(d.playing&&Math.abs(a.currentTime-d.position)>1.5)a.currentTime=d.position;}}lastYtUrl=d.youtubeUrl||null;}
  var seek=document.getElementById('seek');seek.max=d.duration||0;seek.value=d.position||0;document.getElementById('cur').textContent=fmt(d.position);document.getElementById('dur').textContent=fmt(d.duration);
  document.getElementById('npBarSeek').max=d.duration||0;document.getElementById('npBarSeek').value=d.position||0;
  var gs=document.getElementById('gSeek');if(gs){gs.max=d.duration||0;gs.value=d.position||0;} var gc=document.getElementById('gCur');if(gc)gc.textContent=fmt(d.position); var gd=document.getElementById('gDur');if(gd)gd.textContent=fmt(d.duration);
  document.getElementById('vol').value=d.volume;var av=document.getElementById('webAudio');if(av)av.volume=(d.volume||0)/100;
  document.getElementById('shuffle').classList.toggle('on',!!d.shuffle);document.getElementById('repeat').classList.toggle('on',!!d.repeat);
  var si=d.songIndex>=0?d.songIndex:d.currentIndex;document.getElementById('npThumb').src='/artwork?index='+si;document.getElementById('npBarThumb').src='/artwork?index='+si;
  var gt=document.getElementById('gThumb');if(gt)gt.src='/artwork?index='+si; var dt=document.getElementById('dashNpThumb');if(dt)dt.src='/artwork?index='+si;
  var np=document.getElementById('npBar');if(np){if(d.title&&d.title!=='No track'){np.style.display='flex';document.getElementById('npBarTitle').textContent=d.title;document.getElementById('npBarArtist').textContent=d.artist||'';}else{np.style.display='none';}}
  var gtit=document.getElementById('gTitle');if(gtit)gtit.textContent=d.title||'No track'; var gm=document.getElementById('gMeta');if(gm)gm.textContent=(d.artist||'')+(d.album?(' · '+d.album):''); var dnt=document.getElementById('dashNpTitle');if(dnt)dnt.textContent=d.title||'—'; var dna=document.getElementById('dashNpArtist');if(dna)dna.textContent=(d.artist||'')+(d.album?(' · '+d.album):''); var dnm=document.getElementById('dashNpMeta');if(dnm)dnm.textContent=(sourceLabel(d)+' · '+fmt(d.position)+' / '+fmt(d.duration)); var dns=document.getElementById('dashNpSub');if(dns)dns.textContent=sourceLabel(d)+(d.isCasting?' · Casting':'')+(d.party?' · Party':'');
  var pBox=document.getElementById('partyBox');if(pBox){pBox.classList.toggle('on',!!d.party);document.getElementById('partyState').textContent=d.party?'ĐANG BẬT':'ĐANG TẮT';document.getElementById('partyToggle').textContent=d.party?'Party BẬT':'Party TẮT';document.getElementById('partyHint').textContent=d.party?'Party đang bật: bạn thêm bài được, mỗi người chỉ xoá bài mình thêm.':'Party tắt: chỉ điện thoại xoá được bài. Bật party trên điện thoại để mọi người cùng thêm.';}
  trackHistory(d);updateStatusExtra(d);renderList(d);renderVideos(d);renderVideoNowPlaying(d);renderCast(d);
  /* tvVolRow visibility handled in renderCast */
  currentIndexState=d.index||0; if(typeof d.songIndex==='number'&&d.songIndex>=0&&d.songIndex!==lyricFor)loadLyrics(d.songIndex); highlightLyrics((d.position||0)*1000);
}).catch(function(){offline=true;retryCount++;var b=document.getElementById('party');b.textContent='OFFLINE';b.classList.add('offline');setConn('err','Mất kết nối · đang thử lại ('+retryCount+')');document.getElementById('title').childNodes[0].nodeValue='Mất kết nối ';document.getElementById('artist').textContent='Bật lại server trong app HK, trang sẽ tự kết nối lại';});}

function installApp(){if(window._deferredPrompt){window._deferredPrompt.prompt();window._deferredPrompt.userChoice.then(function(res){if(res.outcome==='accepted'){document.getElementById('install').style.display='none';toast('Đã cài ứng dụng','ok');}window._deferredPrompt=null;});}else{toast('Mở menu trình duyệt để cài PWA','ok');}}
function upload(){var input=document.getElementById('file');if(!input.files.length)return;var file=input.files[0];var status=document.getElementById('status');status.textContent='Đang tải lên '+file.name+'…';fetch('/upload?name='+encodeURIComponent(file.name)+whoParam(),{method:'POST',body:file}).then(function(r){return r.json();}).then(function(d){if(d.ok){status.textContent='Tải lên thành công ✓';toast('Đã tải lên','ok');}else{status.textContent='Tải lên thất bại';toast('Tải lên thất bại','err');}setTimeout(poll,500);}).catch(function(){status.textContent='Tải lên thất bại';toast('Lỗi tải lên','err');});}

document.querySelectorAll('.tab').forEach(function(btn){btn.addEventListener('click',function(){switchTab(btn.getAttribute('data-tab'));});});
window.addEventListener('beforeinstallprompt',function(e){e.preventDefault();window._deferredPrompt=e;document.getElementById('install').style.display='block';document.getElementById('hint').style.display='block';document.getElementById('hint').textContent='Bạn có thể cài web remote như ứng dụng (PWA) để mở nhanh từ màn hình chính.';});
document.addEventListener('keydown',function(e){var t=e.target.tagName;if(t==='INPUT'||t==='TEXTAREA'||t==='SELECT')return;if(e.code==='Space'){e.preventDefault();togglePlay();}else if(e.code==='ArrowRight'){seekTrack(Math.min((+document.getElementById('seek').value||0)+5,(+document.getElementById('seek').max||0)));}else if(e.code==='ArrowLeft'){seekTrack(Math.max((+document.getElementById('seek').value||0)-5,0));}else if(e.code==='ArrowUp'){var v=Math.min(100,(+document.getElementById('vol').value||80)+5);document.getElementById('vol').value=v;setVolume(v);}else if(e.code==='ArrowDown'){var v=Math.max(0,(+document.getElementById('vol').value||80)-5);document.getElementById('vol').value=v;setVolume(v);}else if(e.code==='KeyM'){webMuteToggle(document.getElementById('muteWeb'));}else if(e.key==='?'){showHelp();}});
try{var sn=localStorage.getItem('partyNick');if(sn)document.getElementById('nick').value=sn;}catch(e){}
var _initTab=(location.hash||'').replace('#',''); if(!_initTab){try{_initTab=localStorage.getItem('hkTab')||'';}catch(e){}} if(['dashboard','music','video'].indexOf(_initTab)>=0) switchTab(_initTab,false);
window.addEventListener('hashchange',function(){var h=(location.hash||'').replace('#',''); if(['dashboard','music','video'].indexOf(h)>=0) switchTab(h,false);});
eqUpdateUI();
setInterval(poll,1500);poll();
</script>
</body>
</html>
"""
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
#art{ width:min(40vh,40vw); aspect-ratio:1/1; object-fit:cover; border-radius:24px;
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
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(function () {
      document.getElementById('topbar').classList.add('hidden');
      document.getElementById('bottomBar').classList.add('hidden');
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

  // ---------------- track loading
  function loadTrack(index, positionSeconds) {
    cur = index;
    positionSeconds = positionSeconds || 0;
    document.getElementById('art').src = '/artwork?index=' + index + '&v=' + Date.now();
    document.getElementById('artbg').style.backgroundImage = 'url(/artwork?index=' + index + ')';
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
const CACHE = 'HK-shell-v2';
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
      }).catch(function () { return caches.match('/'); })
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
