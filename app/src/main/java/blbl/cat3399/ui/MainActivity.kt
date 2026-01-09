package blbl.cat3399.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityMainBinding
import blbl.cat3399.feature.category.CategoryFragment
import blbl.cat3399.feature.dynamic.DynamicFragment
import blbl.cat3399.feature.home.HomeFragment
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.settings.SettingsActivity
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navAdapter: SidebarNavAdapter
    private var needForceInitialSidebarFocus: Boolean = false
    private var lastMainFocusedView: WeakReference<View>? = null
    private var pausedFocusedView: WeakReference<View>? = null
    private var pausedFocusWasInMain: Boolean = false
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        needForceInitialSidebarFocus = savedInstanceState == null
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        binding.btnSidebarLogin.setOnClickListener { openQrLogin() }
        binding.ivSidebarUser.setOnClickListener {
            if (!BiliClient.cookies.hasSessData()) {
                openQrLogin()
                return@setOnClickListener
            }
            UserInfoDialogFragment.show(supportFragmentManager)
        }
        binding.btnSidebarSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        navAdapter = SidebarNavAdapter(
            onClick = { item ->
                when (item.id) {
                    SidebarNavAdapter.ID_HOME -> showRoot(HomeFragment.newInstance())
                    SidebarNavAdapter.ID_CATEGORY -> showRoot(CategoryFragment.newInstance())
                    SidebarNavAdapter.ID_DYNAMIC -> showRoot(DynamicFragment.newInstance())
                    else -> false
                }
            },
        )
        binding.recyclerSidebar.layoutManager = LinearLayoutManager(this)
        binding.recyclerSidebar.adapter = navAdapter
        navAdapter.submit(
            listOf(
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_HOME, getString(R.string.tab_recommend), R.drawable.ic_nav_home),
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_CATEGORY, getString(R.string.tab_category), R.drawable.ic_nav_category),
                SidebarNavAdapter.NavItem(SidebarNavAdapter.ID_DYNAMIC, getString(R.string.tab_dynamic), R.drawable.ic_nav_dynamic),
            ),
            selectedId = SidebarNavAdapter.ID_HOME,
        )

        if (savedInstanceState == null) {
            navAdapter.select(SidebarNavAdapter.ID_HOME, trigger = true)
        }

        focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null && isInMainContainer(newFocus)) {
                    lastMainFocusedView = WeakReference(newFocus)
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }

        refreshSidebarUser()
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
        restoreFocusAfterResume()
        forceInitialSidebarFocusIfNeeded()
        ensureInitialFocus()
        refreshSidebarUser()
    }

    override fun onPause() {
        val focused = currentFocus
        pausedFocusedView = focused?.let { WeakReference(it) }
        pausedFocusWasInMain = focused != null && isInMainContainer(focused)
        super.onPause()
    }

    override fun onDestroy() {
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused == null && isNavKey(event.keyCode)) {
                ensureInitialFocus()
                return true
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focused != null && isInMainContainer(focused)) {
                        if (tryMoveDynamicVideoToFollowing(focused)) return true
                        if (canEnterSidebarFrom(focused)) {
                            focusSidebarFirstNav()
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focused != null && isInSidebar(focused)) {
                        focusMainFromSidebar()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    if (focused != null && isInSidebar(focused)) {
                        val moved = moveSidebarFocus(up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
                        if (moved) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showRoot(fragment: androidx.fragment.app.Fragment): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .commitAllowingStateLoss()
        return true
    }

    private fun openQrLogin() {
        AppLog.i("MainActivity", "openQrLogin")
        startActivity(Intent(this, QrLoginActivity::class.java))
    }

    private fun refreshSidebarUser() {
        val hasCookie = BiliClient.cookies.hasSessData()
        if (!hasCookie) {
            showLoggedOut()
            return
        }
        lifecycleScope.launch {
            runCatching {
                val nav = BiliApi.nav()
                val data = nav.optJSONObject("data")
                val isLogin = data?.optBoolean("isLogin") ?: false
                val avatarUrl = data?.optString("face")?.takeIf { it.isNotBlank() }
                if (isLogin) showLoggedIn(avatarUrl) else showLoggedOut()
            }.onFailure {
                AppLog.w("MainActivity", "refreshSidebarUser failed", it)
            }
        }
    }

    private fun showLoggedIn(avatarUrl: String?) {
        binding.btnSidebarLogin.visibility = android.view.View.GONE
        binding.ivSidebarUser.visibility = android.view.View.VISIBLE
        blbl.cat3399.core.image.ImageLoader.loadInto(binding.ivSidebarUser, avatarUrl)
    }

    private fun showLoggedOut() {
        binding.ivSidebarUser.visibility = android.view.View.GONE
        binding.btnSidebarLogin.visibility = android.view.View.VISIBLE
    }

    private fun applyUiMode() {
        val tvMode = TvMode.isEnabled(this)
        if (::navAdapter.isInitialized) navAdapter.setShowLabelsAlways(tvMode)

        val widthDp = if (tvMode) 160f else 48f
        val widthPx = dp(widthDp)
        val lp = binding.sidebar.layoutParams
        if (lp.width != widthPx) {
            lp.width = widthPx
            binding.sidebar.layoutParams = lp
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        val pos = navAdapter.selectedAdapterPosition().takeIf { it >= 0 } ?: 0
        binding.recyclerSidebar.post {
            val vh = binding.recyclerSidebar.findViewHolderForAdapterPosition(pos)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            binding.recyclerSidebar.scrollToPosition(pos)
            binding.recyclerSidebar.post {
                binding.recyclerSidebar.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
            }
        }
    }

    private fun forceInitialSidebarFocusIfNeeded() {
        if (!needForceInitialSidebarFocus) return
        val focused = currentFocus
        if (focused == null || !isDescendantOf(focused, binding.recyclerSidebar)) {
            focusSidebarFirstNav()
        }
        needForceInitialSidebarFocus = false
    }

    private fun restoreFocusAfterResume() {
        val desired = pausedFocusedView?.get()
        if (desired != null && desired.isAttachedToWindow && desired.isShown) {
            binding.root.post { desired.requestFocus() }
            return
        }
        if (!pausedFocusWasInMain) return

        val lastMain = lastMainFocusedView?.get()
        if (lastMain != null && lastMain.isAttachedToWindow && lastMain.isShown && isInMainContainer(lastMain)) {
            binding.root.post { lastMain.requestFocus() }
            return
        }

        val focusedNow = currentFocus
        if (focusedNow != null && isInSidebar(focusedNow)) {
            binding.root.post { focusMainFromSidebar() }
        }
    }

    private fun isInSidebar(view: View): Boolean = isDescendantOf(view, binding.sidebar)

    private fun isInMainContainer(view: View): Boolean = isDescendantOf(view, binding.mainContainer)

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            val parent = current.parent
            current = parent as? View
        }
        return false
    }

    private fun focusSidebarFirstNav(): Boolean = focusSidebarNavAt(0)

    private fun focusSidebarNavAt(position: Int): Boolean {
        if (position < 0 || position >= navAdapter.itemCount) return false
        binding.recyclerSidebar.post {
            val vh = binding.recyclerSidebar.findViewHolderForAdapterPosition(position)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            binding.recyclerSidebar.scrollToPosition(position)
            binding.recyclerSidebar.post {
                binding.recyclerSidebar.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
        return true
    }

    private fun focusMainFromSidebar(): Boolean {
        val fragmentView = supportFragmentManager.findFragmentById(R.id.main_container)?.view ?: return false

        val recyclerFollowing = fragmentView.findViewById<RecyclerView?>(R.id.recycler_following)
        if (recyclerFollowing != null) {
            val lastMain = lastMainFocusedView?.get()
            if (lastMain != null &&
                lastMain.isAttachedToWindow &&
                lastMain.isShown &&
                isDescendantOf(lastMain, recyclerFollowing)
            ) {
                lastMain.requestFocus()
                return true
            }

            recyclerFollowing.post {
                val vh = recyclerFollowing.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                    return@post
                }
                if (recyclerFollowing.adapter?.itemCount == 0) {
                    recyclerFollowing.requestFocus()
                    return@post
                }
                recyclerFollowing.scrollToPosition(0)
                recyclerFollowing.post {
                    recyclerFollowing.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recyclerFollowing.requestFocus()
                }
            }
            return true
        }

        val lastMain = lastMainFocusedView?.get()
        if (lastMain != null && lastMain.isAttachedToWindow && lastMain.isShown && isInMainContainer(lastMain)) {
            lastMain.requestFocus()
            return true
        }

        val recycler =
            fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic)
                ?: fragmentView.findViewById(R.id.recycler)

        if (recycler != null) {
            recycler.post {
                val vh = recycler.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                    return@post
                }
                recycler.scrollToPosition(0)
                recycler.post {
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                }
            }
            return true
        }

        val tabLayout = fragmentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout)
        if (tabLayout != null) {
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup
            val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
            tabStrip?.getChildAt(pos)?.requestFocus()
            return true
        }

        fragmentView.requestFocus()
        return true
    }

    private fun tryMoveDynamicVideoToFollowing(focused: View): Boolean {
        val fragmentView = supportFragmentManager.findFragmentById(R.id.main_container)?.view ?: return false
        val recyclerFollowing = fragmentView.findViewById<RecyclerView?>(R.id.recycler_following) ?: return false
        val recyclerDynamic = fragmentView.findViewById<RecyclerView?>(R.id.recycler_dynamic) ?: return false
        if (!isDescendantOf(focused, recyclerDynamic)) return false
        if (!isStaggeredGridLeftEdge(focused, recyclerDynamic)) return false

        recyclerFollowing.post {
            val selectedChild = (0 until recyclerFollowing.childCount)
                .map { recyclerFollowing.getChildAt(it) }
                .firstOrNull { it?.isSelected == true }
            if (selectedChild != null) {
                selectedChild.requestFocus()
                return@post
            }

            val vh = recyclerFollowing.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@post
            }
            if (recyclerFollowing.adapter?.itemCount == 0) {
                recyclerFollowing.requestFocus()
                return@post
            }
            recyclerFollowing.scrollToPosition(0)
            recyclerFollowing.post {
                recyclerFollowing.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recyclerFollowing.requestFocus()
            }
        }
        return true
    }

    private fun isStaggeredGridLeftEdge(view: View, recyclerView: RecyclerView): Boolean {
        recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return false
        val itemView = recyclerView.findContainingItemView(view) ?: return false
        val lp = itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams ?: return false
        return lp.spanIndex == 0
    }

    private fun moveSidebarFocus(up: Boolean): Boolean {
        val focused = currentFocus ?: return false

        val movingToNav = !up && (focused == binding.ivSidebarUser || focused == binding.btnSidebarLogin)
        if (movingToNav) return focusSidebarFirstNav()

        if (focused == binding.btnSidebarSettings) {
            if (!up) return true
            return focusSidebarNavAt(navAdapter.itemCount - 1)
        }

        val inNav = isDescendantOf(focused, binding.recyclerSidebar)
        if (!inNav) {
            if (up) return true
            binding.btnSidebarSettings.requestFocus()
            return true
        }

        val holder = binding.recyclerSidebar.findContainingViewHolder(focused) ?: return false
        val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return false

        return if (up) {
            if (pos > 0) {
                focusSidebarNavAt(pos - 1)
            } else {
                if (binding.ivSidebarUser.visibility == View.VISIBLE) {
                    binding.ivSidebarUser.requestFocus()
                } else if (binding.btnSidebarLogin.visibility == View.VISIBLE) {
                    binding.btnSidebarLogin.requestFocus()
                }
                true
            }
        } else {
            if (pos < navAdapter.itemCount - 1) {
                focusSidebarNavAt(pos + 1)
            } else {
                binding.btnSidebarSettings.requestFocus()
                true
            }
        }
    }

    private fun canEnterSidebarFrom(view: View): Boolean {
        val rv = view.findAncestorRecyclerView()
        if (rv != null) {
            val lm = rv.layoutManager
            if (lm is StaggeredGridLayoutManager) {
                val child = rv.findContainingItemView(view) ?: view
                val lp = child.layoutParams as? StaggeredGridLayoutManager.LayoutParams
                if (lp != null) return lp.spanIndex == 0
            }
        }

        val focusLoc = IntArray(2)
        val containerLoc = IntArray(2)
        view.getLocationOnScreen(focusLoc)
        binding.mainContainer.getLocationOnScreen(containerLoc)
        return (focusLoc[0] - containerLoc[0]) <= dp(24f)
    }

    private fun View.findAncestorRecyclerView(): RecyclerView? {
        var current: View? = this
        while (current != null) {
            if (current is RecyclerView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun dp(valueDp: Float): Int {
        val dm = resources.displayMetrics
        return (valueDp * dm.density).toInt()
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }
}
