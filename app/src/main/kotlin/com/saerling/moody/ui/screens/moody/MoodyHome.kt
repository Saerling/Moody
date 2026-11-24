// MoodyHome.kt
// moody's home, built from scratch for the "vinilo nocturno" redesign: greeting, the moment picker, a bento of
// "keep listening", this week and sleep, then dense rows of what to play next. it reads the same data the old
// home did (HomeViewModel), so nothing it offered is lost, only how it looks.

package com.saerling.moody.ui.screens.moody

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import com.saerling.moody.LocalDatabase
import com.saerling.moody.LocalPlayerAwareWindowInsets
import com.saerling.moody.LocalPlayerConnection
import com.saerling.moody.R
import com.saerling.moody.db.entities.Song
import com.saerling.moody.extensions.toMediaItem
import com.saerling.moody.playback.queues.ListQueue
import com.saerling.moody.playback.queues.YouTubeQueue
import com.saerling.moody.ui.screens.search.searchRoute
import com.saerling.moody.ui.theme.Moody
import com.saerling.moody.ui.theme.MoodyDisplay
import com.saerling.moody.ui.theme.MoodyMono
import com.saerling.moody.ui.theme.MoodyText
import com.saerling.moody.ui.utils.pressBounce
import com.saerling.moody.ui.utils.rememberBounceSource
import com.saerling.moody.viewmodels.HomeViewModel
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val MoodyLabel = TextStyle(fontFamily = MoodyMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp, color = Moody.Ink3)
internal val MoodySection = TextStyle(fontFamily = MoodyDisplay, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Moody.Ink)
internal val MoodyBody = TextStyle(fontFamily = MoodyText, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Moody.Ink)
internal val MoodyMeta = TextStyle(fontFamily = MoodyText, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = Moody.Ink2)

private enum class Moment(@StringRes val label: Int, val query: String?, val sleepMinutes: Int?) {
    ForYou(R.string.moody_for_you, null, null),
    Focus(R.string.inspo_mode_focus, "lofi focus study music", null),
    Relax(R.string.inspo_mode_relax, "chill relaxing music", null),
    Energy(R.string.inspo_mode_energy, "upbeat energy workout music", null),
    Sleep(R.string.inspo_mode_sleep, "ambient sleep music", 30),
}

