package com.mochyy.mochymusic.module.spotify

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.FieldKind
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleField
import com.mochyy.mochymusic.module.ModuleFields
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SignInSpec
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Songs straight from Spotify, like Spotifast does on the desktop: a librespot session (Premium)
 * opened with the desktop client's OAuth, and each track served already decrypted from a local
 * port, so it plays through Moody's own player with the Audio Lab, the EQ and the crossfade.
 *
 * Sign-in and the session recipe come from Square (github.com/Lelonio/Square, GPL-3.0).
 */
class SpotifyModule : SourceModule, ModuleFields {

    override val info = ModuleInfo(
        id = Id,
        name = "Spotify",
        version = "1.0",
        description = "Plays every song straight from Spotify in Ogg Vorbis up to 320 kbps. Needs a Premium account",
        sourceUrl = "https://github.com/librespot-org/librespot",
        author = "Moody",
    )

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val matches = ConcurrentHashMap<String, CatalogTrack>()
    private val misses = ConcurrentHashMap<String, Long>()

    // one pkce pair per sign-in page, kept until the code comes back
    private var verifier = randomText(64)
    private var state = randomText(16)
    @Volatile private var pendingCode: String? = null

    @Volatile private var user: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var webToken: Pair<String, Long>? = null
    private val startLock = Any()
    private var limitedUntil: Long = 0L

    // like Spotifast: a Client ID of the person's own (developer.spotify.com) gives the search its own
    // quota, instead of sharing the desktop client's with everyone; the session still plays the songs
    @Volatile private var personalId: String = ""
    @Volatile private var personalSecret: String = ""
    @Volatile private var personalToken: Pair<String, Long>? = null
    @Volatile private var personalLimitedUntil: Long = 0L

    override fun attach(host: ModuleHost) {
        this.host = host
        limitedUntil = host.storage.get("search_limited_until")?.toLongOrNull() ?: 0L
        runCatching {
            val obj = json.parseToJsonElement(host.storage.get("search_cache") ?: "{}").jsonObject
            obj["matches"]?.jsonObject?.forEach { (k, v) ->
                val t = v.jsonObject
                matches[k] = CatalogTrack(t["id"]!!.jsonPrimitive.content, t["title"]!!.jsonPrimitive.content, t["artist"]!!.jsonPrimitive.content, t["album"]?.jsonPrimitive?.contentOrNull, t["durationMs"]?.jsonPrimitive?.longOrNull, t["isrc"]?.jsonPrimitive?.contentOrNull)
            }
            obj["misses"]?.jsonObject?.forEach { (k, v) -> misses[k] = v.jsonPrimitive.longOrNull ?: 0L }
        }
    }

    override val signIn: SignInSpec
        get() = SignInSpec(
            startUrl = authorizeUrl(),
            origins = listOf("https://accounts.spotify.com", "https://www.spotify.com", "https://open.spotify.com", "https://challenge.spotify.com"),
            sessionOrigins = emptySet(),
            desktopSite = false,
            title = "Spotify",
            subtitle = "Sign in with a Premium account. Moody plays from your account through librespot.",
        )

