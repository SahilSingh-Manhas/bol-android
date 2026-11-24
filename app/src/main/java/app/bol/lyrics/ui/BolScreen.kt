package app.bol.lyrics.ui

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bol.lyrics.data.HistoryItem
import app.bol.lyrics.data.LyricMatch
import app.bol.lyrics.data.LyricsRepository
import app.bol.lyrics.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

private val Ink = Color(0xFF100E0C)
private val Surface = Color(0xFF221E19)
private val Fg = Color(0xFFF6F1E7)
private val Muted = Color(0xFFB7AB9E)
private val Line = Color(0xFF3A332C)
private val Accent = Color(0xFFE4A04A)
private val AccentInk = Color(0xFF1A1208)

class BolViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LyricsRepository()
    private val prefs = app.getSharedPreferences("bol", 0)

    var url by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var song by mutableStateOf<Song?>(null)
    var picked by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var time by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)
    var note by mutableStateOf<String?>(null)
    var cover by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var history by mutableStateOf(readHistory())

    private var ticker: Job? = null
    val match: LyricMatch? get() = song?.matches?.getOrNull(picked)

    fun open(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || loading) return
        url = text
        loading = true
        error = null
        note = null
        stop()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.load(text) } }
            loading = false
            result.onSuccess { loaded ->
                song = loaded
                picked = 0
                time = 0f
                offset = prefs.getFloat("off-${loaded.videoId}", 0f)
                cover = null
                remember(loaded)
                loadCover(loaded.coverUrl)
            }.onFailure { error = it.message ?: "Kuch gadbad ho gayi." }
        }
    }

    fun choose(index: Int) {
        picked = index
        time = 0f
        stop()
    }

    fun toggle() {
        val span = span()
        if (playing) {
            stop()
            return
        }
        if (time >= span) time = 0f
        playing = true
        ticker = viewModelScope.launch {
            while (isActive && playing) {
                delay(100)
                time = (time + 0.1f).coerceAtMost(span())
                if (time >= span()) stop()
            }
        }
    }

    fun seek(value: Float) {
        time = value.coerceIn(0f, span())
    }

    fun nudge(delta: Float) {
        offset = (offset + delta).coerceIn(-15f, 15f)
        song?.let { prefs.edit().putFloat("off-${it.videoId}", offset).apply() }
    }

    fun saveLrc() {
        val current = match ?: return
        if (current.syncedLyrics.isBlank()) {
            error = "Synced lyrics nahi hain."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { repo.saveLrc(getApplication(), current, offset.toDouble()) }
            withContext(Dispatchers.Main) {
                saved.onSuccess { note = "LRC Downloads mein save ho gaya" }
                    .onFailure { error = it.message }
            }
        }
    }

    fun saveCover() {
        val current = song ?: return
        val chosen = match
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching {
                repo.saveCover(
                    getApplication(),
                    current.coverUrl,
                    chosen?.artistName ?: current.author,
                    chosen?.trackName ?: current.videoTitle,
                )
            }
            withContext(Dispatchers.Main) {
                saved.onSuccess { note = "Cover Downloads mein save ho gaya" }
                    .onFailure { error = it.message }
            }
        }
    }

    private fun span(): Float {
        val chosen = match
        val last = chosen?.lines?.lastOrNull()?.time ?: 0.0
        return max(chosen?.duration ?: 0.0, last + 8.0).toFloat().coerceAtLeast(30f)
    }

    private fun stop() {
        playing = false
        ticker?.cancel()
        ticker = null
    }

    private fun loadCover(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val request = okhttp3.Request.Builder().url(url).build()
                okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
            if (bitmap != null) cover = bitmap
        }
    }

    private fun remember(loaded: Song) {
        val item = HistoryItem(
            loaded.videoId,
            loaded.matches.firstOrNull()?.trackName ?: loaded.videoTitle,
            loaded.matches.firstOrNull()?.artistName ?: loaded.author,
            loaded.thumbnail,
        )
        val next = listOf(item) + history.filter { it.videoId != item.videoId }
        history = next.take(8)
        val array = JSONArray()
        history.forEach {
            array.put(JSONObject().put("id", it.videoId).put("title", it.title).put("artist", it.artist).put("thumb", it.thumbnail))
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    private fun readHistory(): List<HistoryItem> {
        val raw = prefs.getString("history", null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(HistoryItem(item.optString("id"), item.optString("title"), item.optString("artist"), item.optString("thumb")))
            }
        }
    }
}

