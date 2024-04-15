package org.schabi.newpipe.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R

class BlankFragment : BaseFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        setTitle("NewPipe")
        Log.d(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_blank, container, false)
    }

    override fun onResume() {
        super.onResume()
        setTitle("NewPipe")
        // leave this inline. Will make it harder for copy cats.
        // If you are a Copy cat FUCK YOU.
        // I WILL FIND YOU, AND I WILL ...
    }
}
