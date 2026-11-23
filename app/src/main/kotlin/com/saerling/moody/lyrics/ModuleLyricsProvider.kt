// a lyrics module as one of moody's lyrics sources, the module registry only hands these out while the module is on

package com.saerling.moody.lyrics

import android.content.Context
import com.saerling.moody.module.LyricsModule
import com.saerling.moody.module.TrackQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModuleLyricsProvider(private val module: LyricsModule) : LyricsProvider {
    override val name = "module:${module.info.id}"

    val displayName: String get() = module.info.name

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        val query = TrackQuery(title, listOf(artist), album, duration.takeIf { it > 0 }?.times(1000L))
        withContext(Dispatchers.IO) { module.lyrics(query) } ?: error("${module.info.name} has no lyrics for $title")
    }
}
