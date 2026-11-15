package com.hk.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.pow

/**
 * Real equalizer UI: every column is one band reported by the device's
 * [android.media.audiofx.Equalizer]; dragging writes the level through
 * [onBandChange] to the native effect.
 */
@Composable
fun EqualizerView(
    song: Song,
    bands: List<AudioFxEngine.Band>,
    eqSupported: Boolean,
    bassSupported: Boolean,
    virtualizerSupported: Boolean,
    bassBoostEnabled: Boolean,
    onBassBoostToggle: () -> Unit,
    virtualizerEnabled: Boolean,
    onVirtualizerToggle: () -> Unit,
    onBandChange: (Short, Float) -> Unit,
    audioSessionId: Int = 0,
    bassGain: Float = 0f,
    volGain: Float = 0f,
    trebGain: Float = 0f,
    onBassGainChange: (Float) -> Unit = {},
    onVolGainChange: (Float) -> Unit = {},
    onTrebGainChange: (Float) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Bộ chỉnh âm & hiệu ứng",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        // ---- Presets ----
        Text(
            "Preset",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf(
                "Flat" to { _: Int -> 0f },
                "Bass" to { hz: Int -> if (hz < 250) 6f else if (hz < 500) 3f else 0f },
                "Treble" to { hz: Int -> if (hz >= 4000) 6f else if (hz >= 2000) 3f else 0f },
                "Vocal" to { hz: Int -> if (hz in 1000..3000) 4f else if (hz < 500) -2f else 0f },
                "Rock" to { hz: Int -> if (hz < 250) 4f else if (hz in 1000..3000) -2f else 4f },
                "Pop" to { hz: Int -> if (hz < 250) 2f else if (hz in 1000..3000) -1f else 3f },
                "Classical" to { hz: Int -> if (hz >= 4000) 4f else if (hz in 1000..3000) 2f else 0f },
                "Jazz" to { hz: Int -> if (hz < 250) 2f else if (hz in 1000..3000) 3f else 2f },
                "Dance" to { hz: Int -> if (hz < 250) 5f else if (hz >= 4000) 3f else 1f }
            )
            presets.forEach { (name, gain) ->
                FilterChip(
                    selected = false,
                    onClick = {
                        bands.forEach { band ->
                            val range = (band.maxMb - band.minMb).toFloat().coerceAtLeast(1f)
                            val gainMb = (gain(band.centerHz) * 100f).toInt()
                                .coerceIn(band.minMb.toInt(), band.maxMb.toInt())
                            val fraction =
                                (gainMb - band.minMb) / range
                            onBandChange(band.index, fraction.coerceIn(0f, 1f))
                        }
                    },
                    label = { Text(name, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.08f)
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- Rotary knobs ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RotaryKnob(
                label = "BASS",
                value = bassGain,
                range = -12f..12f,
                color = song.color1,
                onValueChange = { v ->
                    onBassGainChange(v)
                    bands.filter { it.centerHz < 500 }.forEach { band ->
                        val r = (band.maxMb - band.minMb).toFloat().coerceAtLeast(1f)
                        val gainMb = (v * 100f).toInt().coerceIn(band.minMb.toInt(), band.maxMb.toInt())
                        onBandChange(band.index, ((gainMb - band.minMb) / r).coerceIn(0f, 1f))
                    }
                },
                modifier = Modifier.weight(1f)
            )
            RotaryKnob(
                label = "VOL",
                value = volGain,
                range = -12f..12f,
                color = song.color1,
                onValueChange = { v ->
                    onVolGainChange(v)
                    bands.forEach { band ->
                        val r = (band.maxMb - band.minMb).toFloat().coerceAtLeast(1f)
                        val gainMb = (v * 100f).toInt().coerceIn(band.minMb.toInt(), band.maxMb.toInt())
                        onBandChange(band.index, ((gainMb - band.minMb) / r).coerceIn(0f, 1f))
                    }
                },
                modifier = Modifier.weight(1f)
            )
            RotaryKnob(
                label = "TREB",
                value = trebGain,
                range = -12f..12f,
                color = song.color1,
                onValueChange = { v ->
                    onTrebGainChange(v)
                    bands.filter { it.centerHz >= 4000 }.forEach { band ->
                        val r = (band.maxMb - band.minMb).toFloat().coerceAtLeast(1f)
                        val gainMb = (v * 100f).toInt().coerceIn(band.minMb.toInt(), band.maxMb.toInt())
                        onBandChange(band.index, ((gainMb - band.minMb) / r).coerceIn(0f, 1f))
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        if (!eqSupported || bands.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Thiết bị hoặc bộ giải mã hiện tại không hỗ trợ Equalizer.\n" +
                        "Hãy thử phát một bài hát rồi mở lại tab này.",
                    color = Color(0xFFFFB199),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Text(
                "Kéo thanh để chỉnh • ${bands.size} băng tần",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bands.forEach { band ->
                    val span = (band.maxMb - band.minMb).toFloat().coerceAtLeast(1f)
                    val fraction = ((band.levelMb - band.minMb) / span).coerceIn(0f, 1f)

                    Column(
                        modifier = Modifier.width(54.dp).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.1f dB", band.levelDb),
                            color = song.color1,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))

                        VerticalEqSlider(
                            fraction = fraction,
                            accent = song.color1,
                            onFractionChange = { onBandChange(band.index, it) },
                            modifier = Modifier.weight(1f).width(44.dp)
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(
                            band.label,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Dải tần thực tế của thiết bị (${bands.minOf { it.centerHz }}Hz – ${bands.maxOf { it.centerHz }}Hz)",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Vertical EQ fader drawn from scratch: top = +max dB, bottom = -max dB.
 * A rotated Material slider produced a confusing track, so the value is
 * mapped directly from the touch position instead.
 */
@Composable
private fun VerticalEqSlider(
    fraction: Float,
    accent: Color,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val update: (Float, Int) -> Unit = { y, height ->
        if (height > 0) onFractionChange((1f - y / height).coerceIn(0f, 1f))
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> update(offset.y, size.height) }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> update(offset.y, size.height) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        update(change.position.y, size.height)
                    }
                )
            }
    ) {
        val pad = 10.dp.toPx()
        val trackWidth = 8.dp.toPx()
        val centerX = size.width / 2f
        val top = pad
        val bottom = (size.height - pad).coerceAtLeast(top + 1f)
        val span = bottom - top
        val clamped = fraction.coerceIn(0f, 1f)
        val thumbY = bottom - clamped * span

        drawRoundRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(centerX - trackWidth / 2f, top),
            size = Size(trackWidth, span),
            cornerRadius = CornerRadius(trackWidth / 2f)
        )

        if (thumbY < bottom) {
            drawRoundRect(
                color = accent.copy(alpha = 0.85f),
                topLeft = Offset(centerX - trackWidth / 2f, thumbY),
                size = Size(trackWidth, bottom - thumbY),
                cornerRadius = CornerRadius(trackWidth / 2f)
            )
        }

        val zeroY = bottom - span / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = Offset(centerX - 13.dp.toPx(), zeroY),
            end = Offset(centerX + 13.dp.toPx(), zeroY),
            strokeWidth = 1.dp.toPx()
        )

        drawCircle(color = accent, radius = 8.dp.toPx(), center = Offset(centerX, thumbY))
        drawCircle(
            color = Color(0xFF0B1020),
            radius = 3.5.dp.toPx(),
            center = Offset(centerX, thumbY)
        )
    }
}

/**
 * A round rotary knob. Dragging around the dial changes [value].
 */
@Composable
fun RotaryKnob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val minV = range.start
    val maxV = range.endInclusive
    val span = (maxV - minV).coerceAtLeast(0.001f)
    val fraction = ((value - minV) / span).coerceIn(0f, 1f)
    val angle = 135f + fraction * 270f

    var dragStartAngle by remember { mutableFloatStateOf(0f) }
    var dragStartValue by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(88.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            dragStartAngle = Math.toDegrees(
                                kotlin.math.atan2(
                                    (offset.y - center.y).toDouble(),
                                    (offset.x - center.x).toDouble()
                                )
                            ).toFloat()
                            dragStartValue = value
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false }
                    ) { change, _ ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val cur = Math.toDegrees(
                            kotlin.math.atan2(
                                (change.position.y - center.y).toDouble(),
                                (change.position.x - center.x).toDouble()
                            )
                        ).toFloat()
                        var delta = cur - dragStartAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        val newValue = (dragStartValue + delta / 270f * span)
                            .coerceIn(minV, maxV)
                        onValueChange(newValue)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f - 6.dp.toPx()
                // Background track arc (-135..135)
                drawArc(
                    color = Color.White.copy(alpha = 0.12f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f)
                )
                // Value arc
                val sweep = fraction * 270f
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f)
                )
                // Indicator line
                val rad = Math.toRadians(angle.toDouble())
                val inner = radius * 0.55f
                val outer = radius * 0.92f
                drawLine(
                    color = color,
                    start = Offset(
                        center.x + kotlin.math.cos(rad).toFloat() * inner,
                        center.y + kotlin.math.sin(rad).toFloat() * inner
                    ),
                    end = Offset(
                        center.x + kotlin.math.cos(rad).toFloat() * outer,
                        center.y + kotlin.math.sin(rad).toFloat() * outer
                    ),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // Center dot
                drawCircle(color = color, radius = 4.dp.toPx(), center = center)
                drawCircle(
                    color = Color(0xFF0B1020),
                    radius = 1.5.dp.toPx(),
                    center = center
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            String.format(Locale.US, "%+.0f dB", value),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Draws the live data coming from [android.media.audiofx.Visualizer].
 * Nothing here is synthetic: when the player is silent every style stays flat.
 *
 * Styles: 0 = waveform bars, 1 = spectrum columns, 2 = pulsing ring,
 * 3 = orbiting particles.
 */
@Composable
fun AudioVisualizerView(
    levels: List<Float>,
    isPlaying: Boolean,
    mode: Int,
    primaryColor: Color,
    spectrum: List<Float> = emptyList()
) {
    val bars = if (levels.isEmpty()) List(20) { 0f } else levels
    val spec = if (spectrum.isEmpty()) bars else spectrum
    val alpha = if (isPlaying) 1f else 0.45f

    val transition = rememberInfiniteTransition(label = "viz")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    when (mode) {
        0 -> {
            // Classic EQ bars with a neon gradient, a white-hot cap and a soft
            // reflection below the baseline. Reads best on a small player card.
            Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                val n = bars.size
                if (n == 0) return@Canvas
                val cell = size.width / n
                val barW = (cell * 0.56f).coerceAtLeast(1.5f)
                val baseline = size.height * 0.78f
                val maxH = size.height * 0.72f
                val r = barW / 2f
                val brush = Brush.verticalGradient(
                    listOf(Color.White, primaryColor),
                    startY = baseline - maxH,
                    endY = baseline
                )
                bars.forEachIndexed { index, level ->
                    val p = level.coerceIn(0f, 1f).pow(0.75f)
                    val h = (maxH * (0.08f + p * 0.92f)).coerceAtLeast(barW)
                    val x = index * cell + (cell - barW) / 2f
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.16f * alpha),
                        topLeft = Offset(x, baseline - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(r, r)
                    )
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(x, baseline - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(r, r),
                        alpha = alpha
                    )
                    // Cap highlight.
                    drawCircle(
                        color = Color.White.copy(alpha = (0.55f + 0.45f * p) * alpha),
                        radius = r * 0.85f,
                        center = Offset(x + barW / 2f, baseline - h + r * 0.6f)
                    )
                    // Faint reflection under the baseline.
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.14f * alpha * p),
                        topLeft = Offset(x, baseline + 2f),
                        size = Size(barW, h * 0.22f),
                        cornerRadius = CornerRadius(r, r)
                    )
                }
            }
        }

        1 -> {
            // Smooth waveform: a filled neon curve with a soft glow and a moving
            // hot spot at the current peak.
            Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                val n = spec.size
                if (n < 2) return@Canvas
                val w = size.width
                val h = size.height
                val midY = h / 2f
                val amp = h * 0.40f
                val xs = FloatArray(n) { it * w / (n - 1f) }
                val ys = FloatArray(n) { midY - spec[it].coerceIn(0f, 1f) * amp }

                fun curve(): Path {
                    val path = Path()
                    path.moveTo(xs[0], ys[0])
                    for (i in 0 until n - 1) {
                        val mx = (xs[i] + xs[i + 1]) / 2f
                        path.cubicTo(mx, ys[i], mx, ys[i + 1], xs[i + 1], ys[i + 1])
                    }
                    return path
                }

                val top = curve()
                val area = Path().apply {
                    addPath(top)
                    lineTo(xs[n - 1], midY)
                    lineTo(xs[0], midY)
                    close()
                }
                drawPath(
                    area,
                    brush = Brush.verticalGradient(
                        listOf(primaryColor.copy(alpha = 0.45f * alpha), Color.Transparent),
                        startY = 0f,
                        endY = midY
                    )
                )
                // Mirrored ghost below the centre line.
                drawPath(top, color = primaryColor.copy(alpha = 0.18f * alpha), style = Stroke(1.6f))
                drawPath(top, color = primaryColor.copy(alpha = 0.32f * alpha), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
                drawPath(
                    top,
                    brush = Brush.horizontalGradient(
                        listOf(primaryColor, Color.White.copy(alpha = 0.9f), primaryColor)
                    ),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
                val peak = ys.indices.minByOrNull { ys[it] } ?: 0
                drawCircle(Color.White.copy(alpha = 0.9f * alpha), radius = 2.6f, center = Offset(xs[peak], ys[peak]))
                drawCircle(primaryColor.copy(alpha = 0.5f * alpha), radius = 6f, center = Offset(xs[peak], ys[peak]))
            }
        }

        2 -> {
            // Radial spectrum: bars radiating from a glowing core, slowly rotating.
            Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseR = size.height * 0.20f
                drawCircle(primaryColor.copy(alpha = 0.16f * alpha), radius = baseR * 1.6f, center = center)
                val n = 52
                val total = 2.0 * Math.PI
                val mean = spec.map { it.coerceIn(0f, 1f) }.average().toFloat()
                drawCircle(
                    color = primaryColor.copy(alpha = (0.35f + 0.35f * mean) * alpha),
                    radius = baseR * 0.34f,
                    center = center
                )
                for (i in 0 until n) {
                    val v = spec[(i * spec.size / n) % spec.size].coerceIn(0f, 1f)
                    val angle = (i.toFloat() / n) * total.toFloat() + phase * 0.18f
                    val dx = kotlin.math.cos(angle).toFloat()
                    val dy = kotlin.math.sin(angle).toFloat()
                    val start = Offset(center.x + dx * baseR, center.y + dy * baseR)
                    val len = 2f + v * size.height * 0.30f
                    val end = Offset(center.x + dx * (baseR + len), center.y + dy * (baseR + len))
                    drawLine(
                        color = if (i % 2 == 0) {
                            primaryColor.copy(alpha = (0.45f + 0.5f * v) * alpha)
                        } else {
                            Color.White.copy(alpha = (0.30f + 0.55f * v) * alpha)
                        },
                        start = start,
                        end = end,
                        strokeWidth = 2.8f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        3 -> Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
            // Mirrored spectrum drawn both ways from the center line:
            // symmetric "studio interface" look, no wasted pixels.
            val n = spec.size
            if (n == 0) return@Canvas
            val cell = size.width / n
            val barW = cell * 0.58f
            val midY = size.height / 2f
            drawLine(
                color = primaryColor.copy(alpha = 0.55f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.dp.toPx()
            )
            spec.forEachIndexed { index, value ->
                val p = value.coerceIn(0f, 1f)
                val h = (size.height * 0.46f) * (0.14f + p.pow(0.8f) * 0.86f)
                val x = index * cell + (cell - barW) / 2f
                val r = barW / 2f
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.26f + 0.68f * p),
                    topLeft = Offset(x, midY - h),
                    size = Size(barW, h * 2f),
                    cornerRadius = CornerRadius(r, r)
                )
                drawCircle(
                    color = Color.White.copy(alpha = (0.22f + 0.72f * p) * alpha),
                    radius = r * 0.55f,
                    center = Offset(x + barW / 2f, midY - h)
                )
                drawCircle(
                    color = Color.White.copy(alpha = (0.22f + 0.72f * p) * alpha),
                    radius = r * 0.55f,
                    center = Offset(x + barW / 2f, midY + h)
                )
            }
        }

        else -> Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val palette = shiftedPalette(primaryColor)
            // Nebula glow behind the orbiting sparks.
            drawCircle(
                color = primaryColor.copy(alpha = 0.22f * alpha),
                radius = size.height * 0.42f,
                center = center
            )
            val n = spec.size.coerceAtLeast(18)
            for (i in 0 until n) {
                val p = spec[i % spec.size].coerceIn(0f, 1f)
                val dir = if (i % 3 == 0) 1f else -1f
                val angle = (i.toFloat() / n) * (2.0 * Math.PI).toFloat() + phase * 0.6f * dir
                val radius = size.height * (0.24f + p * 0.44f)
                val x = center.x + kotlin.math.cos(angle).toFloat() * radius
                val y = center.y + kotlin.math.sin(angle).toFloat() * radius
                val dot = palette[i % palette.size]
                // Colored halo under a white-hot core; bigger than before.
                drawCircle(
                    color = dot.copy(alpha = 0.42f + 0.54f * p),
                    radius = 3.6f + p * 6.4f,
                    center = Offset(x, y)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f + 0.55f * p),
                    radius = 1.3f + p * 2.2f,
                    center = Offset(x, y)
                )
            }
        }
    }
}

/**
 * Hue-shifted palette derived from the song's base color so the Constellation
 * sparks feel related to the track artwork instead of arbitrary hues.
 */
private fun shiftedPalette(color: Color): List<Color> {
    fun shift(deg: Float, sMul: Float, vMul: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                255,
                (color.red * 255).toInt().coerceIn(0, 255),
                (color.green * 255).toInt().coerceIn(0, 255),
                (color.blue * 255).toInt().coerceIn(0, 255)
            ),
            hsv
        )
        hsv[0] = (hsv[0] + deg) % 360f
        hsv[1] = (hsv[1] * sMul).coerceIn(0.45f, 1f)
        hsv[2] = (hsv[2] * vMul).coerceIn(0.40f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    return listOf(
        color,
        shift(52f, 0.9f, 1.0f),
        shift(140f, 0.8f, 0.9f),
        shift(215f, 0.7f, 0.75f),
        Color.White
    )
}