@Composable
fun MoodyHome(navController: NavController, viewModel: HomeViewModel = hiltViewModel()) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val history by viewModel.recentHistorySongs.collectAsState()
    val quickPicks by viewModel.quickPicks.collectAsState()
    val forgotten by viewModel.forgottenFavorites.collectAsState()
    val homePage by viewModel.homePage.collectAsState()
    // minutes listened in the last 7 days and the 7 before, for the ring
    val week by remember(database) {
        database.recentEvents(4000).map { events ->
            val now = LocalDateTime.now()
            val thisWeek = events.filter { it.event.timestamp.isAfter(now.minusDays(7)) }.sumOf { it.event.playTime }
            val lastWeek = events.filter { it.event.timestamp.isAfter(now.minusDays(14)) && !it.event.timestamp.isAfter(now.minusDays(7)) }.sumOf { it.event.playTime }
            thisWeek to lastWeek
        }
    }.collectAsState(initial = 0L to 0L)

    fun playSongs(songs: List<Song>, index: Int, title: String) =
        playerConnection.playQueue(ListQueue(title = title, items = songs.map { it.toMediaItem() }, startIndex = index))

    fun openYt(item: YTItem) {
        when (item) {
            is SongItem -> playerConnection.playQueue(YouTubeQueue(WatchEndpoint(videoId = item.id)))
            is AlbumItem -> navController.navigate("album/${item.browseId}")
            is ArtistItem -> navController.navigate("artist/${item.id}")
            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
            else -> Unit
        }
    }

    val keepTitle = stringResource(R.string.moody_keep_listening)
    val quickSongs = quickPicks.orEmpty().filterIsInstance<Song>()
    val now = remember { LocalDateTime.now() }
    val greeting = when (now.hour) {
        in 5..11 -> R.string.moody_good_morning
        in 12..18 -> R.string.moody_good_afternoon
        else -> R.string.moody_good_evening
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    val localPlaylists by viewModel.localPlaylists.collectAsState()
    val accountPlaylists by viewModel.accountPlaylists.collectAsState()
    val made by viewModel.dailyDiscover.collectAsState()
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp

    // prism's shortcuts: liked and downloads first, then your playlists (here and on your accounts) and the albums
    // you played last, two to a row
    val tiles = buildList {
        add(HomeTile(stringResource(R.string.liked_songs), R.drawable.favorite, null, Moody.DeepRed) { navController.navigate("auto_playlist/liked") })
        add(HomeTile(stringResource(R.string.offline), R.drawable.offline, null, Moody.Raised) { navController.navigate("auto_playlist/downloaded") })
        localPlaylists.orEmpty().take(2).forEach { pl ->
            add(HomeTile(pl.playlist.name, R.drawable.queue_music, pl.thumbnails.firstOrNull(), Moody.Raised) { navController.navigate("local_playlist/${pl.id}") })
        }
        accountPlaylists.orEmpty().take(2).forEach { pl ->
            add(HomeTile(pl.title, R.drawable.queue_music, pl.thumbnail, Moody.Raised) { navController.navigate("online_playlist/${pl.id}") })
        }
        history.orEmpty().distinctBy { it.song.albumId ?: it.id }.take(8 - size).forEach { s ->
            add(HomeTile(s.song.albumName ?: s.song.title, R.drawable.album, s.song.thumbnailUrl, Moody.Raised) {
                s.song.albumId?.let { navController.navigate("album/$it") } ?: playSongs(listOf(s), 0, keepTitle)
            })
        }
    }.take(8)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Moody.Night),
        contentPadding = PaddingValues(top = top + 16.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(now.format(DateTimeFormatter.ofPattern("EEE · d MMM", Locale.getDefault())).uppercase(), style = MoodyLabel)
                    Text(stringResource(greeting), style = TextStyle(fontFamily = MoodyDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = Moody.Ink))
                }
                RoundIcon(R.drawable.settings, stringResource(R.string.settings)) { navController.navigate("settings") }
            }
        }
        // the replay teaser on top, as prism opens: this week's listening against the last, a tap opens replay
        item(key = "replay") {
            val (thisWeek, lastWeek) = week
            val ratio = if (lastWeek > 0) (thisWeek.toFloat() / lastWeek).coerceIn(0f, 1f) else if (thisWeek > 0) 1f else 0f
            val source = rememberBounceSource()
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().pressBounce(source).clip(RoundedCornerShape(Moody.RadiusL))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Moody.DeepBlue, Moody.Surface)))
                    .clickable(interactionSource = source, indication = null) { navController.navigate("stats") }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("REPLAY", style = MoodyLabel.copy(color = Moody.Blue))
                    Text(stringResource(R.string.moody_replay_teaser), style = TextStyle(fontFamily = MoodyDisplay, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Moody.Ink))
                    Spacer(Modifier.height(6.dp))
                    Text("${thisWeek / 3_600_000}h ${(thisWeek / 60_000) % 60}m", style = TextStyle(fontFamily = MoodyMono, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Moody.Ink))
                    Text(stringResource(R.string.moody_last_week, "${lastWeek / 3_600_000}h"), style = MoodyMeta.copy(fontSize = 12.sp))
                }
                Ring(ratio, Moody.Green, 72.dp)
            }
        }
        if (tiles.isNotEmpty()) item(key = "tiles") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t -> Tile(t, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (quickSongs.isNotEmpty()) {
            item(key = "quick_title") { SectionTitle(stringResource(R.string.quick_picks)) }
            // columns of four songs that swipe sideways and stop on each column, like prism's (and youtube music's) quick picks
            item(key = "quick") {
                val state = androidx.compose.foundation.lazy.rememberLazyListState()
                val columns = quickSongs.take(20).chunked(4)
                LazyRow(
                    state = state,
                    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(columns, key = { i, _ -> "qp_$i" }) { c, column ->
                        Column(Modifier.width(screenWidth - 64.dp)) {
                            column.forEachIndexed { i, song ->
                                SongRow(song, divider = i > 0) { playSongs(quickSongs, c * 4 + i, keepTitle) }
                            }
                        }
                    }
                }
            }
        }
        made?.takeIf { it.isNotEmpty() }?.let { mixes ->
            item(key = "made_title") { SectionTitle(stringResource(R.string.moody_made_for_you)) }
            item(key = "made") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(mixes.take(12)) { mix ->
                        val item = mix.recommendation
                        Cover(item.thumbnail, item.title, mix.seed.song.title) { openYt(item) }
                    }
                }
            }
        }
        history?.takeIf { it.size > 1 }?.let { songs ->
            item(key = "recent_title") { SectionTitle(stringResource(R.string.inspo_recently_played)) }
            item(key = "recent") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(songs.take(15), key = { _, s -> s.id }) { i, s ->
                        Cover(s.song.thumbnailUrl, s.song.title, s.artists.joinToString { it.name }) { playSongs(songs, i, keepTitle) }
                    }
                }
            }
        }
        forgotten?.takeIf { it.isNotEmpty() }?.let { songs ->
            item(key = "forgotten_title") { SectionTitle(stringResource(R.string.moody_forgotten)) }
            item(key = "forgotten") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    songs.take(5).forEachIndexed { i, song -> SongRow(song, divider = i > 0) { playSongs(songs, i, keepTitle) } }
                }
            }
        }
        // moods as a grid of tiles, each with its accent; sleep also starts a 30 minute timer
        item(key = "moods_title") { SectionTitle(stringResource(R.string.moody_moods)) }
        item(key = "moods") {
            val moods = Moment.entries.filter { it.query != null }
            val accents = listOf(Moody.Blue, Moody.Green, Moody.Red, Moody.Ink2)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                moods.chunked(2).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { i, m ->
                            val accent = accents[(r * 2 + i) % accents.size]
                            val source = rememberBounceSource()
                            Row(
                                Modifier.weight(1f).height(64.dp).pressBounce(source).clip(RoundedCornerShape(Moody.RadiusM)).background(Moody.Surface)
                                    .clickable(interactionSource = source, indication = null) {
                                        m.sleepMinutes?.let { playerConnection.service.sleepTimer.start(it) }
                                        m.query?.let { navController.navigate(searchRoute(it)) }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
                                Text(stringResource(m.label), style = MoodyBody.copy(fontSize = 16.sp), modifier = Modifier.padding(horizontal = 14.dp))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        homePage?.sections.orEmpty().forEachIndexed { index, section ->
            if (section.items.isEmpty()) return@forEachIndexed
            item(key = "yt_title_$index") { SectionTitle(section.title) }
            item(key = "yt_row_$index") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(section.items, key = { it.id }) { item ->
                        val sub = when (item) {
                            is SongItem -> item.artists.joinToString { it.name }
                            is AlbumItem -> item.artists?.joinToString { it.name }.orEmpty()
                            is PlaylistItem -> item.author?.name.orEmpty()
                            else -> ""
                        }
                        Cover(item.thumbnail, item.title, sub, round = item is ArtistItem) { openYt(item) }
                    }
                }
            }
        }
    }
}

