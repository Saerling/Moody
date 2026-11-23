// MoodyLibrary.kt
// moody's library, from scratch: title, search, source chips with their colour dot, the four counts as a dense
// module, the shortcuts (liked, downloads, added, imports) and the newest songs with the dot of where they come from.
// it reads LibraryHomeViewModel, so every list the old library had is still one tap away.

package com.saerling.moody.ui.screens.moody

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.saerling.moody.LocalPlayerAwareWindowInsets
import com.saerling.moody.LocalPlayerConnection
import com.saerling.moody.R
import com.saerling.moody.db.entities.Song
import com.saerling.moody.extensions.toMediaItem
import com.saerling.moody.playback.queues.ListQueue
import com.saerling.moody.ui.component.LocalMenuState
import com.saerling.moody.ui.menu.SongMenu
import com.saerling.moody.ui.screens.settings.importsync.ImportProgressRoute
import com.saerling.moody.ui.screens.settings.importsync.ImportSyncRoute
import com.saerling.moody.ui.theme.Moody
import com.saerling.moody.ui.theme.MoodyDisplay
import com.saerling.moody.ui.theme.MoodyMono
import com.saerling.moody.ui.utils.pressBounce
import com.saerling.moody.ui.utils.rememberBounceSource
import com.saerling.moody.viewmodels.LibraryHomeViewModel

private enum class Source(@StringRes val label: Int, val dot: Color?, val keep: (Song) -> Boolean) {
    All(R.string.lib_filter_all, null, { true }),
    Local(R.string.inspo_filter_local, Moody.Green, { it.song.isLocal }),
    Streaming(R.string.inspo_filter_streaming, Moody.Blue, { !it.song.isLocal }),
    Downloads(R.string.lib_filter_downloaded, Moody.Ink, { it.song.isDownloaded }),
    Liked(R.string.lib_filter_liked, Moody.Red, { it.song.liked }),
}

/** where a song comes from, as the dot on its cover */
internal fun sourceDot(song: Song): Color = when {
    song.song.isLocal -> Moody.Green
    song.song.isDownloaded -> Moody.Ink
    else -> Moody.Blue
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoodyLibrary(navController: NavController, viewModel: LibraryHomeViewModel = hiltViewModel()) {
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val songs by viewModel.songs.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val liked by viewModel.likedSongs.collectAsState()
    val imported by viewModel.importedSources.collectAsState()
    var source by rememberSaveable { mutableStateOf(Source.All) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(songs, source, query) {
        val q = query.trim()
        songs.filter(source.keep).filter {
            q.isEmpty() || it.song.title.contains(q, true) || it.artists.any { a -> a.name.contains(q, true) }
        }
    }
    val title = stringResource(R.string.filter_library)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    fun play(index: Int) = playerConnection?.playQueue(ListQueue(title = title, items = shown.map { it.toMediaItem() }, startIndex = index))

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Moody.Night),
        contentPadding = PaddingValues(top = top + 16.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = TextStyle(fontFamily = MoodyDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = Moody.Ink), modifier = Modifier.weight(1f))
                RoundIcon(R.drawable.sync, stringResource(R.string.moody_import)) { navController.navigate(ImportSyncRoute) }
            }
        }
        item(key = "search") {
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp).clip(CircleShape).background(Moody.Surface).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.search_outline), null, tint = Moody.Ink2, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.moody_search_library), style = MoodyMeta.copy(fontSize = 15.sp))
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = MoodyMeta.copy(fontSize = 15.sp, color = Moody.Ink), cursorBrush = SolidColor(Moody.Blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item(key = "chips") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Source.entries, key = { it.name }) { s ->
                    val on = s == source
                    Row(
                        Modifier.height(36.dp).clip(CircleShape).background(if (on) Moody.Ink else Moody.Surface)
                            .then(if (on) Modifier else Modifier.border(1.dp, Moody.Line, CircleShape))
                            .clickable { source = s }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        s.dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
                        Text(stringResource(s.label), style = MoodyBody.copy(fontSize = 14.sp, color = if (on) Moody.Night else Moody.Ink))
                    }
                }
            }
        }
        item(key = "counts") {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(R.string.songs, songs.size, "library/songs"),
                    Triple(R.string.albums, albums.size, "library/albums"),
                    Triple(R.string.artists, artists.size, "library/artists"),
                    Triple(R.string.playlists, playlists.size, "library/playlists"),
                ).forEach { (label, count, route) ->
                    val src = rememberBounceSource()
                    Column(
                        Modifier.weight(1f).pressBounce(src).clip(RoundedCornerShape(Moody.RadiusM)).background(Moody.Surface)
                            .clickable(interactionSource = src, indication = null) { navController.navigate(route) }.padding(12.dp),
                    ) {
                        Text("%,d".format(count).replace(',', '.'), style = TextStyle(fontFamily = MoodyMono, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Moody.Ink), maxLines = 1)
                        Text(stringResource(label), style = MoodyMeta.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item(key = "shortcuts") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Shortcut(stringResource(R.string.liked), "${liked.size}", Moody.DeepRed) { navController.navigate("auto_playlist/liked") } }
                item { Shortcut(stringResource(R.string.offline), "${songs.count { it.song.isDownloaded }}", Moody.Raised) { navController.navigate("auto_playlist/downloaded") } }
                item { Shortcut(stringResource(R.string.moody_added), "${songs.size}", Moody.DeepBlue) { navController.navigate("library/added") } }
                imported.filter { it.songCount > 0 }.forEach { card ->
                    item { Shortcut(card.source.label, "${card.songCount}", Moody.DeepGreen) { navController.navigate("library/imported/${card.source.key}") } }
                }
                item { Shortcut(stringResource(R.string.moody_import), "+", Moody.Surface) { navController.navigate(ImportProgressRoute) } }
            }
        }
        item(key = "recent_title") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.moody_recently_added), style = MoodySection, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.moody_dot_hint), style = MoodyLabel)
            }
        }
        items(shown.take(80).size, key = { shown[it].id }) { i ->
            val song = shown[i]
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { play(i) },
                    onLongClick = { menuState.show { SongMenu(originalSong = song, navController = navController, onDismiss = menuState::dismiss) } },
                ).padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    AsyncImage(song.song.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(Moody.RadiusS)).background(Moody.Raised))
                    Box(Modifier.align(Alignment.BottomEnd).padding(0.dp).size(12.dp).clip(CircleShape).background(Moody.Night).padding(2.dp).clip(CircleShape).background(sourceDot(song)))
                }
                Column(Modifier.weight(1f)) {
                    Text(song.song.title, style = MoodyBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artists.joinToString { it.name }, style = MoodyMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { menuState.show { SongMenu(originalSong = song, navController = navController, onDismiss = menuState::dismiss) } },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.more_vert), stringResource(R.string.moody_more), tint = Moody.Ink2, modifier = Modifier.size(20.dp)) }
            }
        }
        if (shown.size > 80) item(key = "all") {
            Text(
                stringResource(R.string.moody_see_all, shown.size), style = MoodyBody.copy(color = Moody.Blue),
                modifier = Modifier.padding(horizontal = 20.dp).clickable { navController.navigate("library/songs") }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Shortcut(label: String, count: String, ground: Color, onClick: () -> Unit) {
    val src = rememberBounceSource()
    Column(
        Modifier.size(width = 132.dp, height = 84.dp).pressBounce(src).clip(RoundedCornerShape(Moody.RadiusL)).background(ground)
            .clickable(interactionSource = src, indication = null, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(count, style = TextStyle(fontFamily = MoodyMono, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Moody.Ink))
        Text(label, style = MoodyBody.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
