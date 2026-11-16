package com.hk.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Thin, null-safe wrapper around the platform audio effects.
 *
 * The effects are bound to the audio session of the real playback engine that
 * lives in [MusicService]; creating them here works because the service and
 * the UI share the same process (and the same UID).
 */
class AudioFxEngine(sessionId: Int) {

    data class Band(
        val index: Short,
        val centerHz: Int,
        val levelMb: Short,
        val minMb: Short,
        val maxMb: Short
    ) {
        val label: String
            get() = if (centerHz >= 1000) {
                String.format(Locale.US, "%.0fk", centerHz / 1000f)
            } else {
                "$centerHz"
            }

        val levelDb: Float get() = levelMb / 100f
    }

    private val equalizer: Equalizer? = runCatching {
        Equalizer(0, sessionId).apply { enabled = true }
    }.getOrNull()

    private val bassBoost: BassBoost? = runCatching {
        BassBoost(0, sessionId)
    }.getOrNull()

    private val virtualizer: Virtualizer? = runCatching {
        Virtualizer(0, sessionId)
    }.getOrNull()

    val eqAvailable: Boolean get() = equalizer != null

    val bassAvailable: Boolean get() = bassBoost != null

    val virtualizerAvailable: Boolean get() = virtualizer != null

    private val levelRange: ShortArray? = runCatching {
        equalizer?.bandLevelRange
    }.getOrNull()

    fun bands(): List<Band> {
        val eq = equalizer ?: return emptyList()
        val count = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
        val range = levelRange ?: shortArrayOf(-1500, 1500)
        return (0 until count).mapNotNull { index ->
            runCatching {
                val band = index.toShort()
                Band(
                    index = band,
                    centerHz = eq.getCenterFreq(band) / 1000,
                    levelMb = eq.getBandLevel(band),
                    minMb = range[0],
                    maxMb = range[1]
                )
            }.getOrNull()
        }
    }

    /** [fraction] is 0..1 across the device band range. */
    fun setBandFraction(band: Short, fraction: Float) {
        val eq = equalizer ?: return
        val range = levelRange ?: return
        val clamped = fraction.coerceIn(0f, 1f)
        val level = (range[0] + (range[1] - range[0]) * clamped).toInt().toShort()
        runCatching { eq.setBandLevel(band, level) }
    }

    fun setBassBoost(enable: Boolean) {
        runCatching {
            bassBoost?.let {
                it.setStrength(1000.toShort())
                it.enabled = enable
            }
        }
    }

    fun setVirtualizer(enable: Boolean) {
        runCatching {
            virtualizer?.let {
                it.setStrength(800.toShort())
                it.enabled = enable
            }
        }
    }

    fun release() {
        runCatching { equalizer?.enabled = false; equalizer?.release() }
        runCatching { bassBoost?.enabled = false; bassBoost?.release() }
        runCatching { virtualizer?.enabled = false; virtualizer?.release() }
    }
}

/** While audio is playing, tolerate this much silence before re-arming the
 * native effect. Paused playback never triggers a retry. */
private const val VISUALIZER_SILENT_MS = 4_000L

/** One frame of real audio data: time domain plus frequency bins. */
data class AudioLevels(
    val waveform: List<Float> = List(20) { 0f },
    val spectrum: List<Float> = List(20) { 0f }
)

/**
 * Attaches a real [Visualizer] to the playback audio session and returns both
 * the waveform (for bar/circle styles) and the FFT spectrum (for the column and
 * particle styles).
 *
 * Android only delivers buffers once the audio sink is actually open, and a
 * handed-over session can stay silent for a while after a track change, a
 * buffering stall or a pause. Re-creating the effect for every retry (the old
 * behaviour) released and re-attached the native effect in a tight loop, which
 * made the graph flicker, drop to flat and sometimes never recover.
 *
 * This version keeps one instance alive for the whole session:
 *  - while paused it never touches the effect, so playback resumes instantly;
 *  - a single watchdog re-arms the live instance first, and only re-creates it
 *    with a growing back-off when the sink really looks lost;
 *  - the last frame stays on screen while re-attaching instead of blanking.
 */