    override val fields = listOf(
        ModuleField(
            id = "client_id",
            label = "Client ID propio (opcional)",
            kind = FieldKind.Text,
            hint = "p. ej. 1a2b3c…",
            description = "Como Spotifast: crea una app gratis en developer.spotify.com/dashboard y pega aquí su Client ID. Así la búsqueda usa tu propio límite y Spotify se satura mucho menos.",
        ),
        ModuleField(
            id = "client_secret",
            label = "Client Secret de tu app",
            kind = FieldKind.Text,
            hint = "Ajustes de la app en el panel de Spotify",
            description = "Se guarda solo en este teléfono y solo se usa para pedir el token de búsqueda.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        val id = values["client_id"].orEmpty().trim()
        val secret = values["client_secret"].orEmpty().trim()
        if (id != personalId || secret != personalSecret) {
            personalToken = null
            personalLimitedUntil = 0L
        }
        personalId = id
        personalSecret = secret
    }

    private fun hasPersonal(): Boolean = personalId.isNotEmpty() && personalSecret.isNotEmpty()

    /** an app-only token (client credentials) from the person's own Client ID; null without one or when it fails */
    private fun personalApiToken(): String? {
        if (!hasPersonal()) return null
        personalToken?.let { (token, until) -> if (System.currentTimeMillis() < until) return token }
        val basic = base64("$personalId:$personalSecret".toByteArray())
        val response = runCatching {
            host.http(
                HttpRequest(
                    TOKEN_URL,
                    method = "POST",
                    headers = mapOf("Authorization" to "Basic $basic"),
                    body = "grant_type=client_credentials".toByteArray(),
                    contentType = "application/x-www-form-urlencoded",
                ),
            )
        }.getOrNull()
        if (response == null || !response.ok) {
            host.log("spotify: Client ID propio rechazado (${response?.code}: ${response?.text?.take(120)})")
            return null
        }
        val obj = runCatching { json.parseToJsonElement(response.text).jsonObject }.getOrNull() ?: return null
        val token = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val seconds = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        personalToken = token to (System.currentTimeMillis() + (seconds - 60L).coerceAtLeast(60L) * 1000L)
        return token
    }

    /** how long until some search token may ask again, 0 when one can now */
    private fun searchWaitMs(): Long {
        val now = System.currentTimeMillis()
        val shared = limitedUntil - now
        return if (hasPersonal()) minOf(shared, personalLimitedUntil - now) else shared
    }

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Quality",
            options = listOf(
                StreamOption("320", "Very high", "Ogg Vorbis 320 kbps"),
                StreamOption("160", "High", "Ogg Vorbis 160 kbps, less data"),
                StreamOption("96", "Normal", "Ogg Vorbis 96 kbps, the least data"),
            ),
        ),
    )

    // ---- account ------------------------------------------------------------------------------

    override fun accountState(): AccountState {
        user?.let { return AccountState.SignedIn("Spotify · $it") }
        if (!SpotifyNative.loaded) return AccountState.Expired("Esta versión no incluye el motor de Spotify")
        // a failed first sign-in has no saved user yet, but its reason still has to reach the screen
        val saved = host.storage.get("user") ?: return lastError?.let { AccountState.Expired(it) } ?: AccountState.SignedOut
        return lastError?.let { AccountState.Expired(it) } ?: AccountState.SignedIn("Spotify · $saved")
    }

    override fun onSignInHeader(name: String, value: String, url: String): Boolean {
        if (name != LOOPBACK_HEADER || !value.startsWith(REDIRECT_URI)) return false
        val params = query(value)
        if (params["state"] != state) return false
        pendingCode = params["code"] ?: return false
        return true
    }

    override suspend fun verifyAccount(): AccountState {
        runCatching { ensureStarted() }.onFailure {
            lastError = "El motor de Spotify falló (${it::class.simpleName}: ${it.message})"
            host.log("spotify: $lastError")
        }
        return accountState()
    }

    override fun signOut() {
        if (SpotifyNative.loaded) runCatching { SpotifyNative.nativeSignOut() }
        user = null
        lastError = null
        webToken = null
        host.storage.set("user", null)
        host.storage.set("refresh_token", null)
        matches.clear()
    }

    /** a live session, signing in with the code the sign-in page brought back when there is one; null with [lastError] set otherwise */
    private fun ensureStarted(): Boolean = synchronized(startLock) {
        if (!SpotifyNative.loaded) {
            lastError = "Esta versión no incluye el motor de Spotify"
            return false
        }
        val code = pendingCode
        if (user != null && code == null) return true
        val dir = File(host.folder, "librespot").apply { mkdirs() }.absolutePath

        var token = ""
        lastError = null
        if (code != null) {
            pendingCode = null
            val tokens = exchange(code) ?: run {
                if (lastError == null) lastError = "Spotify no entregó un token"
                return false
            }
            token = tokens.first
            tokens.second?.let { host.storage.set("refresh_token", it) }
        } else if (host.storage.get("user") == null) {
            return false
        }

        var answer = SpotifyNative.nativeStart(dir, token)
        // the kept credential went stale: one more try with a fresh token
        if (answer.startsWith("error:") && token.isEmpty()) {
            refreshed()?.let { answer = SpotifyNative.nativeStart(dir, it) }
        }
        if (answer.startsWith("error:")) {
            val why = answer.removePrefix("error:")
            lastError = if (why.startsWith("not premium")) "Spotify solo reproduce con cuentas Premium" else "No se pudo conectar con Spotify ($why)"
            host.log("spotify: $why")
            return false
        }
        user = answer
        lastError = null
        host.storage.set("user", answer)
        true
    }

    private fun exchange(code: String): Pair<String, String?>? {
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier,
        )
        // the next sign-in gets a fresh pair
        verifier = randomText(64)
        state = randomText(16)
        return postToken(form)?.also { if (it.first.isEmpty()) return null }
    }

    private fun refreshed(): String? {
        val refresh = host.storage.get("refresh_token") ?: return null
        val tokens = postToken(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to CLIENT_ID)) ?: return null
        tokens.second?.let { host.storage.set("refresh_token", it) }
        return tokens.first.takeIf { it.isNotEmpty() }
    }

    private fun postToken(form: Map<String, String>): Pair<String, String?>? {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val response = runCatching {
            host.http(HttpRequest(TOKEN_URL, method = "POST", body = body.toByteArray(), contentType = "application/x-www-form-urlencoded"))
        }.getOrNull()
        if (response == null || !response.ok) {
            val detail = response?.let { "HTTP ${it.code}: ${it.text.take(160)}" } ?: "no connection"
            lastError = if (response?.text?.contains("invalid_grant") == true) "Sesión de Spotify cerrada, inicia sesión de nuevo" else "El inicio de sesión de Spotify no respondió ($detail)"
            if (response?.text?.contains("invalid_grant") == true) host.storage.set("refresh_token", null)
            return null
        }
        val obj = runCatching { json.parseToJsonElement(response.text).jsonObject }.getOrNull()
        val access = obj?.get("access_token")?.jsonPrimitive?.contentOrNull ?: run {
            lastError = "Respuesta de token de Spotify no válida (${response.text.take(160)})"
            return null
        }
        return access to obj["refresh_token"]?.jsonPrimitive?.contentOrNull
    }

    /** a token for the Web API search, from the session itself */
    private fun apiToken(): String? {
        webToken?.let { (token, at) -> if (System.currentTimeMillis() - at < 40 * 60_000L) return token }
        val answer = SpotifyNative.nativeAccessToken()
        val token = if (answer.startsWith("error:")) refreshed() else answer
        token?.let { webToken = it to System.currentTimeMillis() }
        return token
    }

    // ---- songs ----------------------------------------------------------------------------------

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult = diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        report(DiagnosticStep("Motor de Spotify", null, "Conectando…"))
        if (!ensureStarted()) {
            val why = lastError ?: "Inicia sesión en Spotify primero"
            report(DiagnosticStep("Motor de Spotify", false, why))
            return ResolveResult.Unavailable(why)
        }
        report(DiagnosticStep("Motor de Spotify", true, "Conectado como $user"))

        val key = "${track.isrc.orEmpty()}|${track.title}|${track.artists.joinToString(",")}"
        misses[key]?.let { at ->
            if (System.currentTimeMillis() - at < MISS_MEMORY_MS) {
                report(DiagnosticStep("Buscar canción", false, "No está en Spotify"))
                return ResolveResult.NotFound("No está en Spotify")
            }
        }

        report(DiagnosticStep("Buscar canción", null, "Buscando en Spotify…"))
        val song = matches[key] ?: run {
            // only a search waits out the limit: a song found before still plays
            val remaining = searchWaitMs()
            if (remaining > 0) {
                report(DiagnosticStep("Buscar canción", false, "Spotify está saturado, intenta en un momento"))
                return ResolveResult.Unavailable("Spotify está saturado, intenta en un momento", remaining)
            }
            val ownToken = if (hasPersonal() && personalLimitedUntil <= System.currentTimeMillis()) personalApiToken() else null
            val usedPersonal = ownToken != null
            val token = ownToken ?: apiToken().takeIf { limitedUntil <= System.currentTimeMillis() } ?: run {
                report(DiagnosticStep("Buscar canción", false, "Sin token para buscar en Spotify"))
                return ResolveResult.Unavailable("Sin token para buscar en Spotify")
            }
            val queries = buildList {
                track.isrc?.takeIf { it.isNotBlank() }?.let { add("isrc:$it") }
                add("${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim())
            }
            var best: CatalogTrack? = null
            for (q in queries) {
                val response = runCatching {
                    host.http(HttpRequest("$API/search?type=track&limit=10&q=${enc(q)}", headers = mapOf("Authorization" to "Bearer $token")))
                }.getOrElse {
                    host.log("spotify search failed: ${it::class.simpleName}: ${it.message}")
                    report(DiagnosticStep("Buscar canción", false, "La búsqueda de Spotify no respondió"))
                    return ResolveResult.Unavailable("La búsqueda de Spotify no respondió (${it.message ?: it::class.simpleName})")
                }
                if (response.code == 401 && usedPersonal) {
                    personalToken = null
                    report(DiagnosticStep("Buscar canción", false, "Tu Client ID propio fue rechazado; revisa el ID y el Secret"))
                    return ResolveResult.Unavailable("Tu Client ID propio fue rechazado; revisa el ID y el Secret")
                }
                if (response.code == 401) {
                    webToken = null
                    report(DiagnosticStep("Buscar canción", false, "La sesión de búsqueda de Spotify expiró; inicia sesión de nuevo"))
                    return ResolveResult.Unavailable("La sesión de búsqueda de Spotify expiró; inicia sesión de nuevo")
                }
                if (response.code == 429) {
                    val seconds = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull() ?: 30L
                    val wait = (seconds.coerceAtLeast(1L) * 1000L).coerceAtMost(3_600_000L)
                    if (usedPersonal) {
                        personalLimitedUntil = System.currentTimeMillis() + wait
                    } else {
                        limitedUntil = System.currentTimeMillis() + wait
                        host.storage.set("search_limited_until", limitedUntil.toString())
                    }
                    report(DiagnosticStep("Buscar canción", false, "Spotify está saturado, intenta en un momento"))
                    return ResolveResult.Unavailable("Spotify está saturado, intenta en un momento", wait)
                }
                if (response.code >= 500) {
                    report(DiagnosticStep("Buscar canción", false, "La búsqueda de Spotify devolvió ${response.code}"))
                    return ResolveResult.Unavailable("La búsqueda de Spotify devolvió HTTP ${response.code}")
                }
                if (!response.ok) continue
                best = TrackMatcher.best(track, tracksIn(response.text))?.track
                if (best != null) break
            }
            if (best == null) {
                misses[key] = System.currentTimeMillis()
                persistSearchCache()
                report(DiagnosticStep("Buscar canción", false, "No hay una grabación de “${track.title}”"))
                return ResolveResult.NotFound("No se encontró la canción en Spotify")
            }
            matches[key] = best
            persistSearchCache()
            best
        }
        report(DiagnosticStep("Buscar canción", true, "${song.title} · ${song.artist}"))

        val port = SpotifyNative.nativePort()
        if (port <= 0) {
            report(DiagnosticStep("Transmisión", false, "El flujo local no está activo"))
            return ResolveResult.Unavailable("El flujo local no está activo")
        }
        val kbps = picks["quality"] ?: "320"
        report(DiagnosticStep("Transmisión", true, "Ogg Vorbis $kbps kbps"))
        return ResolveResult.Found(
            SourceStream(
                url = "http://127.0.0.1:$port/track/${song.id}?kbps=$kbps",
                format = StreamFormat.Progressive,
                sourceLabel = "Spotify",
                qualityLabel = "Ogg Vorbis $kbps kbps",
                matched = MatchedTrack(song.id, song.title, song.artist, song.album, song.durationMs, confidence = 0.95f),
                codec = "Vorbis",
                sampleRateHz = 44_100,
            ),
        )
    }

    private fun persistSearchCache() {
        val matchEntries = matches.entries.toList().take(500).associate { (k, v) -> k to kotlinx.serialization.json.buildJsonObject {
            put("id", v.id); put("title", v.title); put("artist", v.artist); v.album?.let { put("album", it) }; v.durationMs?.let { put("durationMs", it) }; v.isrc?.let { put("isrc", it) }
        } }
        val missEntries = misses.entries.toList().take(500).associate { it.key to kotlinx.serialization.json.JsonPrimitive(it.value) }
        host.storage.set("search_cache", kotlinx.serialization.json.buildJsonObject { put("matches", kotlinx.serialization.json.JsonObject(matchEntries)); put("misses", kotlinx.serialization.json.JsonObject(missEntries)) }.toString())
    }

    private fun tracksIn(text: String): List<CatalogTrack> {
        val items = runCatching {
            json.parseToJsonElement(text).jsonObject["tracks"]?.jsonObject?.get("items")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (item["is_playable"]?.jsonPrimitive?.contentOrNull == "false") return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            CatalogTrack(
                id = id,
                title = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                artist = (item["artists"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                    .joinToString(", "),
                album = (item["album"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull,
                durationMs = item["duration_ms"]?.jsonPrimitive?.longOrNull,
                isrc = (item["external_ids"] as? JsonObject)?.get("isrc")?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun authorizeUrl(): String {
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val params = mapOf(
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES.joinToString(" "),
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state,
        )
        return AUTHORIZE_URL + "?" + params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    private fun query(link: String): Map<String, String> =
        runCatching { URI(link).rawQuery }.getOrNull().orEmpty().split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }.toMap()

    private fun randomText(bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64(bytes: ByteArray): String = Base64.Default.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        const val Id = "spotify"

        /** librespot's desktop ("keymaster") client id: the only one the access point streams to, and its loopback redirect */
        private const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
        private const val REDIRECT_URI = "http://127.0.0.1:5588/login"
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val API = "https://api.spotify.com/v1"

        /** the same header CaptureWebView sends for a loopback redirect */
        private const val LOOPBACK_HEADER = "x-loopback-redirect"
        private const val MISS_MEMORY_MS = 30 * 60_000L

        private val SCOPES = listOf(
            "streaming",
            "user-read-private",
            "user-read-email",
            "user-library-read",
            "user-library-modify",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-private",
            "playlist-modify-public",
            "user-follow-read",
            "user-top-read",
            "user-read-recently-played",
            "user-read-playback-state",
            "user-modify-playback-state",
        )
    }
}
