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
    data class Failed(val reason: String, val definitive: Boolean = false, val retryAfterMs: Long? = null) : SwitchResolution
}

sealed interface ProviderSwitchState {
    data object Idle : ProviderSwitchState
    data class Working(val providerId: String, val attempt: Int, val of: Int, val progress: String = "") : ProviderSwitchState
    data class Failed(val providerId: String, val reason: String, val fallbackProviderId: String? = null, val retryAfterMs: Long? = null) : ProviderSwitchState
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
    private val _limitedUntil = MutableStateFlow<Map<String, Long>>(emptyMap())
    val limitedUntil: StateFlow<Map<String, Long>> = _limitedUntil.asStateFlow()
    private val _failedProviders = MutableStateFlow<Set<String>>(emptySet())
    val failedProviders: StateFlow<Set<String>> = _failedProviders.asStateFlow()
    private var job: Job? = null

    fun force(providerId: String) {
        val mediaId = host.currentMediaId() ?: return
        if ((_limitedUntil.value[providerId] ?: 0L) > System.currentTimeMillis()) return
        job?.cancel()
        job = scope().launch {
            val wasPlaying = host.holdPlayback()
            val candidates = (listOf(providerId) + host.availableProviders().filter { it != providerId }).filter { id -> id == providerId || (_limitedUntil.value[id] ?: 0L) <= System.currentTimeMillis() }
            _failedProviders.value = _failedProviders.value - providerId
            val requestedProviderId = providerId
            var fallbackProviderId: String? = null
            var failure = "No se pudo obtener la canción"
            for (candidate in candidates) {
                var result: SwitchResolution? = null
                val tries = if (candidate == providerId) MaxTries else 1
                for (attempt in 1..tries) {
                    _state.value = ProviderSwitchState.Working(candidate, attempt, tries, "Intento $attempt de $tries")
                    result = withTimeoutOrNull(AttemptTimeoutMs) {
                        runCatching { host.resolve(candidate, mediaId) }.onFailure {
                            Timber.tag(Tag).w(it, "$candidate try $attempt failed for $mediaId")
                        }.getOrElse { SwitchResolution.Failed(it.message ?: "Provider error") }
                    } ?: SwitchResolution.Failed("La fuente tardó demasiado en responder")
                    if (result is SwitchResolution.Found) break
                    failure = (result as SwitchResolution.Failed).reason
                    val definitive = (result as SwitchResolution.Failed).definitive
                    val retry = (result as SwitchResolution.Failed).retryAfterMs
                    if (retry != null) {
                        _limitedUntil.value = _limitedUntil.value + (candidate to (System.currentTimeMillis() + retry.coerceAtLeast(0L)))
                    }
                    if (retry != null) break
                    if (definitive || attempt == tries) break
                    delay(if (attempt == 1) 300L else 800L)
                }
                if (host.currentMediaId() != mediaId) { _state.value = ProviderSwitchState.Idle; return@launch }
                if (result is SwitchResolution.Found) {
                    host.switchTo(mediaId, result.route, wasPlaying)
                    _state.value = ProviderSwitchState.Done(
                        providerId = candidate,
                        failedProviderId = requestedProviderId.takeIf { it != candidate },
                        reason = failure.takeIf { requestedProviderId != candidate },
                    )
                    return@launch
                }
                if (candidate != requestedProviderId) fallbackProviderId = candidate
            }
            _failedProviders.value = _failedProviders.value + requestedProviderId
            val wait = _limitedUntil.value[requestedProviderId]?.minus(System.currentTimeMillis())?.takeIf { it > 0L }
            _state.value = ProviderSwitchState.Failed(requestedProviderId, friendlyReason(failure), fallbackProviderId, wait)
            if (wasPlaying) host.resumePlayback()
        }
    }

    fun acknowledge() { if (_state.value !is ProviderSwitchState.Working) _state.value = ProviderSwitchState.Idle }

    companion object {
        fun friendlyReason(reason: String): String {
            val r = reason.lowercase()
            return when {
                listOf("429", "rate limit", "slow down", "saturat").any(r::contains) -> "Está saturado, intenta en un momento"
                listOf("timeout", "timed out", "tardó demasiado").any(r::contains) -> "La fuente tardó demasiado en responder"
                listOf("not found", "no matching", "no encontró").any(r::contains) -> "No se encontró la canción"
                listOf("sign in", "sign-in", "signed out", "credentials").any(r::contains) -> "Inicia sesión en esta fuente"
                else -> reason
            }
        }
        private const val Tag = "ProviderSwitch"
        const val MaxTries = 3
        const val DeadlineMs = 20_000L
        // Spotify's first librespot login plus Web API search can exceed 12 seconds on cold start.
        const val AttemptTimeoutMs = 40_000L
    }
}
