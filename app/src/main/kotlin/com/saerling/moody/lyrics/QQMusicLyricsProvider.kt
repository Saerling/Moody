// QQMusicLyricsProvider.kt

package com.saerling.moody.lyrics

import android.content.Context
import com.music.directlyrics.QQMusic
import com.saerling.moody.constants.EnableQQMusicKey
import com.saerling.moody.utils.dataStore
import com.saerling.moody.utils.get

object QQMusicLyricsProvider : LyricsProvider {
    override val name = "QQMusic"
    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableQQMusicKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = QQMusic.getLyrics(title, artist, duration)
}
