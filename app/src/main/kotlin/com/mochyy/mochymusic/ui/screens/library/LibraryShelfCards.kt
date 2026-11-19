// LibraryShelfCards.kt
//
// What the Local and Cached tabs have in common: the songs they count, and the cards built from
// them - Songs, Playlist, Albums, Artist, then Recently added and Liked. Each tab adds its own
// pieces around these: Local its import cards, Cached the list of every cached song.

package com.mochyy.mochymusic.ui.screens.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.db.entities.Artist
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.LikedCover
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.detail.CircleAction
import com.mochyy.mochymusic.ui.component.detail.PlayPill
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import com.mochyy.mochymusic.viewmodels.LibraryHomeViewModel
import com.mochyy.mochymusic.viewmodels.LibraryShelfKind
import com.mochyy.mochymusic.viewmodels.ShelfAlbum

/** What a Local or Cached tab counts. Only collected while the tab is open: the album grouping isn't free. */
@Immutable
class ShelfTabData(
    val kind: LibraryShelfKind,
    val songs: List<Song>,
    val liked: List<Song>,
    val albums: List<ShelfAlbum>,
    val artists: List<Artist>,
    val playlists: List<Playlist>,
) {
    /** The files imported from the phone, as opposed to downloads. Only the Local tab has any. */
    val files: List<Song> = songs.filter { it.song.isLocal }
}

/**
 * Null until the shelf's songs have been read once, so the tab waits a moment instead of flashing
 * its empty state.
 */
@Composable
fun collectShelfTabData(viewModel: LibraryHomeViewModel, kind: LibraryShelfKind): ShelfTabData? {
    val shelf = viewModel.shelf(kind)
    val loaded by shelf.isLoaded.collectAsStateWithLifecycle()
    val songs by shelf.songs.collectAsStateWithLifecycle()
    val liked by shelf.liked.collectAsStateWithLifecycle()
    val albums by shelf.albums.collectAsStateWithLifecycle()
    val artists by shelf.artists.collectAsStateWithLifecycle()
    val playlists by shelf.playlists.collectAsStateWithLifecycle()
    return remember(kind, loaded, songs, liked, albums, artists, playlists) {
        if (loaded) ShelfTabData(kind, songs, liked, albums, artists, playlists) else null
    }
}

/**
 * The category cards, action bar, Recently added and Liked for Local and Cached tabs.
 * Adheres to Material 3 Expressive guidelines: 16dp margins, multiples of 4/8dp, touch targets >=48dp,
 * and high quality empty states.
 */