@Composable
fun BolScreen(sharedUrl: String?, model: BolViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) model.open(sharedUrl)
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = Ink, onBackground = Fg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Ink),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Bol", color = Accent, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                Text("YouTube Music ka link daalo. Synced lyrics yahin chalenge.", color = Muted)
            }
            item {
                OutlinedTextField(
                    value = model.url,
                    onValueChange = { model.url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("music.youtube.com link", color = Muted) },
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { model.open(model.url) },
                    enabled = !model.loading,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                ) { Text(if (model.loading) "Dhoondh rahe hain…" else "Lyrics lao") }
                if (model.loading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Line)
                }
                model.error?.let { Text(it, color = Color(0xFFE7B4A4), modifier = Modifier.padding(top = 8.dp)) }
                model.note?.let { Text(it, color = Accent, modifier = Modifier.padding(top = 8.dp)) }
            }
            val song = model.song
            if (song == null) {
                item {
                    Text("Sample", color = Muted, fontWeight = FontWeight.SemiBold)
                    sample("Kesariya", "https://music.youtube.com/watch?v=BddP6PYo2gs", model)
                    sample("Blinding Lights", "https://www.youtube.com/watch?v=4NRXx6U8ABQ", model)
                }
                if (model.history.isNotEmpty()) {
                    item { Text("Haal ke gaane", color = Muted, fontWeight = FontWeight.SemiBold) }
                    itemsIndexed(model.history) { _, item ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
                                .clickable { model.open("https://www.youtube.com/watch?v=${item.videoId}") }
                                .padding(12.dp),
                        ) {
                            Text(item.title, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(item.artist.ifBlank { "YouTube" }, color = Muted, maxLines = 1)
                        }
                    }
                }
            } else {
                val match = model.match
                item { NowPlaying(model, song, match) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.nudge(-0.5f) }, colors = outline()) { Text("-0.5s") }
                        OutlinedButton(onClick = { model.nudge(0.5f) }, colors = outline()) { Text("+0.5s") }
                        Text("Offset ${model.offset.roundToInt().let { if (it > 0) "+$it" else "$it" }}s", color = Muted, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.saveLrc() }, colors = outline()) { Text("LRC") }
                        OutlinedButton(onClick = { model.saveCover() }, colors = outline()) { Text("Cover") }
                        OutlinedButton(
                            onClick = {
                                val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=${song.videoId}"))
                                context.startActivity(view)
                            },
                            colors = outline(),
                        ) { Text("YouTube") }
                    }
                }
                if (song.matches.size > 1) {
                    item {
                        Text("Versions", color = Muted, fontWeight = FontWeight.SemiBold)
                        song.matches.forEachIndexed { index, item ->
                            Text(
                                "${item.trackName} · ${item.artistName}",
                                color = if (index == model.picked) Accent else Fg,
                                modifier = Modifier.fillMaxWidth().clickable { model.choose(index) }.padding(vertical = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item { LyricList(model) }
                item { TagBlock(song) }
            }
        }
    }
}

@Composable
private fun sample(label: String, link: String, model: BolViewModel) {
    Text(
        label,
        color = Fg,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(Surface)
            .clickable { model.open(link) }.padding(14.dp),
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun NowPlaying(model: BolViewModel, song: Song, match: LyricMatch?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val art = model.cover
        if (art != null) {
            Image(art, null, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
        } else {
            Spacer(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(Surface))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(match?.trackName ?: song.videoTitle, color = Fg, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(match?.artistName ?: song.author, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val span = max(match?.duration ?: 0.0, (match?.lines?.lastOrNull()?.time ?: 0.0) + 8.0).toFloat().coerceAtLeast(30f)
    Button(
        onClick = { model.toggle() },
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
    ) { Text(if (model.playing) "Roko" else "Chalao") }
    Slider(value = model.time.coerceIn(0f, span), onValueChange = { model.seek(it) }, valueRange = 0f..span)
    Text("${clock(model.time)} / ${clock(span)}", color = Muted)
    Text("Timer gaane ke saath chalao. YouTube button se gaana khulega.", color = Muted, fontSize = 13.sp)
}

@Composable
private fun LyricList(model: BolViewModel) {
    val lines = model.match?.lines.orEmpty()
    val lyricTime = model.time + model.offset
    val active = lines.indexOfLast { it.time <= lyricTime }.coerceAtLeast(0)
    val state = rememberLazyListState()
    LaunchedEffect(active) {
        if (lines.isNotEmpty()) state.animateScrollToItem(active)
    }
    if (lines.isEmpty()) {
        Text(model.match?.plainLyrics ?: "Lyrics nahi hain.", color = Fg)
        return
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(360.dp)) {
        itemsIndexed(lines) { index, line ->
            Text(
                line.text,
                color = if (index == active) Accent else if (index < active) Muted else Fg,
                fontSize = if (index == active) 22.sp else 16.sp,
                fontWeight = if (index == active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun TagBlock(song: Song) {
    val tags = song.tags
    Text("Tags", color = Fg, fontWeight = FontWeight.SemiBold)
    listOf(
        "Title" to tags.title,
        "Album" to tags.album,
        "Artist" to tags.artist,
        "Album-Artist" to tags.albumArtist,
        "Genre" to tags.genre,
        "Track number" to tags.trackNumber,
        "Year" to tags.year,
    ).forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted)
            Text(value.ifBlank { "—" }, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Fg,
    unfocusedTextColor = Fg,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Line,
    cursorColor = Accent,
)

@Composable
private fun outline() = ButtonDefaults.outlinedButtonColors(contentColor = Fg)

private fun clock(seconds: Float): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
