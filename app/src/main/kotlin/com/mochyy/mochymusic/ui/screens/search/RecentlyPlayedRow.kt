// "Recently played" strip shown above the search history, so the songs you go back to are one tap away
// instead of buried in an alphabetical list. inspired by @bootshoplala's app library search redesign.

package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import kotlinx.coroutines.flow.map

fun LazyListScope.recentlyPlayedItems() {
    item(key = "recently_played_row", contentType = "recently_played") { RecentlyPlayedRow() }
}

@Composable
private fun RecentlyPlayedRow() {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val songs by remember(database) {
        // newest plays first, each song once
        database.recentEvents(60).map { events -> events.map { it.song }.distinctBy { it.id }.take(12) }
    }.collectAsState(initial = emptyList())
    if (songs.isEmpty()) return
    val title = stringResource(R.string.inspo_recently_played)
    Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                Column(
                    Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            playerConnection?.playQueue(
                                ListQueue(title = title, items = songs.map { it.toMediaItem() }, startIndex = index),
                            )
                        },
                ) {
                    AsyncImage(
                        model = song.song.thumbnailUrl,
                        contentDescription = song.song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        song.song.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
