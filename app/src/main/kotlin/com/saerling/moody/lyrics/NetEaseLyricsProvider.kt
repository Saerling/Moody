// NetEaseLyricsProvider.kt

package com.saerling.moody.lyrics

import android.content.Context
import com.music.directlyrics.NetEase
import com.saerling.moody.constants.EnableNetEaseKey
import com.saerling.moody.utils.dataStore
import com.saerling.moody.utils.get

object NetEaseLyricsProvider : LyricsProvider {
    override val name = "NetEase"
    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableNetEaseKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = NetEase.getLyrics(title, artist, duration)
}
