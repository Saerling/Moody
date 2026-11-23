// LibraryScaffold.kt
//
// The page frame every Library screen shares (collapsing title, search pill, the list below) and
// the search that runs inside it.

package com.saerling.moody.ui.screens.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.saerling.moody.R
import com.saerling.moody.db.entities.Album
import com.saerling.moody.db.entities.Artist
import com.saerling.moody.db.entities.Playlist
import com.saerling.moody.db.entities.Song
import com.saerling.moody.extensions.toMediaItem
import com.saerling.moody.models.toMediaMetadata
import com.saerling.moody.playback.queues.ListQueue
import com.saerling.moody.playback.queues.YouTubeQueue
import com.saerling.moody.ui.component.GlassState
import com.saerling.moody.ui.component.RemoteFocus
import com.saerling.moody.ui.component.glassRoot
import com.saerling.moody.ui.screens.search.SearchColors
import com.saerling.moody.ui.screens.search.SearchField
import com.saerling.moody.ui.screens.search.SearchGlassTopBar
import com.saerling.moody.ui.screens.search.searchTopBarHeight
import com.saerling.moody.viewmodels.ShelfAlbum
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist as InnertubeArtist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem

/**
 * Shared page frame for every Library screen: collapsing title/subtitle, a pinned search pill, and
 * the caller's content below.
 *
 * Designed with Material 3 Expressive guidelines: clean spacing (multiples of 4/8dp), consistent
 * 16dp margins, accessible touch targets (>=48dp), and fluid glassmorphism transitions.
 */
@Composable
fun LibraryScaffold(
    title: String,
    subtitle: String?,
    searchPlaceholder: String,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    listState: LazyListState,
    glassState: GlassState,
    collapseProvider: () -> Float,
    pureBlack: Boolean,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SearchColors.page(pureBlack)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassRoot(glassState, isActive = { collapseProvider() > 0.01f }),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .animateContentSize(),
                contentPadding = PaddingValues(
                    top = searchTopBarHeight(
                        withTitle = true,
                        titleBlockHeight = if (subtitle != null) LibraryTitleBlockHeight else 62.dp,
                    ),
                    bottom = bottomInset + 32.dp,
                ),
                content = content,
            )
        }

        SearchGlassTopBar(
            glassState = glassState,
            progressProvider = collapseProvider,
            pureBlack = pureBlack,
            title = title,
            subtitle = subtitle,
            titleBlockHeight = if (subtitle != null) LibraryTitleBlockHeight else 62.dp,
            trailing = trailing,
        ) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                onSearch = {},
                placeholder = searchPlaceholder,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Search within the library
// ---------------------------------------------------------------------------------------------

/**
 * Displays search results inside the library scaffold.
 * Includes category badge tags, accessible >=48dp touch targets, and a rich empty state with action.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.librarySearchResults(
    results: List<YTItem>,
    query: String,
    onOpen: (YTItem) -> Unit,
    onClearQuery: (() -> Unit)? = null,
) {
    if (results.isEmpty()) {
        item(key = "no_results") {
            LibrarySearchEmptyState(
                query = query,
                onClear = onClearQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 40.dp),
            )
        }
        return
    }

    items(results, key = { it.id }) { item ->
        val typeLabel = when (item) {
            is SongItem -> stringResource(R.string.songs)
            is AlbumItem -> stringResource(R.string.lib_album)
            is ArtistItem -> stringResource(R.string.lib_artist)
            is PlaylistItem -> stringResource(R.string.lib_playlist)
        }
        val subtitle = when (item) {
            is SongItem -> {
                val artists = item.artists.joinToString { it.name }
                if (artists.isNotBlank()) "$artists • $typeLabel" else typeLabel
            }
            is AlbumItem -> {
                val artists = item.artists?.joinToString { it.name }.orEmpty()
                if (artists.isNotBlank()) "$artists • $typeLabel" else typeLabel
            }
            is ArtistItem -> typeLabel
            is PlaylistItem -> item.author?.name?.let { "$it • $typeLabel" } ?: typeLabel
        }

        LibraryListRow(
            title = item.title,
            subtitle = subtitle,
            thumbnailUrl = item.thumbnail,
            onClick = { onOpen(item) },
            onLongClick = { onOpen(item) },
            round = item is ArtistItem,
            songId = (item as? SongItem)?.id,
            sharedElementKey = com.saerling.moody.ui.component.coverTransitionKey(item),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * Modern Spotify/YouTube Music style empty search state: icon + headline + descriptive text + action.
 */
