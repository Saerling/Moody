package com.saerling.moody.smart

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.saerling.moody.db.MusicDatabase
import com.saerling.moody.db.entities.Song
import com.saerling.moody.db.entities.SongPlayStat
import com.saerling.moody.utils.dataStore
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Playlists inteligentes: reglas guardadas como JSON en DataStore (sin tocar la base de datos) que se
 * evalúan cada vez sobre la biblioteca, así se mantienen al día solas. Idea tomada de OpenTune.
 */
@Serializable
enum class SmartRuleType {
    ArtistContains,
    AlbumContains,
    TitleContains,
    MinPlays,
    AddedWithinDays,
    Liked,
    NotPlayedForDays,
    LongerThanMinutes,
    ShorterThanMinutes,
}

@Serializable
data class SmartRule(
    val type: SmartRuleType,
    val text: String = "",
    val number: Int = 0,
)

@Serializable
enum class SmartMatch { All, Any }

@Serializable
enum class SmartOrder { MostPlayed, RecentlyAdded, RecentlyPlayed, Title, Artist, Random }

@Serializable
data class SmartPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val match: SmartMatch = SmartMatch.All,
    val rules: List<SmartRule> = emptyList(),
    val order: SmartOrder = SmartOrder.MostPlayed,
    /** 0 = sin límite */
    val limit: Int = 50,
)

object SmartPlaylists {
    private val Key = stringPreferencesKey("smartPlaylists")
    private val json = Json { ignoreUnknownKeys = true }

    fun all(context: Context): Flow<List<SmartPlaylist>> =
        context.dataStore.data.map { prefs ->
            prefs[Key]?.let { runCatching { json.decodeFromString<List<SmartPlaylist>>(it) }.getOrNull() } ?: emptyList()
        }

    suspend fun save(context: Context, playlist: SmartPlaylist) {
        context.dataStore.edit { prefs ->
            val current = prefs[Key]?.let { runCatching { json.decodeFromString<List<SmartPlaylist>>(it) }.getOrNull() } ?: emptyList()
            val next = if (current.any { it.id == playlist.id }) current.map { if (it.id == playlist.id) playlist else it } else current + playlist
            prefs[Key] = json.encodeToString(next)
        }
    }

    suspend fun delete(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Key]?.let { runCatching { json.decodeFromString<List<SmartPlaylist>>(it) }.getOrNull() } ?: emptyList()
            prefs[Key] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    /** las canciones de [playlist], siempre al día con la biblioteca y el historial */
    fun songs(database: MusicDatabase, playlist: SmartPlaylist): Flow<List<Song>> =
        combine(database.smartPlaylistCandidates(), database.songPlayStats()) { candidates, stats ->
            evaluate(playlist, candidates, stats.associateBy { it.songId }, System.currentTimeMillis())
        }

    fun evaluate(playlist: SmartPlaylist, songs: List<Song>, stats: Map<String, SongPlayStat>, now: Long): List<Song> {
        val day = 24L * 60 * 60 * 1000
        fun matches(song: Song, rule: SmartRule): Boolean {
            val stat = stats[song.id]
            return when (rule.type) {
                SmartRuleType.ArtistContains -> rule.text.isNotBlank() && song.artists.any { it.name.contains(rule.text.trim(), ignoreCase = true) }
                SmartRuleType.AlbumContains -> rule.text.isNotBlank() && (song.album?.title ?: song.song.albumName).orEmpty().contains(rule.text.trim(), ignoreCase = true)
                SmartRuleType.TitleContains -> rule.text.isNotBlank() && song.song.title.contains(rule.text.trim(), ignoreCase = true)
                SmartRuleType.MinPlays -> (stat?.plays ?: 0) >= rule.number
                SmartRuleType.AddedWithinDays -> song.song.inLibrary?.let { now - it.toInstant(ZoneOffset.UTC).toEpochMilli() <= rule.number * day } ?: false
                SmartRuleType.Liked -> song.song.liked
                SmartRuleType.NotPlayedForDays -> stat?.lastPlayed?.let { now - it >= rule.number * day } ?: true
                SmartRuleType.LongerThanMinutes -> song.song.duration > 0 && song.song.duration >= rule.number * 60
                SmartRuleType.ShorterThanMinutes -> song.song.duration > 0 && song.song.duration < rule.number * 60
            }
        }
        val picked = songs.filter { song ->
            if (playlist.rules.isEmpty()) true
            else when (playlist.match) {
                SmartMatch.All -> playlist.rules.all { matches(song, it) }
                SmartMatch.Any -> playlist.rules.any { matches(song, it) }
            }
        }
        val ordered = when (playlist.order) {
            SmartOrder.MostPlayed -> picked.sortedByDescending { stats[it.id]?.plays ?: 0 }
            SmartOrder.RecentlyAdded -> picked.sortedByDescending { it.song.inLibrary }
            SmartOrder.RecentlyPlayed -> picked.sortedByDescending { stats[it.id]?.lastPlayed ?: 0L }
            SmartOrder.Title -> picked.sortedBy { it.song.title.lowercase() }
            SmartOrder.Artist -> picked.sortedBy { it.artists.firstOrNull()?.name?.lowercase().orEmpty() }
            SmartOrder.Random -> picked.shuffled()
        }
        return if (playlist.limit > 0) ordered.take(playlist.limit) else ordered
    }

    fun ruleLabel(type: SmartRuleType): String = when (type) {
        SmartRuleType.ArtistContains -> "Artista contiene"
        SmartRuleType.AlbumContains -> "Álbum contiene"
        SmartRuleType.TitleContains -> "Título contiene"
        SmartRuleType.MinPlays -> "Escuchada al menos (veces)"
        SmartRuleType.AddedWithinDays -> "Añadida en los últimos (días)"
        SmartRuleType.Liked -> "Con me gusta"
        SmartRuleType.NotPlayedForDays -> "Sin escuchar hace (días)"
        SmartRuleType.LongerThanMinutes -> "Dura más de (min)"
        SmartRuleType.ShorterThanMinutes -> "Dura menos de (min)"
    }

    fun orderLabel(order: SmartOrder): String = when (order) {
        SmartOrder.MostPlayed -> "Más escuchadas"
        SmartOrder.RecentlyAdded -> "Añadidas hace poco"
        SmartOrder.RecentlyPlayed -> "Escuchadas hace poco"
        SmartOrder.Title -> "Título"
        SmartOrder.Artist -> "Artista"
        SmartOrder.Random -> "Aleatorio"
    }

    fun takesText(type: SmartRuleType) = type == SmartRuleType.ArtistContains || type == SmartRuleType.AlbumContains || type == SmartRuleType.TitleContains
    fun takesNumber(type: SmartRuleType) = !takesText(type) && type != SmartRuleType.Liked
}
