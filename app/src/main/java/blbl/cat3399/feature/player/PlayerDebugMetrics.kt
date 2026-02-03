package blbl.cat3399.feature.player

import androidx.media3.common.Player

internal class PlayerDebugMetrics {
    var cdnHost: String? = null
    var videoTransferHost: String? = null
    var audioTransferHost: String? = null
    var videoDecoderName: String? = null
    var videoInputWidth: Int? = null
    var videoInputHeight: Int? = null
    var videoInputFps: Float? = null
    var droppedFramesTotal: Long = 0L
    var rebufferCount: Int = 0
    var lastPlaybackState: Int = Player.STATE_IDLE
    var renderFps: Float? = null
    var renderFpsLastAtMs: Long? = null
    var renderedFramesLastCount: Int? = null
    var renderedFramesLastAtMs: Long? = null

    fun reset() {
        cdnHost = null
        videoTransferHost = null
        audioTransferHost = null
        videoDecoderName = null
        videoInputWidth = null
        videoInputHeight = null
        videoInputFps = null
        droppedFramesTotal = 0L
        rebufferCount = 0
        lastPlaybackState = Player.STATE_IDLE
        renderFps = null
        renderFpsLastAtMs = null
        renderedFramesLastCount = null
        renderedFramesLastAtMs = null
    }
}

