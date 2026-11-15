package com.hk.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

/**
 * Concert Mode: a full-screen, audio-reactive light show for the current track.
 *
 * Real spectrum data drives an aurora backdrop, an orbiting particle field and a
 * neon "mountain" waveform; beat detection (fast attacks on the low bins) fires an
 * expanding ring and flashes the particles. The UI overlay auto-hides, tap
 * anywhere to bring it back; the ✕ button (top right) exits.
 */
@Composable
fun ConcertModeView(
    song: Song,
    spectrum: List<Float>,
    isPlaying: Boolean,
    recordAudioGranted: Boolean,
    positionSeconds: Int,
    totalSeconds: Int,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onRequestMic: () -> Unit,
    onClose: () -> Unit
) {
    if (!recordAudioGranted) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF05070E))) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color(0xFFFFD479),
                    modifier = Modifier.size(84.dp)
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "Concert Mode cần quyền micro",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android bắt buộc quyền RECORD_AUDIO để đọc dữ liệu âm thanh của chính app (không ghi giọng nói).",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(song.color1)
                        .clickable { onRequestMic() }
                        .padding(horizontal = 28.dp, vertical = 12.dp)
                ) {
                    Text(
                        "Bật quyền mic",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 12.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        return
    }

    val bins = if (spectrum.isEmpty()) List(48) { 0f } else spectrum
    val bass = bins.take(6).average().toFloat()
    var prevBass by remember { mutableFloatStateOf(0f) }
    var beatAt by remember { mutableLongStateOf(0L) }
    if (isPlaying && bass - prevBass > 0.16f && System.currentTimeMillis() - beatAt > 320L) {
        beatAt = System.currentTimeMillis()
    }
    prevBass = bass
    val pulse = (1f - (System.currentTimeMillis() - beatAt) / 650f)
        .coerceIn(0f, 1f).pow(1.7f)

    val transition = rememberInfiniteTransition(label = "concert")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val flow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow"
    )

    // A small palette derived from the artwork colours.
    val palette = remember(song.color1, song.color2) { concertPalette(song.color1, song.color2) }

    var uiVisible by remember { mutableStateOf(true) }
    LaunchedEffect(uiVisible) {
        if (uiVisible) {
            delay(4500)
            uiVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070E))
            .pointerInput(Unit) {
                detectTapGestures { uiVisible = !uiVisible }
            }
    ) {
        // Blown-up blurred artwork sitting at the very back.
        song.albumArtBitmap?.let { art ->
            Image(
                bitmap = art,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(100.dp)
                    .clip(androidx.compose.ui.graphics.RectangleShape),
                contentScale = ContentScale.Crop,
                alpha = 0.35f
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.40f
            val minDim = minOf(size.width, size.height)

            // --- Aurora glows drifting left → right
            for (k in 0..2) {
                val speed = 0.55f + k * 0.25f
                val x = (((flow * speed + k * 0.34f) % 1.4f) - 0.2f) * size.width
                val y = size.height * (0.22f + k * 0.30f)
                val radius = minDim * (0.55f + 0.18f * k)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette[k].copy(alpha = 0.30f + pulse * 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(x, y),
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset(x, y)
                )
            }

            // --- Expanding beat ring
            if (pulse > 0.05f) {
                val ringR = minDim * (0.18f + (1f - pulse) * 0.52f)
                drawCircle(
                    color = palette[0].copy(alpha = pulse * 0.6f),
                    radius = ringR,
                    center = Offset(cx, cy),
                    style = Stroke(width = (3f + 16f * pulse))
                )
                drawCircle(
                    color = Color.White.copy(alpha = pulse * 0.25f),
                    radius = ringR * 0.7f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )
            }

            // --- Orbiting particle field
            val particles = 56
            for (i in 0 until particles) {
                val golden = 2.399963f
                val bin = bins[(i * bins.size / particles) % bins.size].coerceIn(0f, 1f)
                val dir = if (i % 7 == 0) -1f else 1f
                val angle = i * golden + phase * (0.28f + (i % 3) * 0.13f) * dir
                val band = 0.16f + ((i * 37 % 100) / 100f) * 0.46f
                val radius = minDim * band * (1f + bin * 0.55f + pulse * 0.30f)
                val x = cx + kotlin.math.cos(angle) * radius
                val y = cy + kotlin.math.sin(angle) * radius * 0.86f
                val pSize = 2.2f + bin * 7.5f + if (i % 4 == 0) pulse * 4.5f else pulse * 1.6f
                val color = palette[i % palette.size]
                drawCircle(
                    color = color.copy(alpha = 0.28f + bin * 0.6f),
                    radius = pSize * 2.1f,
                    center = Offset(x, y)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.65f + bin * 0.35f),
                    radius = pSize * 0.55f,
                    center = Offset(x, y)
                )
                if (i % 13 == 0 && pulse > 0.25f) {
                    val streakLen = 26f + pulse * 64f
                    val dx = x - cx
                    val dy = y - cy
                    val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
                    drawLine(
                        color = Color.White.copy(alpha = pulse * 0.7f),
                        start = Offset(x, y),
                        end = Offset(x + dx / len * streakLen, y + dy / len * streakLen),
                        strokeWidth = 2.4f
                    )
                }
            }

            // --- Neon spectrum mountain along the bottom
            val n = bins.size
            if (n >= 2) {
                val step = size.width / (n - 1f)
                val baseY = size.height * 0.97f
                val maxH = size.height * 0.34f
                // Smooth the bins with a moving average so the ridge line is silk.
                fun smoothed(i: Int): Float {
                    val a = bins[(i - 1).coerceAtLeast(0)]
                    val b = bins[i]
                    val c = bins[(i + 1).coerceAtMost(n - 1)]
                    return (a + b * 2f + c) / 4f
                }
                val ys = FloatArray(n) { baseY - smoothed(it).pow(0.75f) * maxH }

                val top = Path().apply {
                    moveTo(0f, ys[0])
                    for (i in 0 until n - 1) {
                        val mx = (i + 0.5f) * step
                        cubicTo(mx, ys[i], mx, ys[i + 1], (i + 1) * step, ys[i + 1])
                    }
                }
                val mountain = Path().apply {
                    addPath(top)
                    lineTo(size.width, baseY)
                    lineTo(0f, baseY)
                    close()
                }
                drawPath(
                    mountain,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            palette[0].copy(alpha = 0.55f + pulse * 0.25f),
                            palette[1].copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        startY = baseY - maxH,
                        endY = baseY
                    )
                )
                drawPath(
                    top,
                    brush = Brush.horizontalGradient(listOf(palette[0], palette[1], palette[0])),
                    style = Stroke(width = 3.2f + pulse * 2.4f)
                )
            }

            // --- Vignette on top of everything else
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    center = Offset(cx, size.height * 0.42f),
                    radius = minDim * 0.95f
                )
            )
        }

        // --- Overlay UI (auto-hides after a few seconds, tap toggles)
        if (uiVisible) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 30.dp, start = 20.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LIVE badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFFFF0844).copy(alpha = 0.9f))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.White, CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "LIVE",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 1.6.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "CONCERT",
                        color = Color.White.copy(alpha = 0.75f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White)
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    song.albumArtBitmap?.let { art ->
                        Image(
                            bitmap = art,
                            contentDescription = null,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .border(3.dp, palette[0].copy(alpha = 0.9f), CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(
                        song.title.ifBlank { "Không tên" },
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song.artist,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(18.dp))

                    val progress = if (totalSeconds > 0)
                        (positionSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(3.dp)
                                .background(palette[0], CircleShape)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrev) {
                            Icon(Icons.Filled.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(38.dp))
                        }
                        IconButton(
                            onClick = onTogglePlay,
                            modifier = Modifier
                                .size(72.dp)
                                .background(palette[0], CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        IconButton(onClick = onNext) {
                            Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(38.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Chạm để ẩn giao diện",
                        color = Color.White.copy(alpha = 0.38f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/** Three-ish colors orbiting the base hues so the show never looks static. */
private fun concertPalette(primary: Color, secondary: Color): List<Color> {
    fun shift(deg: Float, vMul: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                255,
                (primary.red * 255).toInt().coerceIn(0, 255),
                (primary.green * 255).toInt().coerceIn(0, 255),
                (primary.blue * 255).toInt().coerceIn(0, 255)
            ),
            hsv
        )
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        hsv[2] = (hsv[2] * vMul).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    return listOf(
        primary,
        secondary,
        shift(38f, 1.15f),
        shift(-44f, 0.95f)
    )
}
