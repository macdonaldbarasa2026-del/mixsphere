package com.mixsphere.dj.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class LibraryTrack(val uri: Uri, val title: String, val artist: String, val durationMs: Long)

object TrackLibrary {
    fun load(context: Context): List<LibraryTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val result = ArrayList<LibraryTrack>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val duration = cursor.getLong(durationIndex)
                    if (duration < 1000L) continue
                    val id = cursor.getLong(idIndex)
                    val uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id.toString(),
                    )
                    val title = cursor.getString(titleIndex) ?: "UNTITLED"
                    val artist = cursor.getString(artistIndex) ?: "UNKNOWN"
                    result.add(LibraryTrack(uri, title, artist, duration))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
