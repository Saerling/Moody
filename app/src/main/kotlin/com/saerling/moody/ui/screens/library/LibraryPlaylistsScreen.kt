// LibraryPlaylistsScreen.kt

package com.saerling.moody.ui.screens.library

import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.saerling.moody.R
import com.saerling.moody.importer.ImportSource
import com.saerling.moody.ui.component.LocalMenuState
import com.saerling.moody.ui.component.CreatePlaylistDialog
import com.saerling.moody.ui.menu.PlaylistMenu
import com.saerling.moody.viewmodels.LibraryHomeViewModel
import com.saerling.moody.viewmodels.LibraryShelfKind

/**
 * The library's playlists, or with a [shelf] the Local or Cached tab's: only the playlists that
 * have one of that tab's songs in them. With [source], only the playlists imported from that service.
 */
@Composable
fun LibraryPlaylistsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    shelf: LibraryShelfKind? = null,
    source: ImportSource? = null,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val all by (shelf?.let(viewModel::shelf)?.playlists ?: viewModel.playlists).collectAsStateWithLifecycle()
    val playlistSources by viewModel.playlistSources.collectAsState()
    val playlists = remember(all, playlistSources, source) {
        if (source == null) all else all.filter { playlistSources[it.id] == source }
    }
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val resources = LocalContext.current.resources

    LibraryEntityListScreen(
        title = stringResource(R.string.lib_playlist),
        subtitle = when {
            source != null -> stringResource(R.string.lib_playlists_imported_subtitle, source.label)
            shelf == LibraryShelfKind.Local -> stringResource(R.string.lib_playlists_local_subtitle)
            shelf == LibraryShelfKind.Cached -> stringResource(R.string.lib_playlists_cached_subtitle)
            else -> stringResource(R.string.lib_playlists_all_subtitle)
        },
        searchPlaceholder = stringResource(R.string.lib_playlists_search),
        items = playlists,
        idOf = { it.id },
        nameOf = { it.playlist.name },
        subtitleOf = { resources.getQuantityString(R.plurals.lib_song_count, it.songCount, it.songCount) },
        thumbnailOf = { it.thumbnails.firstOrNull() },
        // the same key Home's cards use, so the playlist opens growing out of its cover
        sharedElementKeyOf = { "playlist-${it.id}" },
        onClick = { playlist -> navController.navigate("local_playlist/${playlist.id}") },
        onLongClick = { playlist ->
            menuState.show {
                PlaylistMenu(
                    playlist = playlist,
                    coroutineScope = coroutineScope,
                    onDismiss = menuState::dismiss,
                    navController = navController,
                )
            }
        },
        emptyTitle = stringResource(R.string.lib_empty_playlists_title),
        emptyDescription = stringResource(R.string.lib_empty_playlists_desc),
        emptyActionLabel = stringResource(R.string.lib_action_create_playlist),
        onEmptyAction = { menuState.show { CreatePlaylistDialog(onDismiss = menuState::dismiss) } },
        modifier = modifier,
        pureBlack = pureBlack,
        onRefresh = if (shelf == null && source == null) viewModel::refreshPlaylists else null,
        // las playlists inteligentes viven en su propia página, a un toque desde aquí
        trailing = if (shelf == null && source == null) {
            {
                androidx.compose.material3.IconButton(onClick = { navController.navigate(SmartPlaylistsRoute) }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.AutoAwesome,
                        contentDescription = "Playlists inteligentes",
                    )
                }
            }
        } else null,
    )
}
