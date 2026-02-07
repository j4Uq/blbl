package blbl.cat3399.feature.player

import android.util.TypedValue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

internal fun PlayerActivity.configureSubtitleView() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    // Move subtitles slightly up from the very bottom.
    subtitleView.setBottomPaddingFraction(0.16f)
    // Make background more transparent while keeping readability.
    subtitleView.setStyle(
        CaptionStyleCompat(
            /* foregroundColor= */ 0xFFFFFFFF.toInt(),
            /* backgroundColor= */ 0x22000000,
            /* windowColor= */ 0x00000000,
            /* edgeType= */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            /* edgeColor= */ 0xCC000000.toInt(),
            /* typeface= */ null,
        ),
    )
    applySubtitleTextSize()
}

internal fun PlayerActivity.applySubtitleTextSize() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    val sizeSp =
        session.subtitleTextSizeSp
            .let { if (it.isFinite()) it else 26f }
            .coerceIn(10f, 60f)
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}
