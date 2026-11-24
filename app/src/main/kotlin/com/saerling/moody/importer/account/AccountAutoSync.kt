// AccountAutoSync.kt
// keeps the libraries of the accounts you signed in to (Spotify, Apple Music, Tidal) in step with moody on its own,
// the way WaveBits and BitChord keep their spotify library: no import step. every few hours, when you're online, a
// web page nobody sees opens the service with the sign-in you already made, and the same readers the import uses
// bring in the playlists you added there and read the ones you already have again. youtube music needs none of
// this, it syncs through the app's own account (SyncUtils).

package com.saerling.moody.importer.account

import android.content.Context
import android.webkit.CookieManager
import com.saerling.moody.extensions.isInternetConnected
import com.saerling.moody.importer.ImportOptions
import com.saerling.moody.importer.MusicImportService
import com.saerling.moody.importer.PlaylistResync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/** How one service's automatic sync stands, for the library. */
sealed interface AutoSyncStatus {
    data object Waiting : AutoSyncStatus
    data object Syncing : AutoSyncStatus
    data class Done(val at: Long, val added: Int) : AutoSyncStatus
    /** the service's page opened without a session: sign in again from Import */
    data object NeedsSignIn : AutoSyncStatus
    data object Failed : AutoSyncStatus
}

/** The switches, the last runs and what each service's sync is doing, kept across launches. */
object AutoSyncStore {
    private const val Prefs = "account_auto_sync"
    private const val EveryMs = 6 * 60 * 60 * 1000L
    private const val AfterNoSessionMs = 24 * 60 * 60 * 1000L

    val Services = listOf(AccountService.SPOTIFY, AccountService.APPLE_MUSIC, AccountService.TIDAL)

    private val _status = MutableStateFlow<Map<AccountService, AutoSyncStatus>>(emptyMap())
    val status: StateFlow<Map<AccountService, AutoSyncStatus>> = _status.asStateFlow()

    /** bumped when a switch flips, so the library redraws its rows */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)

    fun isOn(context: Context, service: AccountService) = prefs(context).getBoolean("on_${service.route}", true)

    fun setOn(context: Context, service: AccountService, on: Boolean) {
        prefs(context).edit().putBoolean("on_${service.route}", on).apply()
        if (on) prefs(context).edit().remove("next_${service.route}").apply()
        _changed.update { it + 1 }
    }

    /** a session is kept in the web cookies (spotify, apple), or there's something imported from it to keep fresh */
    fun hasSession(service: AccountService): Boolean = runCatching {
        val cookies = CookieManager.getInstance()
        when (service) {
            AccountService.SPOTIFY -> cookies.getCookie("https://open.spotify.com")?.contains("sp_dc=") == true
            AccountService.APPLE_MUSIC -> cookies.getCookie("https://music.apple.com")?.contains("media-user-token=") == true
            else -> false
        }
    }.getOrDefault(false)

    fun connected(context: Context, service: AccountService, hasImports: Boolean) =
        hasSession(service) || hasImports || prefs(context).getLong("done_${service.route}", 0L) > 0L

    fun isDue(context: Context, service: AccountService) =
        isOn(context, service) && System.currentTimeMillis() >= prefs(context).getLong("next_${service.route}", 0L)

    fun lastDone(context: Context, service: AccountService) = prefs(context).getLong("done_${service.route}", 0L)

    fun set(service: AccountService, status: AutoSyncStatus) = _status.update { it + (service to status) }

    fun finish(context: Context, service: AccountService, status: AutoSyncStatus) {
        set(service, status)
        val now = System.currentTimeMillis()
        prefs(context).edit().apply {
            putLong("next_${service.route}", now + if (status is AutoSyncStatus.NeedsSignIn) AfterNoSessionMs else EveryMs)
            if (status is AutoSyncStatus.Done) putLong("done_${service.route}", now)
        }.apply()
    }

    /** "sync now" from the library: run it on the next pass */
    fun runSoon(context: Context, service: AccountService) {
        prefs(context).edit().remove("next_${service.route}").apply()
        _changed.update { it + 1 }
    }
}

/**
 * Runs the passes, one service at a time. It lives as long as the main screen; the hidden page that signs in is
 * drawn by AccountAutoSyncHost while [running] names a service, and hands its headers here.
 */
