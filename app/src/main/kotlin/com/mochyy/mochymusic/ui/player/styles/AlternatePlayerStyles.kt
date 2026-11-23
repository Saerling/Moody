package com.mochyy.mochymusic.ui.player.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.mochyy.mochymusic.R
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.constants.PlayerStyle
import com.mochyy.mochymusic.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.mochyy.mochymusic.lyrics.LyricsEntry
import com.mochyy.mochymusic.lyrics.LyricsUtils
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.ui.player.carouselText
import com.mochyy.mochymusic.ui.utils.stableStatusBars

/** the player's alternate styles, drawn as they look on the player or, [preview], on a selector card */
@Composable
fun AlternatePlayerStyle(
    style: PlayerStyle,
    actions: StyleActions,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    when (style) {
        PlayerStyle.VIDEO -> com.mochyy.mochymusic.ui.player.video.VideoPlayerStyle(actions, modifier, preview)
        PlayerStyle.BEDSIDE -> com.mochyy.mochymusic.ui.player.bedside.BedsidePlayerStyle(actions, modifier, preview)
        PlayerStyle.BEDSIDE_TILES -> com.mochyy.mochymusic.ui.player.bedside.BedsideTilesStyle(actions, modifier, preview)
        PlayerStyle.MODULE -> ModulePlayerStyle(actions, modifier, preview)
        PlayerStyle.CLASSIC -> Unit
    }
}

/** what every alternate style reads from the player */
internal class StyleTrack(val title: String, val artist: String, val album: String, val artwork: String?)

@Composable
internal fun rememberStyleTrack(): StyleTrack {
    val connection = LocalPlayerConnection.current ?: return StyleTrack("", "", "", null)
    val track by connection.uiState.trackInfo.collectAsState()
    return remember(track) { StyleTrack(track.title, track.artist, track.album, track.thumbnailUrl) }
}

/**
 * The player's row of songs on the live player; a selector card shows the playing song, still.
 */
@Composable
internal fun rememberStyleCarousel(preview: Boolean): com.mochyy.mochymusic.ui.player.SongCarousel? =
    if (preview) null else com.mochyy.mochymusic.ui.player.LocalSongCarousel.current

/** a song's synced lines, and which song they're for */
internal class StyleLyrics(val mediaId: String?, val lines: List<LyricsEntry>)

/** what a style shows for one song in the row: its details, and its lyrics once it has played */
internal class StyleSong(val track: StyleTrack, val lines: List<LyricsEntry>)

/**
 * [content] once per song on screen, riding the row: the playing song, and the one coming in or
 * going out while a swipe or a skip slides them. Each song keeps the lyrics it had while it played,
 * so the one leaving goes with its own words, and a song only takes lyrics that are its own.
 * Without a row (a selector card) it's the playing song alone.
 */
@Composable
internal fun StyleSongs(
    carousel: com.mochyy.mochymusic.ui.player.SongCarousel?,
    track: StyleTrack,
    lyrics: StyleLyrics,
    content: @Composable (song: StyleSong, slotModifier: Modifier) -> Unit,
) {
    if (carousel == null) {
        content(StyleSong(track, lyrics.lines), Modifier)
        return
    }
    for (slot in com.mochyy.mochymusic.ui.player.rememberTextSlots(carousel)) {
        androidx.compose.runtime.key(slot.uid) {
            val playing = slot.step == carousel.step
            val held = remember { arrayOf(emptyList<LyricsEntry>()) }
            if (playing && lyrics.mediaId == slot.mediaId) held[0] = lyrics.lines
            val details = StyleTrack(
                title = slot.title.ifEmpty { if (playing) track.title else "" },
                artist = slot.artist.ifEmpty { if (playing) track.artist else "" },
                album = slot.album,
                artwork = slot.artworkUrl,
            )
            content(StyleSong(details, held[0]), Modifier.carouselText(carousel, slot))
        }
    }
}

// the frame every style is drawn in: tones, gestures, the window's shape, the ••• menu's state
@Composable
internal fun StyleFrame(
    preview: Boolean,
    modifier: Modifier,
    tones: StyleTones,
    /** what lies under the style and holds still when it turns; the playhead split when null */
    background: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.(landscape: Boolean, menuOpen: Boolean, setMenuOpen: (Boolean) -> Unit) -> Unit,
) {
    val connection = LocalPlayerConnection.current
    var menuOpen by remember { mutableStateOf(false) }
    StyleStage(
        tones = tones,
        background = background,
        modifier = modifier
            .then(
                if (preview) {
                    // a card shows the style, it doesn't play it
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                } else Modifier
            ),
    ) { landscape ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .styleGestures(
                    enabled = !preview && connection != null,
                    interactive = LocalStyleInteractive.current,
                    onToggle = { connection?.togglePlayPause() },
                    carousel = rememberStyleCarousel(preview),
                    player = { connection?.player },
                ),
        ) {
            // Inter and nothing else, in every style
            androidx.compose.material3.ProvideTextStyle(
                androidx.compose.ui.text.TextStyle(fontFamily = com.mochyy.mochymusic.ui.theme.InterFontFamily)
            ) {
                content(landscape, menuOpen) { menuOpen = it }
            }
        }
    }
}