private class HomeTile(val title: String, val icon: Int, val image: String?, val ground: Color, val onClick: () -> Unit)

// a shortcut: square art (or an icon on its tint) at the left, the name beside it
@Composable
private fun Tile(tile: HomeTile, modifier: Modifier) {
    val source = rememberBounceSource()
    Row(
        modifier.height(56.dp).pressBounce(source).clip(RoundedCornerShape(Moody.RadiusM)).background(Moody.Surface)
            .clickable(interactionSource = source, indication = null, onClick = tile.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.image != null) {
            AsyncImage(tile.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).background(Moody.Raised))
        } else {
            Box(Modifier.size(56.dp).background(tile.ground), contentAlignment = Alignment.Center) {
                Icon(painterResource(tile.icon), null, tint = Moody.Ink, modifier = Modifier.size(22.dp))
            }
        }
        Text(tile.title, style = MoodyBody.copy(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MoodySection, modifier = Modifier.padding(horizontal = 20.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun SongRow(song: Song, divider: Boolean, onClick: () -> Unit) {
    val source = rememberBounceSource()
    Column {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Moody.Raised))
        Row(
            Modifier.fillMaxWidth().pressBounce(source).clickable(interactionSource = source, indication = null, onClick = onClick).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(song.song.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(Moody.RadiusS)).background(Moody.Raised))
            Column(Modifier.weight(1f)) {
                Text(song.song.title, style = MoodyBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists.joinToString { it.name }, style = MoodyMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun Cover(url: String?, title: String, subtitle: String, round: Boolean = false, onClick: () -> Unit) {
    val source = rememberBounceSource()
    Column(Modifier.width(128.dp).pressBounce(source).clickable(interactionSource = source, indication = null, onClick = onClick)) {
        AsyncImage(
            url, null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(128.dp).clip(if (round) CircleShape else RoundedCornerShape(Moody.RadiusM)).background(Moody.Raised),
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MoodyBody.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = MoodyMeta.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RoundIcon(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Moody.Surface).border(1.dp, Moody.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), label, tint = Moody.Ink, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun PlayKey(label: String, onClick: () -> Unit) {
    val source = rememberBounceSource()
    Box(
        Modifier.pressBounce(source).size(44.dp).clip(CircleShape).background(Moody.Blue).clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(R.drawable.play), label, tint = Moody.OnBlue, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun Ring(fraction: Float, color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val w = 6.dp.toPx()
        val tl = Offset(w / 2, w / 2)
        val s = Size(this.size.width - w, this.size.height - w)
        drawArc(Moody.Line, 0f, 360f, false, tl, s, style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
    }
}
