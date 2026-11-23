package com.saerling.moody.importer

import com.music.innertube.YouTube
import com.music.innertube.utils.completed
import com.saerling.moody.db.MusicDatabase
import android.content.Context
import android.widget.Toast
import com.saerling.moody.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.saerling.moody.db.entities.AlbumEntity
import com.saerling.moody.db.entities.PlaylistEntity
import com.saerling.moody.db.entities.Playlist
import com.saerling.moody.db.entities.PlaylistSongMap
import com.saerling.moody.db.entities.PlaylistSyncOpEntity
import com.saerling.moody.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** Local first YouTube Music edits. The Room queue is the source of truth; failed requests survive
 * process death and are replayed in insertion order. */
@Singleton
class LibrarySync @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
) {
    private val flushMutex = Mutex()
    private var flushJob: Job? = null

    fun pendingCount(playlistId: String): Flow<Int> = database.pendingPlaylistSyncCount(playlistId)

    suspend fun createPlaylist(playlist: PlaylistEntity, sync: Boolean) {
        database.withTransaction {
            insert(playlist)
            if (sync) enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = playlist.id, kind = CREATE, name = playlist.name))
        }
        if (sync) scheduleFlush()
    }

    suspend fun saveRemotePlaylist(playlist: PlaylistEntity) {
        val saved = playlist.copy(bookmarkedAt = LocalDateTime.now())
        database.withTransaction {
            insert(saved)
            saved.browseId?.let { enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = saved.id, remoteId = it, kind = LIKE)) }
        }
        scheduleFlush()
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        database.withTransaction {
            val current = playlistEntity(playlistId) ?: return@withTransaction
            update(current.copy(name = name))
            val createOp = pendingPlaylistSyncOps().firstOrNull { it.playlistId == playlistId && it.kind == CREATE }
            if (createOp != null) updatePlaylistSyncOpName(createOp.seq, name)
            else if (current.browseId != null && current.isEditable) enqueuePlaylistSyncOp(
                PlaylistSyncOpEntity(playlistId = playlistId, remoteId = current.browseId, kind = RENAME, name = name)
            )
        }
        scheduleFlush()
    }

    suspend fun deletePlaylist(playlistId: String) {
        database.withTransaction {
            val current = playlistEntity(playlistId) ?: return@withTransaction
            val queued = pendingPlaylistSyncOps().filter { it.playlistId == playlistId }
            if (current.browseId != null && current.isEditable) {
                // A delete supersedes writes that have not yet reached YouTube.
                queued.forEach { deletePlaylistSyncOp(it.seq) }
                enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = playlistId, remoteId = current.browseId, kind = DELETE))
            } else if (current.browseId == null) {
                queued.forEach { deletePlaylistSyncOp(it.seq) }
            }
            delete(current)
        }
        scheduleFlush()
    }

    suspend fun togglePlaylistLike(playlist: PlaylistEntity): PlaylistEntity {
        var changed = playlist
        database.withTransaction {
            val current = playlistEntity(playlist.id) ?: playlist
            changed = current.localToggleLike()
            update(changed)
            val creating = pendingPlaylistSyncOps().any { it.playlistId == current.id && it.kind == CREATE }
            if (current.browseId != null || creating) enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = current.id, remoteId = current.browseId, kind = if (changed.bookmarkedAt != null) LIKE else UNLIKE))
        }
        scheduleFlush()
        return changed
    }

    suspend fun toggleAlbumLike(album: AlbumEntity): AlbumEntity {
        var changed = album
        database.withTransaction {
            val current = albumEntity(album.id) ?: album
            changed = current.localToggleLike()
            update(changed)
            current.playlistId?.let { enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = "album:${current.id}", remoteId = it, kind = if (changed.bookmarkedAt != null) LIKE else UNLIKE)) }
        }
        scheduleFlush()
        return changed
    }

    suspend fun addSongsToPlaylist(playlist: Playlist, ids: List<String>, keepAtTop: Boolean = true) {
        if (ids.isEmpty()) return
        database.withTransaction {
            val actualIds = ids.distinct().filterNot { it in playlistDuplicates(playlist.id, ids) }
            if (actualIds.isEmpty()) return@withTransaction
            if (playlist.playlist.browseId != null && keepAtTop) addSongsToPlaylistTop(playlist, actualIds)
            else addSongToPlaylist(playlist, actualIds)
            val creating = pendingPlaylistSyncOps().any { it.playlistId == playlist.id && it.kind == CREATE }
            val remote = playlist.playlist.browseId?.takeIf { playlist.playlist.isEditable }
            if (remote != null) {
                actualIds.forEach { id -> enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = playlist.id, remoteId = remote, kind = ADD, videoId = id)) }
            } else if (creating) {
                actualIds.forEach { id -> enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = playlist.id, kind = ADD, videoId = id)) }
            }
        }
        scheduleFlush()
    }

    suspend fun removeSongFromPlaylist(map: PlaylistSongMap) {
        database.withTransaction {
            val row = playlistSongMaps(map.songId).firstOrNull { it.id == map.id }
            val playlist = playlistEntity(map.playlistId)
            if (row != null && playlist?.browseId != null && playlist.isEditable) {
                val queuedAdd = pendingPlaylistSyncOps().firstOrNull { it.playlistId == map.playlistId && it.kind == ADD && it.videoId == map.songId }
                if (queuedAdd != null) deletePlaylistSyncOp(queuedAdd.seq)
                else if (row.setVideoId != null) enqueuePlaylistSyncOp(
                    PlaylistSyncOpEntity(playlistId = map.playlistId, remoteId = playlist.browseId, kind = REMOVE, videoId = map.songId, setVideoId = row.setVideoId)
                )
            }
            move(map.playlistId, map.position, Int.MAX_VALUE)
            delete(map.copy(position = Int.MAX_VALUE))
        }
        scheduleFlush()
    }

    suspend fun moveSong(playlistId: String, from: Int, to: Int) {
        database.withTransaction {
            val rows = playlistSongMaps(playlistId, 0)
            val moved = rows.getOrNull(from)
            val successorIndex = if (from > to) to else to + 1
            val successor = rows.getOrNull(successorIndex)
            move(playlistId, from, to)
            val remote = playlistEntity(playlistId)
            if (remote?.browseId != null && remote.isEditable && moved?.setVideoId != null)
                enqueuePlaylistSyncOp(PlaylistSyncOpEntity(playlistId = playlistId, remoteId = remote.browseId, kind = MOVE, setVideoId = moved.setVideoId, successorSetVideoId = successor?.setVideoId))
        }
        scheduleFlush()
    }

    /** Enqueue an edit whose local row mutation is handled by the caller. Prefer the typed methods
     * above for entity-level changes; this entry point covers song row edits from existing UI. */
    suspend fun enqueue(op: PlaylistSyncOpEntity) {
        database.withTransaction { enqueuePlaylistSyncOp(op) }
        scheduleFlush()
    }

    suspend fun flush() = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            while (true) {
                val op = database.pendingPlaylistSyncOps().firstOrNull() ?: break
                try {
                    val remoteId = op.remoteId ?: database.playlistEntity(op.playlistId)?.browseId
                    when (op.kind) {
                        CREATE -> {
                            val id = op.remoteId ?: YouTube.createPlaylist(op.name.orEmpty())
                            database.withTransaction {
                                val current = playlistEntity(op.playlistId)
                                if (current != null) update(current.copy(browseId = id))
                                updatePlaylistSyncOpRemoteId(op.seq, id)
                            }
                        }
                        RENAME -> YouTube.renamePlaylist(remoteId ?: error("Playlist no longer exists remotely"), op.name.orEmpty()).getOrThrow()
                        DELETE -> YouTube.deletePlaylist(remoteId ?: error("Playlist id missing")).getOrThrow()
                        ADD -> {
                            val id = op.videoId ?: error("Song id missing")
                            val response = YouTube.addToPlaylist(remoteId ?: error("Playlist id missing"), id).getOrThrow()
                            response.playlistEditResults.firstOrNull()?.playlistEditVideoAddedResultData?.setVideoId?.let { setId ->
                                database.setPlaylistSongVideoId(op.playlistId, id, setId)
                            }
                        }
                        REMOVE -> YouTube.removeFromPlaylist(remoteId ?: error("Playlist id missing"), op.videoId ?: error("Song id missing"), op.setVideoId ?: error("Playlist row id missing")).getOrThrow()
                        MOVE -> {
                            val id = remoteId ?: error("Playlist id missing")
                            val freshSongs = YouTube.playlist(id).completed().getOrThrow().songs
                            check(freshSongs.any { it.setVideoId == op.setVideoId }) { "Playlist move conflict: moved row disappeared" }
                            if (op.successorSetVideoId != null) check(freshSongs.any { it.setVideoId == op.successorSetVideoId }) { "Playlist move conflict: destination row disappeared" }
                            YouTube.moveSongPlaylist(id, op.setVideoId ?: error("Playlist row id missing"), op.successorSetVideoId).getOrThrow()
                        }
                        LIKE -> YouTube.likePlaylist(remoteId ?: error("Playlist id missing"), true).getOrThrow()
                        UNLIKE -> YouTube.likePlaylist(remoteId ?: error("Playlist id missing"), false).getOrThrow()
                        else -> error("Unknown library sync operation: ${op.kind}")
                    }
                    database.playlistEntity(op.playlistId)?.let { entity ->
                        database.update(entity.copy(suppressPullUntil = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(10)))
                    }
                    if (op.playlistId.startsWith("album:")) {
                        val albumId = op.playlistId.removePrefix("album:")
                        database.albumEntity(albumId)?.let { entity -> database.update(entity.copy(suppressPullUntil = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(10))) }
                    }
                    database.deletePlaylistSyncOp(op.seq)
                } catch (e: Exception) {
                    database.incrementPlaylistSyncAttempts(op.seq)
                    Timber.tag("LibrarySync").w(e, "Remote library edit queued for retry")
                    // Permanent protocol rejections are surfaced in logs and discarded after three
                    // attempts; transport/session failures remain durable and back off indefinitely.
                    // error()/check() above mean the edit can never apply (missing ids, moved rows): drop it
                    // instead of letting it block every later edit at the head of the queue
                    val permanent = e is IllegalStateException ||
                        e.message?.contains("STATUS_SUCCEEDED", ignoreCase = true) == true ||
                        e.message?.contains("rejected", ignoreCase = true) == true ||
                        e.message?.contains("conflict", ignoreCase = true) == true ||
                        e.message?.contains("not found", ignoreCase = true) == true ||
                        e.message?.contains("not editable", ignoreCase = true) == true ||
                        e.message?.contains("HTTP 400", ignoreCase = true) == true ||
                        e.message?.contains("HTTP 404", ignoreCase = true) == true
                    if (permanent && op.attempts >= 2) {
                        database.deletePlaylistSyncOp(op.seq)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.library_sync_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                    break
                }
            }
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive != true) flushJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { flush() }
                val pending = database.pendingPlaylistSyncOps().firstOrNull() ?: break
                delay((1_000L shl pending.attempts.coerceIn(0, 8)).coerceAtMost(5 * 60_000L))
            }
        }
    }

    fun resumePendingWrites() = scheduleFlush()

    companion object {
        const val CREATE = "CREATE"
        const val RENAME = "RENAME"
        const val DELETE = "DELETE"
        const val ADD = "ADD"
        const val REMOVE = "REMOVE"
        const val MOVE = "MOVE"
        const val LIKE = "LIKE"
        const val UNLIKE = "UNLIKE"
    }
}
