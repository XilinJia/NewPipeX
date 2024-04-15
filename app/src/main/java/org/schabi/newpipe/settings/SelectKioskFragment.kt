package org.schabi.newpipe.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.settings.SelectKioskFragment.SelectKioskAdapter.SelectKioskItemHolder
import org.schabi.newpipe.util.KioskTranslator.getTranslatedKioskName
import org.schabi.newpipe.util.ServiceHelper.getIcon
import org.schabi.newpipe.util.ThemeHelper.getMinWidthDialogTheme
import java.util.*

/**
 * Created by Christian Schabesberger on 09.10.17.
 * SelectKioskFragment.java is part of NewPipe.
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
 * along with NewPipe. If not, see <http:></http:>//www.gnu.org/licenses/>.
 *
 */
class SelectKioskFragment : DialogFragment() {
    private var selectKioskAdapter: SelectKioskAdapter? = null

    private var onSelectedListener: OnSelectedListener? = null

    fun setOnSelectedListener(listener: OnSelectedListener?) {
        onSelectedListener = listener
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
        val v = inflater.inflate(R.layout.select_kiosk_fragment, container, false)
        val recyclerView = v.findViewById<RecyclerView>(R.id.items_list)
        recyclerView.layoutManager = LinearLayoutManager(context)
        try {
            selectKioskAdapter = SelectKioskAdapter()
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Selecting kiosk", e)
        }
        recyclerView.adapter = selectKioskAdapter

        return v
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Handle actions
    ////////////////////////////////////////////////////////////////////////// */
    private fun clickedItem(entry: SelectKioskAdapter.Entry) {
        if (onSelectedListener != null) {
            onSelectedListener!!.onKioskSelected(entry.serviceId, entry.kioskId, entry.kioskName)
        }
        dismiss()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Interfaces
    ////////////////////////////////////////////////////////////////////////// */
    fun interface OnSelectedListener {
        fun onKioskSelected(serviceId: Int, kioskId: String?, kioskName: String?)
    }

    private inner class SelectKioskAdapter : RecyclerView.Adapter<SelectKioskItemHolder>() {
        private val kioskList: MutableList<Entry> = Vector()

        init {
            for (service in NewPipe.getServices()) {
                for (kioskId in service.kioskList.availableKiosks) {
                    val name = String.format(getString(R.string.service_kiosk_string),
                        service.serviceInfo.name,
                        getTranslatedKioskName(kioskId!!, context!!))
                    kioskList.add(Entry(getIcon(service.serviceId),
                        service.serviceId, kioskId, name))
                }
            }
        }

        override fun getItemCount(): Int {
            return kioskList.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): SelectKioskItemHolder {
            val item = LayoutInflater.from(parent.context)
                .inflate(R.layout.select_kiosk_item, parent, false)
            return SelectKioskItemHolder(item)
        }

        override fun onBindViewHolder(holder: SelectKioskItemHolder, position: Int) {
            val entry = kioskList[position]
            holder.titleView.text = entry.kioskName
            holder.thumbnailView
                .setImageDrawable(AppCompatResources.getDrawable(requireContext(), entry.icon))
            holder.view.setOnClickListener { view: View? -> clickedItem(entry) }
        }

        inner class Entry(val icon: Int, val serviceId: Int, val kioskId: String, val kioskName: String)

        inner class SelectKioskItemHolder internal constructor(val view: View) : RecyclerView.ViewHolder(view) {
            val thumbnailView: ImageView = view.findViewById<ImageView>(R.id.itemThumbnailView)
            val titleView: TextView = view.findViewById<TextView>(R.id.itemTitleView)
        }
    }
}