internal fun LazyListScope.libraryShelfCards(
    data: ShelfTabData,
    navController: NavController,
    spaceAbove: Dp,
) {
    val key = data.kind.key

    // If completely empty on this shelf, show an expressive empty state with action
    if (data.songs.isEmpty() && data.playlists.isEmpty() && data.albums.isEmpty() && data.artists.isEmpty()) {
        item(key = "${key}_empty_state") {
            Spacer(modifier = Modifier.height(spaceAbove.coerceAtLeast(16.dp)))
            LibraryShelfEmptyState(
                kind = data.kind,
                onAction = {
                    if (data.kind == LibraryShelfKind.Local) {
                        navController.navigate("library/local")
                    } else {
                        navController.navigate("search")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            )
        }
        return
    }

    // Quick Play / Shuffle actions bar when songs exist
    if (data.songs.isNotEmpty()) {
        item(key = "${key}_playbar") {
            Spacer(modifier = Modifier.height(spaceAbove.coerceAtLeast(8.dp)))
            ShelfPlayActionBar(data = data)
            Spacer(modifier = Modifier.height(16.dp))
        }
    } else {
        item(key = "${key}_space_above") {
            Spacer(modifier = Modifier.height(spaceAbove))
        }
    }

    item(key = "${key}_categories") {
        LibraryCardGrid(
            listOf(
                LibraryCardEntry(
                    title = stringResource(R.string.songs),
                    count = data.songs.size,
                    covers = data.songs.take(4).map { it.song.thumbnailUrl },
                    onClick = { navController.navigate("auto_playlist/$key") },
                ),
                LibraryCardEntry(
                    title = stringResource(R.string.lib_playlist),
                    count = data.playlists.size,
                    covers = data.playlists.take(2).flatMap { it.thumbnails },
                    onClick = { navController.navigate("library/$key/playlists") },
                ),
                LibraryCardEntry(
                    title = stringResource(R.string.albums),
                    count = data.albums.size,
                    covers = data.albums.take(4).map { it.thumbnailUrl },
                    onClick = { navController.navigate("library/$key/albums") },
                ),
                LibraryCardEntry(
                    title = stringResource(R.string.lib_artist),
                    count = data.artists.size,
                    covers = data.artists.take(4).map { it.artist.thumbnailUrl },
                    onClick = { navController.navigate("library/$key/artists") },
                    roundCovers = true,
                ),
            ),
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    item(key = "${key}_recently_added") {
        LibraryWideCard(
            title = stringResource(R.string.library_recently_added),
            count = data.songs.size,
            covers = data.songs.take(4).map { it.song.thumbnailUrl },
            onClick = { navController.navigate("library/$key/added") },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (data.liked.isNotEmpty()) {
        item(key = "${key}_liked") {
            LibraryWideCard(
                title = stringResource(R.string.liked),
                count = data.liked.size,
                covers = data.liked.take(4).map { it.song.thumbnailUrl },
                onClick = { navController.navigate("auto_playlist/${key}_liked") },
                sharedElementKey = "playlist-${key}_liked",
                art = { LikedCover(it) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Quick Play & Shuffle actions above shelf category cards.
 */
@Composable
private fun ShelfPlayActionBar(data: ShelfTabData) {
    val playerConnection = LocalPlayerConnection.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayPill(
            isPlaying = false,
            onClick = {
                if (data.songs.isNotEmpty()) {
                    playerConnection?.playQueue(
                        ListQueue(
                            title = data.kind.label,
                            items = data.songs.map { it.toMediaItem() },
                            startIndex = 0,
                        ),
                    )
                }
            },
        )
        Spacer(modifier = Modifier.width(12.dp))
        CircleAction(
            icon = R.drawable.shuffle,
            contentDescription = stringResource(R.string.shuffle),
            onClick = {
                if (data.songs.isNotEmpty()) {
                    playerConnection?.playQueue(
                        ListQueue(
                            title = data.kind.label,
                            items = data.songs.shuffled().map { it.toMediaItem() },
                            startIndex = 0,
                        ),
                    )
                }
            },
            spinOnClick = true,
        )
    }
}

/**
 * Expressive Empty State for Local / Cached shelves with icon, text, and actionable button.
 */
@Composable
fun LibraryShelfEmptyState(
    kind: LibraryShelfKind,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "emptyActionScale",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(SearchColors.TileHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    if (kind == LibraryShelfKind.Local) R.drawable.library_music else R.drawable.music_note,
                ),
                contentDescription = null,
                tint = SearchColors.Secondary,
                modifier = Modifier.size(32.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (kind == LibraryShelfKind.Local) {
                stringResource(R.string.lib_picker_no_local_files)
            } else {
                stringResource(R.string.lib_cached_empty)
            },
            color = SearchColors.Primary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (kind == LibraryShelfKind.Local) {
                stringResource(R.string.lib_local_let_read)
            } else {
                stringResource(R.string.lib_empty_recent_desc)
            },
            color = SearchColors.Secondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(
                        role = Role.Button,
                        interactionSource = interactionSource,
                        indication = RemoteFocus,
                        onClick = onAction,
                    )
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    text = if (kind == LibraryShelfKind.Local) {
                        stringResource(R.string.lib_local_import_here)
                    } else {
                        stringResource(R.string.lib_action_explore)
                    },
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
