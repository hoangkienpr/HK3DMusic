package com.hk.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext

/**
 * Real Chromecast / Google Cast support.
 *
 * Device discovery goes through [MediaRouter] (the same provider the system
 * cast dialog uses), and playback is handed to [CastPlayer] once a route is
 * selected. Local files are cast over the embedded HTTP server, so the
 * receiver never needs file-system access.
 */
class CastManager(private val context: Context) {

    data class Device(
        val id: String,
        val name: String,
        val route: MediaRouter.RouteInfo
    )

    var available by mutableStateOf(false)
        private set

    var devices by mutableStateOf<List<Device>>(emptyList())
        private set

    var connectedName by mutableStateOf<String?>(null)
        private set

    var castPlayer by mutableStateOf<CastPlayer?>(null)
        private set

    var sessionAvailable by mutableStateOf(false)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    val isCasting: Boolean
        get() = connectedName != null && sessionAvailable

    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var watching = false

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()

        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            val device = devices.firstOrNull { it.id == route.id }
            connectedName = device?.name ?: route.name
            ensurePlayer()
        }

        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            connectedName = null
            releasePlayer()
        }
    }

    fun initialize() {
        if (available) return
        runCatching {
            val ctx = CastContext.getSharedInstance(context)
            castContext = ctx
            android.util.Log.d("CastManager", "CastContext initialized, receiver app id: ${ctx.castOptions?.receiverApplicationId}")
            // Use a broader selector that includes all cast devices
            selector = MediaRouteSelector.Builder()
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build()
            router = MediaRouter.getInstance(context)
            available = true
            lastError = null
            android.util.Log.d("CastManager", "Cast available, router: $router")
        }.onFailure {
            available = false
            lastError = it.message
            android.util.Log.e("CastManager", "Cast init failed", it)
        }
    }

    fun startDiscovery() {
        if (!available) initialize()
        val router = router ?: return
        val selector = selector ?: return
        if (!watching) {
            runCatching {
                router.addCallback(
                    selector,
                    callback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
                )
                watching = true
            }
        }
        refresh()
    }

    fun stopDiscovery() {
        val router = router ?: return
        if (!watching) return
        runCatching { router.removeCallback(callback) }
        watching = false
    }

    /** Cast an arbitrary HTTP stream (e.g. video from our web server) to the connected route. */
    fun playToCast(url: String, title: String, mimeType: String) {
        val player = ensurePlayer() ?: return
        if (!sessionAvailable) return
        runCatching {
            val item = androidx.media3.common.MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist("HK Music Player")
                        .build()
                )
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }.onFailure { lastError = it.message }
    }

    fun connect(device: Device) {
        val player = ensurePlayer()
        if (player == null) {
            lastError = "Cast session is not available on this device"
            return
        }
        connectedName = device.name
        runCatching { device.route.select() }
            .onFailure { lastError = it.message }
    }

    fun disconnect() {
        sessionAvailable = false
        val player = castPlayer
        runCatching { player?.stop() }
        releasePlayer()
        connectedName = null
        val currentRouter = router
        if (currentRouter != null) {
            runCatching { currentRouter.selectRoute(currentRouter.defaultRoute) }
        }
    }

    private fun ensurePlayer(): CastPlayer? {
        val ctx = castContext ?: return null
        if (castPlayer == null) {
            runCatching {
                castPlayer = CastPlayer(ctx).apply {
                    setSessionAvailabilityListener(object : SessionAvailabilityListener {
                        override fun onCastSessionAvailable() {
                            lastError = null
                            sessionAvailable = true
                        }

                        override fun onCastSessionUnavailable() {
                            connectedName = null
                            sessionAvailable = false
                        }
                    })
                }
            }.onFailure { lastError = it.message }
        }
        return castPlayer
    }

    private fun releasePlayer() {
        runCatching { castPlayer?.release() }
        castPlayer = null
    }

    private fun refresh() {
        val router = router ?: return
        val current = router.selectedRoute
        connectedName = if (current != null && !current.isDefault) current.name else null

        val list = router.routes
            .filter { route ->
                route.isEnabled &&
                    !route.isDefault
            }
            .map { Device(it.id, it.name, it) }

        android.util.Log.d("CastManager", "Found ${list.size} routes: ${list.map { it.name }}")
        android.util.Log.d("CastManager", "All routes: ${router?.routes?.map { it.name }}")
        devices = list.sortedBy { it.name.lowercase() }
    }

    fun release() {
        sessionAvailable = false
        stopDiscovery()
        releasePlayer()
        connectedName = null
        devices = emptyList()
    }
}
