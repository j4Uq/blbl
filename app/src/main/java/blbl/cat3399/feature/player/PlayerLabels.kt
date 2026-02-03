package blbl.cat3399.feature.player

internal fun audioLabel(id: Int): String =
    when (id) {
        30251 -> "Hi-Res 无损"
        30250 -> "杜比全景声"
        30280 -> "192K"
        30232 -> "132K"
        30216 -> "64K"
        else -> id.toString()
    }

internal fun qnLabel(qn: Int): String =
    when (qn) {
        16 -> "360P 流畅"
        32 -> "480P 清晰"
        64 -> "720P 高清"
        74 -> "720P60 高帧率"
        80 -> "1080P 高清"
        100 -> "智能修复"
        112 -> "1080P+ 高码率"
        116 -> "1080P60 高帧率"
        120 -> "4K 超清"
        125 -> "HDR 真彩色"
        126 -> "杜比视界"
        127 -> "8K 超高清"
        129 -> "HDR Vivid"
        else -> qn.toString()
    }

internal fun qnRank(qn: Int): Int {
    // Follow docs ordering (roughly increasing quality).
    val order = intArrayOf(6, 16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
    val idx = order.indexOf(qn)
    return if (idx >= 0) idx else (order.size + qn)
}

internal fun areaText(area: Float): String =
    when {
        area >= 0.99f -> "不限"
        area >= 0.78f -> "4/5"
        area >= 0.71f -> "3/4"
        area >= 0.62f -> "2/3"
        area >= 0.55f -> "3/5"
        area >= 0.45f -> "1/2"
        area >= 0.36f -> "2/5"
        area >= 0.29f -> "1/3"
        area >= 0.22f -> "1/4"
        else -> "1/5"
    }

