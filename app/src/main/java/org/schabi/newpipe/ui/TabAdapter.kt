package org.schabi.newpipe.ui

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter

// if changed to BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT => crash if enqueueing stream in
// the background and then clicking on it to open VideoDetailFragment:
// "Cannot setMaxLifecycle for Fragment not attached to FragmentManager"
class TabAdapter(private val fragmentManager: FragmentManager)
    : FragmentPagerAdapter(fragmentManager, BEHAVIOR_SET_USER_VISIBLE_HINT) {

    private val mFragmentList: MutableList<Fragment> = ArrayList()
    private val mFragmentTitleList: MutableList<String> = ArrayList()

    override fun getItem(position: Int): Fragment {
        return mFragmentList[position]
    }

    override fun getCount(): Int {
        return mFragmentList.size
    }

    fun addFragment(fragment: Fragment, title: String) {
        mFragmentList.add(fragment)
        mFragmentTitleList.add(title)
    }

    fun clearAllItems() {
        mFragmentList.clear()
        mFragmentTitleList.clear()
    }

    fun removeItem(position: Int) {
        mFragmentList.removeAt(if (position == 0) 0 else position - 1)
        mFragmentTitleList.removeAt(if (position == 0) 0 else position - 1)
    }

    fun updateItem(position: Int, fragment: Fragment) {
        mFragmentList[position] = fragment
    }

    fun updateItem(title: String, fragment: Fragment) {
        val index = mFragmentTitleList.indexOf(title)
        if (index != -1) {
            updateItem(index, fragment)
        }
    }

    override fun getItemPosition(`object`: Any): Int {
        return if (mFragmentList.contains(`object`)) mFragmentList.indexOf(`object`) else POSITION_NONE
    }

    fun getItemPositionByTitle(title: String): Int {
        return mFragmentTitleList.indexOf(title)
    }

    fun getItemTitle(position: Int): String? {
        if (position < 0 || position >= mFragmentTitleList.size) return null
        return mFragmentTitleList[position]
    }

    fun notifyDataSetUpdate() {
        notifyDataSetChanged()
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        fragmentManager.beginTransaction().remove((`object` as Fragment)).commitNowAllowingStateLoss()
    }
}
