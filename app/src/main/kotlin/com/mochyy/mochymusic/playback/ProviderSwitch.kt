package com.mochyy.mochymusic.playback

import com.mochyy.mochymusic.module.SourceStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

data class PlayingFrom(val providerId: String, val name: String, val quality: String? = null, val matchedId: String? = null) {
    companion object { const val Device = "device"; const val Downloads = "downloads" }
}

class ForcedRoute(val providerId: String, @Volatile var freshStream: SourceStream?)
sealed interface SwitchResolution {
    data class Found(val route: ForcedRoute) : SwitchResolution
    data class Failed(val reason: String, val definitive: Boolean = false) : SwitchResolution
}

sealed interface ProviderSwitchState {
    data object Idle : ProviderSwitchState
    data class Working(val providerId: String, val attempt: Int, val of: Int) : ProviderSwitchState
    data class Failed(val providerId: String, val reason: String) : ProviderSwitchState
    data class Done(val providerId: String, val failedProviderId: String? = null, val reason: String? = null) : ProviderSwitchState
}

class ProviderSwitch(private val scope: () -> CoroutineScope, private val host: Host) {
    interface Host {
        fun currentMediaId(): String?
        fun holdPlayback(): Boolean
        fun resumePlayback()
        suspend fun resolve(providerId: String, mediaId: String): SwitchResolution
        fun availableProviders(): List<String>
        fun switchTo(mediaId: String, route: ForcedRoute, play: Boolean)
    }

    private val _state = MutableStateFlow<ProviderSwitchState>(ProviderSwitchState.Idle)
    val state: StateFlow<ProviderSwitchState> = _state.asStateFlow()
    private var job: Job? = null

    fun force(providerId: String) {
        val mediaId = host.currentMediaId() ?: return
        job?.cancel()
        job = scope().launch {
            val wasPlaying = host.holdPlayback()
            val candidates = listOf(providerId) + host.availableProviders().filter { it != providerId }
            var failedId = providerId
            var failure = "No se pudo obtener la canción"
            for (candidate in candidates) {
                var result: SwitchResolution? = null
                val tries = if (candidate == providerId) MaxTries else 1
                for (attempt in 1..tries) {
                    _state.value = ProviderSwitchState.Working(candidate, attempt, tries)
                    result = withTimeoutOrNull(AttemptTimeoutMs) {
                        runCatching { host.resolve(candidate, mediaId) }.onFailure {
                            Timber.tag(Tag).w(it, "$candidate try $attempt failed for $mediaId")
                        }.getOrElse { SwitchResolution.Failed(it.message ?: "Provider error") }
                    } ?: SwitchResolution.Failed("La fuente tardó demasiado en responder")
                    if (result is SwitchResolution.Found) break
                    failure = (result as SwitchResolution.Failed).reason
                    val definitive = (result as SwitchResolution.Failed).definitive
                    if (definitive || attempt == tries) break
                    delay(if (attempt == 1) 300L else 800L)
                }
                if (host.currentMediaId() != mediaId) { _state.value = ProviderSwitchState.Idle; return@launch }
                if (result is SwitchResolution.Found) {
                    host.switchTo(mediaId, result.route, wasPlaying)
                    _state.value = ProviderSwitchState.Done(candidate, failedId.takeIf { it != candidate }, failure.takeIf { it.isNotBlank() })
                    return@launch
                }
                failedId = candidate
            }
            _state.value = ProviderSwitchState.Failed(failedId, failure)
            if (wasPlaying) host.resumePlayback()
        }
    }

    fun acknowledge() { if (_state.value !is ProviderSwitchState.Working) _state.value = ProviderSwitchState.Idle }

    companion object {
        private const val Tag = "ProviderSwitch"
        const val MaxTries = 3
        const val DeadlineMs = 20_000L
        const val AttemptTimeoutMs = 12_000L
    }
}
