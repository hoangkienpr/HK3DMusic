package com.hk.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun FolderBrowserView(
    folders: List<String>,
    songs: List<Song>,
    currentSong: Song,
    isPlaying: Boolean,
    onSongSelected: (Song) -> Unit
) {
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    val folderSongs = remember(selectedFolder, songs) {
        if (selectedFolder == null) songs else songs.filter { it.folderPath == selectedFolder }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (selectedFolder == null) "ðŸ“ Device Folders (${folders.size})" else "ðŸ“ Folder: $selectedFolder",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (selectedFolder == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(folders) { _, folder ->
                    val count = songs.count { it.folderPath == folder }
                    Surface(
                        onClick = { selectedFolder = folder },
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF101422).copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF00F2FE), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(folder, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text("$count tracks", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { selectedFolder = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color(0xFF00F2FE))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Back to Folders", color = Color(0xFF00F2FE), fontWeight = FontWeight.Bold)
                }
            }
            SongList3DWithGestures(
                filteredSongs = folderSongs,
                currentSong = currentSong,
                isPlaying = isPlaying,
                scrollStyle = 0,
                onSongSelected = onSongSelected,
                onSwipeRightToShare = {},
                onSwipeLeftToRemote = {}
            )
        }
    }
}

@Composable
fun CustomChip(selected: Boolean, text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(0xFF00F2FE).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, if (selected) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.15f))
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun SearchAndHeader(
    activeTab: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentSong: Song
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = when (activeTab) {
                    1 -> "ðŸŽ›ï¸ Audio Equalizer & FX"
                    2 -> "ðŸ“ Folder Directory"
                    3 -> "ðŸ“¡ Share & Remote Hub"
                    else -> "ðŸ”¥ 3D Immersive Library"
                },
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Explore your audio universe in stunning 3D depth",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search songs, artists...", color = Color.White.copy(alpha = 0.4f)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = currentSong.color1) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = currentSong.color1,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.03f)
            ),
            modifier = Modifier.width(320.dp).height(56.dp)
        )
    }
}

