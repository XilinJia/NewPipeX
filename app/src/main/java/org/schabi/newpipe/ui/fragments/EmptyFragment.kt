package org.schabi.newpipe.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.schabi.newpipe.R
import org.schabi.newpipe.util.Logd

class EmptyFragment : BaseFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val showMessage = requireArguments().getBoolean(SHOW_MESSAGE)
        Logd(TAG, "onCreateView")
        val view = inflater.inflate(R.layout.fragment_empty, container, false)
        view.findViewById<View>(R.id.empty_state_view).visibility = if (showMessage) View.VISIBLE else View.GONE
        return view
    }

    companion object {
        private const val SHOW_MESSAGE = "SHOW_MESSAGE"

        @JvmStatic
        fun newInstance(showMessage: Boolean): EmptyFragment {
            val emptyFragment = EmptyFragment()
            val bundle = Bundle(1)
            bundle.putBoolean(SHOW_MESSAGE, showMessage)
            emptyFragment.arguments = bundle
            return emptyFragment
        }
    }
}
