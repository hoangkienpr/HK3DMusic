package com.hk.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Locale

/** Queries the device's video library — same MediaStore family we use for audio. */
object VideoRepository {

    fun scan(context: Context): List<VideoItem> {
        val projections = listOf(
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.MIME_TYPE
            ),
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.MIME_TYPE
            ),
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION
            ),
            arrayOf(MediaStore.Video.Media._ID)
        )
        val items = mutableListOf<VideoItem>()
        var scanned = false
        for (projection in projections) {
            if (scanned) break
            val sort = if (projection.contains(MediaStore.Video.Media.DATE_ADDED)) {
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            } else {
                "${MediaStore.Video.Media._ID} DESC"
            }
            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sort
                )?.use { cursor ->
                // Use getColumnIndex (not OrThrow) so a single missing column
                // on a particular device does not discard the whole list.
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val wCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val folderCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)

                if (idCol < 0) return@use
                android.util.Log.e("VideoRepository", "projection=${projection.size} cursorCount=${cursor.count} idCol=$idCol")

                while (cursor.moveToNext()) {
                    runCatching {
                        val id = cursor.getLong(idCol)
                        val uri = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        items += VideoItem(
                            id = id,
                            title = if (titleCol >= 0) cursor.getString(titleCol)?.ifBlank { "Video $id" } ?: "Video $id" else "Video $id",
                            durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                            sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                            width = if (wCol >= 0) cursor.getInt(wCol) else 0,
                            height = if (hCol >= 0) cursor.getInt(hCol) else 0,
                            folder = if (folderCol >= 0) cursor.getString(folderCol) ?: "Movies" else "Movies",
                            uri = uri,
                            dateAdded = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                            mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                        )
                    }
                }
                scanned = true
            }
        }.onFailure { e ->
            android.util.Log.e("VideoRepository", "scan failed", e)
        }
        if (scanned) break
        }
        android.util.Log.e("VideoRepository", "scan returned ${items.size} videos")
        return items
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / 1_048_576.0
        return if (mb >= 1024) {
            String.format(Locale.US, "%.1f GB", mb / 1024)
        } else {
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
