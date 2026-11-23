// MoodySettings.kt
// moody's settings, from scratch as in the mockup: profile card, a search into every setting, then five groups
// (appearance, sound, sources and accounts, lyrics and player, app). each row opens the page that already held
// that setting, so nothing that could be set before is lost.

package com.saerling.moody.ui.screens.moody

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.saerling.moody.LocalPlayerAwareWindowInsets
import com.saerling.moody.R
import com.saerling.moody.constants.AppFontKey
import com.saerling.moody.constants.LastFMUsernameKey
import com.saerling.moody.constants.ProfilePicUriKey
import com.saerling.moody.constants.PureBlackKey
import com.saerling.moody.constants.UsernameKey
import com.saerling.moody.core.updater.UpdateState
import com.saerling.moody.ui.component.AppSwitch
import com.saerling.moody.ui.component.LocalZoomOutOverlayState
import com.saerling.moody.ui.screens.settings.NowPlayingSettingsRoute
import com.saerling.moody.ui.screens.settings.PlaybackSettingsRoute
import com.saerling.moody.ui.screens.settings.ProxySettingsRoute
import com.saerling.moody.ui.screens.settings.SettingsListRoute
import com.saerling.moody.ui.screens.settings.importsync.ImportSyncRoute
import com.saerling.moody.ui.screens.settings.integrations.IntegrationsRoute
import com.saerling.moody.ui.screens.settings.modules.ModulesRoute
import com.saerling.moody.ui.screens.settings.storage.StorageRoute
import com.saerling.moody.ui.screens.update.rememberUpdateState
import com.saerling.moody.ui.screens.update.showUpdateSheet
import com.saerling.moody.ui.theme.Moody
import com.saerling.moody.ui.theme.MoodyDisplay
import com.saerling.moody.ui.theme.MoodyMono
import com.saerling.moody.ui.theme.UserFont
import com.saerling.moody.utils.rememberPreference

private sealed interface Trail {
    data class Value(val text: String) : Trail
    data class Dot(val color: Color, val text: String) : Trail
    data class Switch(val on: Boolean, val set: (Boolean) -> Unit) : Trail
}

private class Entry(val title: String, val subtitle: String?, val trail: Trail?, val onClick: () -> Unit)

