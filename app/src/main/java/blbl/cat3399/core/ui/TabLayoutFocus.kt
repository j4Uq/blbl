package blbl.cat3399.core.ui

import android.animation.AnimatorInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StyleRes
import blbl.cat3399.R
import com.google.android.material.tabs.TabLayout

fun TabLayout.enableDpadTabFocus(
    selectOnFocus: Boolean = true,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    enableDpadTabFocus(selectOnFocusProvider = { selectOnFocus }, onTabFocused = onTabFocused)
}

fun TabLayout.enableDpadTabFocus(
    selectOnFocusProvider: () -> Boolean,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    val tabStrip = getChildAt(0) as? ViewGroup ?: return
    for (i in 0 until tabStrip.childCount) {
        val index = i
        val tabView = tabStrip.getChildAt(i)
        tabView.isFocusable = true
        tabView.isClickable = true
        tabView.stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.blbl_focus_scale)
        tabView.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (selectOnFocusProvider() && selectedTabPosition != index) {
                getTabAt(index)?.select()
            }
            onTabFocused?.invoke(index)
        }
    }
}

/**
 * Enforce user-scale-aware tab text size for [Widget.Blbl.TabLayout].
 *
 * Why: TabLayout text size is defined in a TextAppearance (sp) which is normalized by [UiDensity],
 * but does not automatically apply the user preference [UiScale]. In B plan we keep userScale as
 * explicit opt-in, so we patch tab labels where we want them to respect `uiScaleFactor`.
 */
fun TabLayout.enforceUserScaleTabTextSize(
    @StyleRes textAppearanceRes: Int = R.style.TextAppearance_Blbl_Tab,
    scale: Float = UiScale.factor(context),
) {
    val s = scale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
    val basePx =
        context.obtainStyledAttributes(textAppearanceRes, intArrayOf(android.R.attr.textSize)).run {
            try {
                getDimension(0, 0f)
            } finally {
                recycle()
            }
        }
    if (basePx <= 0f) return
    val expectedPx = context.uiScaler(s).scaledPxF(basePx, minPx = 1f)

    fun findFirstTextView(view: View): TextView? {
        if (view is TextView) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val found = findFirstTextView(group.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    val tabStrip = getChildAt(0) as? ViewGroup ?: return
    for (i in 0 until tabStrip.childCount) {
        val tabView = tabStrip.getChildAt(i)
        val tv = findFirstTextView(tabView) ?: continue
        tv.setTextSizePxIfChanged(expectedPx)
    }
}

fun TabLayout.scheduleUserScaleTabTextSizeFix(
    isAlive: () -> Boolean,
    @StyleRes textAppearanceRes: Int = R.style.TextAppearance_Blbl_Tab,
) {
    val pending = (getTag(R.id.tag_tab_text_scale_fix_pending) as? Boolean) == true
    if (pending) return
    setTag(R.id.tag_tab_text_scale_fix_pending, true)

    // TabLayout may re-apply TextAppearance on selection / layout, which overwrites any manual px sizing.
    // Running on pre-draw ensures we always win the last write before the frame is rendered.
    doOnPreDrawIfAlive(isAlive = isAlive) {
        setTag(R.id.tag_tab_text_scale_fix_pending, false)
        enforceUserScaleTabTextSize(textAppearanceRes = textAppearanceRes)
    }
    invalidate()
}

fun TabLayout.installUserScaleTabTextSizeFixer(
    isAlive: () -> Boolean,
    @StyleRes textAppearanceRes: Int = R.style.TextAppearance_Blbl_Tab,
) {
    val existing = getTag(R.id.tag_tab_text_scale_fix_listener) as? TabLayout.OnTabSelectedListener
    if (existing != null) {
        scheduleUserScaleTabTextSizeFix(isAlive = isAlive, textAppearanceRes = textAppearanceRes)
        return
    }

    val listener =
        object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                scheduleUserScaleTabTextSizeFix(isAlive = isAlive, textAppearanceRes = textAppearanceRes)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                scheduleUserScaleTabTextSizeFix(isAlive = isAlive, textAppearanceRes = textAppearanceRes)
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                scheduleUserScaleTabTextSizeFix(isAlive = isAlive, textAppearanceRes = textAppearanceRes)
            }
        }
    addOnTabSelectedListener(listener)
    setTag(R.id.tag_tab_text_scale_fix_listener, listener)
    scheduleUserScaleTabTextSizeFix(isAlive = isAlive, textAppearanceRes = textAppearanceRes)
}
