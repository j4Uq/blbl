package blbl.cat3399.core.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs

object TvMode {
    fun isEnabled(context: Context): Boolean {
        return when (BiliClient.prefs.uiMode) {
            AppPrefs.UI_MODE_TV -> true
            AppPrefs.UI_MODE_NORMAL -> false
            else -> isTelevisionDevice(context)
        }
    }

    private fun isTelevisionDevice(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY)) return true
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
