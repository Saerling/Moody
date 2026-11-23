// LibrarySongsScreen.kt
//
// The Songs screen inside Library: Spotify/YouTube Music style experience with filter chips
// (All, Downloaded, Liked), visible Sort & View mode (List vs 2-column Grid) toggle, clean
// Play & Shuffle header bar, accessible touch targets (>=48dp), and rich empty states.

package com.mochyy.mochymusic.ui.screens.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.BoneColor
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.SongSwipeBox
import com.mochyy.mochymusic.ui.component.detail.CircleAction
import com.mochyy.mochymusic.ui.component.detail.PlayPill
import com.mochyy.mochymusic.ui.component.detail.TrackSubtitleStyle
import com.mochyy.mochymusic.ui.component.detail.TrackTitleStyle
import com.mochyy.mochymusic.ui.component.librarySwipeAction
import com.mochyy.mochymusic.ui.component.queueSwipeAction
import com.mochyy.mochymusic.ui.menu.SongMenu
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import com.mochyy.mochymusic.ui.screens.search.rememberCollapseProgress
import com.mochyy.mochymusic.ui.utils.resize
import com.mochyy.mochymusic.viewmodels.LibraryHomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// each source keeps its moody color, the same dot the songs carry
private enum class SongFilterOption(val labelRes: Int, val dot: Color?) {
    ALL(R.string.lib_filter_all, null),
    DOWNLOADED(R.string.lib_filter_downloaded, com.mochyy.mochymusic.ui.theme.Moody.Ink),
    LIKED(R.string.lib_filter_liked, com.mochyy.mochymusic.ui.theme.Moody.Red),
    // where a song comes from, so local files and streamed ones can be told apart in one tap
    LOCAL(R.string.inspo_filter_local, com.mochyy.mochymusic.ui.theme.Moody.Green),
    STREAMING(R.string.inspo_filter_streaming, com.mochyy.mochymusic.ui.theme.Moody.Blue),
}

private enum class SongSortOption(val labelRes: Int) {
    TITLE_ASC(R.string.sort_by_name),
    ARTIST_ASC(R.string.sort_by_artist),
    RECENTLY_ADDED(R.string.sort_by_create_date),
}

