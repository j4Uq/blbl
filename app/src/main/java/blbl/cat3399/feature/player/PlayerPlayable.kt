package blbl.cat3399.feature.player

internal sealed interface Playable {
    data class Dash(
        val videoUrl: String,
        val audioUrl: String,
        val videoUrlCandidates: List<String>,
        val audioUrlCandidates: List<String>,
        val qn: Int,
        val codecid: Int,
        val audioId: Int,
        val audioKind: DashAudioKind,
        val isDolbyVision: Boolean,
    ) : Playable

    data class VideoOnly(
        val videoUrl: String,
        val videoUrlCandidates: List<String>,
        val qn: Int,
        val codecid: Int,
        val isDolbyVision: Boolean,
    ) : Playable

    data class Progressive(
        val url: String,
        val urlCandidates: List<String>,
    ) : Playable
}

internal enum class DashAudioKind { NORMAL, DOLBY, FLAC }

internal data class PlaybackConstraints(
    val allowDolbyVision: Boolean = true,
    val allowDolbyAudio: Boolean = true,
    val allowFlacAudio: Boolean = true,
)

