package blbl.cat3399.feature.player

import org.json.JSONObject

internal fun parseUgcSeasonPlaylistFromView(ugcSeason: JSONObject): List<PlayerPlaylistItem> {
    val sections = ugcSeason.optJSONArray("sections") ?: return emptyList()
    val out = ArrayList<PlayerPlaylistItem>(ugcSeason.optInt("ep_count").coerceAtLeast(0))
    for (i in 0 until sections.length()) {
        val section = sections.optJSONObject(i) ?: continue
        val eps = section.optJSONArray("episodes") ?: continue
        for (j in 0 until eps.length()) {
            val ep = eps.optJSONObject(j) ?: continue
            val arc = ep.optJSONObject("arc") ?: JSONObject()
            val bvid = ep.optString("bvid", "").trim().ifBlank { arc.optString("bvid", "").trim() }
            if (bvid.isBlank()) continue
            val cid = ep.optLong("cid").takeIf { it > 0 } ?: arc.optLong("cid").takeIf { it > 0 }
            val aid = ep.optLong("aid").takeIf { it > 0 } ?: arc.optLong("aid").takeIf { it > 0 }
            val title = ep.optString("title", "").trim().takeIf { it.isNotBlank() } ?: arc.optString("title", "").trim()
            out.add(
                PlayerPlaylistItem(
                    bvid = bvid,
                    cid = cid,
                    aid = aid,
                    title = title.takeIf { it.isNotBlank() },
                ),
            )
        }
    }
    return out
}

internal fun parseMultiPagePlaylistFromView(viewData: JSONObject, bvid: String, aid: Long?): List<PlayerPlaylistItem> {
    val pages = viewData.optJSONArray("pages") ?: return emptyList()
    if (pages.length() <= 1) return emptyList()
    val out = ArrayList<PlayerPlaylistItem>(pages.length())
    for (i in 0 until pages.length()) {
        val obj = pages.optJSONObject(i) ?: continue
        val cid = obj.optLong("cid").takeIf { it > 0 } ?: continue
        val page = obj.optInt("page").takeIf { it > 0 } ?: (i + 1)
        val part = obj.optString("part", "").trim()
        val title =
            if (part.isBlank()) {
                "P$page"
            } else {
                "P$page $part"
            }
        out.add(
            PlayerPlaylistItem(
                bvid = bvid,
                cid = cid,
                aid = aid,
                title = title,
            ),
        )
    }
    return out
}

internal fun parseUgcSeasonPlaylistFromArchivesList(json: JSONObject): List<PlayerPlaylistItem> {
    val archives = json.optJSONObject("data")?.optJSONArray("archives") ?: return emptyList()
    val out = ArrayList<PlayerPlaylistItem>(archives.length())
    for (i in 0 until archives.length()) {
        val obj = archives.optJSONObject(i) ?: continue
        val bvid = obj.optString("bvid", "").trim()
        if (bvid.isBlank()) continue
        val aid = obj.optLong("aid").takeIf { it > 0 }
        val title = obj.optString("title", "").trim().takeIf { it.isNotBlank() }
        out.add(
            PlayerPlaylistItem(
                bvid = bvid,
                aid = aid,
                title = title,
            ),
        )
    }
    return out
}

internal fun pickPlaylistIndexForCurrentMedia(list: List<PlayerPlaylistItem>, bvid: String, aid: Long?, cid: Long?): Int {
    val safeBvid = bvid.trim()
    if (cid != null && cid > 0) {
        val byCid = list.indexOfFirst { it.cid == cid }
        if (byCid >= 0) return byCid
    }
    if (aid != null && aid > 0) {
        val byAid = list.indexOfFirst { it.aid == aid }
        if (byAid >= 0) return byAid
    }
    if (safeBvid.isNotBlank()) {
        val byBvid = list.indexOfFirst { it.bvid == safeBvid }
        if (byBvid >= 0) return byBvid
    }
    return -1
}

internal fun isMultiPagePlaylist(list: List<PlayerPlaylistItem>, currentBvid: String): Boolean {
    if (list.size < 2) return false
    val bvid = currentBvid.trim().takeIf { it.isNotBlank() } ?: return false
    return list.all { it.bvid == bvid && (it.cid ?: 0L) > 0L }
}

