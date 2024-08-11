package org.schabi.newpipe.settings

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.ui.local.subscription.SubscriptionManager
import org.schabi.newpipe.settings.SelectChannelFragment.SelectChannelAdapter.SelectChannelItemHolder
import org.schabi.newpipe.util.ThemeHelper.getMinWidthDialogTheme
import org.schabi.newpipe.util.image.PicassoHelper.loadAvatar
import java.util.*

/**
 * Created by Christian Schabesberger on 26.09.17.
 * SelectChannelFragment.java is part of NewPipe.
 *
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http:></http:>//www.gnu.org/licenses/>.
 *
 */
class SelectChannelFragment : DialogFragment() {
    private var onSelectedListener: OnSelectedListener? = null
    private var onCancelListener: OnCancelListener? = null

    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var recyclerView: RecyclerView? = null

    private var subscriptions: List<SubscriptionEntity> = Vector()

    fun setOnSelectedListener(listener: OnSelectedListener?) {
        onSelectedListener = listener
    }

    fun setOnCancelListener(listener: OnCancelListener?) {
        onCancelListener = listener
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, getMinWidthDialogTheme(requireContext()))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.select_channel_fragment, container, false)
        recyclerView = v.findViewById(R.id.items_list)
        recyclerView!!.setLayoutManager(LinearLayoutManager(context))
        val channelAdapter = SelectChannelAdapter()
        recyclerView!!.setAdapter(channelAdapter)

        progressBar = v.findViewById(R.id.progressBar)
        emptyView = v.findViewById(R.id.empty_state_view)
        progressBar!!.setVisibility(View.VISIBLE)
        recyclerView!!.setVisibility(View.GONE)
        emptyView!!.setVisibility(View.GONE)


        val subscriptionManager = SubscriptionManager(requireContext())
        subscriptionManager.subscriptions().toObservable()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(subscriptionObserver)

        return v
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Handle actions
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCancel(dialogInterface: DialogInterface) {
        super.onCancel(dialogInterface)
        if (onCancelListener != null) {
            onCancelListener!!.onCancel()
        }
    }

    private fun clickedItem(position: Int) {
        if (onSelectedListener != null) {
            val entry = subscriptions[position]
            onSelectedListener!!
                .onChannelSelected(entry.serviceId, entry.url, entry.name)
        }
        dismiss()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Item handling
    ////////////////////////////////////////////////////////////////////////// */
    private fun displayChannels(newSubscriptions: List<SubscriptionEntity>) {
        this.subscriptions = newSubscriptions
        progressBar!!.visibility = View.GONE
        if (newSubscriptions.isEmpty()) {
            emptyView!!.visibility = View.VISIBLE
            return
        }
        recyclerView!!.visibility = View.VISIBLE
    }

    private val subscriptionObserver: Observer<List<SubscriptionEntity>>
        get() = object : Observer<List<SubscriptionEntity>> {
            override fun onSubscribe(disposable: Disposable) {}

            override fun onNext(newSubscriptions: List<SubscriptionEntity>) {
                displayChannels(newSubscriptions)
            }

            override fun onError(exception: Throwable) {
                showUiErrorSnackbar(this@SelectChannelFragment,
                    "Loading subscription", exception)
            }

            override fun onComplete() {}
        }

    /*//////////////////////////////////////////////////////////////////////////
    // Interfaces
    ////////////////////////////////////////////////////////////////////////// */
    fun interface OnSelectedListener {
        fun onChannelSelected(serviceId: Int, url: String?, name: String?)
    }

    interface OnCancelListener {
        fun onCancel()
    }

    private inner class SelectChannelAdapter

        : RecyclerView.Adapter<SelectChannelItemHolder?>() {
        override fun onCreateViewHolder(parent: ViewGroup,
                                        viewType: Int
        ): SelectChannelItemHolder {
            val item = LayoutInflater.from(parent.context)
                .inflate(R.layout.select_channel_item, parent, false)
            return SelectChannelItemHolder(item)
        }

        override fun onBindViewHolder(holder: SelectChannelItemHolder, position: Int) {
            val entry = subscriptions[position]
            holder.titleView.text = entry.name
            holder.view.setOnClickListener { view: View? -> clickedItem(position) }
            loadAvatar(entry.avatarUrl).into(holder.thumbnailView)
        }

        override fun getItemCount(): Int {
            return subscriptions.size
        }

        inner class SelectChannelItemHolder internal constructor(val view: View) : RecyclerView.ViewHolder(view) {
            val thumbnailView: ImageView = view.findViewById<ImageView>(R.id.itemThumbnailView)
            val titleView: TextView = view.findViewById<TextView>(R.id.itemTitleView)
        }
    }
}
