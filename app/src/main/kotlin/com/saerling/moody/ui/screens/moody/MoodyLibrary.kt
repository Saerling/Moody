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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
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
import com.saerling.moody.importer.account.AccountService
import com.saerling.moody.importer.account.AutoSyncStatus
import com.saerling.moody.importer.account.AutoSyncStore

/** where a song comes from, as the dot on its cover */
internal fun sourceDot(song: Song): Color = when {
    song.song.isLocal -> Moody.Green
    song.song.isDownloaded -> Moody.Ink
    else -> Moody.Blue
}

// the library as a music app's library has always opened (BitChord, Apple Music): a plain list of places with a
// chevron, the connected sources under them, then only the collections as covers. no filters, counters or bento.
@Composable
fun MoodyLibrary(navController: NavController, viewModel: LibraryHomeViewModel = hiltViewModel()) {
    val songs by viewModel.songs.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val liked by viewModel.likedSongs.collectAsState()
    val imported by viewModel.importedSources.collectAsState()
    val downloaded = remember(songs) { songs.count { it.song.isDownloaded } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val autoStatus by AutoSyncStore.status.collectAsState()
    val autoChanged by AutoSyncStore.changed.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Moody.Night),
        contentPadding = PaddingValues(top = top + 16.dp, bottom = bottom + 24.dp),
    ) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.filter_library),
                    style = TextStyle(fontFamily = MoodyDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Moody.Ink),
                    modifier = Modifier.weight(1f),
                )
                RoundIcon(R.drawable.add, stringResource(R.string.moody_import)) { navController.navigate(ImportProgressRoute) }
            }
        }
        val places = listOf(
            Place(R.drawable.queue_music, R.string.playlists, playlists.size, "library/playlists"),
            Place(R.drawable.person, R.string.artists, artists.size, "library/artists"),
            Place(R.drawable.album, R.string.albums, albums.size, "library/albums"),
            Place(R.drawable.music_note, R.string.songs, songs.size, "library/songs"),
            Place(R.drawable.favorite, R.string.liked, liked.size, "auto_playlist/liked", Moody.Red),
            Place(R.drawable.offline, R.string.offline, downloaded, "auto_playlist/downloaded"),
        )
        items(places.size, key = { "place_${places[it].route}" }) { i ->
            val p = places[i]
            PlaceRow(stringResource(p.label), p.count, p.icon, p.tint, divider = i > 0) { navController.navigate(p.route) }
        }
        // the accounts and services the library is kept in step with, each one a place of its own
        val sources = imported.filter { it.songCount > 0 }
        if (sources.isNotEmpty()) {
            item(key = "sources_gap") { Spacer(Modifier.height(20.dp)) }
            items(sources.size, key = { "source_${sources[it].source.key}" }) { i ->
                val card = sources[i]
                PlaceRow(card.source.label, card.songCount, R.drawable.library_music, Moody.Green, divider = i > 0) {
                    navController.navigate("library/imported/${card.source.key}")
                }
            }
        }
        // spotify, apple music and tidal: kept in step on their own once you've signed in (AccountAutoSync)
        val autoServices = AutoSyncStore.Services.filter { svc ->
            AutoSyncStore.connected(context, svc, imported.any { it.source.key == svc.route })
        }
        if (autoServices.isNotEmpty()) {
            item(key = "auto_title") {
                Text(stringResource(R.string.moody_autosync_title).uppercase(), style = MoodyLabel, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp))
            }
            items(autoServices.size, key = { "auto_${autoServices[it].route}" }) { i ->
                val svc = autoServices[i]
                AutoSyncRow(svc, autoStatus[svc], divider = i > 0, changed = autoChanged)
            }
        }
        if (playlists.isNotEmpty()) {
            item(key = "pl_title") { ShelfTitle(stringResource(R.string.playlists)) { navController.navigate("library/playlists") } }
            item(key = "pl_row") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(playlists.take(5), key = { it.id }) { pl ->
                        Cover(pl.thumbnails.firstOrNull(), pl.playlist.name, "") { navController.navigate("local_playlist/${pl.id}") }
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "al_title") { ShelfTitle(stringResource(R.string.moody_recently_added)) { navController.navigate("library/albums") } }
            val rows = albums.take(6).chunked(2)
            items(rows.size, key = { "al_${rows[it].first().id}" }) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    rows[r].forEach { album ->
                        GridCover(album.thumbnailUrl, album.title, album.artists.joinToString { it.name }, Modifier.weight(1f)) {
                            navController.navigate("album/${album.id}")
                        }
                    }
                    if (rows[r].size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AutoSyncRow(service: AccountService, status: AutoSyncStatus?, divider: Boolean, changed: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on = remember(changed) { AutoSyncStore.isOn(context, service) }
    val last = remember(changed, status) { AutoSyncStore.lastDone(context, service) }
    val ago = if (last > 0) android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString() else ""
    val line = when {
        !on -> stringResource(R.string.moody_autosync_off)
        status is AutoSyncStatus.Syncing -> stringResource(R.string.moody_autosync_syncing)
        status is AutoSyncStatus.NeedsSignIn -> stringResource(R.string.moody_autosync_signin)
        status is AutoSyncStatus.Failed -> stringResource(R.string.moody_autosync_failed)
        last > 0 -> stringResource(R.string.moody_autosync_done, ago)
        else -> stringResource(R.string.moody_autosync_waiting)
    }
    val dot = when {
        !on -> Moody.Ink3
        status is AutoSyncStatus.NeedsSignIn || status is AutoSyncStatus.Failed -> Moody.Red
        status is AutoSyncStatus.Syncing -> Moody.Blue
        else -> Moody.Green
    }
    Column(Modifier.fillMaxWidth().clickable { AutoSyncStore.runSoon(context, service) }) {
        if (divider) Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(1.dp).background(Moody.Line))
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp).clip(CircleShape).background(dot)) }
            Column(Modifier.weight(1f)) {
                Text(service.label, style = MoodyBody.copy(fontSize = 17.sp, fontWeight = FontWeight.Normal), maxLines = 1)
                Text(line, style = MoodyMeta.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            com.saerling.moody.ui.component.AppSwitch(checked = on, onCheckedChange = { AutoSyncStore.setOn(context, service, it) })
        }
    }
}

private class Place(val icon: Int, val label: Int, val count: Int, val route: String, val tint: Color = Moody.Blue)

@Composable
private fun PlaceRow(label: String, count: Int, icon: Int, tint: Color, divider: Boolean, onClick: () -> Unit) {
    val src = rememberBounceSource()
    Column(Modifier.fillMaxWidth().pressBounce(src).clickable(interactionSource = src, indication = null, onClick = onClick)) {
        if (divider) Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(1.dp).background(Moody.Line))
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(24.dp))
            Text(label, style = MoodyBody.copy(fontSize = 17.sp, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count > 0) Text("%,d".format(count).replace(',', '.'), style = TextStyle(fontFamily = MoodyMono, fontSize = 13.sp, color = Moody.Ink3))
            Icon(painterResource(R.drawable.navigate_next), null, tint = Moody.Ink3, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ShelfTitle(text: String, onAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 28.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MoodySection, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onAll), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.navigate_next), text, tint = Moody.Ink2, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun GridCover(url: String?, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    val src = rememberBounceSource()
    Column(modifier.pressBounce(src).clickable(interactionSource = src, indication = null, onClick = onClick)) {
        AsyncImage(
            url, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Moody.RadiusM)).background(Moody.Raised),
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MoodyBody.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = MoodyMeta.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
