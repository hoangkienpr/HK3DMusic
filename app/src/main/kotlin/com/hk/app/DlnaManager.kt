package com.hk.app

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** A TV as shown in the cast picker, whatever protocol it speaks. */
data class CastTarget(
    val id: String,
    val name: String,
    val isDlna: Boolean
)

/**
 * Minimal DLNA / UPnP AV control point written from scratch (no third-party lib).
 *
 * Discovery: SSDP M-SEARCH for `urn:schemas-upnp-org:device:MediaRenderer:1`,
 * then the device description XML is fetched to read `friendlyName` and the
 * `AVTransport` control URL.
 *
 * Playback: the TV pulls the audio straight from the app's own HTTP server, so
 * all we do is push `SetAVTransportURI` (with DIDL-Lite metadata) + `Play`,
 * then poll `GetTransportInfo` / `GetPositionInfo` to mirror the state in the UI.
 */
class DlnaManager(private val context: Context? = null) {

    data class Device(
        val id: String,
        val name: String,
        val location: String,
        val controlUrl: String,
        val renderingControlUrl: String? = null,
        val model: String = ""
    )

    var devices by mutableStateOf<List<Device>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var connectedName by mutableStateOf<String?>(null)
        private set
    var connectedId by mutableStateOf<String?>(null)
        private set
    var transportPlaying by mutableStateOf(false)
        private set
    var positionSeconds by mutableStateOf(0)
        private set
    var durationSeconds by mutableStateOf(0)
        private set
    var volume by mutableStateOf(50)
        private set
    var muted by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    val isCasting: Boolean get() = connectedName != null

    private var current: Device? = null
    private var currentTrackId: String? = null
    private var polling = false
    // Last time we sent Play/Pause. The renderer can still report PLAYING for a
    // moment after a Pause, so polling must not flip the UI back.
    private var lastCommandAt = 0L
    // Some TVs resume on their own a few seconds after a Pause (they drop the
    // paused stream and restart it). Remember the user's intent so polling can
    // re-assert Pause instead of reporting it as playing again.
    private var pausedAt = 0L
    private var pauseRetries = 0

    // All SOAP traffic runs here so the UI thread never blocks on a slow TV.
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dlna-control").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ discovery

