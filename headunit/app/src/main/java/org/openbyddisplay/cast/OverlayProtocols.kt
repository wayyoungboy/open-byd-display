package org.openbyddisplay.cast

import org.json.JSONArray
import org.json.JSONObject

/** Music / lyric overlay payload builders for :47787 (self-developed subset). */
object MusicOverlayProtocol {
    private val LRC_LINE = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?](.*)""")

    fun track(
        title: String,
        artist: String,
        lyricLines: List<String> = emptyList(),
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        playing: Boolean = true,
    ): JSONObject = JSONObject()
        .put("title", title)
        .put("artist", artist)
        .put("playing", playing)
        .put("position_ms", positionMs)
        .put("duration_ms", durationMs)
        .put("lyrics", JSONArray(lyricLines))

    fun parseLrc(text: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (line in text.lineSequence()) {
            val m = LRC_LINE.matchEntire(line.trim()) ?: continue
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            if (sec >= 60 || out.size >= 10_000) continue
            val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val ms = min * 60_000 + sec * 1_000 + frac
            val body = m.groupValues[4].trim()
            if (body.isNotEmpty()) out.add(ms to body)
        }
        return out.sortedBy { it.first }
    }

    fun linesAt(timeline: List<Pair<Long, String>>, positionMs: Long, count: Int): List<String> {
        if (timeline.isEmpty() || count <= 0) return emptyList()
        var idx = timeline.indexOfLast { it.first <= positionMs }
        if (idx < 0) return emptyList()
        val start = (idx - count / 2).coerceAtLeast(0)
        return timeline.drop(start).take(count).map { it.second }
    }
}

/** Simple navigation / TBT overlay payload for :47786. */
object NavOverlayProtocol {
    fun guidance(
        nextRoad: String,
        distanceMeters: Int,
        icon: String = "straight",
        remainingMeters: Int? = null,
        etaSec: Int? = null,
        lanes: List<String> = emptyList(),
        speedLimit: Int? = null,
    ): JSONObject {
        val o = JSONObject()
            .put("next_road", nextRoad)
            .put("distance_m", distanceMeters)
            .put("icon", icon)
            .put("lanes", JSONArray(lanes))
        if (remainingMeters != null) o.put("remaining_m", remainingMeters)
        if (etaSec != null) o.put("eta_sec", etaSec)
        if (speedLimit != null) o.put("speed_limit", speedLimit)
        return o
    }
}
