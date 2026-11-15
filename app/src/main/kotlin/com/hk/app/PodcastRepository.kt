package com.hk.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** One episode of an RSS podcast. */
data class PodcastEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val publishedMs: Long,
    val durationSec: Int,
    val artworkUrl: String?
)

/** A parsed podcast feed (episodes included). */
data class PodcastFeed(
    val url: String,
    val title: String,
    val artworkUrl: String?,
    val episodes: List<PodcastEpisode>
)

/** Minimal but real RSS 2.0 + iTunes podcast parser (no external XML libraries). */
object PodcastRepository {

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    )

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val trimmed = text.trim()
        for (format in dateFormats) {
            try {
                return format.parse(trimmed)?.time ?: 0L
            } catch (_: Exception) {
                // try the next format
            }
        }
        return 0L
    }

    private fun parseDuration(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val t = text.trim()
        if (t.all { it.isDigit() }) return t.toIntOrNull() ?: 0
        val parts = t.split(':').mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    private fun localName(raw: String): String {
        val idx = raw.lastIndexOf(':')
        return if (idx >= 0) raw.substring(idx + 1) else raw
    }

    fun parse(xml: String, feedUrl: String): PodcastFeed? {
        return runCatching { parseReader(StringReader(xml), feedUrl) }.getOrElse { t ->
            android.util.Log.e("PodcastRepo", "parse failed for $feedUrl", t)
            null
        }
    }

    private fun parseReader(reader: java.io.Reader, feedUrl: String, maxEpisodes: Int = 60): PodcastFeed? {
        return runCatching {
            val factory = XmlPullParserFactory.newInstance()
            // Keep namespaces off so tag names arrive as "itunes:image" etc.
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(reader)

            var feedTitle = ""
            var feedArtwork: String? = null
            var inItem = false
            var inChannel = false

            var itemGuid = ""
            var itemTitle = ""
            var itemDescription = ""
            var itemAudio = ""
            var itemPubDate = ""
            var itemDuration = ""
            var itemArtwork: String? = null

            val episodes = mutableListOf<PodcastEpisode>()

            var event = parser.eventType
            var currentTag = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = localName(parser.name ?: "")
                        when (currentTag) {
                            "item" -> {
                                inItem = true
                                itemGuid = ""; itemTitle = ""; itemDescription = ""
                                itemAudio = ""; itemPubDate = ""; itemDuration = ""
                                itemArtwork = null
                            }
                            "channel" -> inChannel = true
                            "enclosure" -> {
                                if (inItem) {
                                    parser.getAttributeValue(null, "url")?.let { itemAudio = it }
                                }
                            }
                            "image" -> {
                                parser.getAttributeValue(null, "href")?.let {
                                    if (inItem) itemArtwork = it else if (feedArtwork == null) feedArtwork = it
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = localName(parser.name ?: "")
                        if (tag == "item") {
                            if (itemAudio.isNotBlank() && episodes.size < maxEpisodes) {
                                episodes += PodcastEpisode(
                                    guid = itemGuid.ifBlank { itemAudio },
                                    title = itemTitle.ifBlank { "Episode" },
                                    description = itemDescription,
                                    audioUrl = itemAudio,
                                    publishedMs = parseDate(itemPubDate),
                                    durationSec = parseDuration(itemDuration),
                                    artworkUrl = itemArtwork ?: feedArtwork
                                )
                            }
                            inItem = false
                            if (episodes.size >= maxEpisodes && feedTitle.isNotBlank()) {
                                return@runCatching PodcastFeed(feedUrl, feedTitle, feedArtwork, episodes)
                            }
                        }
                        if (tag == "channel") inChannel = false
                        currentTag = ""
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            if (inItem) {
                                when (currentTag) {
                                    "title" -> itemTitle = text
                                    "description" -> if (itemDescription.isBlank()) itemDescription = text
                                    "summary" -> if (itemDescription.isBlank()) itemDescription = text
                                    "guid" -> itemGuid = text
                                    "pubDate" -> itemPubDate = text
                                    "duration" -> itemDuration = text
                                }
                            } else if (inChannel && currentTag == "title") {
                                if (feedTitle.isBlank()) feedTitle = text
                            }
                        }
                    }
                }
                event = parser.next()
            }

            if (feedTitle.isBlank() || episodes.isEmpty()) {
                return@runCatching null
            }
            PodcastFeed(feedUrl, feedTitle, feedArtwork, episodes)
        }.getOrElse { t ->
            android.util.Log.e("PodcastRepo", "parse failed for $feedUrl", t)
            null
        }
    }

    suspend fun fetch(feedUrl: String): PodcastFeed? = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (feedUrl.startsWith("http", ignoreCase = true)) feedUrl else "https://$feedUrl"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "HK3DMusic/1.0 (Android)")
            conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.instanceFollowRedirects = true
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                parseReader(reader, url)
            }
        }.getOrElse { t ->
            android.util.Log.e("PodcastRepo", "fetch failed for $feedUrl", t)
            null
        }
    }

    /** Download the episode into Music/Podcasts via MediaStore; returns the new Uri or null. */
    suspend fun download(context: Context, episode: PodcastEpisode): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val safe = episode.title
                .replace(Regex("[^\\p{L}\\p{N}._\\- ]"), "_")
                .trim()
                .take(80)
                .ifBlank { "episode_${System.currentTimeMillis()}" }
            val fileName = if (safe.endsWith(".mp3", ignoreCase = true)) safe else "$safe.mp3"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.TITLE, episode.title)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Podcasts")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return@runCatching null
            context.contentResolver.openOutputStream(uri)?.use { output ->
                val conn = URL(episode.audioUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "HK3DMusic/1.0 (Android)")
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.inputStream.use { input -> input.copyTo(output, 64 * 1024) }
            } ?: return@runCatching null
            // Publish only after the file is fully written.
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null
            )
            uri
        }.getOrNull()
    }
}
