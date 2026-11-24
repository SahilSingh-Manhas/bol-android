package app.bol.lyrics.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class LyricsRepository {
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun load(rawUrl: String): Song {
        val id = parseYouTubeId(rawUrl) ?: error("Yeh YouTube link nahi lagta.")
        val oembed = getJson("https://www.youtube.com/oembed?url=${enc("https://www.youtube.com/watch?v=$id")}&format=json")
            ?: error("Yeh video nahi mili.")
        val title = oembed.optString("title").ifBlank { "YouTube" }
        val author = cleanAuthor(oembed.optString("author_name"))
        val guess = guessTrack(title, author)
        val matches = searchLyrics(guess)
        if (matches.isEmpty()) error("Is gaane ke synced lyrics nahi mile.")
        val best = matches.first()
        val itunes = itunesSong(best.trackName, best.artistName)
        val tags = tagsFrom(best, itunes)
        val cover = itunes?.optString("artworkUrl100").orEmpty().toHighRes()
            .ifBlank { "https://i.ytimg.com/vi/$id/maxresdefault.jpg" }
        val thumb = oembed.optString("thumbnail_url").ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
        return Song(id, title, author, thumb, matches, tags, cover)
    }

    fun buildLrc(song: LyricMatch, offsetSeconds: Double): String {
        val ms = kotlin.math.round(-offsetSeconds * 1000).toInt()
        val header = buildList {
            add("[ti:${song.trackName}]")
            if (song.artistName.isNotBlank()) add("[ar:${song.artistName}]")
            if (song.albumName.isNotBlank()) add("[al:${song.albumName}]")
            if (ms != 0) add("[offset:${if (ms > 0) "+" else ""}$ms]")
        }.joinToString("\n")
        val body = song.syncedLyrics.lineSequence()
            .filterNot { it.trim().startsWith("[offset:") }
            .joinToString("\n")
            .trim()
        return "$header\n$body\n"
    }

    fun saveLrc(context: Context, song: LyricMatch, offsetSeconds: Double): String {
        val name = fileBase(song.artistName, song.trackName) + ".lrc"
        writeDownload(context, name, "application/octet-stream", buildLrc(song, offsetSeconds).toByteArray())
        return name
    }

    fun saveCover(context: Context, url: String, artist: String, title: String): String {
        val bytes = getBytes(url)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val square = bounds.outWidth > 0 && bounds.outWidth == bounds.outHeight
        val payload = if (square) bytes else {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("Cover nahi khuli.")
            val side = min(bitmap.width, bitmap.height)
            val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            val stream = java.io.ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            if (cropped != bitmap) cropped.recycle()
            bitmap.recycle()
            stream.toByteArray()
        }
        val name = fileBase(artist, title) + ".jpg"
        writeDownload(context, name, "image/jpeg", payload)
        return name
    }

    private fun searchLyrics(guess: TrackGuess): List<LyricMatch> {
        val jobs = mutableListOf<String>()
        if (guess.track.isNotBlank()) {
            val structured = buildString {
                append("https://lrclib.net/api/search?track_name=")
                append(enc(guess.track))
                if (guess.artist.isNotBlank()) {
                    append("&artist_name=")
                    append(enc(guess.artist))
                }
            }
            jobs += structured
        }
        val query = listOf(guess.track, guess.artist, guess.hint).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isNotBlank()) jobs += "https://lrclib.net/api/search?q=${enc(query)}"
        val found = linkedMapOf<Int, LyricMatch>()
        var ok = false
        for (url in jobs.distinct()) {
            val array = getArray(url) ?: continue
            ok = true
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                if (id < 0) continue
                val match = item.toMatch()
                val prev = found[id]
                if (prev == null || match.syncedLyrics.length > prev.syncedLyrics.length) found[id] = match
            }
        }
        if (!ok) error("Lyrics service abhi jawab nahi de rahi.")
        return found.values
            .filter { it.syncedLyrics.isNotBlank() || it.plainLyrics.isNotBlank() }
            .sortedByDescending { score(guess, it) }
            .take(6)
    }

    private fun itunesSong(track: String, artist: String): JSONObject? {
        if (track.isBlank() && artist.isBlank()) return null
        val url = "https://itunes.apple.com/search?term=${enc("$track $artist".trim())}&entity=song&limit=8"
        val body = getJson(url) ?: return null
        val results = body.optJSONArray("results") ?: return null
        val wantTrack = normalize(track)
        val wantArtist = normalize(artist)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until results.length()) {
            val song = results.optJSONObject(i) ?: continue
            val name = normalize(song.optString("trackName"))
            if (name.isBlank()) continue
            var score = when {
                wantTrack.isBlank() -> 10
                name == wantTrack -> 40
                name.contains(wantTrack) || wantTrack.contains(name) -> 24
                else -> continue
            }
            val who = normalize(song.optString("artistName"))
            if (wantArtist.isNotBlank() && (who.contains(wantArtist) || wantArtist.contains(who))) score += 16
            if (score > bestScore) {
                bestScore = score
                best = song
            }
        }
        return best
    }

    private fun tagsFrom(match: LyricMatch, itunes: JSONObject?): SongTags {
        val year = itunes?.optString("releaseDate").orEmpty().take(4).filter { it.isDigit() }
        return SongTags(
            title = itunes?.optString("trackName").orEmpty().ifBlank { match.trackName },
            album = itunes?.optString("collectionName").orEmpty().ifBlank { match.albumName },
            artist = itunes?.optString("artistName").orEmpty().ifBlank { match.artistName },
            albumArtist = itunes?.optString("collectionArtistName").orEmpty().ifBlank { match.artistName },
            genre = itunes?.optString("primaryGenreName").orEmpty(),
            trackNumber = itunes?.optInt("trackNumber", 0)?.takeIf { it > 0 }?.toString().orEmpty(),
            year = year,
        )
    }

    private fun writeDownload(context: Context, name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("File save nahi ho payi.")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("File save nahi ho payi.")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    }

    private fun getBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "image/*,*/*").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Cover nahi mili.")
            return response.body?.bytes() ?: error("Cover nahi mili.")
        }
    }

    private fun getJson(url: String): JSONObject? {
        val text = getText(url) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun getArray(url: String): JSONArray? {
        val text = getText(url) ?: return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    private fun getText(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun JSONObject.toMatch(): LyricMatch {
        val synced = optString("syncedLyrics")
        return LyricMatch(
            id = optInt("id"),
            trackName = optString("trackName").ifBlank { "Unknown" },
            artistName = optString("artistName"),
            albumName = optString("albumName"),
            duration = optDouble("duration", 0.0),
            plainLyrics = optString("plainLyrics").take(20_000),
            syncedLyrics = synced.take(40_000),
            lines = parseSynced(synced),
        )
    }

    companion object {
        private const val UA = "Bol Android (synced lyrics lookup)"
        private val idPattern = Regex("^[\\w-]{11}$")
        private val noise = listOf(
            Regex("""\((?:official|lyrics?|lyric video|audio|visualizer|music video|mv|hd|4k)[^)]*\)""", RegexOption.IGNORE_CASE),
            Regex("""\[(?:official|lyrics?|lyric video|audio|visualizer|music video|mv|hd|4k)[^\]]*\]""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:official\s+(?:music\s+)?video|official\s+audio|lyric\s+video|full\s+video|video\s+song)\b""", RegexOption.IGNORE_CASE),
        )
        private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")

        fun parseYouTubeId(input: String): String? {
            val raw = input.trim()
            if (raw.matches(idPattern)) return raw
            val uri = Uri.parse(if (raw.contains("://")) raw else "https://$raw")
            val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
            if (host !in setOf("youtu.be", "youtube.com", "music.youtube.com", "youtube-nocookie.com", "music.youtube-nocookie.com")) {
                return null
            }
            if (host == "youtu.be") return uri.pathSegments.firstOrNull()?.takeIf { it.matches(idPattern) }
            uri.getQueryParameter("v")?.takeIf { it.matches(idPattern) }?.let { return it }
            val parts = uri.pathSegments
            val key = parts.indexOfFirst { it in setOf("embed", "shorts", "live", "v", "watch") }
            return parts.getOrNull(key + 1)?.takeIf { key >= 0 && it.matches(idPattern) }
        }

        fun parseSynced(raw: String): List<LyricLine> {
            if (raw.isBlank()) return emptyList()
            val lines = mutableListOf<LyricLine>()
            for (row in raw.lineSequence()) {
                val marks = stamp.findAll(row).toList()
                if (marks.isEmpty()) continue
                val text = row.substring(marks.last().range.last + 1).trim()
                if (text.isEmpty()) continue
                for (mark in marks) {
                    val time = mark.groupValues[1].toDouble() * 60 + mark.groupValues[2].toDouble()
                    lines += LyricLine(time, text)
                }
            }
            return lines.sortedBy { it.time }
        }

        fun fileBase(artist: String, title: String): String {
            return listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
                .replace(Regex("""[^\p{L}\p{N}\s._-]+"""), "")
                .trim()
                .take(80)
                .ifBlank { "lyrics" }
        }

        private fun cleanAuthor(author: String) = author.replace(Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE), "").trim()

        private fun guessTrack(title: String, author: String): TrackGuess {
            var cleaned = title
            for (pattern in noise) cleaned = cleaned.replace(pattern, " ")
            cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
            val core = cleaned.substringBefore("|").trim()
            val pieces = core.split(Regex("""\s+[-–—]\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
            val label = Regex("t-series|sony music|zee music|yash raj|saregama", RegexOption.IGNORE_CASE).containsMatchIn(author)
            return when {
                pieces.size >= 2 && !label -> TrackGuess(pieces.first(), pieces.drop(1).joinToString(" - "), "")
                pieces.size >= 2 -> TrackGuess("", pieces.first(), pieces.drop(1).joinToString(" "))
                else -> TrackGuess(if (label) "" else author, core.ifBlank { cleaned }, "")
            }
        }

        private fun normalize(value: String) = value.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

        private fun score(guess: TrackGuess, match: LyricMatch): Int {
            var score = if (match.syncedLyrics.isNotBlank()) 30 else 8
            val want = normalize(guess.track)
            val name = normalize(match.trackName)
            if (want.isNotBlank() && (name == want || name.contains(want) || want.contains(name))) score += 40
            val artist = normalize(guess.artist)
            val who = normalize(match.artistName)
            if (artist.isNotBlank() && (who.contains(artist) || artist.contains(who))) score += 16
            return score
        }

        private fun enc(value: String) = Uri.encode(value)

        private fun String.toHighRes(): String {
            if (!startsWith("https://") || !contains("mzstatic.com")) return ""
            return replace(Regex("""/\d+x\d+bb(?=\.)"""), "/3000x3000bb")
        }
    }
}

private data class TrackGuess(val artist: String, val track: String, val hint: String)
