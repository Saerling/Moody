// "how do you want to listen?" strip on home: pick a moment instead of hunting for a playlist.
// each card says what it starts and for how long. inspired by @elsehear.fm (listen while studying, relaxing
// or falling asleep) and the "30-sec reading" card in @uixfoysal's Medora.

package com.mochyy.mochymusic.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.ui.screens.search.searchRoute
import com.mochyy.mochymusic.ui.theme.Moody
import com.mochyy.mochymusic.ui.utils.pressBounce
import com.mochyy.mochymusic.ui.utils.rememberBounceSource

private enum class ListeningMode(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val query: String,
    val sleepMinutes: Int?,
    val colors: List<Color>,
) {
    // moody's deep tones: blue to focus, green to relax, red for energy, plain surface to sleep
    Focus(R.string.inspo_mode_focus, R.string.inspo_mode_focus_detail, "lofi focus study music", null, listOf(Moody.DeepBlue, Moody.DeepBlue)),
    Relax(R.string.inspo_mode_relax, R.string.inspo_mode_relax_detail, "chill relaxing music", null, listOf(Moody.DeepGreen, Moody.DeepGreen)),
    Energy(R.string.inspo_mode_energy, R.string.inspo_mode_energy_detail, "upbeat energy workout music", null, listOf(Moody.DeepRed, Moody.DeepRed)),
    Sleep(R.string.inspo_mode_sleep, R.string.inspo_mode_sleep_detail, "ambient sleep music", 30, listOf(Moody.Raised, Moody.Raised)),
}

@Composable
fun ListeningModesRow(navController: NavController, playerConnection: PlayerConnection, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.inspo_modes_title),
            style = HomeLabelStyle,
            color = Moody.Ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ListeningMode.entries, key = { it.name }) { mode ->
                val source = rememberBounceSource()
                Column(
                    Modifier
                        .width(148.dp)
                        .pressBounce(source)
                        .clip(RoundedCornerShape(Moody.RadiusL))
                        .background(Brush.linearGradient(mode.colors))
                        .clickable(interactionSource = source, indication = null) {
                            mode.sleepMinutes?.let { playerConnection.service.sleepTimer.start(it) }
                            navController.navigate(searchRoute(mode.query))
                        }
                        .padding(14.dp),
                ) {
                    Text(stringResource(mode.title), color = Moody.Ink, style = MaterialTheme.typography.titleMedium, fontFamily = com.mochyy.mochymusic.ui.theme.MoodyDisplay, fontWeight = FontWeight.Bold)
                    Text(stringResource(mode.detail), color = Moody.Ink2, style = MaterialTheme.typography.bodySmall, fontFamily = com.mochyy.mochymusic.ui.theme.MoodyMono)
                }
            }
        }
    }
}
