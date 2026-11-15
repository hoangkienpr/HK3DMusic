package com.hk.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SuggestedPodcast(val title: String, val desc: String, val url: String)

private fun normPodcastUrl(url: String): String = url.trim().trimEnd('/').lowercase()

private val PODCAST_PRESETS = listOf(
    SuggestedPodcast("VOV - Tin nhanh 24h", "VOV — Tin tức cập nhật nhanh (RSS)", "https://anchor.fm/s/86f0c04/podcast/rss"),
    SuggestedPodcast("VOV - Chương trình Thời sự", "VOV — Thời sự trong ngày", "https://anchor.fm/s/86ec8d4/podcast/rss"),
    SuggestedPodcast("VOV - Kinh tế Tài chính", "VOV — Kinh tế, tài chính", "https://anchor.fm/s/84e912c/podcast/rss"),
    SuggestedPodcast("VOV - Khoa học Công nghệ", "VOV — Khoa học và công nghệ", "https://anchor.fm/s/89fc894/podcast/rss"),
    SuggestedPodcast("VOV - Sự kiện Bàn luận", "VOV — Bình luận các sự kiện nổi bật", "https://anchor.fm/s/8535a2c/podcast/rss"),
    SuggestedPodcast("SBS Vietnamese - SBS Việt ngữ", "SBS (Úc) — Tin tức/câu chuyện bằng tiếng Việt", "https://sbs-ondemand.streamguys1.com/sbs-Vietnamese/"),
    SuggestedPodcast("Planet Money", "NPR — Kinh tế dễ hiểu, kể chuyện hấp dẫn (RSS ổn định)", "https://feeds.npr.org/510289/podcast.xml"),
    SuggestedPodcast("The Indicator", "NPR — Kinh tế ngắn gọn (từ Planet Money)", "https://feeds.npr.org/510325/podcast.xml"),
    SuggestedPodcast("Up First", "NPR — Bản tin tin tức buổi sáng", "https://feeds.npr.org/510318/podcast.xml"),
    SuggestedPodcast("Global News Podcast", "BBC — Tin tức thế giới hằng ngày", "https://podcasts.files.bbci.co.uk/p02nq0gn.rss"),
    SuggestedPodcast("TED Talks Daily", "TED — Bài nói truyền cảm hứng mỗi ngày", "https://feeds.feedburner.com/TEDTalks_audio")
)

