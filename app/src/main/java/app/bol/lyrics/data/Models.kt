package app.bol.lyrics.data

data class LyricLine(val time: Double, val text: String)

data class LyricMatch(
    val id: Int,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val duration: Double,
    val plainLyrics: String,
    val syncedLyrics: String,
    val lines: List<LyricLine>,
)

data class SongTags(
    val title: String,
    val album: String,
    val artist: String,
    val albumArtist: String,
    val genre: String,
    val trackNumber: String,
    val year: String,
)

data class Song(
    val videoId: String,
    val videoTitle: String,
    val author: String,
    val thumbnail: String,
    val matches: List<LyricMatch>,
    val tags: SongTags,
    val coverUrl: String,
)

data class HistoryItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
)
