package com.hk.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.util.JsonReader
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Reads the device audio library from MediaStore.
 *
 * Everything runs on [Dispatchers.IO]; album art is downscaled before it is
 * decoded so a large library cannot exhaust the heap.
 */
object LibraryRepository {

    private const val ART_MAX_PX = 320
    private const val MIN_TRACK_DURATION_MS = 30_000L

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "webm")

    private val unwantedFolders = listOf(
        "/Recordings/", "/Voice", "/Ringtones/", "/Alarms/",
        "/Notifications/", "/CallRecording/", "/Android/media/"
    )

    suspend fun load(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= $MIN_TRACK_DURATION_MS")
        }

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val dataPath = cursor.getString(dataCol) ?: ""
                    if (unwantedFolders.any { dataPath.contains(it, ignoreCase = true) }) continue

                    val audioId = cursor.getLong(idCol)
                    val durationMs = cursor.getLong(durationCol)
                    if (durationMs < MIN_TRACK_DURATION_MS) continue

                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioId
                    )
                    val art = loadArtworkOverride(context, audioId) ?: readArtwork(context, uri)
                    val palette = paletteFrom(art)
                    val saved = TagStore.read(context, audioId)

                    val rawTitle = fixVietnameseEncoding(cursor.getString(titleCol))
                    val fileTitle = titleFromFileName(dataPath)
                    // Many downloaded tracks carry a mangled ID3 title ("?o鄋 K?t Cao
                    // B?ng") while the file name itself is correct Vietnamese, so
                    // prefer the file name whenever the tag is obviously broken.
                    val title = saved.title ?: when {
                        !looksBroken(rawTitle) -> rawTitle
                        fileTitle.isNotBlank() -> fileTitle
                        else -> "Unknown title"
                    }
                    val folder = folderNameOf(dataPath)
                    val rawAlbum = fixVietnameseEncoding(cursor.getString(albumCol))
                    val album = saved.album ?: when {
                        !looksBroken(rawAlbum) -> rawAlbum
                        folder.isNotBlank() -> folder
                        else -> "Unknown album"
                    }
                    val artist = saved.artist ?: fixVietnameseEncoding(
                        cursor.getString(artistCol)
                    ).ifBlank { "Unknown artist" }

                    result.add(
                        Song(
                            id = audioId,
                            title = title,
                            artist = artist,
                            album = album,
                            durationSeconds = max(1, (durationMs / 1000L).toInt()),
                            genre = saved.genre ?: folder,
                            color1 = palette.first,
                            color2 = palette.second,
                            lyrics = readLyrics(context, audioId, dataPath),
                            audioUri = uri,
                            albumArtBitmap = art?.asImageBitmap(),
                            folderPath = folder
                        )
                    )
                }
            }
        }

        runCatching {
            uploadDirectory(context).listFiles()
                ?.filter { it.isFile && it.length() > 0L && it.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.sortedBy { it.name }
                ?.forEach { file -> result.add(songFromFile(context, file)) }
        }

        // Offline YouTube downloads also live in app-private storage; without
        // this scan they vanish from the library on every app restart.
        runCatching {
            youtubeDownloadDir(context).listFiles()
                ?.filter { it.isFile && it.length() > 0L && it.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file -> result.add(songFromFile(context, file, folderLabel = "YouTube")) }
        }

        result
    }

    /** Builds a [Song] for a file that lives in app storage (web upload). */
    suspend fun songFromFile(
        context: Context,
        file: File,
        folderLabel: String = "Web Uploads"
    ): Song = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        // Uploaded files are stored as "<timestamp>_<original name>"; the
        // timestamp must not leak into the displayed title.
        val displayName = file.nameWithoutExtension.replace(Regex("^\\d{10,}_"), "")
        var title = displayName
        var artist = if (folderLabel == "YouTube") "YouTube" else "Web upload"
        var album = if (folderLabel == "YouTube") "YouTube" else "Uploaded"
        var durationMs = 0L
        var art: Bitmap? = null

        runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }?.let { album = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { durationMs = it }
            retriever.embeddedPicture?.let { art = decodeArtwork(it, ART_MAX_PX) }
        }
        runCatching { retriever.release() }

        val songId = file.name.hashCode().toLong() and 0xFFFFFFFFL
        val saved = TagStore.read(context, songId)
        art = loadArtworkOverride(context, songId) ?: art

        val palette = paletteFrom(art)
        Song(
            id = songId,
            title = saved.title ?: fixVietnameseEncoding(title),
            artist = saved.artist ?: fixVietnameseEncoding(artist),
            album = saved.album ?: fixVietnameseEncoding(album),
            durationSeconds = max(1, (durationMs / 1000L).toInt()),
            genre = saved.genre ?: folderLabel,
            color1 = palette.first,
            color2 = palette.second,
            lyrics = readLyrics(context, songId, file.absolutePath),
            audioUri = Uri.fromFile(file),
            albumArtBitmap = art?.asImageBitmap(),
            folderPath = folderLabel
        )
    }

    fun uploadDirectory(context: Context): File =
        File(context.filesDir, "uploaded_music").apply { mkdirs() }

    fun lyricsFile(context: Context, songId: Long): File =
        File(context.filesDir, "lyrics").apply { mkdirs() }.let { File(it, "$songId.lrc") }

    suspend fun importLyrics(context: Context, songId: Long, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    String(it.readBytes(), StandardCharsets.UTF_8).removePrefix("\uFEFF")
                } ?: return@runCatching false
                lyricsFile(context, songId).writeText(text, StandardCharsets.UTF_8)
                true
            }.getOrDefault(false)
        }

    /** Reloads only the lyrics of one song (after the user imported a .lrc file). */
    suspend fun reloadLyrics(context: Context, song: Song): List<String> = withContext(Dispatchers.IO) {
        readLyrics(context, song.id, "")
    }

    /** Reloads only the lyrics of one song by id. */
    suspend fun reloadLyrics(context: Context, songId: Long): List<String> = withContext(Dispatchers.IO) {
        readLyrics(context, songId, "")
    }

    // ---------------------------------------------------------------- metadata editing

    sealed interface TagWriteResult {
        data object Written : TagWriteResult
        data class NeedsPermission(val sender: IntentSender) : TagWriteResult
        data class Failed(val message: String) : TagWriteResult
    }

    /** User picked artwork for this song, stored next to the library. */
    private fun artworkOverrideFile(context: Context, songId: Long): File =
        File(context.filesDir, "artwork_overrides").apply { mkdirs() }
            .let { File(it, "$songId.png") }

    suspend fun saveArtworkOverride(
        context: Context,
        songId: Long,
        bitmap: Bitmap
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = if (bitmap.width > 640 || bitmap.height > 640) {
                val ratio = 640f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            artworkOverrideFile(context, songId).outputStream().use {
                scaled.compress(Bitmap.CompressFormat.PNG, 95, it)
            }
            true
        }.getOrDefault(false)
    }

    private fun loadArtworkOverride(context: Context, songId: Long): Bitmap? = runCatching {
        val file = artworkOverrideFile(context, songId)
        if (file.exists() && file.length() > 0L) BitmapFactory.decodeFile(file.absolutePath)
        else null
    }.getOrNull()

    /**
     * Writes the new tags to MediaStore. On Android 10+ tracks that were not
     * created by this app require an explicit user grant; when that happens the
     * caller receives [TagWriteResult.NeedsPermission] and should launch the
     * given [IntentSender] and retry. An app-side copy is always stored first so
     * the edit is visible even if the system write is refused.
     */
    suspend fun writeTags(
        context: Context,
        song: Song,
        title: String,
        artist: String,
        album: String,
        genre: String
    ): TagWriteResult = withContext(Dispatchers.IO) {
        TagStore.save(
            context,
            song.id,
            TagStore.Override(
                title = title.ifBlank { null },
                artist = artist.ifBlank { null },
                album = album.ifBlank { null },
                genre = genre.ifBlank { null }
            )
        )

        val uri = song.audioUri
        // Uploaded files live in app storage, MediaStore knows nothing about them.
        if (uri == null || uri.scheme != "content") return@withContext TagWriteResult.Written

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            runCatching { put(MediaStore.Audio.Media.GENRE, genre) }
        }

        try {
            val rows = context.contentResolver.update(uri, values, null, null)
            if (rows > 0) TagWriteResult.Written
            else TagWriteResult.Failed("MediaStore không ghi được thẻ (0 dòng)")
        } catch (security: SecurityException) {
            val sender = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (security as? android.app.RecoverableSecurityException)
                        ?.userAction?.actionIntent?.intentSender
                } else {
                    null
                }
            }.getOrNull()
            if (sender != null) TagWriteResult.NeedsPermission(sender)
            else TagWriteResult.Failed(security.message ?: "Thiếu quyền sửa thẻ nhạc")
        }
    }

    // ---------------------------------------------------------------- auto-fetch

    /** Directory where downloaded artwork is cached. */
    fun artworkCacheDir(context: Context): File =
        File(context.filesDir, "artwork").apply { mkdirs() }

    /** Directory where downloaded YouTube audio is stored. */
    fun youtubeDownloadDir(context: Context): File =
        File(context.filesDir, "youtube_music").apply { mkdirs() }

    /**
     * Evicts the oldest cached artwork once the cache grows past [maxFiles] or
     * [maxBytes]. Downloads are the smallest assets we keep; music is untouched.
     */
    fun pruneArtworkCache(context: Context, maxFiles: Int = 300, maxBytes: Long = 30L * 1024 * 1024) {
        val dir = artworkCacheDir(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var remainingCount = files.size
        var remainingBytes = files.sumOf { it.length() }
        if (remainingCount <= maxFiles && remainingBytes <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (remainingCount <= maxFiles && remainingBytes <= maxBytes) break
            val size = file.length()
            if (runCatching { file.delete() }.isSuccess) {
                remainingCount -= 1
                remainingBytes -= size
            }
        }
    }

    // ---------------------------------------------------------------- Listening history & recommendations

    private val PREFS_NAME = "listening_history"
    private val KEY_PLAY_COUNT = "play_count_"
    private val KEY_LAST_PLAYED = "last_played_"
    private val KEY_SKIP_COUNT = "skip_count_"

    fun recordPlay(context: Context, songId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt("$KEY_PLAY_COUNT$songId", 0)
        prefs.edit()
            .putInt("$KEY_PLAY_COUNT$songId", count + 1)
            .putLong("$KEY_LAST_PLAYED$songId", System.currentTimeMillis())
            .apply()
    }

    fun recordSkip(context: Context, songId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt("$KEY_SKIP_COUNT$songId", 0)
        prefs.edit().putInt("$KEY_SKIP_COUNT$songId", count + 1).apply()
    }

    fun getPlayCount(context: Context, songId: Long): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("$KEY_PLAY_COUNT$songId", 0)

    fun getLastPlayed(context: Context, songId: Long): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("$KEY_LAST_PLAYED$songId", 0)

    fun getSkipCount(context: Context, songId: Long): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("$KEY_SKIP_COUNT$songId", 0)

    /**
     * Simple recommendation algorithm.
     * Score = (playCount * 2) + (recentPlay bonus) - (skipCount * 5)
     * Boosts songs from same artist/album/genre as recently played songs.
     */
    fun getRecommendations(context: Context, songs: List<Song>, limit: Int = 10): List<Song> {
        if (songs.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val oneWeekMs = 7 * 24 * 60 * 60 * 1000L

        // Find recently played songs (last 7 days)
        val recentSongs = songs.filter { getLastPlayed(context, it.id) > now - oneWeekMs }
        val recentArtists = recentSongs.map { it.artist }.toSet()
        val recentAlbums = recentSongs.map { it.album }.toSet()

        return songs
            .filter { getPlayCount(context, it.id) > 0 || recentSongs.isEmpty() }
            .map { song ->
                var score = 0f
                // Play count bonus
                score += getPlayCount(context, song.id) * 2f
                // Recent play bonus
                val lastPlayed = getLastPlayed(context, song.id)
                if (lastPlayed > 0) {
                    val daysSince = (now - lastPlayed) / (24 * 60 * 60 * 1000L)
                    score += (30 - daysSince.coerceAtMost(30)) * 0.5f
                }
                // Skip penalty
                score -= getSkipCount(context, song.id) * 5f
                // Artist/album boost
                if (song.artist in recentArtists) score += 10f
                if (song.album in recentAlbums) score += 5f
                // Discovery bonus for unplayed songs
                if (getPlayCount(context, song.id) == 0) score += 3f
                song to score
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // ---------------------------------------------------------------- YouTube search

    /** Invidious instance for YouTube search and stream extraction. */
    private const val INVIDIOUS_INSTANCE = "https://yt.chocolatemoo53.com"

    data class YouTubeResult(
        val videoId: String,
        val title: String,
        val author: String,
        val duration: Int, // seconds
        val thumbnailUrl: String,
        val audioStreamUrl: String? = null
    )

    /**
     * Searches YouTube via Invidious API.
     * Streams results: [onResult] is invoked for each video as soon as it is
     * parsed, so the UI can show them progressively instead of waiting for the
     * whole response. Returns the full list.
     *
     * [page] selects the Invidious result page (1-based) for "load more".
     */
    suspend fun searchYouTube(
        query: String,
        page: Int = 1,
        sortBy: String? = null,
        date: String? = null,
        duration: String? = null,
        features: String? = null,
        onResult: ((YouTubeResult) -> Unit)? = null
    ): List<YouTubeResult> = withContext(Dispatchers.IO) {
        runCatching {
            fun okParam(v: String?, allowed: Set<String>) = v?.trim()?.takeIf { it in allowed }
            val sb = okParam(sortBy, setOf("relevance", "rating", "upload_date", "view_count"))
            val dt = okParam(date, setOf("hour", "today", "week", "month", "year"))
            val du = okParam(duration, setOf("short", "long", "medium"))
            val ft = okParam(features, setOf("hd", "subtitles", "creative_commons", "3d", "live", "4k", "360", "location", "hdr"))
            val extra = buildString {
                if (sb != null) append("&sort_by=").append(java.net.URLEncoder.encode(sb, "UTF-8"))
                if (dt != null) append("&date=").append(java.net.URLEncoder.encode(dt, "UTF-8"))
                if (du != null) append("&duration=").append(java.net.URLEncoder.encode(du, "UTF-8"))
                if (ft != null) append("&features=").append(java.net.URLEncoder.encode(ft, "UTF-8"))
            }
            val url = "$INVIDIOUS_INSTANCE/api/v1/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") +
                "&type=video&page=$page$extra"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 20000

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@runCatching emptyList()
            }

            val results = mutableListOf<YouTubeResult>()
            JsonReader(conn.inputStream.bufferedReader()).use { reader ->
                reader.isLenient = true
                reader.beginArray()
                while (reader.hasNext()) {
                    runCatching {
                        var videoId = ""
                        var title = ""
                        var author = ""
                        var duration = 0
                        var thumbUrl = ""
                        var type = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "videoId" -> videoId = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
                                "title" -> title = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
                                "author" -> author = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
                                "type" -> type = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
                                "lengthSeconds" -> duration = when (reader.peek()) {
                                    android.util.JsonToken.NULL -> { reader.nextNull(); 0 }
                                    android.util.JsonToken.NUMBER -> try { reader.nextInt() } catch (_: Exception) { reader.nextDouble().toInt() }
                                    android.util.JsonToken.STRING -> reader.nextString().toIntOrNull() ?: 0
                                    else -> { reader.skipValue(); 0 }
                                }
                                "videoThumbnails" -> {
                                    if (reader.peek() == android.util.JsonToken.NULL) {
                                        reader.nextNull()
                                    } else {
                                        reader.beginArray()
                                        if (reader.hasNext()) {
                                            if (reader.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                                                reader.beginObject()
                                                while (reader.hasNext()) {
                                                    if (reader.nextName() == "url") {
                                                        thumbUrl = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); thumbUrl } else reader.nextString()
                                                    } else {
                                                        reader.skipValue()
                                                    }
                                                }
                                                reader.endObject()
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        while (reader.hasNext()) reader.skipValue()
                                        reader.endArray()
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (type.isNotBlank() && type != "video") return@runCatching
                        if (videoId.isBlank()) return@runCatching
                        if (thumbUrl.isBlank()) {
                            thumbUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                        }
                        val r = YouTubeResult(videoId, title, author, duration, thumbUrl)
                        results.add(r)
                        onResult?.invoke(r)
                    }.onFailure {
                        runCatching { reader.skipValue() }
                    }
                }
                reader.endArray()
            }
            conn.disconnect()
            results
        }.getOrDefault(emptyList())
    }

    private data class AudioFormat(val url: String, val bitrate: Int, val mimeType: String)

    /**
     * Picks the best audio-only stream for [videoId]. When [maxBitrateBps] is
     * given, the highest format not exceeding the cap is chosen; otherwise the
     * bitrate-capped best format wins.
     */
    /** Pure helpers over a parsed /api/v1/videos response. */

    private fun qualityHeight(label: String): Int = when (label.trim().lowercase()) {
        "144p" -> 144
        "240p" -> 240
        "360p" -> 360
        "480p" -> 480
        "720p" -> 720
        "1080p" -> 1080
        "1440p" -> 1440
        "2160p" -> 2160
        else -> 0
    }

    private fun selectVideo(obj: JSONObject, preferred: String? = null): Pair<String, Boolean>? {
        val pref = preferred?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it != "auto" }
        val maxH = when (pref) {
            "144p" -> 144
            "240p" -> 240
            "360p" -> 360
            "480p" -> 480
            "720p" -> 720
            "1080p" -> 1080
            else -> Int.MAX_VALUE
        }

        // 1) Muxed streams (video+audio together), if any exist at all.
        val muxed = obj.optJSONArray("formatStreams")
        if (muxed != null) {
            var bestUrl: String? = null
            var bestH = -1
            var fallbackUrl: String? = null
            var fallbackH = Int.MAX_VALUE
            for (i in 0 until muxed.length()) {
                val fmt = muxed.getJSONObject(i)
                val mimeType = fmt.optString("type", "")
                if (!mimeType.startsWith("video/mp4")) continue
                val streamUrl = fmt.optString("url", "")
                if (streamUrl.isBlank()) continue
                val h = qualityHeight(fmt.optString("qualityLabel"))
                if (h <= 0) continue
                if (h < fallbackH) { fallbackH = h; fallbackUrl = streamUrl }
                if (h <= maxH && h > bestH) {
                    bestH = h
                    bestUrl = streamUrl
                }
            }
            val chosen = bestUrl ?: fallbackUrl
            if (chosen != null) return chosen to true
        }

        // 2) Adaptive streams are video-only; prefer avc1 (H.264) for compatibility.
        val formats = obj.optJSONArray("adaptiveFormats") ?: return null
        var best: Triple<String, Int, Int>? = null // url, height, avcBonus
        var fallback: Triple<String, Int, Int>? = null
        for (i in 0 until formats.length()) {
            val fmt = formats.getJSONObject(i)
            val mimeType = fmt.optString("type", "")
            if (!mimeType.startsWith("video/mp4")) continue
            val streamUrl = fmt.optString("url", "")
            if (streamUrl.isBlank()) continue
            val h = qualityHeight(fmt.optString("qualityLabel"))
            if (h <= 0) continue
            val avc = if (mimeType.contains("avc1")) 1 else 0
            if (fallback == null || h < fallback!!.second || (h == fallback!!.second && avc > fallback!!.third)) {
                fallback = Triple(streamUrl, h, avc)
            }
            // Default cap for adaptive (separated) streams to reduce stalling, unless user asked higher
            val cap = if (pref == null) minOf(maxH, 720) else maxH
            if (h <= cap) {
                if (best == null || h > best!!.second || (h == best!!.second && avc > best!!.third)) {
                    best = Triple(streamUrl, h, avc)
                }
            }
        }
        val chosen = best ?: fallback
        return chosen?.let { it.first to false }
    }

    private fun selectAudio(obj: JSONObject, maxBitrateBps: Int?): AudioFormat? {
        val formats = obj.optJSONArray("adaptiveFormats") ?: return null

        var best: AudioFormat? = null
        var bestUnder: AudioFormat? = null
        for (i in 0 until formats.length()) {
            val fmt = formats.getJSONObject(i)
            val mimeType = fmt.optString("type", "")
            if (!mimeType.startsWith("audio/")) continue
            val streamUrl = fmt.optString("url", "")
            if (streamUrl.isBlank()) continue
            val bitrate = fmt.optInt("bitrate", 0)
            val candidate = AudioFormat(streamUrl, bitrate, mimeType)
            if (maxBitrateBps != null) {
                if (bitrate in 1..maxBitrateBps) {
                    if (best == null || bitrate > best!!.bitrate) best = candidate
                } else if (bestUnder == null || bitrate < bestUnder!!.bitrate) {
                    bestUnder = candidate
                }
            } else {
                if (best == null || bitrate > best!!.bitrate) best = candidate
            }
        }
        return best ?: bestUnder
    }

    private class YouTubeStreams(
        val videoUrl: String?,
        val isMuxed: Boolean,
        val audioUrl: String?
    )

    /**
     * Single metadata round-trip: parses /api/v1/videos once and derives both
     * the video and audio selections (halves the previous two-fetch startup).
     */
    private suspend fun fetchYouTubeStreams(videoId: String, preferredQuality: String? = null): YouTubeStreams? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$INVIDIOUS_INSTANCE/api/v1/videos/$videoId"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@runCatching null
                }
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val obj = JSONObject(json)
                val video = selectVideo(obj, preferredQuality)
                val audio = if (video?.second == true) null else selectAudio(obj, null)?.url
                YouTubeStreams(video?.first, video?.second ?: false, audio)
            }.getOrNull()
        }

    /** Returns a direct URL to a playable video stream for [videoId]. */
    suspend fun getYouTubeVideoUrl(videoId: String, preferredQuality: String? = null): String? =
        fetchYouTubeStreams(videoId, preferredQuality)?.videoUrl

    /** Everything the web remote needs to start a YouTube video on the phone. */
    data class YouTubeStreamInfo(
        val videoUrl: String,
        val audioUrl: String?,
        val title: String,
        val durationSec: Int,
        val thumbUrl: String
    )

    suspend fun getYouTubeStreamInfo(videoId: String, preferredQuality: String? = null): YouTubeStreamInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$INVIDIOUS_INSTANCE/api/v1/videos/$videoId"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@runCatching null
                }
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val obj = JSONObject(json)
                val videoSel = selectVideo(obj, preferredQuality)
                val video = videoSel?.first ?: return@runCatching null
                val audio = if (videoSel.second) null else selectAudio(obj, null)?.url
                val thumb = obj.optJSONArray("videoThumbnails")?.optJSONObject(0)?.optString("url")
                    ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                YouTubeStreamInfo(
                    videoUrl = video,
                    audioUrl = audio,
                    title = obj.optString("title", "YouTube"),
                    durationSec = obj.optInt("lengthSeconds", 0),
                    thumbUrl = thumb
                )
            }.getOrNull()
        }

    /**
     * Returns the (video, audio) stream URLs for [videoId]. When the instance
     * carries muxed streams, audio comes back null (already merged).
     */
    suspend fun getYouTubeVideoAndAudioUrls(videoId: String, preferredQuality: String? = null): Pair<String, String?>? =
        fetchYouTubeStreams(videoId, preferredQuality)?.let { streams ->
            val video = streams.videoUrl ?: return@let null
            video to streams.audioUrl
        }

    private suspend fun pickYouTubeAudioFormat(videoId: String, maxBitrateBps: Int?): AudioFormat? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$INVIDIOUS_INSTANCE/api/v1/videos/$videoId"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@runCatching null
                }

                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val obj = JSONObject(json)
                selectAudio(obj, maxBitrateBps)
            }.getOrNull()
        }

    // ---------------------------------------------------------------- YouTube playlist bulk download

    /**
     * Extracts a playlist id from a YouTube playlist URL ("?list=PL…", "&list=RD…")
     * or accepts a bare playlist id as-is.
     */
    private fun parsePlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val match = Regex("[?&]list=([A-Za-z0-9_-]+)").find(trimmed)
        val id = match?.groupValues?.get(1) ?: trimmed
        return if (Regex("^[A-Za-z0-9_-]{10,}$").matches(id)) id else null
    }

    /** Lists every video in a YouTube playlist via Invidious. */
    suspend fun getYouTubePlaylist(input: String): List<YouTubeResult> = withContext(Dispatchers.IO) {
        val id = parsePlaylistId(input) ?: return@withContext emptyList()
        runCatching {
            val url = "$INVIDIOUS_INSTANCE/api/v1/playlists/$id"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@runCatching emptyList()
            }

            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val obj = JSONObject(json)
            val videos = obj.optJSONArray("videos") ?: return@runCatching emptyList()
            val results = mutableListOf<YouTubeResult>()
            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i)
                val videoId = video.optString("videoId", "")
                if (videoId.isBlank()) continue
                val title = video.optString("title", "")
                val author = video.optString("author", "")
                val duration = video.optInt("lengthSeconds", 0)
                val thumbnails = video.optJSONArray("videoThumbnails")
                val thumbUrl = if (thumbnails != null && thumbnails.length() > 0) {
                    thumbnails.getJSONObject(0).optString("url", "")
                } else {
                    "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                }
                results.add(YouTubeResult(videoId, title, author, duration, thumbUrl))
            }
            results
        }.getOrDefault(emptyList())
    }

    /**
     * Gets the audio stream URL for a YouTube video via Invidious.
     */
    suspend fun getYouTubeAudioUrl(videoId: String): String? =
        pickYouTubeAudioFormat(videoId, null)?.url

    /**
     * Downloads a YouTube audio file and returns the local file.
     * When [maxBitrateBps] is set the highest format at or below the cap is used.
     */
    suspend fun downloadYouTubeAudio(
        context: Context,
        videoId: String,
        title: String,
        maxBitrateBps: Int? = null
    ): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val picked = pickYouTubeAudioFormat(videoId, maxBitrateBps) ?: return@runCatching null
                val ext = when {
                    picked.mimeType.contains("webm") -> "webm"
                    picked.mimeType.contains("mp4") -> "m4a"
                    picked.mimeType.contains("mpeg") -> "mp3"
                    picked.mimeType.contains("ogg") -> "ogg"
                    picked.mimeType.contains("aac") -> "aac"
                    else -> "mp3"
                }
                val dir = youtubeDownloadDir(context)
                val safeName = title.replace(Regex("[^\\w\\s-]"), "").trim().take(50)
                    .ifBlank { "youtube_$videoId" }
                val file = File(dir, "$safeName.$ext")

                val conn = URL(picked.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                file
            }.getOrNull()
        }

    /** Lists offline YouTube downloads, newest first. */
    fun youtubeDownloads(context: Context): List<File> =
        youtubeDownloadDir(context).listFiles()
            ?.filter { it.isFile && it.length() > 0L && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Deletes one offline YouTube download. Returns true on success. */
    fun deleteYoutubeDownload(file: File): Boolean =
        runCatching { file.delete() }.getOrDefault(false)

    /**
     * Fetches album artwork from Cover Art Archive (MusicBrainz).
     * Returns the downloaded Bitmap or null on failure.
     */
    suspend fun fetchArtwork(context: Context, title: String, artist: String): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                // Step 1: Search MusicBrainz for release MBID
                val searchUrl = "https://musicbrainz.org/ws/2/release/?query=" +
                    "release:${java.net.URLEncoder.encode(title, "UTF-8")} AND artist:${java.net.URLEncoder.encode(artist, "UTF-8")}" +
                    "&fmt=json&limit=1"
                val searchConn = URL(searchUrl).openConnection() as HttpURLConnection
                searchConn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
                searchConn.connectTimeout = 10000
                searchConn.readTimeout = 10000
                val searchJson = searchConn.inputStream.bufferedReader().readText()
                searchConn.disconnect()

                val releases = JSONObject(searchJson).optJSONArray("releases") ?: return@runCatching null
                if (releases.length() == 0) return@runCatching null
                val mbid = releases.getJSONObject(0).optString("id") ?: return@runCatching null
                if (mbid.isBlank()) return@runCatching null

                // Step 2: Fetch cover art
                val artUrl = "https://coverartarchive.org/release/$mbid/front-250"
                val artConn = URL(artUrl).openConnection() as HttpURLConnection
                artConn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
                artConn.connectTimeout = 10000
                artConn.readTimeout = 15000
                val bytes = artConn.inputStream.readBytes()
                artConn.disconnect()

                // Cache the artwork
                val cacheFile = File(artworkCacheDir(context), "$mbid.jpg")
                cacheFile.writeBytes(bytes)

                decodeArtwork(bytes, ART_MAX_PX)
            }.getOrNull()
        }

    /**
     * Fetches lyrics from LRCLIB (https://lrclib.net).
     * Returns the LRC-formatted lyrics text or null on failure.
     */
    suspend fun fetchLyrics(context: Context, title: String, artist: String, durationSeconds: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://lrclib.net/api/get?artist_name=" +
                    java.net.URLEncoder.encode(artist, "UTF-8") +
                    "&track_name=" + java.net.URLEncoder.encode(title, "UTF-8") +
                    "&duration=$durationSeconds"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "HK3DMusicPlayer/1.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@runCatching null
                }

                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val obj = JSONObject(json)
                val synced = obj.optString("syncedLyrics", "")
                val plain = obj.optString("plainLyrics", "")
                when {
                    synced.isNotBlank() -> synced
                    plain.isNotBlank() -> plain
                    else -> null
                }
            }.getOrNull()
        }

    /**
     * Saves fetched lyrics to the local lyrics file for a song.
     */
    suspend fun saveFetchedLyrics(context: Context, songId: Long, lyrics: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                lyricsFile(context, songId).writeText(lyrics, StandardCharsets.UTF_8)
                true
            }.getOrDefault(false)
        }
    /**
     */
    fun cachedArtworkFile(context: Context, mbid: String): File =
        File(artworkCacheDir(context), "$mbid.jpg")

    // ---------------------------------------------------------------- helpers

    private fun folderNameOf(dataPath: String): String =
        runCatching { File(dataPath).parentFile?.name ?: "Music" }.getOrDefault("Music")

    /**
     * True when a metadata string cannot be displayed as-is: the original
     * charset was destroyed and replaced by '?' / U+FFFD, or a CJK codepage
     * leaked into a Vietnamese title.
     */
    private fun looksBroken(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.equals("<unknown>", ignoreCase = true)) return true
        if (text.contains('\uFFFD') || text.contains('?')) return true
        if (text.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }) return true
        return text.none { it.isLetter() }
    }

    /**
     * Turns "đoàn-kết-cao-bằng [usesuno.com] (1).mp3" into "Đoàn Kết Cao Bằng",
     * used as a fallback when the embedded tag is corrupted beyond repair.
     */
    private fun titleFromFileName(dataPath: String): String {
        if (dataPath.isBlank()) return ""
        val fileName = File(dataPath).name
        var name = fileName.substringBeforeLast('.', fileName)
        name = name.replace(Regex("\\[[^\\]]*]"), " ")
        name = name.replace(Regex("\\(\\s*\\d+\\s*\\)"), " ")
        name = name.replace(
            Regex("(?i)\\s*[-–—_]?\\s*(?:usesuno\\.com|www\\.[\\w.-]+|[\\w-]+\\.(?:com|net|vn|org))\\s*$"),
            " "
        )
        name = name.replace(Regex("[_\\-]+"), " ")
        name = name.replace(Regex("[–—]+"), " - ")
        name = name.replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) return ""
        // Only re-case names that are entirely lower case, so properly typed
        // file names keep their original capitalisation.
        if (name.none { it.isUpperCase() }) {
            name = name.split(' ').joinToString(" ") { word ->
                if (word.isEmpty()) word else word[0].uppercaseChar() + word.substring(1)
            }
        }
        return name
    }

    private fun readLyrics(context: Context, songId: Long, dataPath: String): List<String> {
        val candidates = mutableListOf<File>()
        if (dataPath.isNotBlank()) {
            val file = File(dataPath)
            val dir = file.parentFile
            val base = file.name.substringBeforeLast('.', file.name)
            if (dir != null) {
                candidates.add(File(dir, "$base.lrc"))
                candidates.add(File(dir, "$base.LRC"))
            }
        }
        candidates.add(lyricsFile(context, songId))
        candidates.add(File(File(context.cacheDir, "lyrics"), "$songId.lrc"))

        for (candidate in candidates) {
            val lines = runCatching {
                if (candidate.exists() && candidate.canRead() && candidate.length() > 0) {
                    // Files saved by Windows editors often start with a UTF-8 BOM,
                    // which would otherwise become part of the first lyric line.
                    candidate.readText(StandardCharsets.UTF_8)
                        .removePrefix("\uFEFF")
                        .lines()
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    private fun readArtwork(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture
            if (bytes != null) decodeArtwork(bytes, ART_MAX_PX) else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeArtwork(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun paletteFrom(bitmap: Bitmap?): Pair<Color, Color> {
        val fallback = Color(0xFF00F2FE) to Color(0xFF4FACFE)
        if (bitmap == null) return fallback

        return runCatching {
            val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0
            for (x in 0 until small.width) {
                for (y in 0 until small.height) {
                    val pixel = small.getPixel(x, y)
                    val pr = (pixel shr 16) and 0xFF
                    val pg = (pixel shr 8) and 0xFF
                    val pb = pixel and 0xFF
                    if (pr + pg + pb < 60 || pr + pg + pb > 720) continue
                    r += pr; g += pg; b += pb; count++
                }
            }
            if (count == 0) return fallback

            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV(
                (r / count).toInt(), (g / count).toInt(), (b / count).toInt(), hsv
            )
            hsv[1] = hsv[1].coerceAtLeast(0.5f)
            hsv[2] = hsv[2].coerceIn(0.45f, 0.95f)
            val primary = Color(android.graphics.Color.HSVToColor(hsv))

            hsv[0] = (hsv[0] + 45f) % 360f
            hsv[1] = (hsv[1] * 0.85f).coerceAtLeast(0.4f)
            val accent = Color(android.graphics.Color.HSVToColor(hsv))
            primary to accent
        }.getOrDefault(fallback)
    }
}

/**
 * Repairs the classic "UTF-8 read as ISO-8859-1" mojibake. Text that is
 * already valid UTF-8 is returned untouched.
 */
fun fixVietnameseEncoding(text: String?): String {
    if (text.isNullOrBlank()) return ""
    // Latin-1 mojibake always contains characters in the U+0080..U+00FF range.
    if (text.none { it.code in 0x80..0xFF }) return text
    return try {
        val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
        val decoded = String(bytes, StandardCharsets.UTF_8)
        val originalBroken = text.count { it.code in 0x80..0xFF }
        val decodedBroken = decoded.count { it == '\uFFFD' }
        if (decodedBroken < originalBroken) decoded else text
    } catch (_: Exception) {
        text
    }
}

/** Local IPv4 address of the Wi-Fi / Ethernet interface, or null when offline. */
fun localIpv4(context: Context): String? {
    runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        props.linkAddresses
            .map { it.address }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?.let { return it }
    }

    runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            if (!intf.isUp || intf.isLoopback) continue
            val addresses = intf.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { return it }
                }
            }
        }
    }
    return null
}