@Composable
fun LibrarySearchEmptyState(
    query: String,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(SearchColors.TileHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.search_off),
                contentDescription = null,
                tint = SearchColors.Secondary,
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.lib_no_results, query),
            color = SearchColors.Primary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.lib_empty_search_desc),
            color = SearchColors.Secondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        if (onClear != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF2E2E32))
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = RemoteFocus,
                        onClick = onClear,
                    )
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.lib_action_clear_search),
                    color = SearchColors.Primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Opens what a search result or pinned tile stands for. [librarySongs] is where a song result is
 * looked up first: a file imported from the device has no YouTube id to start a radio from, so it
 * plays from its file instead - starting a radio with it would fail on the spot.
 */
internal fun openLibraryItem(
    item: YTItem,
    navController: NavController,
    playerConnection: com.saerling.moody.playback.PlayerConnection?,
    librarySongs: List<Song> = emptyList(),
) {
    when (item) {
        is SongItem -> {
            val file = librarySongs.firstOrNull { it.id == item.id }?.takeIf { it.song.isLocal }
            if (file != null) {
                playerConnection?.playQueue(ListQueue(title = file.title, items = listOf(file.toMediaItem())))
            } else {
                playerConnection?.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
            }
        }
        is AlbumItem -> navController.navigate("album/${item.browseId}")
        is ArtistItem -> navController.navigate("artist/${item.id}")
        is PlaylistItem -> navController.navigate(
            if (item.id.startsWith("LP")) "local_playlist/${item.id}" else "online_playlist/${item.id}",
        )
    }
}

internal fun searchLibrary(
    query: String,
    songs: List<Song>,
    artists: List<Artist>,
    albums: List<Album>,
    playlists: List<Playlist>,
): List<YTItem> {
    val q = query.lowercase()
    val results = ArrayList<YTItem>()
    songs.filter { it.song.title.lowercase().contains(q) }.take(8).forEach {
        results += SongItem(
            id = it.id,
            title = it.title,
            artists = it.artists.map { a -> InnertubeArtist(name = a.name, id = a.id) },
            thumbnail = it.song.thumbnailUrl ?: "",
            explicit = it.song.explicit,
        )
    }
    artists.filter { it.artist.name.lowercase().contains(q) }.take(6).forEach {
        results += ArtistItem(
            id = it.id,
            title = it.title,
            thumbnail = it.artist.thumbnailUrl,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }
    albums.filter { it.album.title.lowercase().contains(q) }.take(6).forEach {
        results += AlbumItem(
            browseId = it.id,
            playlistId = it.album.playlistId ?: "",
            title = it.title,
            artists = it.artists.map { a -> InnertubeArtist(name = a.name, id = a.id) },
            thumbnail = it.album.thumbnailUrl ?: "",
            explicit = it.album.explicit,
        )
    }
    playlists.filter { it.playlist.name.lowercase().contains(q) }.take(6).forEach {
        results += PlaylistItem(
            id = it.id,
            title = it.title,
            author = null,
            songCountText = null,
            thumbnail = it.thumbnails.firstOrNull(),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }
    return results
}

/**
 * The library search, over one shelf's things only (the Local or Cached tab).
 */
internal fun searchShelf(
    query: String,
    songs: List<Song>,
    artists: List<Artist>,
    albums: List<ShelfAlbum>,
    playlists: List<Playlist>,
): List<YTItem> {
    val q = query.lowercase()
    val albumHits = albums
        .filter { it.albumId != null && it.title.lowercase().contains(q) }
        .take(6)
        .map {
            AlbumItem(
                browseId = it.albumId!!,
                playlistId = "",
                title = it.title,
                artists = null,
                thumbnail = it.thumbnailUrl ?: "",
            )
        }
    return searchLibrary(query, songs, artists, emptyList(), emptyList()) +
        albumHits +
        searchLibrary(query, emptyList(), emptyList(), emptyList(), playlists)
}
