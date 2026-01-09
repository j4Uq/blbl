package blbl.cat3399.feature.category

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayoutMediator
import blbl.cat3399.core.model.Zone
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.databinding.FragmentCategoryBinding

class CategoryFragment : Fragment() {
    private var _binding: FragmentCategoryBinding? = null
    private val binding get() = _binding!!

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
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = zones[position].title
        }.attach()
        binding.tabLayout.post { binding.tabLayout.enableDpadTabFocus() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = CategoryFragment()
    }
}
