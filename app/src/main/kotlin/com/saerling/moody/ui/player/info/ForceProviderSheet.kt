// ForceProviderSheet.kt

package com.saerling.moody.ui.player.info

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saerling.moody.LocalPlayerConnection
import com.saerling.moody.R
import com.saerling.moody.constants.AudioQuality
import com.saerling.moody.constants.AudioQualityKey
import com.saerling.moody.module.StreamOption
import com.saerling.moody.modules.SourceModules
import com.saerling.moody.playback.ProviderSwitchState
import com.saerling.moody.ui.player.menu.LocalMenuSheetClose
import com.saerling.moody.ui.player.menu.MenuRowSurface
import com.saerling.moody.ui.player.menu.MenuSheetSurface
import com.saerling.moody.utils.rememberPreference
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import com.saerling.moody.ui.component.RemoteFocus

private val YouTubeQualities = listOf(AudioQuality.AUTO, AudioQuality.HIGH, AudioQuality.MEDIUM, AudioQuality.LOW)

private fun qualityLabelRes(quality: AudioQuality): Int = when (quality) {
    AudioQuality.HIGH -> R.string.aq_quality_high
    AudioQuality.MEDIUM -> R.string.aq_quality_medium
    AudioQuality.LOW -> R.string.aq_quality_low
    else -> R.string.aq_quality_auto
}