    fun startDiscovery() {
        if (scanning) return
        scanning = true
        Thread({
            val lock = try {
                val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifi?.createMulticastLock("hk_dlna_ssdp")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            } catch (_: Exception) { null }
            try {
            val found = LinkedHashMap<String, Device>()
            val locations = LinkedHashSet<String>()
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 400
                    socket.broadcast = true
                    val payload = ssdpRequest().toByteArray(Charsets.US_ASCII)
                    val multicast = InetAddress.getByName("239.255.255.250")
                    // Some TVs ignore the first M-SEARCH (radio just woke up), so
                    // keep re-sending while we listen and stay open long enough
                    // for a slow renderer to answer.
                    val deadline = System.currentTimeMillis() + 4000
                    val buffer = ByteArray(4096)
                    while (System.currentTimeMillis() < deadline) {
                        runCatching {
                            socket.send(DatagramPacket(payload, payload.size, multicast, 1900))
                        }
                        // Listen for ~700ms after each search.
                        val listenUntil = System.currentTimeMillis() + 700
                        while (System.currentTimeMillis() < listenUntil) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val location = text.lineSequence()
                                .firstOrNull { it.trim().lowercase(Locale.US).startsWith("location:") }
                                ?.substringAfter(':')
                                ?.trim()
                            if (!location.isNullOrEmpty()) locations.add(location)
                        }
                    }
                }
            }.onFailure { lastError = it.message }

            for (location in locations) {
                val device = fetchDescription(location) ?: continue
                found[device.id] = device
            }
            // Merge instead of replace: a TV that is busy streaming (or a flaky
            // Wi-Fi moment) often skips an M-SEARCH round. Dropping it would make
            // the list look empty until the next successful scan.
            val merged = LinkedHashMap<String, Device>()
            devices.forEach { merged[it.id] = it }
            found.forEach { (id, device) -> merged[id] = device }
            current?.let { merged[it.id] = it }
            if (merged.isNotEmpty()) {
                devices = merged.values.sortedBy { it.name.lowercase(Locale.US) }
            }
            } finally {
                try { if (lock?.isHeld == true) lock.release() } catch (_: Exception) {}
                scanning = false
            }
        }, "dlna-ssdp").apply { isDaemon = true }.start()
    }

    private fun ssdpRequest(): String = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    private fun fetchDescription(location: String): Device? = runCatching {
        parseDeviceDescription(httpGet(location), location)
    }.getOrNull()

    internal fun parseDeviceDescription(xml: String, location: String): Device? {
        val base = URL(location)
        val friendly = Regex("<friendlyName[^>]*>(.*?)</friendlyName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        val model = Regex("<modelName[^>]*>(.*?)</modelName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        val udn = Regex("<UDN[^>]*>(.*?)</UDN>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        if (udn.isEmpty()) return null

        var controlUrl = ""
        var renderingControlUrl: String? = null
        val serviceRegex = Regex("<service[^>]*>(.*?)</service>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in serviceRegex.findAll(xml)) {
            val block = m.groupValues[1]
            val type = Regex("<serviceType[^>]*>(.*?)</serviceType>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val ctrl = Regex("<controlURL[^>]*>(.*?)</controlURL>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (ctrl.isEmpty()) continue
            val resolved = URL(base, ctrl).toString()
            if (type.contains("AVTransport", ignoreCase = true) && controlUrl.isEmpty()) {
                controlUrl = resolved
            } else if (type.contains("RenderingControl", ignoreCase = true) && renderingControlUrl == null) {
                renderingControlUrl = resolved
            }
        }
        if (controlUrl.isEmpty()) return null
        return Device(
            id = udn,
            name = friendly.ifEmpty { "DLNA Renderer" },
            location = location,
            controlUrl = controlUrl,
            renderingControlUrl = renderingControlUrl,
            model = model
        )
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.instanceFollowRedirects = true
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------------------------ session

    fun connect(device: Device) {
        pausedAt = 0L
        pauseRetries = 0
        current = device
        currentTrackId = null
        connectedName = device.name
        connectedId = device.id
        positionSeconds = 0
        durationSeconds = 0
        transportPlaying = false
        lastError = null
    }

    fun disconnect() {
        pausedAt = 0L
        pauseRetries = 0
        val device = current
        current = null
        currentTrackId = null
        connectedName = null
        connectedId = null
        transportPlaying = false
        positionSeconds = 0
        durationSeconds = 0
        if (device != null) {
            io.execute { runCatching { soap(device, "Stop", "<InstanceID>0</InstanceID>") } }
        }
    }

    fun release() {
        disconnect()
    }

    /** Loads [url] on the renderer and starts playback. Re-sending the same track only resumes. */
    fun playTrack(
        trackId: String,
        url: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?
    ) {
        val device = current ?: return
        if (currentTrackId == trackId) {
            play()
            return
        }
        currentTrackId = trackId
        positionSeconds = 0
        val didl = buildDidl(url, title, artist, album, artworkUrl)
        io.execute {
            runCatching {
                soap(
                    device,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(url)}</CurrentURI>" +
                        "<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>"
                )
                // Some TVs need a beat before accepting Play right after the URI change.
                Thread.sleep(250)
                soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                transportPlaying = true
                lastCommandAt = System.currentTimeMillis()
                pausedAt = 0L
                pauseRetries = 0
            }
        }
    }

    fun play() {
        val device = current ?: return
        pausedAt = 0L
        transportPlaying = true
        lastCommandAt = System.currentTimeMillis()
        io.execute {
            runCatching {
                soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            }
        }
    }

    fun pause() {
        val device = current ?: return
        pausedAt = System.currentTimeMillis()
        pauseRetries = 0
        transportPlaying = false
        lastCommandAt = System.currentTimeMillis()
        io.execute {
            runCatching {
                soap(device, "Pause", "<InstanceID>0</InstanceID>")
            }
        }
    }

    fun seekTo(milliseconds: Long) {
        val device = current ?: return
        val target = formatTime(milliseconds)
        positionSeconds = (milliseconds / 1000L).toInt()
        io.execute {
            runCatching {
                soap(
                    device,
                    "Seek",
                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>"
                )
            }
        }
    }

    fun setTvVolume(percent: Int) {
        val device = current ?: return
        val url = device.renderingControlUrl ?: return
        val clamped = percent.coerceIn(0, 100)
        volume = clamped
        io.execute {
            runCatching {
                soap(
                    device,
                    "SetVolume",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$clamped</DesiredVolume>",
                    RENDERING_CONTROL
                )
            }
        }
    }

    fun setTvMute(mute: Boolean) {
        val device = current ?: return
        if (device.renderingControlUrl == null) return
        muted = mute
        io.execute {
            runCatching {
                soap(
                    device,
                    "SetMute",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (mute) 1 else 0}</DesiredMute>",
                    RENDERING_CONTROL
                )
            }
        }
    }

    /** Pulls transport state + position from the renderer. Safe to call from the UI twice a second. */
    fun poll() {
        val device = current ?: return
        if (polling) return
        polling = true
        io.execute {
            try {
                val transport = soap(device, "GetTransportInfo", "<InstanceID>0</InstanceID>")
                val state = transport?.let { tagValue(it, "CurrentTransportState") }.orEmpty()
                val tvPlaying = state.equals("PLAYING", ignoreCase = true)
                // The user asked for pause: if the renderer restarted the stream
                // by itself, put it back to pause (bounded retries).
                if (pausedAt > 0L && tvPlaying &&
                    System.currentTimeMillis() - pausedAt < 15000 && pauseRetries < 4
                ) {
                    pauseRetries++
                    runCatching { soap(device, "Pause", "<InstanceID>0</InstanceID>") }
                    transportPlaying = false
                } else if (state.isNotEmpty() && System.currentTimeMillis() - lastCommandAt > 2000) {
                    transportPlaying = tvPlaying
                }

                val position = soap(device, "GetPositionInfo", "<InstanceID>0</InstanceID>")
                if (position != null) {
                    positionSeconds = parseTime(tagValue(position, "RelTime"))
                    val total = parseTime(tagValue(position, "TrackDuration"))
                    if (total > 0) durationSeconds = total
                }

                if (device.renderingControlUrl != null) {
                    val vol = soap(device, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>", RENDERING_CONTROL)
                    if (vol != null) {
                        val v = tagValue(vol, "CurrentVolume").toIntOrNull()
                        if (v != null) volume = v.coerceIn(0, 100)
                    }
                    val m = soap(device, "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>", RENDERING_CONTROL)
                    if (m != null) {
                        muted = tagValue(m, "CurrentMute").equals("1") || tagValue(m, "CurrentMute").equals("true", ignoreCase = true)
                    }
                }
            } finally {
                polling = false
            }
        }
    }

    // ------------------------------------------------------------------ SOAP

    private fun soap(device: Device, action: String, arguments: String, service: String = AV_TRANSPORT): String? = runCatching {
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$service\">$arguments</u:$action>" +
            "</s:Body></s:Envelope>"

        val url = if (service == RENDERING_CONTROL) (device.renderingControlUrl ?: return null) else device.controlUrl
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty(
            "SOAPACTION",
            "\"$service#$action\""
        )
        connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()

        if (code !in 200..299) lastError = "TV từ chối lệnh $action (HTTP $code)"
        body
    }.getOrElse {
        lastError = it.message
        null
    }

    private fun buildDidl(
        url: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?
    ): String {
        val art = if (artworkUrl != null) {
            "<upnp:albumArtURI>${xmlEscape(artworkUrl)}</upnp:albumArtURI>"
        } else {
            ""
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<dc:creator>${xmlEscape(artist)}</dc:creator>" +
            "<upnp:artist>${xmlEscape(artist)}</upnp:artist>" +
            "<upnp:album>${xmlEscape(album)}</upnp:album>" +
            art +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/mpeg:*\">${xmlEscape(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    internal fun tagValue(xml: String, tag: String): String {
        val match = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }

    internal fun parseTime(value: String): Int {
        val parts = value.substringBefore('.').split(':')
        if (parts.size != 3) return 0
        val hours = parts[0].toIntOrNull() ?: 0
        val minutes = parts[1].toIntOrNull() ?: 0
        val seconds = parts[2].toIntOrNull() ?: 0
        return hours * 3600 + minutes * 60 + seconds
    }

    internal fun formatTime(milliseconds: Long): String {
        val total = (milliseconds / 1000L).coerceAtLeast(0)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            total / 3600,
            (total % 3600) / 60,
            total % 60
        )
    }

    companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    }

    internal fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
