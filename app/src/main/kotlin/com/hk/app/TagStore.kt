package com.hk.app

import android.content.Context

/**
 * Local, persistent overrides for track metadata.
 *
 * MediaStore is the primary store for real device files (see
 * [LibraryRepository.writeTags]), but two cases need an app-side copy:
 *
 *  - files that live in the app's own storage (web uploads) cannot be edited
 *    through MediaStore, and
 *  - artwork cannot be written back by MediaStore at all on modern Android.
 *
 * Overrides win over the values read from the media database, so an edit is
 * visible immediately and survives a restart even if the platform refused the
 * system-wide write.
 */
object TagStore {

    private const val PREFS = "tag_overrides"

    data class Override(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val genre: String? = null
    ) {
        val isEmpty: Boolean
            get() = title == null && artist == null && album == null && genre == null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context, songId: Long): Override {
        val p = prefs(context)
        return Override(
            title = p.getString("title_$songId", null),
            artist = p.getString("artist_$songId", null),
            album = p.getString("album_$songId", null),
            genre = p.getString("genre_$songId", null)
        )
    }

    fun save(context: Context, songId: Long, override: Override) {
        prefs(context).edit()
            .putString("title_$songId", override.title)
            .putString("artist_$songId", override.artist)
            .putString("album_$songId", override.album)
            .putString("genre_$songId", override.genre)
            .apply()
    }
}