/** The backdrop's warp clock, shared by the copies crossfading along the row. */
@Composable
private fun rememberBackdropClock(running: Boolean): () -> Float {
    val time = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val showing = rememberStyleShowing()
    androidx.compose.runtime.LaunchedEffect(running, showing) {
        if (!running) return@LaunchedEffect
        // the time is absolute, so it can sleep while the player is collapsed and wake up in step
        androidx.compose.runtime.snapshotFlow { showing() }.collectLatest { shown ->
            if (!shown) return@collectLatest
            time.floatValue = backdropTime(System.nanoTime())
            while (true) {
                androidx.compose.animation.core.withInfiniteAnimationFrameNanos { frameTimeNanos ->
                    time.floatValue = backdropTime(frameTimeNanos)
                }
            }
        }
    }
    return remember { { time.floatValue } }
}

private fun backdropTime(frameTimeNanos: Long): Float =
    ((frameTimeNanos / 1_000_000f) * (100f / 100_000f)).mod(100f)

// where the system bars fall on a style; in landscape the player hides the status bar, so only the side bars and the cutout are left
internal class StyleInsets(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

@Composable
internal fun styleInsets(landscape: Boolean): StyleInsets {
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val sides = WindowInsets.navigationBars.union(WindowInsets.displayCutout).asPaddingValues()
    val status = if (landscape) 0.dp else WindowInsets.stableStatusBars.asPaddingValues().calculateTopPadding()
    return StyleInsets(
        start = sides.calculateStartPadding(direction),
        top = status,
        end = sides.calculateEndPadding(direction),
        bottom = sides.calculateBottomPadding(),
    )
}

// ---- Poster ------------------------------------------------------------------------------

/** what the words give the visualizer's bars under the cover (beside it, sideways) */
private val VisualizerRoom = 60.dp
private val VisualizerRoomSideways = 40.dp
/** how far the words move down while the cover's seek bar is out */
private val RingPush = 34.dp
/** the Turntable's pills, side by side: as tall as one */
private val PillsHeight = 42.dp
/** the menu's chevron, the Turntable's size */
private val PosterChevron = 43.dp

/**
 * The current song's synced lines, the background vocals left out, with their pronunciation and
 * translation filled in by the same code as the lyrics page when [readings] (the live player, not
 * a selector card). Lyrics without timings show nothing here: a style performs lines in time.
 */
@Composable
internal fun rememberStyleLyrics(readings: Boolean): StyleLyrics {
    val connection = LocalPlayerConnection.current
    val lyricsEntity = connection?.currentLyrics?.collectAsState(initial = null)?.value
    val all = remember(lyricsEntity?.lyrics) {
        val raw = lyricsEntity?.lyrics
        if (raw.isNullOrBlank() || raw == LYRICS_NOT_FOUND) emptyList()
        else runCatching { LyricsUtils.parseLyricsCached(raw) }.getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() && !LyricsUtils.isUnsynced(it) }.orEmpty()
    }
    if (readings && connection != null) {
        val metadata by connection.mediaMetadata.collectAsState()
        // The flagship's bottom card is what asks for a song's lyrics; a style has no card, so it
        // asks itself. Without this the words only came once the flagship had been shown.
        val loader: com.mochyy.mochymusic.viewmodels.LyricsScreenViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
        androidx.compose.runtime.LaunchedEffect(metadata?.id) {
            metadata?.let(loader::ensureLyricsLoaded)
        }
        if (all.isNotEmpty()) {
            // the whole song, background lines too, so a stored translation lines up with the lyrics page's
            com.mochyy.mochymusic.ui.player.LyricsReadings(all, metadata?.id, com.mochyy.mochymusic.LocalDatabase.current, lyricsEntity)
        }
    }
    val lines = remember(all) { all.filter { !it.isBackground && it.text.isNotBlank() } }
    return remember(lyricsEntity?.id, lines) { StyleLyrics(lyricsEntity?.id, lines) }
}

// ---- Timeline ----------------------------------------------------------------------------

/** the played time on its own, so the second ticking over recomposes this text and nothing around it */
@Composable
internal fun PlayedTime(position: LivePosition, color: Color, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    val seconds = rememberPlayedSeconds(position)
    Text(
        text = makeTimeString(seconds * 1_000L),
        color = color,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}
