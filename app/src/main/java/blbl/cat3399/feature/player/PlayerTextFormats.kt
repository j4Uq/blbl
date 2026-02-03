package blbl.cat3399.feature.player

import org.json.JSONArray
import java.util.Locale

internal fun normalizeUrl(url: String): String {
    val u = url.trim()
    return when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") || u.startsWith("https://") -> u
        else -> "https://$u"
    }
}

internal fun buildWebVtt(body: JSONArray): String {
    val sb = StringBuilder()
    sb.append("WEBVTT\n\n")
    for (i in 0 until body.length()) {
        val line = body.optJSONObject(i) ?: continue
        val from = line.optDouble("from", -1.0)
        val to = line.optDouble("to", -1.0)
        val content = line.optString("content", "").trim()
        if (from < 0 || to <= 0 || content.isBlank()) continue
        sb.append(formatVttTime(from)).append(" --> ").append(formatVttTime(to)).append('\n')
        sb.append(content.replace('\n', ' ')).append("\n\n")
    }
    return sb.toString()
}

private fun formatVttTime(sec: Double): String {
    val ms = (sec * 1000.0).toLong().coerceAtLeast(0L)
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    val s = (ms % 60_000) / 1000
    val milli = ms % 1000
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, milli)
}

internal fun formatHms(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    // Keep the same style as other duration displays:
    // - < 1h: mm:ss (00:06 / 15:10)
    // - >= 1h: h:mm:ss (1:01:20)
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