/** "force change": plays this song from another of the providers that are switched on, at the quality picked */
@Composable
fun ForceProviderSheet(currentProviderId: String?, onDismiss: () -> Unit) {
    val service = LocalPlayerConnection.current?.service ?: return
    val modules = service.sourceModules
    val order by modules.order.collectAsState()
    val youTubeOn by modules.youTubeEnabled.collectAsState()
    val entries by modules.entries.collectAsState()
    val state by service.providerSwitch.state.collectAsState()
    val limitedUntil by service.providerSwitch.limitedUntil.collectAsState()
    val failedProviders by service.providerSwitch.failedProviders.collectAsState()
    val playingFrom by service.playingFrom.collectAsState()
    val currentMediaId = service.player.currentMediaItem?.mediaId
    val activeProviderId = currentMediaId?.let { playingFrom[it]?.providerId } ?: currentProviderId
    val (youTubeQuality, setYouTubeQuality) = rememberPreference(AudioQualityKey, AudioQuality.AUTO.name)
    // only what's switched on (and signed in) is offered: a provider that's off couldn't play it anyway
    val providers = remember(order, youTubeOn, entries) { order.filter { modules.isUsable(it) } }

    LaunchedEffect(Unit) { modules.refresh() }
    DisposableEffect(Unit) { onDispose { service.providerSwitch.acknowledge() } }

    MenuSheetSurface(onDismiss = onDismiss, wrapHeight = true) { _ ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        ) {
            InfoColumn(modifier = Modifier.widthIn(max = 600.dp)) {
                InfoSheetTitle(stringResource(R.string.pc_force_title))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.pc_force_hint),
                    color = InfoMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                SwitchStatus(state, nameOf = modules::nameOf)
                providers.forEach { id ->
                    val module = modules.module(id)
                    val choice = module?.choices?.firstOrNull { it.id.contains("quality", ignoreCase = true) }
                    val picked = choice?.let { c -> modules.picks(id)[c.id] ?: c.options.firstOrNull()?.id }
                    ProviderChoiceRow(
                        providerId = id,
                        name = modules.nameOf(id),
                        isCurrent = id == activeProviderId,
                        isLoading = state is ProviderSwitchState.Working && (state as ProviderSwitchState.Working).providerId == id,
                        isFailed = id in failedProviders,
                        limitedUntil = limitedUntil[id],
                        busy = state is ProviderSwitchState.Working,
                        options = when {
                            choice != null && id == "spotify" -> choice.options.map { option ->
                                option.copy(label = when (option.id) {
                                    "320" -> stringResource(R.string.pc_quality_very_high)
                                    "160" -> stringResource(R.string.pc_quality_high)
                                    else -> stringResource(R.string.pc_quality_normal)
                                })
                            }
                            choice != null -> choice.options
                            id == SourceModules.YouTubeId -> YouTubeQualities.map { StreamOption(it.name, stringResource(qualityLabelRes(it)), "") }
                            else -> emptyList()
                        },
                        selectedOption = if (id == SourceModules.YouTubeId) youTubeQuality else picked,
                        onPick = { option ->
                            if (option != null) {
                                if (id == SourceModules.YouTubeId) setYouTubeQuality(option) else choice?.let { modules.setPick(id, it.id, option) }
                            }
                            service.providerSwitch.force(id)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SwitchStatus(state: ProviderSwitchState, nameOf: (String) -> String) {
    AnimatedVisibility(
        visible = state !is ProviderSwitchState.Idle,
        enter = fadeIn(tween(200)) + expandVertically(tween(260)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MenuRowSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            when (state) {
                is ProviderSwitchState.Working -> {
                    Text(stringResource(R.string.pc_force_fetching, nameOf(state.providerId)), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.pc_force_try_of, state.attempt, state.of), color = InfoMuted, fontSize = 12.sp)
                }
                is ProviderSwitchState.Failed -> {
                    Text(stringResource(R.string.pc_force_failed, nameOf(state.providerId), state.reason), color = Color(0xFFFF7A7A), fontSize = 13.sp)
                }
                is ProviderSwitchState.Done -> {
                    Text(
                        if (state.failedProviderId == null) stringResource(R.string.pc_force_now_playing, nameOf(state.providerId))
                        else stringResource(R.string.pc_force_fallback, nameOf(state.failedProviderId), com.saerling.moody.playback.ProviderSwitch.friendlyReason(state.reason.orEmpty()), nameOf(state.providerId)),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
                ProviderSwitchState.Idle -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChoiceRow(
    providerId: String,
    name: String,
    isCurrent: Boolean,
    isLoading: Boolean,
    isFailed: Boolean,
    limitedUntil: Long?,
    busy: Boolean,
    options: List<StreamOption>,
    selectedOption: String?,
    /** null when the row itself was tapped, keeping the quality as it is */
    onPick: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isCurrent) Color(0x332BC5B4) else MenuRowSurface)
            .then(if (isCurrent) Modifier.border(1.dp, Color(0xFF2BC5B4), RoundedCornerShape(18.dp)) else Modifier)
            .clickable(
                        enabled = !busy && (limitedUntil ?: 0L) <= System.currentTimeMillis(),
                interactionSource = remember { MutableInteractionSource() },
                indication = RemoteFocus,
            ) { onPick(null) }
            .padding(horizontal = 14.dp, vertical = 12.dp).semantics { contentDescription = name },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(providerId, name, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the name gives way (ellipsis) so the status chip is never cut
                    Text(text = name, color = Color.White, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isCurrent) { Spacer(Modifier.width(8.dp)); EqualizerMark() }
                    Spacer(Modifier.width(8.dp))
                    ProviderStatusChip(isCurrent, isLoading, isFailed, limitedUntil)
                }
            }
        }
        if (options.size > 1) {
            Spacer(Modifier.height(8.dp))
            // wraps onto a new line instead of running off the card's edge
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    QualityChip(
                        label = option.label,
                        selected = option.id == selectedOption,
                enabled = !busy && (limitedUntil ?: 0L) <= System.currentTimeMillis(),
                        onClick = { onPick(option.id) },
                    )
                }
            }
        }
    }
}

@Composable private fun ProviderStatusChip(current: Boolean, loading: Boolean, failed: Boolean, limitedUntil: Long?) {
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(limitedUntil) { while (limitedUntil != null && limitedUntil > now) { delay(1000); now = System.currentTimeMillis() } }
    val seconds = limitedUntil?.let { ((it - now).coerceAtLeast(0L) + 999L) / 1000L } ?: 0L
    val limited = seconds > 0
    val label = when { limited -> stringResource(R.string.pc_provider_limited, seconds); current -> stringResource(R.string.pc_provider_playing); loading -> stringResource(R.string.pc_provider_loading); failed -> stringResource(R.string.pc_provider_failed); else -> stringResource(R.string.pc_provider_available) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(CircleShape).background(if (limited) Color(0x33FFB020) else if (failed) Color(0x33FF5555) else Color.White.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (loading) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFF2BC5B4)); Spacer(Modifier.width(4.dp)) }
        Text(label, color = if (limited) Color(0xFFFFC04D) else if (failed) Color(0xFFFF7777) else if (current) Color(0xFF63E3D3) else InfoMuted, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

@Composable private fun EqualizerMark() {
    val transition = rememberInfiniteTransition(label = "providerEqualizer")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val scale by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(360 + index * 100), RepeatMode.Reverse), label = "bar$index")
            Box(Modifier.width(3.dp).height(12.dp).graphicsLayer { scaleY = scale }.clip(CircleShape).background(Color(0xFF2BC5B4)))
        }
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Color.White) else Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), shape))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = RemoteFocus, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = label, color = if (selected) Color.Black else Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            if (selected) Text("✓", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
