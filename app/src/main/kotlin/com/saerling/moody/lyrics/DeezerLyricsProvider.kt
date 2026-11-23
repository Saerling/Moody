// DeezerLyricsProvider.kt

package com.saerling.moody.lyrics

import android.content.Context
import com.music.directlyrics.Deezer
import com.saerling.moody.constants.EnableDeezerKey
import com.saerling.moody.utils.dataStore
import com.saerling.moody.utils.get

object DeezerLyricsProvider : LyricsProvider {
    override val name = "Deezer"
    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableDeezerKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = Deezer.getLyrics(title, artist, duration)
}
