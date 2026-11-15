package com.hk.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ShareHubView(
    currentSong: Song,
    localIp: String?,
    port: Int,
    serverRunning: Boolean,
    serverError: String?,
    clients: Int,
    onServerToggle: () -> Unit,
    castAvailable: Boolean,
    castDevices: List<CastTarget>,
    castConnectedName: String?,
    castScanning: Boolean = false,
    onCastRescan: () -> Unit = {},
    onCastConnect: (CastTarget) -> Unit,
    onCastDisconnect: () -> Unit,
    onCastPlayCurrent: () -> Unit,
    context: Context,
    playlistShareUrl: String? = null,
    partyMode: Boolean = false,
    partySongCount: Int = 0,
    onPartyToggle: (Boolean) -> Unit = {}
) {
    val remoteUrl = if (localIp != null) "http://$localIp:$port" else "Chưa có Wi-Fi"
    val qrBitmap = remember(remoteUrl) {
        if (localIp != null) generateQrCode(remoteUrl, 512) else null
    }
    val accent = currentSong.color1

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF101422).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wifi, null, tint = accent, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Web remote",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (serverRunning) "Đang chạy · $clients thiết bị kết nối"
                                else "Đang tắt",
                                color = if (serverRunning) Color(0xFF7CE38B)
                                else Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        remoteUrl,
                        color = accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(180.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                "Cần Wi-Fi",
                                color = Color.Black.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (serverError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(serverError!!, color = Color(0xFFFFB199), fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (localIp == null) {
                                    Toast.makeText(context, "Chưa kết nối Wi-Fi", Toast.LENGTH_SHORT)
                                        .show()
                                } else {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("Remote URL", remoteUrl)
                                    )
                                    Toast.makeText(context, "Đã copy URL", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Copy link", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onServerToggle,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serverRunning) Color(0xFFFF0844) else accent
                            ),
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                if (serverRunning) "Dừng server" else "Bật server",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            val apkSizeMb = remember(context) {
                runCatching { File(context.applicationInfo.sourceDir).length() / 1_048_576L }
                    .getOrNull() ?: 0L
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF101422).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Share,
                            null,
                            tint = accent,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Chia sẻ ứng dụng",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Bản cài đặt hiện tại · ~${apkSizeMb} MB",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sao chép APK của HK3DMusic rồi gửi cho người khác qua Wi-Fi, " +
                            "Bluetooth, Nearby Share hoặc mạng xã hội. Người nhận cần cho phép " +
                            "cài app từ nguồn không xác định.",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (!shareAppApk(context)) {
                                Toast.makeText(context, "Không tạo được file APK", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Chia sẻ app (APK)",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF101422).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, if (partyMode) Color(0xFFFFD479).copy(alpha = 0.55f) else accent.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(18.dp)
                ) {
                    Icon(
                        Icons.Default.Group,
                        null,
                        tint = if (partyMode) Color(0xFFFFD479) else accent,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Party mode",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (partyMode)
                                "Đang bật · khách trên web thêm bài được; mỗi người chỉ xoá được bài của mình${if (partySongCount > 0) " · $partySongCount bài do khách thêm" else ""}"
                            else
                                "Đang tắt · chỉ máy này được xoá bài",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = partyMode,
                        onCheckedChange = onPartyToggle
                    )
                }
            }
        }

        item {
            val playlistBitmap = remember(playlistShareUrl) {
                playlistShareUrl?.let { generateQrCode(it, 512) }
            }
            if (playlistBitmap != null && playlistShareUrl != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF101422).copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "🎵 Chia sẻ danh sách phát",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Máy khác quét mã để mở đúng danh sách này trên web remote rồi phát lên máy bạn.",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Image(
                            bitmap = playlistBitmap,
                            contentDescription = "QR danh sách phát",
                            modifier = Modifier
                                .size(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("Playlist", playlistShareUrl)
                                )
                                Toast.makeText(context, "Đã copy link playlist", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        ) {
                            Text("Copy link playlist", color = accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF101422).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (castConnectedName != null) Icons.Default.CastConnected else Icons.Default.Cast,
                            null,
                            tint = accent,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Chromecast / DLNA",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when {
                                    castConnectedName != null -> "Đang phát trên: $castConnectedName"
                                    castScanning -> "Đang quét thiết bị trong Wi-Fi…"
                                    castDevices.isEmpty() -> "Chưa thấy thiết bị — bấm nút quét để tìm lại"
                                    else -> "Tìm thấy ${castDevices.size} thiết bị"
                                },
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                        if (castScanning) {
                            CircularProgressIndicator(
                                color = accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = onCastRescan, enabled = !castScanning) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Quét lại thiết bị",
                                tint = accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    if (castConnectedName != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tv, null, tint = accent)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                castConnectedName!!,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCastDisconnect) {
                                Text("Ngắt", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onCastPlayCurrent,
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("▶ Phát bài hiện tại trên TV", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (serverRunning)
                                "Chọn bài hát hoặc bấm play như bình thường — âm thanh sẽ ra TV."
                            else
                                "Cần bật server (nút Bật server ở trên) để TV nhận được nhạc.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    } else {
                        castDevices.forEach { device ->
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                onClick = { onCastConnect(device) },
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Tv,
                                        null,
                                        tint = Color.White.copy(alpha = 0.75f)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        device.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (device.isDlna) {
                                        Text(
                                            "DLNA",
                                            color = accent,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(accent.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("Kết nối", color = accent, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            val tvUrl = if (localIp != null) "http://$localIp:$port/tv" else null
            val accent = currentSong.color1
            val tvQrBitmap = remember(tvUrl) { tvUrl?.let { generateQrCode(it, 512) } }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF101422).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tv, null, tint = accent, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "TV Mode — Cast Bridge",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Mở trên TV (trình duyệt) — màn hình full + lyric đồng bộ + EQ",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (tvUrl != null) {
                        Text(
                            text = tvUrl,
                            color = accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(168.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (tvQrBitmap != null) {
                                Image(
                                    bitmap = tvQrBitmap,
                                    contentDescription = "QR TV Mode",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text("Không tạo được QR", color = Color.Black, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("TV URL", tvUrl))
                                    Toast.makeText(context, "Đã copy link TV", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.14f)
                                ),
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Copy link TV", color = Color.White, fontSize = 12.sp)
                            }
                            Button(
                                onClick = onServerToggle,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (serverRunning) Color(0xFFFF0844) else accent
                                ),
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    if (serverRunning) "Dừng server" else "Bật server",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        Text(
                            "Cần kết nối Wi-Fi để dùng TV Mode",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Copies this build's own APK to the cache (that copy is the backup of the
 * installed app) and opens the system share sheet so it can be sent through
 * Wi-Fi, Bluetooth, Nearby Share or any messenger. The receiver installs it
 * as a normal APK.
 */
private fun shareAppApk(context: Context): Boolean = runCatching {
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "1"
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    // Never hand out a stale APK from before an update.
    dir.listFiles()?.forEach { it.delete() }
    val target = File(dir, "HK3DMusic-v$versionName.apk")
    File(context.applicationInfo.sourceDir).inputStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "HK3DMusic v$versionName")
        putExtra(Intent.EXTRA_TEXT, "HK3DMusic — app nghe nhạc; cài từ nguồn không xác định")
        clipData = ClipData.newRawUri("HK3DMusic.apk", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Chia sẻ app HK3DMusic"))
    true
}.getOrElse { false }
