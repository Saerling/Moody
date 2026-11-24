// LiveSmokeTest.kt
// talks to the real youtube music, only when LIVE=1 (the diagnostics workflow): checks the parts moody's sync and
// home stand on still answer. with YT_COOKIE (a repo secret) it also reads the signed-in account's library.

package com.music.innertube

import com.music.innertube.models.PlaylistItem
import com.music.innertube.utils.completed
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveSmokeTest {
    private val live = System.getenv("LIVE") == "1"

    private fun report(step: String, result: Result<*>, detail: (Any?) -> String) {
        result.fold(
            onSuccess = { println("OK   $step: ${detail(it)}") },
            onFailure = { println("FAIL $step: ${it::class.simpleName}: ${it.message?.take(300)}"); it.printStackTrace() },
        )
    }

    @Test
    fun publicEndpoints() = runBlocking {
        assumeTrue(live)
        val visitor = YouTube.visitorData()
        report("visitorData", visitor) { (it as String).take(12) + "…" }
        visitor.getOrNull()?.let { YouTube.visitorData = it }
        report("home", YouTube.home()) { p -> (p as com.music.innertube.pages.HomePage).sections.joinToString { "${it.title}(${it.items.size})" } }
        val search = YouTube.search("lofi", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST)
        report("search playlists", search) { "${(it as com.music.innertube.pages.SearchResult).items.size} items" }
        val first = search.getOrNull()?.items?.filterIsInstance<PlaylistItem>()?.firstOrNull()
        if (first != null) report("playlist ${first.id}", YouTube.playlist(first.id).completed()) { p ->
            (p as com.music.innertube.pages.PlaylistPage).let { "${it.playlist.title}: ${it.songs.size} songs" }
        }
    }

    @Test
    fun signedInLibrary() = runBlocking {
        assumeTrue(live)
        val cookie = System.getenv("YT_COOKIE").orEmpty()
        assumeTrue("no YT_COOKIE secret", cookie.isNotBlank())
        YouTube.cookie = cookie
        YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
        report("accountInfo", YouTube.accountInfo()) { it.toString().take(120) }
        report("saved playlists", YouTube.library("FEmusic_liked_playlists").completed()) { p ->
            (p as com.music.innertube.pages.LibraryPage).items.joinToString { it.title }.take(400)
        }
        report("liked songs (LM)", YouTube.playlist("LM").completed()) { p -> "${(p as com.music.innertube.pages.PlaylistPage).songs.size} songs" }
        report("library songs", YouTube.library("FEmusic_liked_videos").completed()) { "${(it as com.music.innertube.pages.LibraryPage).items.size} items" }
        report("library albums", YouTube.library("FEmusic_liked_albums").completed()) { "${(it as com.music.innertube.pages.LibraryPage).items.size} items" }
        report("subscriptions", YouTube.library("FEmusic_library_corpus_track_artists").completed()) { "${(it as com.music.innertube.pages.LibraryPage).items.size} items" }
    }
}
