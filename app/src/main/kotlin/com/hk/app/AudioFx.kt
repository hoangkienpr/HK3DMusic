package com.hk.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** How many times attach is retried while waiting for the first buffer.
 * The Visualizer can only deliver once the audio sink actually opens (i.e.
 * when playback starts), so a short cap here kills the visualizer for the
 * whole pinned-session lifetime when the app sits paused at startup. */
private const val MAX_VISUALIZER_ATTEMPTS = 400

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
 * Android requires the RECORD_AUDIO permission for this API since Android 9.
 * When the effect cannot be created the values stay flat instead of showing
 * fabricated animation.
 *
 * The session can be handed over before the player has actually opened its
 * audio sink (streamed tracks, YouTube, right after a track change). In that
 * case attaching fails or never delivers a buffer, so every attempt is guarded
 * by a watchdog that re-creates the effect a few times. The caller keeps
 * polling the real session id, and a changed id restarts this effect with a
 * fresh attempt counter.
 */
@Composable
fun rememberAudioLevels(
    sessionId: Int,
    enabled: Boolean,
    bars: Int = 20
): AudioLevels {
    var levels by remember {
        mutableStateOf(AudioLevels(List(bars) { 0f }, List(bars) { 0f }))
    }
    // Auto-gain for the spectrum: the hottest bin drives the normalization
    // (instant attack, ~15% decay per frame), so every column moves instead
    // of only a few mid-range bins.
    var spectrumGain by remember { mutableFloatStateOf(0.25f) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    // Restarts the effect when an attach attempt has to be repeated.
    var attempt by remember(sessionId, enabled) { mutableIntStateOf(0) }

    DisposableEffect(sessionId, enabled, bars, attempt) {
        if (sessionId <= 0 || !enabled) {
            handler.post { levels = AudioLevels(List(bars) { 0f }, List(bars) { 0f }) }
            return@DisposableEffect onDispose { }
        }

        var disposed = false
        var delivered = false
        var watchdog: Runnable? = null

        fun retryLater(delayMs: Long = -1L) {
            if (disposed || delivered || attempt >= MAX_VISUALIZER_ATTEMPTS) return
            val effectiveDelay = if (delayMs > 0L) delayMs
                else (300L + attempt * 150L).coerceAtMost(1500L)
            val action = Runnable {
                if (!disposed && !delivered) attempt++
            }
            watchdog = action
            handler.postDelayed(action, effectiveDelay)
        }

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
            // Normalize against the running peak bin (fast attack, slow release):
            // the whole row dances instead of a couple of mid-range columns.
            val bandPeak = computed.maxOrNull() ?: 0f
            val next = max(bandPeak, spectrumGain * 0.85f).coerceIn(0.05f, 1f)
            spectrumGain = next
            return computed.map { (it / next).coerceIn(0f, 1f) }
        }

        val visualizer = runCatching { Visualizer(sessionId) }.onFailure {
            android.util.Log.w("AudioFx", "Visualizer ctor failed for session=$sessionId (attempt=$attempt)", it)
        }.getOrNull()
        val attachOk = visualizer != null && runCatching {
            val captureRange = Visualizer.getCaptureSizeRange()
            android.util.Log.i("AudioFx", "captureRange=[${captureRange.getOrNull(0)}, ${captureRange.getOrNull(1)}] maxCaptureRate=${Visualizer.getMaxCaptureRate()} session=$sessionId")
            visualizer.captureSize = captureRange[0].coerceAtLeast(128)
            android.util.Log.i("AudioFx", "Visualizer captureSize=${visualizer.captureSize} session=$sessionId")
            visualizer.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform == null || waveform.isEmpty()) return
                        if (!delivered) {
                            android.util.Log.i("AudioFx", "Visualizer first waveform buffer delivered (session=$sessionId)")
                        }
                        delivered = true
                        val computed = waveformOf(waveform)
                        handler.post { levels = levels.copy(waveform = computed) }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 4) return
                        delivered = true
                        val computed = spectrumOf(fft)
                        handler.post { levels = levels.copy(spectrum = computed) }
                    }
                },
                max(1, Visualizer.getMaxCaptureRate() / 2),
                true,
                true
            )
            visualizer.enabled = true
        }.onFailure { e ->
            android.util.Log.w("AudioFx", "Visualizer configure/attach threw (session=$sessionId attempt=$attempt)", e)
        }.isSuccess

        if (!attachOk) {
            android.util.Log.w("AudioFx", "Visualizer attach failed for session=$sessionId (attempt=$attempt)")
            runCatching { visualizer?.enabled = false }
            runCatching { visualizer?.release() }
            handler.post { levels = AudioLevels(List(bars) { 0f }, List(bars) { 0f }) }
            retryLater()
            return@DisposableEffect onDispose {
                disposed = true
                watchdog?.let { handler.removeCallbacks(it) }
            }
        }

        // Attached, but no buffer yet: the sink may still be warming up.
        retryLater(delayMs = 1_500L)

        onDispose {
            disposed = true
            watchdog?.let { handler.removeCallbacks(it) }
            runCatching { visualizer?.enabled = false }
            runCatching { visualizer?.release() }
            handler.post { levels = AudioLevels(List(bars) { 0f }, List(bars) { 0f }) }
        }
    }

    return levels
}