@Composable
fun PodcastView(
    feeds: List<PodcastFeed>,
    loading: Boolean,
    error: String?,
    message: String?,
    addingUrl: String?,
    expanded: Map<String, Boolean>,
    downloadedGuids: Set<String>,
    downloadingGuid: String?,
    onAddFeed: (String) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onToggleExpand: (String) -> Unit,
    onPlayEpisode: (PodcastFeed, PodcastEpisode) -> Unit,
    onDownloadEpisode: (PodcastFeed, PodcastEpisode) -> Unit
) {
    var rssUrl by remember { mutableStateOf("") }
    var showGuide by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(true) }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.US) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Filled.RssFeed,
                contentDescription = null,
                tint = Color(0xFFFFB454),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Podcast",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "${feeds.sumOf { it.episodes.size }} tập",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onRefreshAll, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        color = Color(0xFFFFB454),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Làm mới", tint = Color(0xFFFFB454))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Add a feed by RSS URL
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = rssUrl,
                onValueChange = { rssUrl = it },
                placeholder = { Text("Dán link RSS podcast…", color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onAddFeed(rssUrl.trim()); rssUrl = "" },
                enabled = rssUrl.isNotBlank() && !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB454)),
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Thêm", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, color = Color(0xFFFFB199), fontSize = 11.sp)
        }
        message?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, color = Color(0xFFB7F5C2), fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showPresets = !showPresets },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gợi ý podcast Việt Nam & quốc tế (RSS hoạt động)", color = Color(0xFFFFD699), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (showPresets) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Color(0xFFFFB454))
            }
            if (showPresets) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Gồm podcast Việt Nam (VOV, SBS Việt ngữ) và quốc tế (Planet Money, BBC, TED…). Bấm “Thêm” để đăng ký; bạn vẫn có thể dán link RSS khác bên trên.", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, lineHeight = 14.sp)
                if (addingUrl != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("⏳ Đang tải và đọc RSS, vui lòng chờ...", color = Color(0xFFFFD699), fontSize = 11.sp)
                }
                message?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = Color(0xFFB7F5C2), fontSize = 11.sp)
                }
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = Color(0xFFFFB199), fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                PODCAST_PRESETS.forEach { p ->
                    val added = feeds.any { normPodcastUrl(it.url) == normPodcastUrl(p.url) }
                    val isAdding = addingUrl != null && normPodcastUrl(addingUrl) == normPodcastUrl(p.url)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.desc, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onAddFeed(p.url) },
                            enabled = !added && !isAdding,
                            colors = ButtonDefaults.buttonColors(containerColor = if (added) Color.White.copy(alpha = 0.12f) else Color(0xFFFFB454)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            if (isAdding) {
                                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Đang thêm", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text(if (added) "Đã thêm" else "Thêm", color = if (added) Color.White.copy(alpha = 0.6f) else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showGuide = !showGuide },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hướng dẫn lấy link RSS podcast", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (showGuide) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
            }
            if (showGuide) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "• Link RSS thường có đuôi .xml/.rss hoặc chứa 'feed'/'rss'.\n" +
                    "• Trang chủ podcast: tìm nút RSS/Subscribe/Feed và sao chép link.\n" +
                    "• Apple Podcasts: mở show trên web, sao chép link, rồi tìm RSS qua dịch vụ như getrssfeed.com (dán link Apple Podcasts để lấy RSS).\n" +
                    "• Spotify: Spotify không cho RSS trực tiếp; dùng công cụ chuyển đổi (vd. getrssfeed.com, spotifeed.timdorr.com nếu còn hoạt động) để lấy RSS từ link show Spotify.\n" +
                    "• Google Podcasts/YouTube: nhiều show không còn RSS công khai; ưu tiên lấy RSS từ trang chính thức của chương trình.\n" +
                    "• Sau khi có link, dán vào ô trên và bấm Thêm. Nếu báo lỗi, thử mở link trong trình duyệt để kiểm tra (phải là XML/RSS).",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (feeds.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = 28.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.RssFeed,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.18f),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "Chưa đăng ký podcast nào",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Dán link RSS (ví dụ từ Spotify / Apple Podcasts / trang chủ show) rồi bấm Thêm. Mỗi tập tải về là một bài hát trong thư viện — phát được trên web remote và chiếu lên TV.",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp
                )
            }
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(feeds.size, key = { feeds[it].url }) { i ->
                val feed = feeds[i]
                val isExpanded = expanded[feed.url] == true
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleExpand(feed.url) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (feed.artworkUrl != null) {
                            AsyncImage(
                                model = feed.artworkUrl,
                                contentDescription = feed.title,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Filled.RssFeed,
                                contentDescription = null,
                                tint = Color(0xFFFFB454),
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF2A2418))
                                    .padding(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                feed.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${feed.episodes.size} tập",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { onRemoveFeed(feed.url) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Bỏ đăng ký", tint = Color.White.copy(alpha = 0.45f))
                        }
                        Icon(
                            if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFFFFB454)
                        )
                    }

                    if (isExpanded) {
                        feed.episodes.take(30).forEach { episode ->
                            val isDownloaded = episode.guid in downloadedGuids
                            val isDownloading = episode.guid == downloadingGuid
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.03f))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onPlayEpisode(feed, episode) },
                                    modifier = Modifier.size(38.dp).background(Color(0xFFFFB454), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "Phát",
                                        tint = Color.Black,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        episode.title,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 15.sp
                                    )
                                    val meta = buildList {
                                        if (episode.publishedMs > 0) add(dateFormat.format(Date(episode.publishedMs)))
                                        if (episode.durationSec > 0) add(formatTime(episode.durationSec))
                                    }.joinToString(" · ")
                                    if (meta.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(meta, color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp)
                                    }
                                }
                                IconButton(onClick = { onDownloadEpisode(feed, episode) }) {
                                    when {
                                        isDownloading -> CircularProgressIndicator(
                                            color = Color(0xFFFFB454),
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        isDownloaded -> Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Đã tải",
                                            tint = Color(0xFF7CE38B)
                                        )
                                        else -> Icon(
                                            Icons.Filled.Download,
                                            contentDescription = "Tải về",
                                            tint = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Tip: tập đã tải về nằm trong Music/Podcasts — chúng tự động xuất hiện trong thư viện Nhạc (và trên web remote, cast TV).",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
