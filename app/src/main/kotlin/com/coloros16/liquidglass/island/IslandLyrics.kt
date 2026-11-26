package com.coloros16.liquidglass.island

import android.content.SharedPreferences
import android.media.session.PlaybackState
import android.os.SystemClock
import java.security.MessageDigest
import java.util.Locale

internal object IslandLyrics {
    const val ENABLED = IslandPrefs.PREFIX + "lyrics_enabled"
    const val TRANSLATION = IslandPrefs.PREFIX + "lyrics_translation"
    const val TRANSLATION_FIRST = IslandPrefs.PREFIX + "lyrics_translation_first"
    const val TRACKS = IslandPrefs.PREFIX + "lyric_track_"
    const val MAX_BYTES = 96 * 1024
    private val stamps = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""\[offset\s*:\s*([+-]?\d{1,7})]""", RegexOption.IGNORE_CASE)

    data class Line(val time: Long, val parts: List<String>)
    data class Track(val lines: List<Line>, val translated: Boolean, val translationFirst: Boolean) {
        fun index(position: Long): Int {
            val index = lines.binarySearchBy(position) { it.time }
            return if (index < 0) -index - 2 else index
        }
        fun display(index: Int): String {
            val part = lines.getOrNull(index)?.parts.orEmpty()
            if (!translated || part.size < 2) return part.firstOrNull().orEmpty()
            return (if (translationFirst) part.reversed() else part).joinToString(" · ")
        }
    }

    fun parse(raw: String): List<Line> {
        val shift = offset.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val result = java.util.TreeMap<Long, LinkedHashSet<String>>()
        raw.lineSequence().forEach { source ->
            val markers = stamps.findAll(source).take(12).toList()
            val last = markers.lastOrNull() ?: return@forEach
            val text = source.substring(last.range.last + 1).trim().take(400)
            if (text.isEmpty()) return@forEach
            for (m in markers) {
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].toLong()
                if (seconds >= 60) continue
                val ms = m.groupValues[3].padEnd(3, '0').toLong()
                val whenMs = (minutes * 60_000L + seconds * 1_000L + ms + shift).coerceAtLeast(0)
                val at = result.getOrPut(whenMs) { LinkedHashSet() }
                if (at.size < 2) at.add(text)
            }
        }
        return result.entries.take(3000).map { (time, parts) -> Line(time, parts.toList()) }
    }

    fun key(title: String, artist: String): String {
        val track = title.trim().lowercase(Locale.ROOT) + "\u0000" + artist.trim().lowercase(Locale.ROOT)
        val hash = MessageDigest.getInstance("SHA-256").digest(track.toByteArray())
        return TRACKS + hash.take(16).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    fun load(prefs: SharedPreferences, title: String, artist: String): Track? {
        if (!prefs.getBoolean(ENABLED, false)) return null
        val raw = prefs.getString(key(title, artist), null)
            ?: prefs.getString(key(title, ""), null) ?: return null
        val lines = parse(raw)
        return Track(lines, prefs.getBoolean(TRANSLATION, true), prefs.getBoolean(TRANSLATION_FIRST, false))
            .takeIf { lines.isNotEmpty() }
    }

    fun position(state: PlaybackState): Long {
        val advancing = state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0
        val delta = if (advancing) ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed)
            .toLong() else 0
        return (state.position + delta).coerceAtLeast(0)
    }
}
