package com.mochyy.mochymusic.db.entities

/** cuántas veces sonó una canción y cuándo fue la última (milisegundos UTC), para las playlists inteligentes */
data class SongPlayStat(
    val songId: String,
    val plays: Int,
    val lastPlayed: Long?,
)
