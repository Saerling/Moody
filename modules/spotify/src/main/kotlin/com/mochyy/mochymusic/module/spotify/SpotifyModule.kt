package com.mochyy.mochymusic.module.spotify

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleChoice
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

/**
 * Songs straight from Spotify, like Spotifast does on the desktop: a librespot session (Premium)
 * opened with the desktop client's OAuth, and each track served already decrypted from a local
 * port, so it plays through Moody's own player with the Audio Lab, the EQ and the crossfade.
 *
 * Sign-in and the session recipe come from Square (github.com/Lelonio/Square, GPL-3.0).
 */
class SpotifyModule : SourceModule {

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

    override fun attach(host: ModuleHost) {
        this.host = host
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
        if (!SpotifyNative.loaded) return AccountState.Expired("This build has no Spotify engine")
        val saved = host.storage.get("user") ?: return AccountState.SignedOut
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
        ensureStarted()
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
            lastError = "This build has no Spotify engine"
            return false
        }
        val code = pendingCode
        if (user != null && code == null) return true
        val dir = File(host.folder, "librespot").apply { mkdirs() }.absolutePath

        var token = ""
        if (code != null) {
            pendingCode = null
            val tokens = exchange(code) ?: return false
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
            lastError = if (why.startsWith("not premium")) "Spotify only streams to Premium accounts" else "Couldn't connect to Spotify ($why)"
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
            lastError = if (response?.text?.contains("invalid_grant") == true) "Signed out of Spotify, sign in again" else "Spotify's sign-in didn't answer"
            if (response?.text?.contains("invalid_grant") == true) host.storage.set("refresh_token", null)
            return null
        }
        val obj = runCatching { json.parseToJsonElement(response.text).jsonObject }.getOrNull() ?: return null
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
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
        report(DiagnosticStep("Spotify engine", null, "Connecting…"))
        if (!ensureStarted()) {
            val why = lastError ?: "Sign in to Spotify first"
            report(DiagnosticStep("Spotify engine", false, why))
            return ResolveResult.Unavailable(why)
        }
        report(DiagnosticStep("Spotify engine", true, "Signed in as $user"))

        val key = "${track.isrc.orEmpty()}|${track.title}|${track.artists.joinToString(",")}"
        misses[key]?.let { at ->
            if (System.currentTimeMillis() - at < MISS_MEMORY_MS) {
                report(DiagnosticStep("Find the song", false, "Not on Spotify"))
                return ResolveResult.NotFound("Not on Spotify")
            }
        }

        report(DiagnosticStep("Find the song", null, "Searching Spotify…"))
        val song = matches[key] ?: run {
            val token = apiToken() ?: run {
                report(DiagnosticStep("Find the song", false, "No token for Spotify's search"))
                return ResolveResult.Unavailable("No token for Spotify's search")
            }
            val queries = buildList {
                track.isrc?.takeIf { it.isNotBlank() }?.let { add("isrc:$it") }
                add("${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim())
            }
            var best: CatalogTrack? = null
            for (q in queries) {
                val response = runCatching {
                    host.http(HttpRequest("$API/search?type=track&limit=10&q=${enc(q)}", headers = mapOf("Authorization" to "Bearer $token")))
                }.getOrNull()
                if (response == null) {
                    report(DiagnosticStep("Find the song", false, "Spotify didn't answer"))
                    return ResolveResult.Unavailable("Spotify didn't answer")
                }
                if (response.code == 401) webToken = null
                if (response.code == 429) {
                    report(DiagnosticStep("Find the song", false, "Spotify asked to slow down"))
                    return ResolveResult.Unavailable("Spotify asked to slow down")
                }
                if (!response.ok) continue
                best = TrackMatcher.best(track, tracksIn(response.text))?.track
                if (best != null) break
            }
            if (best == null) {
                misses[key] = System.currentTimeMillis()
                report(DiagnosticStep("Find the song", false, "No matching recording of “${track.title}”"))
                return ResolveResult.NotFound("No matching recording on Spotify")
            }
            matches[key] = best
            best
        }
        report(DiagnosticStep("Find the song", true, "${song.title} · ${song.artist}"))

        val port = SpotifyNative.nativePort()
        if (port <= 0) {
            report(DiagnosticStep("Stream", false, "The local stream isn't running"))
            return ResolveResult.Unavailable("The local stream isn't running")
        }
        val kbps = picks["quality"] ?: "320"
        report(DiagnosticStep("Stream", true, "Ogg Vorbis $kbps kbps"))
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
