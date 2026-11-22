package com.mochyy.mochymusic.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable, ordered write to YouTube Music. Payload fields are deliberately primitive so Room
 * does not need a converter and old queued writes remain readable across app upgrades. */
@Entity(tableName = "playlist_sync_op", indices = [Index(value = ["playlist_id", "seq"])])
data class PlaylistSyncOpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
    val kind: String,
    @ColumnInfo(name = "video_id") val videoId: String? = null,
    @ColumnInfo(name = "set_video_id") val setVideoId: String? = null,
    @ColumnInfo(name = "successor_set_video_id") val successorSetVideoId: String? = null,
    val name: String? = null,
    val attempts: Int = 0,
)