@Composable
fun rememberAudioLevels(
    sessionId: Int,
    enabled: Boolean,
    isPlaying: Boolean = true,
    bars: Int = 20
): AudioLevels {
    var levels by remember {
        mutableStateOf(AudioLevels(List(bars) { 0f }, List(bars) { 0f }))
    }
    val playing = rememberUpdatedState(isPlaying)

    DisposableEffect(sessionId, enabled, bars, isPlaying) {
        // Only attach once playback is actually running. On several devices an
        // effect created before the audio sink opens stays on the session but
        // never receives a buffer, and it then blocks later re-attachments.
        if (sessionId <= 0 || !enabled || !isPlaying) {
            return@DisposableEffect onDispose { }
        }

        // One handler per attachment: disposing cancels every pending watchdog
        // and post without touching other compositions.
        val handler = Handler(Looper.getMainLooper())
        var disposed = false
        var hasData = false
        var lastDataAt = 0L
        var failures = 0
        var visualizer: Visualizer? = null
        // Per-attachment auto-gain for the spectrum: the hottest bin drives the
        // normalization (instant attack, ~15% decay per frame), so every column
        // moves instead of only a few mid-range bins.
        var gain = 0.25f

        fun waveformOf(waveform: ByteArray): List<Float> {
            val chunk = max(1, waveform.size / bars)
            val computed = ArrayList<Float>(bars)
            for (i in 0 until bars) {
                val from = i * chunk
                val to = min(waveform.size, from + chunk)
                if (from >= to) {
                    computed.add(0f)
                    continue
                }
                var sum = 0.0
                for (j in from until to) {
                    val sample = (waveform[j].toInt() and 0xFF) - 128
                    sum += (sample * sample).toDouble()
                }
                val rms = sqrt(sum / (to - from))
                computed.add((rms / 128.0).toFloat().coerceIn(0f, 1f))
            }
            return computed
        }

        fun spectrumOf(fft: ByteArray): List<Float> {
            val bins = fft.size / 2
            if (bins <= 1) return List(bars) { 0f }
            val computed = ArrayList<Float>(bars)
            for (i in 0 until bars) {
                // Log spacing: the low half of the spectrum is where music lives.
                val lo = (bins.toDouble().pow(i / bars.toDouble()) - 1)
                    .toInt().coerceIn(0, bins - 1)
                val hi = (bins.toDouble().pow((i + 1) / bars.toDouble()) - 1)
                    .toInt().coerceIn(lo + 1, bins)
                var peak = 0f
                for (bin in lo until hi) {
                    val re = (fft[bin * 2].toInt() and 0xFF) - 128
                    val im = (fft[bin * 2 + 1].toInt() and 0xFF) - 128
                    val magnitude = sqrt((re * re + im * im).toDouble()).toFloat() / 128f
                    if (magnitude > peak) peak = magnitude
                }
                // sqrt compresses the dynamic range so quiet highs stay visible.
                computed.add(sqrt(peak).coerceIn(0f, 1f))
            }
            // Normalize against the running peak bin (fast attack, slow release).
            val bandPeak = computed.maxOrNull() ?: 0f
            val next = max(bandPeak, gain * 0.85f).coerceIn(0.05f, 1f)
            gain = next
            return computed.map { (it / next).coerceIn(0f, 1f) }
        }

        val listener = object : Visualizer.OnDataCaptureListener {
            var waveformFrames = 0
            var fftFrames = 0

            override fun onWaveFormDataCapture(
                visualizer: Visualizer?,
                waveform: ByteArray?,
                samplingRate: Int
            ) {
                if (waveform == null || waveform.isEmpty()) return
                if (!hasData) Log.i("AudioFx", "first waveform buffer size=${waveform.size} rate=$samplingRate")
                hasData = true
                failures = 0
                lastDataAt = SystemClock.elapsedRealtime()
                val computed = waveformOf(waveform)
                if (++waveformFrames % 600 == 0) {
                    Log.i("AudioFx", "waveform frames=$waveformFrames peak=${computed.maxOrNull()}")
                }
                handler.post { if (!disposed) levels = levels.copy(waveform = computed) }
            }

            override fun onFftDataCapture(
                visualizer: Visualizer?,
                fft: ByteArray?,
                samplingRate: Int
            ) {
                if (fft == null || fft.size < 4) return
                if (!hasData) Log.i("AudioFx", "first fft buffer size=${fft.size} rate=$samplingRate")
                hasData = true
                failures = 0
                lastDataAt = SystemClock.elapsedRealtime()
                val computed = spectrumOf(fft)
                if (++fftFrames % 600 == 0) {
                    Log.i("AudioFx", "fft frames=$fftFrames peak=${computed.maxOrNull()}")
                }
                handler.post { if (!disposed) levels = levels.copy(spectrum = computed) }
            }
        }

        fun releaseVisualizer() {
            val current = visualizer ?: return
            visualizer = null
            runCatching { current.enabled = false }
            runCatching { current.release() }
        }

        fun attachVisualizer(): Boolean {
            releaseVisualizer()
            val created = runCatching { Visualizer(sessionId) }.getOrNull() ?: run {
                Log.w("AudioFx", "Visualizer() failed for session=$sessionId")
                return false
            }
            fun configure() {
                val range = Visualizer.getCaptureSizeRange()
                created.captureSize = (range.getOrNull(0) ?: 128).coerceAtLeast(128)
                created.setDataCaptureListener(
                    listener,
                    max(1, Visualizer.getMaxCaptureRate() / 2),
                    true,
                    true
                )
                created.enabled = true
            }
            var error = runCatching { configure() }.exceptionOrNull()
            if (error != null) {
                // A stale effect from an earlier attach can still be ENABLED on
                // this session, which makes setCaptureSize() throw. Disable it
                // and try once more before giving up on this cycle.
                runCatching { created.enabled = false }
                error = runCatching { configure() }.exceptionOrNull()
            }
            if (error != null) {
                Log.w("AudioFx", "Visualizer attach failed for session=$sessionId", error)
                runCatching { created.release() }
                return false
            }
            Log.i("AudioFx", "Visualizer attached session=$sessionId capture=${created.captureSize} rate=${Visualizer.getMaxCaptureRate()}")
            visualizer = created
            return true
        }

        // Single watchdog: checks liveness and repairs the attachment. It used
        // to be a DisposableEffect key, which tore the effect down every retry.
        fun watchdog() {
            if (disposed) return
            var delay = 3_000L
            val now = SystemClock.elapsedRealtime()
            val silent = !hasData || now - lastDataAt > VISUALIZER_SILENT_MS

            if (!playing.value) {
                // Paused/stopped: no buffers are expected, so leave the effect
                // untouched. Playback (or a new track) resumes it instantly.
                delay = 3_000L
            } else if (silent) {
                hasData = false
                failures++
                Log.w("AudioFx", "visualizer silent while playing (failures=$failures visualizer=${visualizer != null})")
                if (visualizer != null && failures % 3 != 0) {
                    // Cheap re-arm on the live instance first.
                    runCatching { visualizer?.enabled = false }
                    runCatching { visualizer?.enabled = true }
                    delay = 1_500L
                } else {
                    // Re-create with a growing back-off so we never spin the
                    // audio effect while the sink is still opening.
                    attachVisualizer()
                    delay = (2_000L + failures * 750L).coerceAtMost(8_000L)
                }
            }

            handler.postDelayed({ watchdog() }, delay)
        }

        // Give the player a moment to open the audio sink, then attach. The
        // watchdog repairs it if the sink was still warming up.
        handler.postDelayed({ attachVisualizer() }, 500L)
        handler.postDelayed({ watchdog() }, 500L + VISUALIZER_SILENT_MS)

        onDispose {
            disposed = true
            handler.removeCallbacksAndMessages(null)
            releaseVisualizer()
        }
    }

    return levels
}
