package blbl.cat3399.feature.category

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Zone
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.databinding.FragmentCategoryBinding
import blbl.cat3399.feature.video.VideoGridFragment

class CategoryFragment : Fragment() {
    private var _binding: FragmentCategoryBinding? = null
    private val binding get() = _binding!!
    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null

    private val zones: List<Zone> = listOf(
        Zone("全站", null),
        Zone("动画", 1),
        Zone("音乐", 3),
        Zone("舞蹈", 129),
        Zone("游戏", 4),
        Zone("知识", 36),
        Zone("科技", 188),
        Zone("运动", 234),
        Zone("汽车", 223),
        Zone("生活", 160),
        Zone("美食", 211),
        Zone("动物圈", 217),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.viewPager.adapter = CategoryPagerAdapter(this, zones)
        AppLog.d(
            "Category",
            "pager init count=${zones.size} offscreen=${binding.viewPager.offscreenPageLimit} t=${SystemClock.uptimeMillis()}",
        )
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = zones[position].title
        }.attach()
        binding.tabLayout.post {
            binding.tabLayout.enableDpadTabFocus { position ->
                val zone = zones.getOrNull(position)
                AppLog.d(
                    "Category",
                    "tab focus pos=$position title=${zone?.title} tid=${zone?.tid} t=${SystemClock.uptimeMillis()}",
                )
            }
            val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return@post
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusCurrentPageFirstCardFromTab()
                        return@setOnKeyListener true
                    }
                    false
                }
            }
        }
        pageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val zone = zones.getOrNull(position)
                    AppLog.d(
                        "Category",
                        "page selected pos=$position title=${zone?.title} tid=${zone?.tid} t=${SystemClock.uptimeMillis()}",
                    )
                }
            }
        binding.viewPager.registerOnPageChangeCallback(pageCallback!!)
    }

    private fun focusCurrentPageFirstCardFromTab(): Boolean {
        val pagerAdapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = pagerAdapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val pageFragment =
            when {
                byTag is VideoGridFragment -> byTag
                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is VideoGridFragment } as? VideoGridFragment
            } ?: return false
        return pageFragment.requestFocusFirstCardFromTab()
    }

    override fun onDestroyView() {
        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = CategoryFragment()
    }
}