@HiltViewModel
class AccountAutoSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistResync: PlaylistResync,
    private val importService: MusicImportService,
) : ViewModel() {

    private val _running = MutableStateFlow<AccountService?>(null)
    val running: StateFlow<AccountService?> = _running.asStateFlow()

    @Volatile private var session: AccountImportSession? = null

    init {
        viewModelScope.launch {
            // let the app open first: the hidden page is the heavy part
            delay(StartDelayMs)
            while (true) {
                val next = if (context.isInternetConnected()) nextDue() else null
                if (next == null) {
                    // a flipped switch or "sync now" wakes it early
                    withTimeoutOrNull(IdleMs) { AutoSyncStore.changed.first { it > 0 && nextDue() != null } }
                    continue
                }
                runOne(next)
            }
        }
    }

    private suspend fun nextDue(): AccountService? = withContext(Dispatchers.IO) {
        AutoSyncStore.Services.firstOrNull { service ->
            AutoSyncStore.isDue(context, service) &&
                (AutoSyncStore.hasSession(service) || playlistResync.accountTargets(service).isNotEmpty())
        }
    }

    private suspend fun runOne(service: AccountService) {
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        val s = AccountImportSession(service, context, scope)
        session = s
        AutoSyncStore.set(service, AutoSyncStatus.Syncing)
        _running.value = service
        s.start()
        val result = withTimeoutOrNull(PassTimeoutMs) { syncWith(service, s) } ?: run {
            // the page never handed over a signed-in session in time
            if (s.library == null) AutoSyncStatus.NeedsSignIn else AutoSyncStatus.Failed
        }
        Timber.tag(Tag).d("${service.label}: $result")
        _running.value = null
        session = null
        scope.cancel()
        AutoSyncStore.finish(context, service, result)
    }

    private suspend fun syncWith(service: AccountService, s: AccountImportSession): AutoSyncStatus {
        val targets = playlistResync.accountTargets(service)
        val listed = s.state.first { it is AccountImportState.Choosing || it is AccountImportState.Failed }
        if (listed is AccountImportState.Failed) return if (listed.signedOut) AutoSyncStatus.NeedsSignIn else AutoSyncStatus.Failed
        listed as AccountImportState.Choosing

        // what's new on the account: playlists not imported yet, and liked songs the first time
        val knownIds = targets.mapNotNull { it.remoteId }.toSet()
        val knownNames = targets.map { it.name.lowercase() }.toSet()
        val fresh = listed.playlists.filter { p ->
            when {
                p.isLiked -> targets.isEmpty()
                p.isLibrary -> false
                else -> p.id !in knownIds && p.name.lowercase() !in knownNames
            }
        }
        var added = 0
        if (fresh.isNotEmpty()) {
            s.selectOnly(fresh.map { it.id }.toSet())
            s.readSelected()
            val parsed = merge(s.ready.filterNotNull().map { it }, s.notice.filterNotNull().map { null }).first()
            s.consumeReady()
            if (parsed != null) {
                waitForImports()
                if (importService.startImport(parsed, ImportOptions(mirrorToYouTube = false))) added = parsed.playlists.size
            }
        }

        // the ones already here, read again so songs added or removed there show up
        if (targets.isNotEmpty()) {
            waitForImports()
            s.resyncNow(targets)
            val reads = merge(
                s.resyncReads.filterNotNull().map { it },
                s.state.map { it as? AccountImportState.Failed }.filterNotNull().map { null },
                s.state.map { (it as? AccountImportState.Choosing)?.pickingFor }.filterNotNull().map { null },
            ).first()
            if (reads != null && reads.reads.isNotEmpty()) {
                waitForImports()
                playlistResync.startWithReads(reads.reads)
            }
        }
        return AutoSyncStatus.Done(System.currentTimeMillis(), added)
    }

    // one import at a time: a running one (yours, or the step before) finishes first
    private suspend fun waitForImports() {
        importService.progress.first { !it.isRunning }
    }

    fun onHeader(name: String, value: String, url: String) {
        session?.onHeader(name, value, url)
    }

    fun onApplePageProbe(raw: String?) {
        session?.onApplePageProbe(raw)
    }

    fun onCookies(cookieHeader: String?) {
        session?.onCookies(cookieHeader)
    }

    private companion object {
        const val Tag = "AutoSync"
        const val StartDelayMs = 25_000L
        const val IdleMs = 15 * 60 * 1000L
        const val PassTimeoutMs = 3 * 60 * 1000L
    }
}