@Composable
fun LibrarySongsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val songs by viewModel.songs.collectAsState()
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val scope = rememberCoroutineScope()
    val songsTitle = stringResource(R.string.songs)

    var query by remember { mutableStateOf(TextFieldValue()) }
    var activeFilter by rememberSaveable { mutableStateOf(SongFilterOption.ALL) }
    var activeSort by rememberSaveable { mutableStateOf(SongSortOption.TITLE_ASC) }
    var isGridView by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val glassState = remember { GlassState() }
    val collapse = rememberCollapseProgress(listState)
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    val idle = remember { MutableStateFlow<com.mochyy.mochymusic.models.MediaMetadata?>(null) }
    val metadataFlow = playerConnection.mediaMetadata ?: idle
    val nowPlaying by metadataFlow.collectAsState()
    val isPlaying by (playerConnection.isEffectivelyPlaying ?: remember { MutableStateFlow(false) }).collectAsState()

    // 1. Filter songs by filter chip and search query
    val filteredSongs = remember(songs, activeFilter, query.text) {
        val q = query.text.trim()
        songs.filter { song ->
            val matchesFilter = when (activeFilter) {
                SongFilterOption.ALL -> true
                SongFilterOption.DOWNLOADED -> song.song.isDownloaded || song.song.isLocal
                SongFilterOption.LIKED -> song.song.liked
                SongFilterOption.LOCAL -> song.song.isLocal
                SongFilterOption.STREAMING -> !song.song.isLocal
            }
            val matchesQuery = if (q.isBlank()) {
                true
            } else {
                song.song.title.contains(q, ignoreCase = true) ||
                    song.artists.any { it.name.contains(q, ignoreCase = true) }
            }
            matchesFilter && matchesQuery
        }
    }

    // 2. Sort filtered songs
    val sortedSongs = remember(filteredSongs, activeSort) {
        when (activeSort) {
            SongSortOption.TITLE_ASC -> sortForLibraryIndex(filteredSongs) { it.song.title }
            SongSortOption.ARTIST_ASC -> filteredSongs.sortedBy { it.artists.firstOrNull()?.name?.trim()?.lowercase() ?: "" }
            SongSortOption.RECENTLY_ADDED -> filteredSongs.sortedWith(
                compareByDescending { it.song.inLibrary ?: it.song.dateModified ?: it.song.date },
            )
        }
    }

    // A-Z indexing sections when in list view sorted by Title
    val useSections = !isGridView && activeSort == SongSortOption.TITLE_ASC && query.text.isBlank()
    val sections = remember(sortedSongs, useSections) {
        if (useSections) indexSections(sortedSongs) { it.song.title } else emptyList()
    }

    val sectionIndex = remember(sections) {
        if (sections.isEmpty()) {
            emptyMap()
        } else {
            var offset = 3 // 3 leading items: playbar, filter chips, view toolbar
            buildMap {
                sections.forEach { (sec, entries) ->
                    put(sec.key, offset)
                    offset += 1 + entries.size
                }
            }
        }
    }

    fun playQueue(startIndex: Int, shuffle: Boolean) {
        if (sortedSongs.isEmpty()) return
        val ordered = if (shuffle) sortedSongs.shuffled() else sortedSongs
        playerConnection.playQueue(
            ListQueue(
                title = songsTitle,
                items = ordered.map { it.toMediaItem() },
                startIndex = if (shuffle) 0 else startIndex.coerceAtLeast(0),
            ),
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        LibraryScaffold(
            title = songsTitle,
            subtitle = stringResource(R.plurals.n_song, sortedSongs.size, sortedSongs.size),
            searchPlaceholder = stringResource(R.string.lib_songs_search),
            query = query,
            onQueryChange = { query = it },
            listState = listState,
            glassState = glassState,
            collapseProvider = { collapse.value },
            pureBlack = pureBlack,
            bottomInset = bottomInset,
        ) {
            // Item 0: Play & Shuffle action bar
            item(key = "songs_playbar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayPill(
                        isPlaying = isPlaying && sortedSongs.any { it.id == nowPlaying?.id },
                        onClick = { playQueue(0, shuffle = false) },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    CircleAction(
                        icon = R.drawable.shuffle,
                        contentDescription = stringResource(R.string.shuffle),
                        onClick = { playQueue(0, shuffle = true) },
                        spinOnClick = true,
                    )
                }
            }

            // Item 1: Filter chips row (All, Downloaded, Liked)
            item(key = "songs_filter_chips") {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(SongFilterOption.entries, key = { it.name }) { option ->
                        SongFilterChip(
                            label = stringResource(option.labelRes),
                            dot = option.dot,
                            isSelected = option == activeFilter,
                            onSelect = { activeFilter = option },
                        )
                    }
                }
            }

            // Item 2: Sort by & View Mode (List vs Grid) toolbar
            item(key = "songs_view_toolbar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Sort dropdown button (>=48dp touch target)
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClick = { showSortMenu = true },
                                )
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.tune),
                                contentDescription = stringResource(R.string.menu_sort_by),
                                tint = SearchColors.Secondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(activeSort.labelRes),
                                color = SearchColors.Primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier = Modifier.background(Color(0xFF222226)),
                        ) {
                            SongSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(option.labelRes),
                                            color = if (option == activeSort) SearchColors.Primary else SearchColors.Secondary,
                                            fontWeight = if (option == activeSort) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        activeSort = option
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    }

                    // Grid / List toggle button (>=48dp touch target)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClick = { isGridView = !isGridView },
                            ),
                    ) {
                        Icon(
                            painter = painterResource(if (isGridView) R.drawable.list else R.drawable.grid_view),
                            contentDescription = stringResource(if (isGridView) R.string.lib_view_list else R.string.lib_view_grid),
                            tint = SearchColors.Primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Empty state
            if (sortedSongs.isEmpty()) {
                item(key = "songs_empty") {
                    SongsEmptyState(
                        filter = activeFilter,
                        hasQuery = query.text.isNotBlank(),
                        onClearFilter = { activeFilter = SongFilterOption.ALL },
                        onClearQuery = { query = TextFieldValue() },
                        onExplore = { navController.navigate("search") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 40.dp),
                    )
                }
                return@LibraryScaffold
            }

            // Grid View
            if (isGridView) {
                val chunked = sortedSongs.chunked(2)
                itemsIndexed(chunked, key = { _, pair -> pair.first().id }) { _, pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        pair.forEach { song ->
                            val active = nowPlaying?.id == song.id
                            LibrarySongGridTile(
                                song = song,
                                isActive = active,
                                isPlaying = active && isPlaying,
                                onClick = { playQueue(sortedSongs.indexOf(song), shuffle = false) },
                                onLongClick = {
                                    menuState.show {
                                        SongMenu(originalSong = song, navController = navController, onDismiss = menuState::dismiss)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else if (useSections) {
                // List View with Alphabetical A-Z letter headers
                sections.forEach { (sec, entries) ->
                    item(key = "header_${sec.key}") {
                        LibraryLetterHeader(sec.label)
                    }
                    items(entries, key = { it.id }) { song ->
                        SongListItem(
                            song = song,
                            nowPlayingId = nowPlaying?.id,
                            isPlaying = isPlaying,
                            onClick = { playQueue(sortedSongs.indexOf(song), shuffle = false) },
                            onLongClick = {
                                menuState.show {
                                    SongMenu(originalSong = song, navController = navController, onDismiss = menuState::dismiss)
                                }
                            },
                        )
                    }
                }
            } else {
                // List View flat
                items(sortedSongs, key = { it.id }) { song ->
                    SongListItem(
                        song = song,
                        nowPlayingId = nowPlaying?.id,
                        isPlaying = isPlaying,
                        onClick = { playQueue(sortedSongs.indexOf(song), shuffle = false) },
                        onLongClick = {
                            menuState.show {
                                SongMenu(originalSong = song, navController = navController, onDismiss = menuState::dismiss)
                            }
                        },
                    )
                }
            }
        }

        // Fast-scroll alphabet index rail when active in list view
        if (useSections && sectionIndex.isNotEmpty()) {
            LibraryIndexRail(
                sections = sections.map { it.first },
                onSectionSelected = { key ->
                    sectionIndex[key]?.let { index ->
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SongListItem(
    song: Song,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    SongSwipeBox(
        slab = false,
        library = { librarySwipeAction(song.song) },
        queue = { queueSwipeAction { song.toMediaItem() } },
    ) {
        val active = nowPlayingId == song.id
        LibraryListRow(
            title = song.song.title,
            subtitle = song.artists.joinToString { it.name }.ifBlank { null },
            thumbnailUrl = song.song.thumbnailUrl,
            isActive = active,
            isPlaying = active && isPlaying,
            songId = song.id,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LibrarySongGridTile(
    song: Song,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "songGridTileScale",
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = RemoteFocus,
                onClick = onClick,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(BoneColor),
        ) {
            if (song.song.thumbnailUrl != null) {
                AsyncImage(
                    model = song.song.thumbnailUrl.resize(400, 400),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = SearchColors.Secondary,
                    modifier = Modifier.fillMaxSize(0.35f),
                )
            }

            if (isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = song.song.title,
            style = TrackTitleStyle,
            color = if (isActive) Color(0xFF64B5F6) else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )

        val artistText = song.artists.joinToString { it.name }.ifBlank { null }
        if (artistText != null) {
            Text(
                text = artistText,
                style = TrackSubtitleStyle,
                color = SearchColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SongFilterChip(
    label: String,
    dot: Color?,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val background by animateColorAsState(
        targetValue = if (isSelected) com.mochyy.mochymusic.ui.theme.Moody.Ink else com.mochyy.mochymusic.ui.theme.Moody.Surface,
        animationSpec = tween(180),
        label = "songFilterChipBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) com.mochyy.mochymusic.ui.theme.Moody.Night else com.mochyy.mochymusic.ui.theme.Moody.Ink,
        animationSpec = tween(180),
        label = "songFilterChipContent",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(34.dp)
                .clip(CircleShape)
                .background(background)
                .then(
                    if (!isSelected) {
                        Modifier.border(BorderStroke(1.dp, com.mochyy.mochymusic.ui.theme.Moody.Line), CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    role = Role.Tab,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect()
                    },
                )
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SongsEmptyState(
    filter: SongFilterOption,
    hasQuery: Boolean,
    onClearFilter: () -> Unit,
    onClearQuery: () -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                painter = painterResource(if (hasQuery) R.drawable.search_off else R.drawable.music_note),
                contentDescription = null,
                tint = SearchColors.Secondary,
                modifier = Modifier.size(32.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when {
                hasQuery -> stringResource(R.string.lib_nothing_to_show)
                filter == SongFilterOption.LIKED -> stringResource(R.string.lib_empty_songs_title)
                filter == SongFilterOption.DOWNLOADED -> stringResource(R.string.lib_empty_songs_title)
                else -> stringResource(R.string.lib_empty_songs_title)
            },
            color = SearchColors.Primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (hasQuery) {
                stringResource(R.string.lib_empty_search_desc)
            } else {
                stringResource(R.string.lib_empty_songs_desc)
            },
            color = SearchColors.Secondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        val (actionText, action) = when {
            hasQuery -> stringResource(R.string.lib_action_clear_search) to onClearQuery
            filter != SongFilterOption.ALL -> stringResource(R.string.lib_action_clear_filter) to onClearFilter
            else -> stringResource(R.string.lib_action_explore) to onExplore
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(role = Role.Button, onClick = action)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = actionText,
                color = Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
