package blbl.cat3399.core.model

data class VideoCard(
    val bvid: String,
    val cid: Long?,
    val aid: Long? = null,
    val epId: Long? = null,
    val business: String? = null,
    val subType: Int? = null,
    val title: String,
    val coverUrl: String,
    val durationSec: Int,
    val ownerName: String,
    val ownerFace: String?,
    val ownerMid: Long? = null,
    val view: Long?,
    val danmaku: Long?,
    val pubDate: Long?,
    val pubDateText: String?,
    val isChargingArc: Boolean = false,
) {
    fun stableKey(): String =
        when {
            bvid.isNotBlank() -> "bvid:$bvid"
            epId != null && epId > 0 -> "ep:$epId"
            aid != null && aid > 0 -> "aid:$aid"
            cid != null && cid > 0 -> "cid:$cid"
            else -> "title:${title.hashCode()}"
        }
}
