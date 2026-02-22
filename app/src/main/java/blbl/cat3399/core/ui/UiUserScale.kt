package blbl.cat3399.core.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import blbl.cat3399.R
import blbl.cat3399.core.prefs.AppPrefs
import kotlin.math.roundToInt

/**
 * Apply the user UI scale factor ([UiScale]) by overriding [Configuration.densityDpi] for a *subtree*.
 *
 * Why:
 * - [UiDensity] already normalizes device/resolution/system-density to the baseline at Activity attachBaseContext().
 * - [UiScale] is a user preference (0.70~1.40) and in B-plan we apply it explicitly.
 * - Wrapping a Context like this makes dp/sp/@dimen automatically reflect the user scale, removing lots of
 *   per-module manual `applySizing(uiScale)` code.
 *
 * Important:
 * - This is **opt-in**: do NOT replace [BaseActivity.attachBaseContext] with this, otherwise it becomes global.
 * - Avoid double-wrapping: this helper is idempotent (wrapping an already-wrapped context won't stack scales).
 */
object UiUserScale {
    fun isWrapped(context: Context): Boolean = context is UserScaledContext

    fun unwrap(context: Context): Context = (context as? UserScaledContext)?.originalBase ?: context

    fun wrap(
        base: Context,
        scale: Float = UiScale.factor(base),
    ): Context {
        val desiredScaleRaw =
            scale
                .takeIf { it.isFinite() && it > 0f }
                ?: 1.0f
        val desiredScale = desiredScaleRaw.coerceIn(AppPrefs.UI_SCALE_FACTOR_MIN, AppPrefs.UI_SCALE_FACTOR_MAX)

        val originalBase = unwrap(base)
        if (desiredScale == 1.0f) return originalBase

        val res = originalBase.resources
        val currentDensityDpi = res.configuration.densityDpi
        val targetDensityDpi =
            (currentDensityDpi * desiredScale)
                .roundToInt()
                .coerceAtLeast(1)
        if (currentDensityDpi == targetDensityDpi) return originalBase

        val config = Configuration(res.configuration)
        config.densityDpi = targetDensityDpi

        // Important: use a themed wrapper so AppCompat/MaterialComponents views can inflate correctly.
        // We must apply a concrete theme resource here (instead of `theme.setTo(...)`) because some
        // internal Material layouts rely on AppCompat theme attrs like `listPreferredItemHeightSmall`.
        //
        // Note: the app uses Theme.Blbl as the global theme in AndroidManifest.xml.
        val themed = ContextThemeWrapper(originalBase, R.style.Theme_Blbl)
        themed.applyOverrideConfiguration(config)
        return UserScaledContext(
            base = themed,
            originalBase = originalBase,
            uiScale = desiredScale,
            targetDensityDpi = targetDensityDpi,
        )
    }

    private class UserScaledContext(
        base: Context,
        val originalBase: Context,
        val uiScale: Float,
        val targetDensityDpi: Int,
    ) : ContextWrapper(base)
}

fun Context.userScaledContext(scale: Float = UiScale.factor(this)): Context = UiUserScale.wrap(this, scale)

fun LayoutInflater.cloneInUserScale(context: Context, scale: Float = UiScale.factor(context)): LayoutInflater {
    return cloneInContext(UiUserScale.wrap(context, scale))
}
