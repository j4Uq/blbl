package blbl.cat3399.feature.my

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MyPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = 6

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MyHistoryFragment()
            1 -> MyFavFoldersFragment()
            2 -> MyBangumiFollowFragment.newInstance(type = 1)
            3 -> MyBangumiFollowFragment.newInstance(type = 2)
            4 -> MyToViewFragment()
            else -> MyLikeFragment()
        }
    }
}
