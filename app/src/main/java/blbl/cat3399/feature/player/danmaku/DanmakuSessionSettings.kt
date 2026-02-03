package blbl.cat3399.feature.player.danmaku

data class DanmakuSessionSettings(
    val enabled: Boolean,
    val opacity: Float,
    val textSizeSp: Float,
    val speedLevel: Int,
    val area: Float,
) {
    fun toConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = enabled,
            opacity = opacity,
            textSizeSp = textSizeSp,
            speedLevel = speedLevel,
            area = area,
        )
}

