package org.schabi.newpipe

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import icepick.Icepick
import icepick.State

abstract class BaseFragment : Fragment() {
    @JvmField
    protected val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())
    @JvmField
    protected var activity: AppCompatActivity? = null

    //These values are used for controlling fragments when they are part of the frontpage
    @JvmField
    @State
    var useAsFrontPage: Boolean = false

    fun useAsFrontPage(value: Boolean) {
        useAsFrontPage = value
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's Lifecycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity = context as AppCompatActivity
    }

    override fun onDetach() {
        super.onDetach()
        activity = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (DEBUG) {
            Log.d(TAG, "onCreate() called with: savedInstanceState = [$savedInstanceState]")
        }
        super.onCreate(savedInstanceState)
        Icepick.restoreInstanceState(this, savedInstanceState)
        if (savedInstanceState != null) {
            onRestoreInstanceState(savedInstanceState)
        }
    }


    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootView, savedInstanceState)
        if (DEBUG) {
            Log.d(TAG, "onViewCreated() called with: rootView = [$rootView], savedInstanceState = [$savedInstanceState]")
        }
        initViews(rootView, savedInstanceState)
        initListeners()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    protected open fun onRestoreInstanceState(savedInstanceState: Bundle) {
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
    /**
     * This method is called in [.onViewCreated] to initialize the views.
     *
     *
     *
     * [.initListeners] is called after this method to initialize the corresponding
     * listeners.
     *
     * @param rootView The inflated view for this fragment
     * (provided by [.onViewCreated])
     * @param savedInstanceState The saved state of this fragment
     * (provided by [.onViewCreated])
     */
    protected open fun initViews(rootView: View, savedInstanceState: Bundle?) {}

    /**
     * Initialize the listeners for this fragment.
     *
     *
     *
     * This method is called after [.initViews]
     * in [.onViewCreated].
     *
     */
    protected open fun initListeners() {
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    @SuppressLint("UseRequireInsteadOfGet")
    open fun setTitle(title: String) {
        if (DEBUG) {
            Log.d(TAG, "setTitle() called with: title = [$title]")
        }
        if (!useAsFrontPage && activity?.supportActionBar != null) {
            activity!!.supportActionBar!!.setDisplayShowTitleEnabled(true)
            activity!!.supportActionBar!!.title = title
        }
    }

    protected val fM: FragmentManager?
        /**
         * Finds the root fragment by looping through all of the parent fragments. The root fragment
         * is supposed to be [org.schabi.newpipe.fragments.MainFragment], and is the fragment that
         * handles keeping the backstack of opened fragments in NewPipe, and also the player bottom
         * sheet. This function therefore returns the fragment manager of said fragment.
         *
         * @return the fragment manager of the root fragment, i.e.
         * [org.schabi.newpipe.fragments.MainFragment]
         */
        get() {
            var current: Fragment? = this
            while (current!!.parentFragment != null) {
                current = current.parentFragment
            }
            return current.fragmentManager
        }

    companion object {
        @JvmField
        val DEBUG: Boolean = MainActivity.DEBUG
    }
}