@Composable
fun NavigationSidebar(
    activeTab: Int,
    onTabSelected: (Int) -> Unit,
    songCount: Int
) {
    Surface(
        modifier = Modifier.width(260.dp).fillMaxHeight(),
        color = Color(0xFF060911).copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF00F2FE), Color(0xFF4FACFE))),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Headphones, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("HK 3D", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Immersive Audio", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                Text("MENU", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                SidebarNavItem(
                    icon = Icons.Default.LibraryMusic,
                    label = "Library",
                    badge = songCount.toString(),
                    isSelected = activeTab == 0,
                    onClick = { onTabSelected(0) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SidebarNavItem(
                    icon = Icons.Default.Equalizer,
                    label = "Equalizer & FX",
                    badge = "PRO",
                    isSelected = activeTab == 1,
                    onClick = { onTabSelected(1) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SidebarNavItem(
                    icon = Icons.Default.Folder,
                    label = "Folders",
                    badge = "NEW",
                    isSelected = activeTab == 2,
                    onClick = { onTabSelected(2) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SidebarNavItem(
                    icon = Icons.Default.Share,
                    label = "Share & Remote",
                    badge = "P2P",
                    isSelected = activeTab == 3,
                    onClick = { onTabSelected(3) }
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.04f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFFFEE140), Color(0xFFFA709A))), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("ðŸŽ§", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Hi-Fi Lossless", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("24-bit / 192kHz", color = Color(0xFF00F2FE), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) Color(0xFF00F2FE).copy(alpha = 0.15f) else Color.Transparent
    val contentColor = if (isSelected) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.7f)
    val borderColor = if (isSelected) Color(0xFF00F2FE).copy(alpha = 0.3f) else Color.Transparent

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(label, color = contentColor, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            }
            Surface(
                shape = CircleShape,
                color = if (isSelected) Color(0xFF00F2FE).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f)
            ) {
                Text(
                    text = badge,
                    color = if (isSelected) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun SongItem3D(
    song: Song,
    isSelected: Boolean,
    isPlaying: Boolean,
    rotationX: Float,
    rotationY: Float,
    rotationZ: Float,
    translationX: Float,
    translationY: Float,
    scale: Float,
    alpha: Float,
    onClick: () -> Unit
) {
    val cardBg = if (isSelected) {
        Brush.linearGradient(listOf(song.color1.copy(alpha = 0.5f), song.color2.copy(alpha = 0.4f)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF101422).copy(alpha = 0.8f), Color(0xFF070A12).copy(alpha = 0.8f)))
    }

    val borderColor = if (isSelected) song.color1 else Color.White.copy(alpha = 0.12f)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .graphicsLayer {
                this.rotationX = rotationX
                this.rotationY = rotationY
                this.rotationZ = rotationZ
                this.translationX = translationX
                this.translationY = translationY
                this.scaleX = scale
                this.scaleY = scale
                this.alpha = alpha
                cameraDistance = 16f * density
            }
            .shadow(if (isSelected) 16.dp else 4.dp, RoundedCornerShape(20.dp), ambientColor = song.color1, spotColor = song.color2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cardBg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                brush = Brush.linearGradient(listOf(song.color1, song.color2)),
                                shape = RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (song.albumArtBitmap != null) {
                            Image(
                                bitmap = song.albumArtBitmap,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else if (isPlaying) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.height(18.dp)
                            ) {
                                val infiniteTransition = rememberInfiniteTransition()
                                val h1 by infiniteTransition.animateFloat(6f, 18f, infiniteRepeatable(tween(400), RepeatMode.Reverse))
                                val h2 by infiniteTransition.animateFloat(10f, 22f, infiniteRepeatable(tween(300), RepeatMode.Reverse))
                                val h3 by infiniteTransition.animateFloat(4f, 14f, infiniteRepeatable(tween(500), RepeatMode.Reverse))

                                Box(modifier = Modifier.width(3.dp).height(h1.dp).background(Color.White, CircleShape))
                                Box(modifier = Modifier.width(3.dp).height(h2.dp).background(Color.White, CircleShape))
                                Box(modifier = Modifier.width(3.dp).height(h3.dp).background(Color.White, CircleShape))
                            }
                        } else {
                            Text("ðŸ’¿", fontSize = 20.sp)
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPlaying) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = song.color1,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = song.title,
                                color = if (isPlaying) song.color1 else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${song.artist} â€¢ ${song.album}",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatTime(song.durationSeconds),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun PlayerDashboard(
    song: Song,
    isPlaying: Boolean,
    currentPositionSeconds: Int,
    volume: Float,
    isShuffle: Boolean,
    isRepeat: Boolean,
    playbackSpeed: Float,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Int) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onSpeedChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.width(360.dp).fillMaxHeight(),
        color = Color(0xFF060911).copy(alpha = 0.9f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Now Playing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(48.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtBitmap != null) {
                    Image(
                        bitmap = song.albumArtBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(220.dp).clip(CircleShape).border(2.dp, song.color1, CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    val infiniteTransition = rememberInfiniteTransition()
                    val vinylRotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(6000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(if (isPlaying) vinylRotation else 0f)
                            .background(Color(0xFF11141D), CircleShape)
                            .border(2.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val radius = size.minDimension / 2f
                            drawCircle(color = Color.White.copy(alpha = 0.04f), radius = radius * 0.85f, style = Stroke(2.dp.toPx()))
                            drawCircle(color = Color.White.copy(alpha = 0.04f), radius = radius * 0.70f, style = Stroke(2.dp.toPx()))
                            drawCircle(color = Color.White.copy(alpha = 0.04f), radius = radius * 0.55f, style = Stroke(2.dp.toPx()))
                        }

                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .background(
                                    brush = Brush.linearGradient(listOf(song.color1, song.color2)),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color(0xFF060911), CircleShape)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = song.artist,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.03f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    val lrcLines = remember(song.lyrics) { parseLrc(song.lyrics) }
                    val currentMs = currentPositionSeconds * 1000L
                    val lyricIndex = lrcLines.indexOfLast { it.timeMs <= currentMs }.coerceAtLeast(0)
                    Text(
                        text = if (lrcLines.isEmpty()) {
                            "ChÆ°a cÃ³ lá»i bÃ i hÃ¡t (.lrc)"
                        } else {
                            "â€œ${lrcLines[lyricIndex].text}â€"
                        },
                        color = if (lrcLines.isEmpty()) {
                            Color.White.copy(alpha = 0.4f)
                        } else {
                            song.color1
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = currentPositionSeconds.toFloat(),
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..song.durationSeconds.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = song.color1,
                        activeTrackColor = song.color1,
                        inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(currentPositionSeconds), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    Text(formatTime(song.durationSeconds), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShuffleToggle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffle) song.color1 else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            brush = Brush.linearGradient(listOf(song.color1, song.color2)),
                            shape = CircleShape
                        )
                        .shadow(12.dp, CircleShape, ambientColor = song.color1, spotColor = song.color2)
                        .clickable(onClick = onPlayPauseToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onRepeatToggle) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (isRepeat) song.color1 else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (volume > 0f) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeMute,
                    contentDescription = "Volume",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.8f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(volume * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Speed control
            SpeedControl(
                currentSpeed = playbackSpeed,
                onSpeedChange = onSpeedChange,
                accentColor = song.color1
            )
        }
    }
}

@Composable
fun SongList3DWithGestures(
    filteredSongs: List<Song>,
    currentSong: Song,
    isPlaying: Boolean,
    scrollStyle: Int,
    onSongSelected: (Song) -> Unit,
    onSwipeRightToShare: (Song) -> Unit,
    onSwipeLeftToRemote: () -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll to currently playing song
    LaunchedEffect(currentSong.id) {
        val index = filteredSongs.indexOfFirst { it.id == currentSong.id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (filteredSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tracks found in library", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp)
            ) {
                itemsIndexed(filteredSongs) { index, song ->
                    val isSelected = song.id == currentSong.id

                    val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
                    val viewportHeight = listState.layoutInfo.viewportEndOffset.toFloat()
                    val centerOffset = viewportHeight / 2f

                    val itemCenter = itemInfo?.let { itemInfo.offset.toFloat() + itemInfo.size / 2f } ?: centerOffset
                    val distanceToCenter = itemCenter - centerOffset
                    val normalizedDistance = (distanceToCenter / (viewportHeight / 2f)).coerceIn(-1f, 1f)

                    var rotationXVal = 0f
                    var rotationYVal = 0f
                    var rotationZVal = 0f
                    var translationXVal = 0f
                    var translationYVal = 0f
                    var scaleVal = 1f
                    var alphaVal = 1f

                    when (scrollStyle) {
                        0 -> { // 3D Perspective Tilt
                            rotationXVal = -normalizedDistance * 35f
                            scaleVal = 1f - (abs(normalizedDistance) * 0.15f)
                            alphaVal = 1f - (abs(normalizedDistance) * 0.35f)
                        }
                        1 -> { // Cover Flow Y-Axis
                            rotationYVal = normalizedDistance * 45f
                            translationXVal = normalizedDistance * -40f
                            scaleVal = 1f - (abs(normalizedDistance) * 0.2f)
                            alphaVal = 1f - (abs(normalizedDistance) * 0.3f)
                        }
                        2 -> { // Circular Wheel Arc / Fan Spread
                            val angle = normalizedDistance * 55f
                            rotationZVal = angle
                            translationXVal = (1f - cos(Math.toRadians(angle.toDouble())).toFloat()) * -130f
                            translationYVal = sin(Math.toRadians(angle.toDouble())).toFloat() * 95f
                            scaleVal = 1f - (abs(normalizedDistance) * 0.25f)
                            alphaVal = 1f - (abs(normalizedDistance) * 0.4f)
                        }
                    }

                    var dragTotal by remember(song.id) { mutableStateOf(0f) }
                    Box(
                        modifier = Modifier
                            .pointerInput(song.id) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragTotal = 0f },
                                    onDragEnd = { dragTotal = 0f },
                                    onDragCancel = { dragTotal = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        dragTotal += dragAmount
                                        if (dragTotal > 120f) {
                                            dragTotal = 0f
                                            onSwipeRightToShare(song)
                                        } else if (dragTotal < -120f) {
                                            dragTotal = 0f
                                            onSwipeLeftToRemote()
                                        }
                                    }
                                )
                            }
                            .clickable(onClick = { onSongSelected(song) })
                    ) {
                        SongItem3D(
                            song = song,
                            isSelected = isSelected,
                            isPlaying = isSelected && isPlaying,
                            rotationX = rotationXVal,
                            rotationY = rotationYVal,
                            rotationZ = rotationZVal,
                            translationX = translationXVal,
                            translationY = translationYVal,
                            scale = scaleVal.coerceIn(0.75f, 1.15f),
                            alpha = alphaVal.coerceIn(0.3f, 1.0f),
                            onClick = { onSongSelected(song) }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// MiniPlayer - Collapsible bottom bar (YouTube Music style)
// ============================================================
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    currentPositionSeconds: Int,
    totalSeconds: Int,
    playbackSpeed: Float,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onExpand: () -> Unit,
    localMuted: Boolean = false,
    onLocalMuteToggle: () -> Unit = {}
) {
    val dragOffset = remember { mutableStateOf(0f) }
    val isExpanded = remember { mutableStateOf(false) }
    val miniHeight = 72.dp
    val expandedHeight = 280.dp

    val currentHeight = if (isExpanded.value) expandedHeight else miniHeight

    // Drag gesture to expand/collapse
    val dragModifier = Modifier
        .fillMaxWidth()
        .height(currentHeight)
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { },
                onDragEnd = {
                    if (dragOffset.value < -100) {
                        isExpanded.value = true
                        dragOffset.value = 0f
                    } else if (dragOffset.value > 100) {
                        isExpanded.value = false
                        dragOffset.value = 0f
                    } else {
                        dragOffset.value = 0f
                    }
                },
                onDragCancel = { dragOffset.value = 0f },
                onDrag = { change, dragAmount ->
                    dragOffset.value = (dragOffset.value + dragAmount.y).coerceIn(-expandedHeight.toPx(), miniHeight.toPx())
                }
            )
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    ) {
        Column(
            modifier = dragModifier
                .background(
                    Color(0xFF0A0E1A).copy(alpha = 0.95f),
                    RoundedCornerShape(24.dp)
                )
                .border(BorderStroke(1.dp, song.color1.copy(alpha = 0.3f)), RoundedCornerShape(24.dp))
                .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = song.color1.copy(alpha = 0.3f))
        ) {
            // Mini mode content
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(miniHeight)
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp)
                    .clickable(onClick = onExpand)
            ) {
                // Album art
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    if (song.albumArtBitmap != null) {
                        Image(
                            bitmap = song.albumArtBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1A1F3A), RoundedCornerShape(8.dp))
                                .border(1.dp, song.color1.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = song.color1, modifier = Modifier.size(24.dp))
                        }
                    }
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = song.color1,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Song info
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        song.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Local mute â€” silence only this phone's speaker (TV/web unaffected)
                IconButton(onClick = onLocalMuteToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (localMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (localMuted) "Báº­t tiáº¿ng mÃ¡y" else "Táº¯t tiáº¿ng mÃ¡y",
                        tint = if (localMuted) song.color1 else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Brush.linearGradient(listOf(song.color1, song.color2)), CircleShape)
                            .clickable(onClick = onPlayPauseToggle)
                            .shadow(4.dp, CircleShape, ambientColor = song.color1.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded.value,
                enter = expandVertically(animationSpec = tween(300, easing = { it * it })) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(200, easing = { it * it })) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .padding(top = 12.dp)
                ) {
                    // Progress bar
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = currentPositionSeconds.toFloat().coerceIn(0f, totalSeconds.toFloat().coerceAtLeast(1f)),
                            onValueChange = { onSeek(it.toInt()) },
                            valueRange = 0f..totalSeconds.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = song.color1,
                                activeTrackColor = song.color1,
                                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatTime(currentPositionSeconds), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                            Text(formatTime(totalSeconds), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Speed control - slider with dots
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Speed", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Text("${playbackSpeed}x", color = song.color1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                        val sliderValue = speeds.indexOfFirst { abs(it - playbackSpeed) < 0.05f }.coerceAtLeast(0).toFloat()
                        Slider(
                            value = sliderValue,
                            onValueChange = { value ->
                                val idx = value.toInt().coerceIn(0, speeds.lastIndex)
                                onSpeedChange(speeds[idx])
                            },
                            valueRange = 0f..speeds.lastIndex.toFloat(),
                            steps = speeds.size - 2,
                            colors = SliderDefaults.colors(
                                thumbColor = song.color1,
                                activeTrackColor = song.color1,
                                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.fillMaxWidth().height(20.dp)
                        )
                        // Dots row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            speeds.forEach { speed ->
                                val isSelected = abs(playbackSpeed - speed) < 0.05f
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (isSelected) song.color1 else Color.White.copy(alpha = 0.3f),
                                                CircleShape
                                            )
                                    )
                                    Text(
                                        "${speed}x",
                                        color = if (isSelected) song.color1 else Color.White.copy(alpha = 0.4f),
                                        fontSize = 8.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// TagEditorDialog - Edit song metadata
// ============================================================
@Composable
fun TagEditorDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String, album: String, genre: String) -> Unit
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var genre by remember { mutableStateOf(song.genre) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Sá»­a tháº» nháº¡c", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Thay Ä‘á»•i Ä‘Æ°á»£c lÆ°u vÃ o MediaStore Ä‘á»ƒ cÃ¡c app khÃ¡c cÅ©ng tháº¥y.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TagField("TÃªn bÃ i", title, { title = it }, song.color1)
                TagField("Nghá»‡ sÄ©", artist, { artist = it }, song.color1)
                TagField("Album", album, { album = it }, song.color1)
                TagField("Thá»ƒ loáº¡i / thÆ° má»¥c", genre, { genre = it }, song.color1)
                TextButton(
                    onClick = {
                        // Repairs tags such as "?oé„‹ K?t Cao B?ng" (UTF-8 text that
                        // was decoded as Latin-1 somewhere upstream).
                        title = fixVietnameseEncoding(title)
                        artist = fixVietnameseEncoding(artist)
                        album = fixVietnameseEncoding(album)
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("ðŸ©¹ Sá»­a lá»—i mÃ£ hoÃ¡ tÃªn", color = song.color1, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, artist, album, genre) }) {
                Text("LÆ°u", color = song.color1, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Huá»·", color = Color.White.copy(alpha = 0.6f))
            }
        },
        containerColor = Color(0xFF1A1F3A)
    )
}

@Composable
private fun TagField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    accent: Color
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// ============================================================
// AutoFetchDialog - Fetch artwork and lyrics
// ============================================================
@Composable
fun AutoFetchDialog(
    song: Song,
    onDismiss: () -> Unit,
    onFetchArtwork: () -> Unit,
    onFetchLyrics: () -> Unit,
    isFetching: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-fetch Metadata", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Automatically fetch artwork and lyrics for \"${song.title}\"",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
                if (isFetching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = song.color1, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Fetching...", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFetchArtwork, enabled = !isFetching) {
                    Text("Fetch Artwork", color = song.color1)
                }
                TextButton(onClick = onFetchLyrics, enabled = !isFetching) {
                    Text("Fetch Lyrics", color = song.color1)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.White.copy(alpha = 0.6f))
            }
        },
        containerColor = Color(0xFF1A1F3A)
    )
}

// ============================================================
// YouTubeResultsView - Display YouTube search results
// ============================================================
@Composable
fun YouTubeResultsView(
    results: List<LibraryRepository.YouTubeResult>,
    isLoading: Boolean,
    isLoadingMore: Boolean = false,
    hasMore: Boolean = true,
    onLoadMore: () -> Unit = {},
    song: Song,
    onPlay: (LibraryRepository.YouTubeResult) -> Unit,
    onDownload: (LibraryRepository.YouTubeResult) -> Unit
) {
    if (isLoading && results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = song.color1)
        }
        return
    }

    if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Nháº­p tá»« khÃ³a Ä‘á»ƒ tÃ¬m video YouTube", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val reachedEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(reachedEnd) {
        if (reachedEnd && hasMore && !isLoadingMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(results.size) { index ->
            val result = results[index]
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF101422).copy(alpha = 0.85f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    coil.compose.AsyncImage(
                        model = result.thumbnailUrl,
                        contentDescription = result.title,
                        modifier = Modifier
                            .size(80.dp, 45.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            result.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            result.author,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatTime(result.duration),
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { onPlay(result) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = song.color1,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = { onDownload(result) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        item {
            if (isLoadingMore) {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = song.color1, modifier = Modifier.size(24.dp))
                }
            } else if (hasMore) {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    Text("Vuá»‘t Ä‘á»ƒ táº£i thÃªmâ€¦", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                }
            }
        }
    }
}
// ============================================================
@Composable
fun SpeedControl(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    accentColor: Color
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Speed", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text("${currentSpeed}x", color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        val sliderValue = speeds.indexOfFirst { abs(it - currentSpeed) < 0.05f }.coerceAtLeast(0).toFloat()
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                val idx = value.toInt().coerceIn(0, speeds.lastIndex)
                onSpeedChange(speeds[idx])
            },
            valueRange = 0f..speeds.lastIndex.toFloat(),
            steps = speeds.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            ),
            modifier = Modifier.fillMaxWidth().height(20.dp)
        )
        // Dots row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            speeds.forEach { speed ->
                val isSelected = abs(currentSpeed - speed) < 0.05f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isSelected) accentColor else Color.White.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                    Text(
                        "${speed}x",
                        color = if (isSelected) accentColor else Color.White.copy(alpha = 0.4f),
                        fontSize = 8.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}


/**
 * Karaoke (synced lyrics) panel for the Full Player screen.
 * Empty state lets the user fetch .lrc lyrics online (LRCLIB) or import a .lrc file.
 */
@Composable
fun KaraokeLyricsView(
    song: Song,
    positionSeconds: Int,
    accentColor: Color,
    onSeekSeconds: (Int) -> Unit,
    onFetchOnline: () -> Unit,
    onImportLrc: () -> Unit,
    isFetching: Boolean,
    offsetMs: Int = 0,
    onOffsetChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lrcLines = remember(song.lyrics) { parseLrc(song.lyrics) }
    val currentMs = positionSeconds * 1000L + offsetMs
    val currentIndex = remember(currentMs, lrcLines.size) {
        lrcLines.indexOfLast { it.timeMs <= currentMs }.coerceAtLeast(0)
    }

    if (lrcLines.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {
                Text(
                    "ChÆ°a cÃ³ lá»i bÃ i hÃ¡t",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Äáº·t file .lrc cÃ¹ng tÃªn vá»›i file nháº¡c, nháº­p tá»« mÃ¡y, hoáº·c tÃ¬m online (LRCLIB)",
                    color = Color.White.copy(alpha = 0.38f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onFetchOnline,
                        enabled = !isFetching,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text(
                            if (isFetching) "Äang tÃ¬mâ€¦" else "TÃ¬m lá»i online",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onImportLrc,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Nháº­p .lrc", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onOffsetChange(offsetMs - 500) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "Lui lyric", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(16.dp))
            }
            Text(
                String.format(java.util.Locale.US, "%+.1fs", offsetMs / 1000f),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
            IconButton(onClick = { onOffsetChange(offsetMs + 500) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Toi lyric", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(16.dp))
            }
        }
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 88.dp)
        ) {
            itemsIndexed(lrcLines) { index, line ->
                val isCurrent = index == currentIndex
                Text(
                    text = line.text,
                    color = if (isCurrent) accentColor else Color.White.copy(alpha = 0.38f),
                    fontSize = if (isCurrent) 19.sp else 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeekSeconds((line.timeMs / 1000L).toInt()) }
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                )
            }
        }
        // Quick actions: fetch online / import .lrc
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onFetchOnline, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = "TÃ¬m lá»i online",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onImportLrc, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.UploadFile,
                    contentDescription = "Nháº­p file .lrc",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

