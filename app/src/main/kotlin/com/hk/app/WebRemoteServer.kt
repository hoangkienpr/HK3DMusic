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

/** Everything the UI wants to know about the current playback state. */
data class RemoteState(
    val songs: List<Song>,
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
    val owners: List<String?> = emptyList()
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
<html lang="en">
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
html::-webkit-scrollbar,body::-webkit-scrollbar,.list::-webkit-scrollbar,.lyr::-webkit-scrollbar{display:none;width:0;height:0}
html,body,.list,.lyr{scrollbar-width:none;-ms-overflow-style:none}
body{background:#05070e;color:#fff;font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif;
     min-height:100vh;display:flex;justify-content:center;align-items:center;
     padding:calc(16px + env(safe-area-inset-top)) 16px calc(16px + env(safe-area-inset-bottom))}
.card{width:100%;max-width:460px;background:rgba(16,20,34,.92);border:1px solid rgba(255,255,255,.12);
      border-radius:24px;padding:22px;box-shadow:0 24px 60px rgba(0,0,0,.7)}
.row{display:flex;align-items:center;justify-content:space-between;gap:10px}
.brand{font-weight:800;letter-spacing:2px;font-size:13px;color:#00f2fe}
.badge{font-size:10px;font-weight:800;letter-spacing:1px;padding:4px 10px;border-radius:10px;
       background:rgba(0,242,254,.15);color:#00f2fe;border:1px solid rgba(0,242,254,.4)}
.badge.offline{background:rgba(255,80,80,.15);color:#ff6b6b;border-color:rgba(255,80,80,.45)}
.install{display:none;width:100%;height:38px;border-radius:12px;font-size:12px;margin-top:14px;
       background:rgba(0,242,254,.14);border:1px solid rgba(0,242,254,.4);color:#00f2fe}
.hint{display:none;margin-top:10px;font-size:11px;line-height:1.55;color:rgba(255,255,255,.5);
      background:rgba(255,255,255,.05);border-radius:12px;padding:10px 12px}
.hint b{color:#00f2fe;font-weight:700}
.title{font-size:20px;font-weight:800;margin-top:18px;line-height:1.25}
.sub{font-size:13px;color:rgba(255,255,255,.55);margin-top:4px}
input[type=range]{width:100%;-webkit-appearance:none;background:rgba(255,255,255,.12);
      height:6px;border-radius:3px;outline:none;margin-top:14px}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:16px;height:16px;border-radius:50%;
      background:#00f2fe;box-shadow:0 0 12px #00f2fee6;cursor:pointer}
.small{font-size:11px;color:rgba(255,255,255,.45);display:flex;justify-content:space-between;margin-top:6px}
.controls{display:flex;justify-content:center;align-items:center;gap:18px;margin:22px 0}
button{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.16);color:#fff;
       width:52px;height:52px;border-radius:50%;font-size:19px;cursor:pointer;transition:.15s}
button:hover{background:rgba(255,255,255,.18)}
button.play{width:66px;height:66px;font-size:24px;background:linear-gradient(135deg,#00f2fe,#4facfe);
       color:#000;border:none;box-shadow:0 8px 24px rgba(0,242,254,.35)}
button.mini{width:auto;height:36px;border-radius:12px;font-size:12px;padding:0 14px}
.mini.eqOn{border-color:#00f2fe;color:#00f2fe}
.castList{display:flex;flex-direction:column;gap:6px;margin-bottom:10px}
.castItem{display:flex;align-items:center;justify-content:space-between;gap:8px;background:rgba(0,0,0,.3);
          border:1px solid rgba(255,255,255,.06);border-radius:12px;padding:9px 12px}
.castItem .nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;color:rgba(255,255,255,.75)}
.castItem.casting{border-color:rgba(255,8,68,.4);background:rgba(255,8,68,.08)}
.castBtn{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.14);color:#fff;
         width:46px;height:28px;border-radius:9px;font-size:14px;cursor:pointer;padding:0;line-height:1}
.castBtn:hover{background:rgba(255,255,255,.16)}
.vol{display:flex;align-items:center;gap:10px;margin-top:6px}
.vol input{margin-top:0}
.label{font-size:10px;letter-spacing:1.6px;color:rgba(255,255,255,.4);font-weight:800;margin:20px 0 8px}
.list{max-height:190px;overflow-y:auto;background:rgba(0,0,0,.3);border-radius:14px;padding:6px;
      border:1px solid rgba(255,255,255,.06)}
.item{padding:9px 12px;border-radius:9px;font-size:13px;color:rgba(255,255,255,.72);cursor:pointer;
      display:flex;align-items:center;justify-content:space-between;gap:8px}
.item .nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.item .pick{display:flex;align-items:center}
.item input[type=checkbox]{accent-color:#00f2fe;width:15px;height:15px}
.item .del{background:transparent;border:none;color:rgba(255,255,255,.35);font-size:12px;
           width:22px;height:22px;padding:0;line-height:1}
.item .del:hover{color:#ff6b6b;background:transparent}
.lyr{max-height:150px;overflow-y:auto;background:rgba(0,0,0,.3);border:1px solid rgba(255,255,255,.06);
     border-radius:14px;padding:10px 12px;font-size:12px;line-height:1.7;color:rgba(255,255,255,.5)}
.lyr .on{color:#00f2fe;font-weight:700}
.item:hover{background:rgba(255,255,255,.08);color:#fff}
.item.active{background:rgba(0,242,254,.14);color:#00f2fe;font-weight:700}
.item .by{font-size:10px;color:#ffd479;margin-left:6px;font-weight:600}
.partyBox{background:rgba(0,0,0,.3);border:1px solid rgba(255,255,255,.08);border-radius:14px;padding:10px 12px;margin-bottom:6px}
.partyBox.on{border-color:rgba(255,212,121,.55);background:rgba(255,212,121,.07)}
.partyHead{display:flex;align-items:center;gap:8px;font-size:11px;letter-spacing:1px;
           font-weight:800;color:rgba(255,255,255,.55);margin-bottom:8px}
.partyHead .dot{width:8px;height:8px;border-radius:50%;background:rgba(255,255,255,.25)}
.partyBox.on .partyHead{color:#ffd479}
.partyBox.on .dot{background:#ffd479;box-shadow:0 0 10px #ffd479}
.partyRow{display:flex;gap:6px;align-items:center}
.partyRow input[type=text]{flex:1;min-width:0;padding:7px 9px;border-radius:9px;font-size:12px;
       border:1px solid rgba(255,255,255,.15);background:rgba(255,255,255,.06);color:#fff;outline:none}
.partyRow input[type=text]:focus{border-color:rgba(0,242,254,.55)}
.partyRow button{height:32px;border-radius:9px;font-size:11px;padding:0 12px;width:auto}
.partyHint{font-size:10px;color:rgba(255,255,255,.42);margin-top:6px;line-height:1.45}
.upload{border:2px dashed rgba(255,255,255,.16);border-radius:14px;padding:14px;text-align:center}
.upload button{background:rgba(255,255,255,.1);border-radius:10px}
.foot{margin-top:14px;font-size:10px;color:rgba(255,255,255,.3);text-align:center}
.wrap{display:block}
@media (min-width:960px){
  .wrap{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:26px;align-items:start}
  .card{max-width:1080px;padding:26px 30px}
  .list{max-height:255px}
  .lyr{max-height:220px}
  .controls{margin:18px 0}
}
</style>
</head>
<body>
<div class="card">
  <div class="row">
    <span class="brand">HK REMOTE</span>
    <span class="badge" id="party">REMOTE</span>
  </div>
  <div class="wrap">
    <div class="colA">
      <button class="install" id="install" onclick="installApp()">📲 Cài đặt ứng dụng</button>
  <div class="hint" id="hint"></div>
  <div class="title" id="title">${htmlEscape(state.songs.getOrNull(state.currentIndex)?.title ?: "No track")}</div>
  <div class="sub" id="artist">${htmlEscape(state.songs.getOrNull(state.currentIndex)?.artist ?: "")}</div>

  <input type="range" id="seek" min="0" max="100" value="0" onchange="seekTrack(this.value)">
  <div class="small"><span id="cur">0:00</span><span id="dur">0:00</span></div>

  <div class="controls">
    <button onclick="cmd('prev')">⏮</button>
    <button class="play" id="play" onclick="togglePlay()">⏸</button>
    <button onclick="cmd('next')">⏭</button>
  </div>

  <div class="row">
    <button class="mini" id="shuffle" onclick="cmd('shuffle')">🔀 Shuffle</button>
    <button class="mini" id="repeat" onclick="cmd('repeat')">🔁 Repeat</button>
  </div>

  <div class="vol">
    <span>🔈</span>
    <input type="range" id="vol" min="0" max="100" value="80" onchange="setVolume(this.value)">
    <span>🔊</span>
    <button class="mini" id="muteWeb" onclick="webMuteToggle(this)">🔊 Web</button>
  </div>
  <div class="vol" id="tvVolRow" style="display:none">
    <span>📺</span>
    <input type="range" id="tvVol" min="0" max="100" value="50" onchange="setTvVolume(this.value)">
    <span>🔊</span>
    <button class="mini" id="muteTv" onclick="tvMuteToggle(this)">🔊 TV</button>
  </div>
  <div class="row" style="margin-top:8px">
    <button class="mini" id="webPlay" onclick="toggleWebPlay()">🔊 Phát trên web</button>
    <button class="mini" onclick="window.open('http://' + location.host + '/tv', '_blank')">📺 TV Cast Bridge</button>
  </div>
  <div class="label">CAST TO TV</div>
  <div class="row" style="margin-bottom:6px">
    <button class="mini" onclick="castScan()">🔍 Quét TV</button>
    <button class="mini" id="muteTvCastBtn" onclick="castMuteToggle(this)">🔇 Tắt tiếng TV</button>
    <span id="castStat" style="font-size:11px;color:rgba(255,255,255,.5);max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">Đang quét…</span>
  </div>
  <div class="castList" id="castDevices"></div>
  <div class="label">EQ WEB</div>
  <div class="row" style="margin-bottom:4px">
    <button class="mini" id="eqBass" onclick="setEq(10, 2, -4, this)">Bass</button>
    <button class="mini eqOn" id="eqNorm" onclick="setEq(0, 0, 0, this)">Normal</button>
    <button class="mini" id="eqTreble" onclick="setEq(-4, 3, 10, this)">Treble</button>
  </div>
  <div class="row" style="margin-bottom:4px">
    <button class="mini" id="eqVocal" onclick="setEq(-2, 4, 2, this)">Vocal</button>
    <button class="mini" id="eqRock" onclick="setEq(4, -2, 4, this)">Rock</button>
    <button class="mini" id="eqPop" onclick="setEq(2, -1, 3, this)">Pop</button>
  </div>
  <audio id="webAudio" preload="auto" style="width:100%;margin-top:6px;display:none"></audio>
    </div>
    <div class="colB">
  <div class="label">PARTY MODE</div>
  <div class="partyBox" id="partyBox">
    <div class="partyHead"><span class="dot"></span><span id="partyState">ĐANG TẮT</span></div>
    <div class="partyRow">
      <input type="text" id="nick" maxlength="24" placeholder="Tên của bạn" oninput="saveNick()">
      <span id="partyToggle" class="mini" style="display:inline-flex;align-items:center;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.16);border-radius:9px;height:32px;padding:0 12px;font-size:11px">Party</span>
    </div>
    <div class="partyHint" id="partyHint">Party tắt: chỉ điện thoại xoá được bài. Bật party trên điện thoại để mọi người tự thêm bài.</div>
  </div>
  <div class="label">PLAYLIST</div>
  <input type="text" id="plSearch" placeholder="Tìm trong playlist…" oninput="renderList(lastData)" style="width:100%;padding:6px 8px;margin-bottom:6px;border-radius:8px;border:1px solid rgba(255,255,255,.15);background:rgba(255,255,255,.06);color:#fff;font-size:12px">
  <div class="row" style="margin-bottom:8px">
    <button class="mini" onclick="playSelected()">▶ Phát bài đã chọn (<span id="selCount">0</span>)</button>
    <button class="mini" onclick="clearSel()">Bỏ chọn</button>
  </div>
  <div class="list" id="list"></div>

  <div class="label">LỜI BÀI HÁT</div>
  <div class="lyr" id="lyrics">Chưa có lời bài hát</div>

  <div class="label">UPLOAD FROM THIS COMPUTER</div>
  <div class="upload">
    <input type="file" id="file" accept="audio/*" hidden onchange="upload()">
    <button onclick="document.getElementById('file').click()">📥 Choose audio file</button>
    <div class="small" id="status" style="justify-content:center;margin-top:8px"></div>
  </div>
    </div>
  </div>
  <div class="foot">HK Music Player · ${htmlEscape(state.localIp)}:${state.port}<span id="pwa"></span></div>
</div>

<script>
function fmt(s){s=Math.max(0,Math.floor(s));var m=Math.floor(s/60);var x=s%60;return m+":"+(x<10?"0"+x:x);}
var webMuted=false;
function nickName(){
  var el=document.getElementById('nick');
  return el ? (el.value||'').trim() : '';
}
function saveNick(){
  try{ localStorage.setItem('partyNick', nickName()); }catch(e){}
  renderList(lastData||{});
}
function whoParam(){
  var n=nickName();
  return n ? ('&who='+encodeURIComponent(n)) : '';
}
function webMuteToggle(btn){
  webMuted=!webMuted;
  var a=document.getElementById('webAudio');
  if(a) a.muted=webMuted;
  btn.textContent = webMuted ? '🔇 Web' : '🔊 Web';
}
function cmd(a){fetch('/'+a,{cache:'no-store'}).then(poll).catch(function(){});}
function seekTrack(v){
  fetch('/seek?pos='+v,{cache:'no-store'});
  var a=document.getElementById('webAudio');
  if(a && Math.abs(a.currentTime - v) > 0.8) a.currentTime = +v;
}
function setVolume(v){
  fetch('/volume?val='+v,{cache:'no-store'});
  var a=document.getElementById('webAudio');
  if(a) a.volume = (+v) / 100;
}
function setTvVolume(v){
  fetch('/volume?val='+v,{cache:'no-store'});
}
function tvMuteToggle(btn){
  var newMuted = btn.textContent.indexOf('Bật') >= 0;
  fetch('/mute?on='+(newMuted?1:0),{cache:'no-store'}).then(function(){ poll(); }).catch(function(){});
  btn.textContent = newMuted ? '🔊 Bật tiếng TV' : '🔇 Tắt tiếng TV';
}
var webPlay=false;
var lastData=null;
var castKey='';
var currentIndex=-1;
var webPlayIndex=-1;
var lastYtUrl=null;
var currentYtUrl=null;
var eqCtx=null,eqLow=null,eqMid=null,eqHigh=null;
function buildEq(){
  if(eqCtx) return;
  var AC = window.AudioContext || window.webkitAudioContext;
  if(!AC) return;
  try{
    var a = document.getElementById('webAudio');
    var ctx = new AC();
    var src = ctx.createMediaElementSource(a);
    eqLow = ctx.createBiquadFilter(); eqLow.type='lowshelf'; eqLow.frequency.value=180; eqLow.gain.value=0;
    eqMid = ctx.createBiquadFilter(); eqMid.type='peaking'; eqMid.frequency.value=1000; eqMid.Q.value=0.9; eqMid.gain.value=0;
    eqHigh = ctx.createBiquadFilter(); eqHigh.type='highshelf'; eqHigh.frequency.value=3800; eqHigh.gain.value=0;
    src.connect(eqLow); eqLow.connect(eqMid); eqMid.connect(eqHigh); eqHigh.connect(ctx.destination);
    eqCtx = ctx;
  }catch(e){ eqCtx = null; }
}
function setEq(l, m, h, el){
  if(!eqCtx) return;
  var t = eqCtx.currentTime;
  eqLow.gain.setTargetAtTime(l, t, 0.08);
  eqMid.gain.setTargetAtTime(m, t, 0.08);
  eqHigh.gain.setTargetAtTime(h, t, 0.08);
  var ids = ['eqBass', 'eqNorm', 'eqTreble', 'eqVocal', 'eqRock', 'eqPop'];
  for (var i = 0; i < ids.length; i++) {
    var el2 = document.getElementById(ids[i]);
    if (el2) el2.classList.toggle('eqOn', !!el && el.id === ids[i]);
  }
}
function toggleWebPlay(){
  var a=document.getElementById('webAudio');
  var btn=document.getElementById('webPlay');
  webPlay=!webPlay;
  if(webPlay){
    // Phone stays the master; this browser mirrors position/play state live.
    buildEq();
    if (eqCtx && eqCtx.state === 'suspended') eqCtx.resume();
    a.style.display='block';
    if(currentYtUrl){ a.src='/youtube'; a.setAttribute('data-idx',-1); webPlayIndex=-2; lastYtUrl=currentYtUrl; }
    else { a.src='/audio?index='+currentIndex; a.setAttribute('data-idx',currentIndex); webPlayIndex=-1; }
    a.load();
    a.currentTime = 0;
    btn.textContent='🔈 Tắt phát trên web';
    poll();
  } else {
    a.pause();
    a.removeAttribute('src');
    a.style.display='none';
    webPlayIndex=-1;
    btn.textContent='🔊 Phát trên web';
    poll();
  }
}
function togglePlay(){
  cmd('toggle');
}
var lastIndex=-1;
var lastKey='';
var selected=new Set();
function toggleSel(el){
  var v=parseInt(el.value,10);
  if(el.checked) selected.add(v); else selected.delete(v);
  document.getElementById('selCount').textContent=selected.size;
}
function clearSel(){
  selected.clear();
  var cb=document.querySelectorAll('.item input[type=checkbox]');
  for(var i=0;i<cb.length;i++) cb[i].checked=false;
  document.getElementById('selCount').textContent=0;
}
function castScan(){ fetch('/cast/scan',{cache:'no-store'}).then(poll).catch(function(){}); }
function castMuteToggle(btn){
  var muted = btn.textContent.indexOf('Bật') >= 0; // currently muted if showing 'Bật tiếng'
  var newMuted = !muted;
  fetch('/mute?on='+(newMuted?1:0),{cache:'no-store'}).then(function(){ poll(); }).catch(function(){});
  btn.textContent = newMuted ? '🔊 Bật tiếng TV' : '🔇 Tắt tiếng TV';
}
function castConnect(id){
  var btns=document.querySelectorAll('#castDevices button');
  for(var i=0;i<btns.length;i++){ if(btns[i].textContent.indexOf('Kết nối')>=0){ btns[i].textContent='Đang kết nối…'; btns[i].disabled=true; } }
  fetch('/cast/connect?id='+encodeURIComponent(id),{cache:'no-store'})
    .then(function(){ setTimeout(function(){ castKey=''; poll(); }, 1500); })
    .catch(function(){ castKey=''; poll(); });
}
function castDisconnect(){ fetch('/cast/disconnect',{cache:'no-store'}).then(poll).catch(function(){}); }
function castPlay(i){ fetch('/cast/play?index='+i,{cache:'no-store'}).then(poll).catch(function(){}); }
function renderCast(d){
  var box=document.getElementById('castDevices');
  var stat=document.getElementById('castStat');
  if(!box) return;
  var devices=d.castDevices||[];
  var connected=d.castConnected||null;
  var connectedId=d.castConnectedId||null;
  var key=''+(!!d.castAvailable)+'|'+(connected||'')+'|'+(connectedId||'')+'|'+devices.map(function(x){return x.id+'='+x.name;}).join(',');
  if(key===castKey){
    if(stat) stat.textContent = connected ? ('Đang chiếu: '+connected) : 'Chưa kết nối';
    return;
  }
  castKey=key;
  if(!d.castAvailable){
    box.innerHTML='<div class="castItem"><span class="nm">Thiết bị không hỗ trợ Cast</span></div>';
    stat.textContent='Không hỗ trợ';
    return;
  }
  stat.textContent = connected ? ('Đang chiếu: '+connected) : 'Chưa kết nối';
  if(!devices.length){
    box.innerHTML='<div class="castItem"><span class="nm">Không thấy TV — cùng Wi-Fi, bấm "Quét TV"</span></div>';
    return;
  }
  var html='';
  for(var i=0;i<devices.length;i++){
    var dev=devices[i];
    var isConn = (connectedId && connectedId===dev.id) || (!connectedId && connected===dev.name);
    html+='<div class="castItem'+(isConn?' casting':'')+'">'
      +'<span class="nm">'+escapeHtml(dev.name)+'</span>'
      +(isConn
        ? '<button class="mini" style="border-color:#ff0844;color:#ff0844" onclick="castDisconnect()">Ngắt</button>'
        : '<button class="mini" onclick="castConnect(\''+dev.id+'\')">Kết nối</button>')
      +'</div>';
  }
  box.innerHTML=html;
}
function playSelected(){
  if(selected.size===0){ return; }
  var ids=Array.from(selected).join(',');
  fetch('/selection?indices='+ids,{cache:'no-store'}).then(poll).catch(function(){});
}
function renderList(d){
  var box=document.getElementById('list');
  if(!d.playlist) return;
  var party=!!d.party;
  var me=nickName().toLowerCase();
  var key=(party?'P':'p')+me+'|'+d.playlist.map(function(s){return s.pos+':'+s.index+':'+s.title+':'+(s.by||'');}).join('|');
  if(key===lastKey){
    var items=box.children;
    for(var i=0;i<items.length;i++) items[i].className='item'+(i===d.index?' active':'');
    return;
  }
  var html='';
  for(var i=0;i<d.playlist.length;i++){
    var s=d.playlist[i];
    var pos=(s.pos===undefined?i:s.pos);
    var by=s.by?String(s.by):'';
    var canRemove=!party||!by||by.toLowerCase()===me;
    html+='<div class="item'+(pos===d.index?' active':'')+'">'
      +'<label class="pick"><input type="checkbox" value="'+s.index+'"'+(selected.has(s.index)?' checked':'')+' onclick="toggleSel(this)"></label>'
      +'<span class="nm" onclick="playPos('+pos+')">'+(i+1)+'. '+escapeHtml(s.title)
      +(by?'<span class="by">· '+escapeHtml(by)+'</span>':'')+'</span>'
      +'<span>'+fmt(s.duration)+'</span>'
      +'<button class="castBtn" title="Chiếu bài này lên TV" onclick="castPlay('+s.index+')">📺</button>'
      +(canRemove?'<button class="del" title="Bỏ khỏi danh sách phát" onclick="removeAt('+pos+')">✕</button>':'')
      +'</div>';
  }
  box.innerHTML=html;
  lastKey=key;
  document.getElementById('selCount').textContent=selected.size;
}
function escapeHtml(t){return String(t).replace(/[&<>"']/g,function(c){
  return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function playIndex(i){fetch('/play?index='+i,{cache:'no-store'}).then(poll);}
function playPos(p){fetch('/playqueue?pos='+p,{cache:'no-store'}).then(poll);}
function removeAt(p){fetch('/remove?pos='+p+whoParam(),{cache:'no-store'}).then(poll);}
var lyricFor=-1;
function loadLyrics(idx){
  lyricFor=idx;
  var box=document.getElementById('lyrics');
  fetch('/api/lyrics?index='+idx,{cache:'no-store'}).then(function(r){return r.json();})
    .then(function(d){
      if(lyricFor!==idx) return;
      var lines=d.lines||[];
      if(lines.length===0){box.innerHTML='Chưa có lời bài hát (.lrc)';return;}
      var html='';
      for(var i=0;i<lines.length;i++){
        html+='<div data-t="'+(lines[i].t||0)+'">'+escapeHtml(lines[i].text)+'</div>';
      }
      box.innerHTML=html;
    }).catch(function(){});
}
function highlightLyrics(ms){
  var box=document.getElementById('lyrics');
  var nodes=box.children;
  var active=-1;
  for(var i=0;i<nodes.length;i++){
    var t=parseInt(nodes[i].getAttribute('data-t'),10)||0;
    if(t<=ms) active=i;
    nodes[i].className='';
  }
  if(active>=0 && nodes[active]){
    nodes[active].className='on';
    if(nodes[active].scrollIntoView) nodes[active].scrollIntoView({block:'nearest'});
  }
}
function poll(){
  fetch('/api/status',{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){
    lastData=d;
    currentYtUrl = d.youtubeUrl || null;
    document.getElementById('title').textContent=d.title;
    document.getElementById('artist').textContent=d.artist+(d.album?(' · '+d.album):'');
        var wa=document.getElementById('webAudio');
    var playingNow;
    if(webPlay){ playingNow=!wa.paused; } else { playingNow=!!d.playing; }
    document.getElementById('play').textContent=playingNow?'⏸':'▶';
    if(webPlay){
      var a=document.getElementById('webAudio');
      if(d.youtubeUrl){
        if(lastYtUrl !== d.youtubeUrl){
          lastYtUrl = d.youtubeUrl;
          webPlayIndex = -2;
          a.src = '/youtube';
          a.load();
          a.currentTime = d.position||0;
          if(d.playing) a.play().catch(function(){});
        } else {
          if(d.playing && a.paused) a.play().catch(function(){});
          else if(!d.playing && !a.paused) a.pause();
          if(d.playing && Math.abs(a.currentTime - d.position) > 1.5){ a.currentTime = d.position; }
        }
      } else if(typeof d.songIndex==='number' && d.songIndex>=0){
        if(webPlayIndex!==d.songIndex){
          a.setAttribute('data-idx',d.songIndex);
          a.src='/audio?index='+d.songIndex;
          a.load();
          a.currentTime = d.position||0;
          if(d.playing) a.play().catch(function(){});
          webPlayIndex=d.songIndex;
        } else {
          if(d.playing && a.paused) a.play().catch(function(){});
          else if(!d.playing && !a.paused) a.pause();
          if(d.playing && Math.abs(a.currentTime - d.position) > 1.5){ a.currentTime = d.position; }
        }
      }
      lastYtUrl = d.youtubeUrl || null;
    }
    var seek=document.getElementById('seek');
    seek.max=d.duration; seek.value=d.position;
    document.getElementById('cur').textContent=fmt(d.position);
    document.getElementById('dur').textContent=fmt(d.duration);
    document.getElementById('vol').value=d.volume;
    var av = document.getElementById('webAudio');
    if (av) av.volume = d.volume / 100;
    document.getElementById('shuffle').style.color=d.shuffle?'#00f2fe':'';
    document.getElementById('repeat').style.color=d.repeat?'#00f2fe':'';
    document.getElementById('party').textContent=d.clients>0?('REMOTE · '+d.clients+' 📱'):'REMOTE';
    document.getElementById('party').classList.remove('offline');
    var pBox=document.getElementById('partyBox');
    if(pBox){
      pBox.classList.toggle('on', !!d.party);
      document.getElementById('partyState').textContent=d.party?'ĐANG BẬT':'ĐANG TẮT';
      document.getElementById('partyToggle').textContent=d.party?'Party BẬT':'Party TẮT';
      document.getElementById('partyHint').textContent=d.party
        ? 'Party đang bật: bạn thêm bài được, chỉ xoá được bài bạn thêm. Bật/tắt party trên điện thoại.'
        : 'Party tắt: chỉ điện thoại xoá được bài. Bật party trên điện thoại để mọi người tự thêm bài.';
    }
    offline=false;
    renderList(d);
    renderCast(d);
    var tvr=document.getElementById('tvVolRow');
    if(tvr) tvr.style.display = d.isCasting ? 'flex' : 'none';
    currentIndex=d.index||0;
    if(typeof d.songIndex==='number' && d.songIndex>=0 && d.songIndex!==lyricFor) loadLyrics(d.songIndex);
    highlightLyrics(d.position*1000);
  }).catch(function(){setOffline();});
}
var offline=false;
function setOffline(){
  if(offline) return;
  offline=true;
  var badge=document.getElementById('party');
  badge.textContent='OFFLINE';
  badge.classList.add('offline');
  document.getElementById('title').textContent='Mất kết nối';
  document.getElementById('artist').textContent='Bật lại server trong app HK, trang sẽ tự kết nối lại';
  document.getElementById('play').textContent='▶';
  document.getElementById('list').innerHTML='';
  lastKey='';
}
function installApp(){
  if(deferredPrompt){
    deferredPrompt.prompt();
    deferredPrompt.userChoice.then(function(res){
      if(res.outcome==='accepted') document.getElementById('install').style.display='none';
      deferredPrompt=null;
    });
    return;
  }
  var h=document.getElementById('hint');
  h.style.display='block';
  if(!window.isSecureContext){
    h.innerHTML='Trên chính điện thoại này: mở <b>http://localhost:${state.port}</b> '+
      '(nút <b>Mở Web Player</b> trong app). Chrome coi localhost là HTTPS nên cài được PWA và chạy offline.<br>'+
      'Từ máy khác: cần <b>HTTPS</b>; cách nhanh là cắm USB, chạy '+
      '<b>adb reverse tcp:${state.port} tcp:${state.port}</b> rồi mở '+
      '<b>http://localhost:${state.port}</b>. Hoặc menu ⋮ → “Thêm vào màn hình chính” để tạo lối tắt.';
  } else if(/iphone|ipad|ipod/i.test(navigator.userAgent)){
    h.innerHTML='Trên iPhone: bấm nút <b>Chia sẻ</b> → <b>Thêm vào màn hình chính</b>.';
  } else {
    h.innerHTML='Bấm menu <b>⋮</b> của Chrome → <b>Cài đặt ứng dụng</b> / <b>Thêm vào màn hình chính</b>.';
  }
}
var deferredPrompt=null;
window.addEventListener('beforeinstallprompt',function(e){
  e.preventDefault();
  deferredPrompt=e;
  document.getElementById('install').style.display='block';
});
window.addEventListener('appinstalled',function(){
  document.getElementById('install').style.display='none';
  document.getElementById('hint').style.display='none';
});
(function(){
  var m=location.hash.match(/sel=([0-9,]+)/);
  if(!m) return;
  m[1].split(',').forEach(function(v){
    var n=parseInt(v,10);
    if(!isNaN(n)) selected.add(n);
  });
  document.getElementById('selCount').textContent=selected.size;
})();
if('serviceWorker' in navigator){
  navigator.serviceWorker.register('/sw.js').then(function(){
    document.getElementById('pwa').textContent=' · PWA sẵn sàng ✓';
  }).catch(function(){
    document.getElementById('pwa').textContent=' · PWA lỗi đăng ký';
  });
} else {
  document.getElementById('pwa').textContent=' · PWA cần localhost/HTTPS';
}
var standalone=window.matchMedia('(display-mode: standalone)').matches||window.navigator.standalone;
if(standalone){
  document.querySelector('.brand').textContent='HK · APP';
} else {
  document.getElementById('install').style.display='block';
}
function upload(){
  var input=document.getElementById('file');
  if(!input.files.length) return;
  var file=input.files[0];
  var status=document.getElementById('status');
  status.textContent='Uploading '+file.name+'…';
  fetch('/upload?name='+encodeURIComponent(file.name)+whoParam(),{method:'POST',body:file})
    .then(function(r){return r.json();})
    .then(function(d){status.textContent=d.ok?'Uploaded ✓':'Upload failed';setTimeout(poll,500);})
    .catch(function(){status.textContent='Upload failed';});
}
try{ var sn=localStorage.getItem('partyNick'); if(sn) document.getElementById('nick').value=sn; }catch(e){}
setInterval(poll,1000);
poll();
document.addEventListener('keydown', function(e){
  var t=e.target.tagName;
  if(t==='INPUT'||t==='TEXTAREA') return;
  if(e.code==='Space'){ e.preventDefault(); togglePlay(); }
  else if(e.code==='ArrowRight'){ seekTrack(Math.min((document.getElementById('seek').value||0)*1+5, (document.getElementById('seek').max||100)*1)); }
  else if(e.code==='ArrowLeft'){ seekTrack(Math.max((document.getElementById('seek').value||0)*1-5, 0)); }
  else if(e.code==='ArrowUp'){ var v=Math.min(100,(document.getElementById('vol').value||80)*1+5); document.getElementById('vol').value=v; setVolume(v); }
  else if(e.code==='ArrowDown'){ var v=Math.max(0,(document.getElementById('vol').value||80)*1-5); document.getElementById('vol').value=v; setVolume(v); }
  else if(e.code==='KeyM'){ webMuteToggle(document.getElementById('muteWeb')); }
});
</script>
</body>
</html>
        """.trimIndent()
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

    /**
     * Fullscreen 10-foot theater for TVs with a browser: streams the current
     * track from /audio, renders the synced lyrics, a live Web Audio spectrum
     * and a Biquad-based EQ (Bass / Normal / Treble presets). Behaves like a
     * cast target — the phone drives /next and the page follows on its own.
     */
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
}
