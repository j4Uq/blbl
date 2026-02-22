package blbl.cat3399.core.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Build
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    private var createdUiScaleFactor: Float? = null
    private var pendingUiScaleRecreate: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiDensity.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdUiScaleFactor = UiScale.factor(this)
    }

    override fun onResume() {
        super.onResume()
        maybeRecreateOnUiScaleChanged()
    }

    protected open fun shouldRecreateOnUiScaleChange(): Boolean = true

    private fun maybeRecreateOnUiScaleChanged() {
        if (!shouldRecreateOnUiScaleChange()) return
        if (isFinishing || isDestroyed) return

        val created = createdUiScaleFactor ?: UiScale.factor(this).also { createdUiScaleFactor = it }
        val now = UiScale.factor(this)
        if (created == now) return

        if (pendingUiScaleRecreate) return
        pendingUiScaleRecreate = true
        createdUiScaleFactor = now

        // Post to avoid triggering recreate while subclasses are still running their own onResume logic.
        window?.decorView?.post {
            pendingUiScaleRecreate = false
            if (!shouldRecreateOnUiScaleChange()) return@post
            if (isFinishing || isDestroyed) return@post
            recreate()
        }
    }

    protected fun applyCloseTransitionNoAnim() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
