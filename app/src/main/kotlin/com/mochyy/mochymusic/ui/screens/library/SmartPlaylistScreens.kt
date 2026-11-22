package com.mochyy.mochymusic.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.smart.SmartMatch
import com.mochyy.mochymusic.smart.SmartOrder
import com.mochyy.mochymusic.smart.SmartPlaylist
import com.mochyy.mochymusic.smart.SmartPlaylists
import com.mochyy.mochymusic.smart.SmartRule
import com.mochyy.mochymusic.smart.SmartRuleType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

const val SmartPlaylistsRoute = "smart_playlists"

/** la lista de playlists inteligentes, con el botón para crear una nueva */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPlaylistsScreen(navController: NavController) {
    val context = LocalContext.current
    val playlists by remember { SmartPlaylists.all(context) }.collectAsState(initial = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists inteligentes") },
                navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atrás") } },
                actions = { IconButton(onClick = { navController.navigate("smart_playlist_edit/new") }) { Icon(Icons.Rounded.Add, contentDescription = "Nueva playlist inteligente") } },
            )
        },
    ) { padding ->
        if (playlists.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Crea playlists que se llenan solas", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Elige reglas (artista, más escuchadas, añadidas hace poco, me gusta…) y Moody la mantiene al día.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { navController.navigate("smart_playlist_edit/new") }) { Text("Nueva playlist inteligente") }
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${playlist.rules.size} reglas · ${SmartPlaylists.orderLabel(playlist.order)}") },
                        leadingContent = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                        modifier = Modifier.clickable { navController.navigate("smart_playlist/${playlist.id}") },
                    )
                }
            }
        }
    }
}

/** las canciones de una playlist inteligente, con reproducir y aleatorio */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPlaylistScreen(navController: NavController, id: String) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val playlists by remember { SmartPlaylists.all(context) }.collectAsState(initial = null)
    val playlist = playlists?.firstOrNull { it.id == id }
    val songs by remember(playlist) { playlist?.let { SmartPlaylists.songs(database, it) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())

    fun play(shuffled: Boolean, start: Int = 0) {
        val list = if (shuffled) songs.shuffled() else songs
        if (list.isEmpty()) return
        playerConnection?.playQueue(ListQueue(title = playlist?.name, items = list.map { it.toMediaItem() }, startIndex = start))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Playlist inteligente", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atrás") } },
                actions = {
                    IconButton(onClick = { navController.navigate("smart_playlist_edit/$id") }) { Icon(Icons.Rounded.Edit, contentDescription = "Editar reglas") }
                    IconButton(onClick = {
                        scope.launch {
                            SmartPlaylists.delete(context, id)
                            navController.navigateUp()
                        }
                    }) { Icon(Icons.Rounded.Delete, contentDescription = "Borrar") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { play(false) }, enabled = songs.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Reproducir")
                    }
                    FilledTonalButton(onClick = { play(true) }, enabled = songs.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Aleatorio")
                    }
                }
                Text(
                    if (songs.isEmpty()) "Ninguna canción cumple estas reglas todavía" else "${songs.size} canciones",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                ListItem(
                    headlineContent = { Text(song.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(song.artists.joinToString { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        AsyncImage(
                            model = song.song.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    },
                    modifier = Modifier.clickable { play(false, index) },
                )
            }
        }
    }
}

/** crear o editar las reglas; [id] es "new" para una nueva */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPlaylistEditorScreen(navController: NavController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by remember { SmartPlaylists.all(context) }.collectAsState(initial = null)
    val loaded = playlists ?: return
    val existing = loaded.firstOrNull { it.id == id }
    val base = remember(existing) { existing ?: SmartPlaylist(name = "", rules = listOf(SmartRule(SmartRuleType.MinPlays, number = 3))) }
    var name by remember(base) { mutableStateOf(base.name) }
    var match by remember(base) { mutableStateOf(base.match) }
    var order by remember(base) { mutableStateOf(base.order) }
    var limit by remember(base) { mutableStateOf(if (base.limit > 0) base.limit.toString() else "") }
    val rules = remember(base) { mutableStateListOf<SmartRule>().apply { addAll(base.rules) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Nueva playlist inteligente" else "Editar reglas") },
                navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atrás") } },
                actions = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val saved = base.copy(name = name.trim(), match = match, order = order, rules = rules.toList(), limit = limit.toIntOrNull()?.coerceAtLeast(0) ?: 0)
                            scope.launch {
                                SmartPlaylists.save(context, saved)
                                navController.navigateUp()
                                if (existing == null) navController.navigate("smart_playlist/${saved.id}")
                            }
                        },
                    ) { Text("Guardar") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text("Las canciones deben cumplir", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = match == SmartMatch.All, onClick = { match = SmartMatch.All }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Todas las reglas") }
                    SegmentedButton(selected = match == SmartMatch.Any, onClick = { match = SmartMatch.Any }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Cualquiera") }
                }
            }
            itemsIndexed(rules) { index, rule ->
                RuleRow(
                    rule = rule,
                    onChange = { rules[index] = it },
                    onRemove = { rules.removeAt(index) },
                )
            }
            item {
                OutlinedButton(onClick = { rules.add(SmartRule(SmartRuleType.ArtistContains)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Añadir regla")
                }
            }
            item {
                Text("Ordenar por", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Picker(label = SmartPlaylists.orderLabel(order), options = SmartOrder.entries.map { it to SmartPlaylists.orderLabel(it) }, onPick = { order = it })
            }
            item {
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit).take(4) },
                    label = { Text("Máximo de canciones (vacío = sin límite)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RuleRow(rule: SmartRule, onChange: (SmartRule) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Picker(
                    label = SmartPlaylists.ruleLabel(rule.type),
                    options = SmartRuleType.entries.map { it to SmartPlaylists.ruleLabel(it) },
                    onPick = { onChange(SmartRule(it, rule.text, rule.number)) },
                )
            }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Quitar regla") }
        }
        if (SmartPlaylists.takesText(rule.type)) {
            OutlinedTextField(value = rule.text, onValueChange = { onChange(rule.copy(text = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else if (SmartPlaylists.takesNumber(rule.type)) {
            OutlinedTextField(
                value = if (rule.number == 0) "" else rule.number.toString(),
                onValueChange = { onChange(rule.copy(number = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun <T> Picker(label: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}
