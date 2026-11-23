package com.saerling.moody.module.spotify

/** the librespot core in native/spotify (libmoodyspotify.so) */
internal object SpotifyNative {
    /** false when the app was built without the native core, or the phone's ABI has none */
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("moodyspotify") }.isSuccess }

    /** connects with the kept credential, or [token] for a first sign-in; the username, or "error:<why>" */
    @JvmStatic external fun nativeStart(dir: String, token: String): String

    /** the port of the local server that streams decrypted tracks, -1 before [nativeStart] */
    @JvmStatic external fun nativePort(): Int

    /** a Web API token from the session, or "error:<why>" */
    @JvmStatic external fun nativeAccessToken(): String

    /** Pathfinder searchDesktop response JSON, or error:<why>. */
    @JvmStatic external fun nativeSearch(query: String, hash: String, appVersion: String): String

    @JvmStatic external fun nativeSignOut()
}