@Composable
fun MoodySettings(navController: NavController) {
    val (pureBlack, setPureBlack) = rememberPreference(PureBlackKey, defaultValue = true)
    val (font, setFont) = rememberPreference(AppFontKey, defaultValue = "moody")
    val username by rememberPreference(UsernameKey, defaultValue = "")
    val avatar by rememberPreference(ProfilePicUriKey, defaultValue = "")
    val lastfm by rememberPreference(LastFMUsernameKey, defaultValue = "")
    val update by rememberUpdateState()
    val popup = LocalZoomOutOverlayState.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val go: (String) -> () -> Unit = { route -> { navController.navigate(route) } }
    val fontName = when (font) {
        "inter" -> "Inter"; "system" -> stringResource(R.string.inspo_font_system); "serif" -> "Serif"; "mono" -> "Mono"; else -> "Atkinson"
    }
    val updateTrail = when (update) {
        is UpdateState.Available -> Trail.Dot(Moody.Blue, stringResource(R.string.moody_update_available))
        is UpdateState.UpToDate -> Trail.Dot(Moody.Green, stringResource(R.string.moody_up_to_date))
        is UpdateState.Failed -> Trail.Dot(Moody.Red, stringResource(R.string.moody_update_failed))
        else -> Trail.Value("…")
    }

    val groups = listOf(
        R.string.moody_group_appearance to listOf(
            Entry(stringResource(R.string.inspo_pitch_black), stringResource(R.string.inspo_pitch_black_desc), Trail.Switch(pureBlack, setPureBlack)) { setPureBlack(!pureBlack) },
            Entry(stringResource(R.string.inspo_font), null, Trail.Value(fontName)) {
                val c = UserFont.choices; setFont(c[(c.indexOf(font) + 1).mod(c.size)])
            },
            Entry(stringResource(R.string.moody_customise), stringResource(R.string.moody_customise_desc), null, go("customisation_settings")),
            Entry(stringResource(R.string.moody_gestures), null, null, go("gesture_settings")),
        ),
        R.string.moody_group_sound to listOf(
            Entry(stringResource(R.string.moody_audio_lab), stringResource(R.string.moody_audio_lab_desc), null, go("audio_lab")),
            Entry(stringResource(R.string.moody_quality), null, null, go("audio_quality")),
            Entry(stringResource(R.string.moody_equalizer_m), null, null, go("equalizer")),
            Entry(stringResource(R.string.moody_transitions), stringResource(R.string.moody_transitions_desc), null, go("crossmix_settings")),
            Entry(stringResource(R.string.moody_playback), null, null, go(PlaybackSettingsRoute)),
        ),
        R.string.moody_group_sources to listOf(
            Entry(stringResource(R.string.moody_modules), stringResource(R.string.moody_modules_desc), null, go(ModulesRoute)),
            Entry(stringResource(R.string.moody_accounts), stringResource(R.string.moody_accounts_desc),
                if (lastfm.isNotBlank()) Trail.Dot(Moody.Green, stringResource(R.string.moody_connected)) else null, go(IntegrationsRoute)),
            Entry(stringResource(R.string.moody_import), stringResource(R.string.moody_import_desc), null, go(ImportSyncRoute)),
            Entry(stringResource(R.string.moody_proxy), null, null, go(ProxySettingsRoute)),
        ),
        R.string.moody_group_player to listOf(
            Entry(stringResource(R.string.moody_now_playing), stringResource(R.string.moody_now_playing_desc), null, go(NowPlayingSettingsRoute)),
        ),
        R.string.moody_group_app to listOf(
            Entry(stringResource(R.string.moody_updates), stringResource(R.string.moody_updates_desc), updateTrail) { popup.showUpdateSheet(update) },
            Entry(stringResource(R.string.moody_storage), stringResource(R.string.moody_storage_desc), null, go(StorageRoute)),
            Entry(stringResource(R.string.moody_general), null, null, go("general_settings")),
            Entry(stringResource(R.string.moody_experimental), null, null, go("experimental_settings")),
            Entry(stringResource(R.string.moody_all_settings), null, null, go(SettingsListRoute)),
        ),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Moody.Night),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "head") {
            Text(stringResource(R.string.settings), style = TextStyle(fontFamily = MoodyDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = Moody.Ink))
        }
        item(key = "profile") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Moody.RadiusL)).background(Moody.DeepBlue).clickable { navController.navigate("stats") }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF3A6FD8)), contentAlignment = Alignment.Center) {
                    if (avatar.isNotBlank()) AsyncImage(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(username.take(1).uppercase().ifEmpty { "M" }, style = TextStyle(fontFamily = MoodyDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Moody.Ink))
                }
                Column(Modifier.weight(1f)) {
                    Text(username.ifBlank { stringResource(R.string.moody_your_profile) }, style = MoodyBody.copy(fontSize = 16.sp))
                    Text(stringResource(R.string.moody_profile_desc), style = MoodyMeta.copy(color = Moody.Ink2))
                }
                Chevron()
            }
        }
        item(key = "search") {
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(Moody.Surface).clickable { navController.navigate(SettingsListRoute) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.search_outline), null, tint = Moody.Ink2, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.moody_search_settings), style = MoodyMeta.copy(fontSize = 15.sp))
            }
        }
        groups.forEach { (title, entries) ->
            item(key = "g$title") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(title).uppercase(), style = MoodyLabel.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 4.dp))
                    Column(Modifier.clip(RoundedCornerShape(Moody.RadiusL)).background(Moody.Surface)) {
                        entries.forEachIndexed { i, e ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Moody.Raised))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = e.onClick).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, style = MoodyBody)
                                    e.subtitle?.let { Text(it, style = MoodyMeta) }
                                }
                                when (val t = e.trail) {
                                    is Trail.Switch -> AppSwitch(checked = t.on, onCheckedChange = t.set)
                                    is Trail.Dot -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(t.color))
                                        Text(t.text, style = TextStyle(fontFamily = MoodyMono, fontSize = 12.sp, color = Moody.Ink2))
                                    }
                                    is Trail.Value -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(t.text, style = TextStyle(fontFamily = MoodyMono, fontSize = 12.sp, color = Moody.Ink2)); Chevron()
                                    }
                                    null -> Chevron()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chevron() {
    Icon(painterResource(R.drawable.navigate_next), null, tint = Moody.Ink3, modifier = Modifier.size(18.dp))
}
