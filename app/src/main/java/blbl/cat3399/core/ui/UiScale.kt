package blbl.cat3399.core.ui

import android.content.Context
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs

object UiScale {
    // Baseline chosen from a known-good TV layout:
    // 1920x1080 with screen scale shown as x1.50 in "设备信息 -> 屏幕".
    private const val TV_BASELINE_DENSITY = 1.5f

    // Avoid extreme sizes if the device reports unusual density values.
    private const val MIN_SCALE = 0.60f
    private const val MAX_SCALE = 1.40f

    fun presetFactor(prefValue: String): Float {
        return when (prefValue) {
            AppPrefs.SIDEBAR_SIZE_SMALL -> 0.90f
            AppPrefs.SIDEBAR_SIZE_LARGE -> 1.10f
            else -> 1.00f
        }
    }

    fun densityFixFactor(context: Context, tvMode: Boolean): Float {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1.0f
        val densityFix = if (tvMode) (TV_BASELINE_DENSITY / density) else 1.0f
        return densityFix.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun factor(context: Context, tvMode: Boolean): Float {
        return factor(
            context = context,
            tvMode = tvMode,
            prefValue = BiliClient.prefs.sidebarSize,
        )
    }

    fun factor(context: Context, tvMode: Boolean, prefValue: String): Float {
        val preset = presetFactor(prefValue)
        return (densityFixFactor(context, tvMode) * preset).coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
