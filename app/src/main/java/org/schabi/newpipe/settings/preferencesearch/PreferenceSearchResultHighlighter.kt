package org.schabi.newpipe.settings.preferencesearch

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup.PreferencePositionCallback
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R

object PreferenceSearchResultHighlighter {
    private const val TAG = "PrefSearchResHighlter"

    /**
     * Highlight the specified preference.
     * <br></br>
     * Note: This function is Thread independent (can be called from outside of the main thread).
     *
     * @param item The item to highlight
     * @param prefsFragment The fragment where the items is located on
     */
    fun highlight(
            item: PreferenceSearchItem,
            prefsFragment: PreferenceFragmentCompat
    ) {
        Handler(Looper.getMainLooper()).post { doHighlight(item, prefsFragment) }
    }

    private fun doHighlight(
            item: PreferenceSearchItem,
            prefsFragment: PreferenceFragmentCompat
    ) {
        val prefResult = prefsFragment.findPreference<Preference>(item.key)

        if (prefResult == null) {
            Log.w(TAG, "Preference '" + item.key + "' not found on '" + prefsFragment + "'")
            return
        }

        val recyclerView = prefsFragment.listView
        val adapter = recyclerView.adapter
        if (adapter is PreferencePositionCallback) {
            val position = (adapter as PreferencePositionCallback)
                .getPreferenceAdapterPosition(prefResult)
            if (position != RecyclerView.NO_POSITION) {
                recyclerView.scrollToPosition(position)
                recyclerView.postDelayed({
                    val holder =
                        recyclerView.findViewHolderForAdapterPosition(position)
                    if (holder != null) {
                        val background = holder.itemView.background
                        if (background is RippleDrawable) {
                            showRippleAnimation(background)
                            return@postDelayed
                        }
                    }
                    highlightFallback(prefsFragment, prefResult)
                }, 200)
                return
            }
        }
        highlightFallback(prefsFragment, prefResult)
    }

    /**
     * Alternative highlighting (shows an → arrow in front of the setting)if ripple does not work.
     *
     * @param prefsFragment
     * @param prefResult
     */
    private fun highlightFallback(
            prefsFragment: PreferenceFragmentCompat,
            prefResult: Preference
    ) {
        // Get primary color from text for highlight icon
        val typedValue = TypedValue()
        val theme = prefsFragment.requireActivity().theme
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val arr = prefsFragment.requireActivity().obtainStyledAttributes(
                typedValue.data,
                intArrayOf(android.R.attr.textColorPrimary))
        val color = arr.getColor(0, -0x1ac6cb)
        arr.recycle()

        // Show highlight icon
        val oldIcon = prefResult.icon
        val oldSpaceReserved = prefResult.isIconSpaceReserved
        val highlightIcon =
            AppCompatResources.getDrawable(
                prefsFragment.requireContext(),
                R.drawable.ic_play_arrow)
        highlightIcon!!.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        prefResult.icon = highlightIcon

        prefsFragment.scrollToPreference(prefResult)

        Handler(Looper.getMainLooper()).postDelayed({
            prefResult.icon = oldIcon
            prefResult.isIconSpaceReserved = oldSpaceReserved
        }, 1000)
    }

    private fun showRippleAnimation(rippleDrawable: RippleDrawable) {
        rippleDrawable.setState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled))
        Handler(Looper.getMainLooper())
            .postDelayed({ rippleDrawable.setState(intArrayOf()) }, 1000)
    }
}
