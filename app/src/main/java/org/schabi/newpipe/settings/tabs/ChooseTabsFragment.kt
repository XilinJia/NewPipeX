package org.schabi.newpipe.settings.tabs

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.*
import android.view.View.OnTouchListener
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.settings.SelectChannelFragment
import org.schabi.newpipe.settings.SelectKioskFragment
import org.schabi.newpipe.settings.SelectPlaylistFragment
import org.schabi.newpipe.settings.tabs.AddTabDialog.ChooseTabListItem
import org.schabi.newpipe.settings.tabs.Tab.*
import org.schabi.newpipe.util.ServiceHelper.getNameOfServiceById
import org.schabi.newpipe.util.ThemeHelper.setTitleToAppCompatActivity
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class ChooseTabsFragment : Fragment() {
    private var tabsManager: TabsManager? = null

    private val tabList: MutableList<Tab> = ArrayList()
    private var selectedTabsAdapter: SelectedTabsAdapter? = null

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tabsManager = TabsManager.getManager(requireContext())
        updateTabList()

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_choose_tabs, container, false)
    }

    override fun onViewCreated(rootView: View,
                               savedInstanceState: Bundle?
    ) {
        super.onViewCreated(rootView, savedInstanceState)

        initButton(rootView)

        val listSelectedTabs = rootView.findViewById<RecyclerView>(R.id.selectedTabs)
        listSelectedTabs.layoutManager = LinearLayoutManager(requireContext())

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(listSelectedTabs)

        selectedTabsAdapter = SelectedTabsAdapter(requireContext(), itemTouchHelper)
        listSelectedTabs.adapter = selectedTabsAdapter
    }

    override fun onResume() {
        super.onResume()
        setTitleToAppCompatActivity(activity,
            getString(R.string.main_page_content))
    }

    override fun onPause() {
        super.onPause()
        saveChanges()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateOptionsMenu(menu: Menu,
                                     inflater: MenuInflater
    ) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_chooser_fragment, menu)
        menu.findItem(R.id.menu_item_restore_default).setOnMenuItemClickListener { item: MenuItem? ->
            restoreDefaults()
            true
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun updateTabList() {
        tabList.clear()
        tabList.addAll(tabsManager!!.tabs)
    }

    private fun saveChanges() {
        tabsManager!!.saveTabs(tabList)
    }

    private fun restoreDefaults() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.restore_defaults)
            .setMessage(R.string.restore_defaults_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { dialog: DialogInterface?, which: Int ->
                tabsManager!!.resetTabs()
                updateTabList()
                selectedTabsAdapter!!.notifyDataSetChanged()
            }
            .show()
    }

    private fun initButton(rootView: View) {
        val fab = rootView.findViewById<FloatingActionButton>(R.id.addTabsButton)
        fab.setOnClickListener { v: View? ->
            val availableTabs = getAvailableTabs(requireContext())
            if (availableTabs.size == 0) {
                //Toast.makeText(requireContext(), "No available tabs", Toast.LENGTH_SHORT).show();
                return@setOnClickListener
            }

            val actionListener = DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                val selected = availableTabs[which]
                addTab(selected.tabId)
            }
            AddTabDialog(requireContext(), availableTabs, actionListener)
                .show()
        }
    }

    private fun addTab(tab: Tab) {
        tabList.add(tab)
        selectedTabsAdapter!!.notifyDataSetChanged()
    }

    private fun addTab(tabId: Int) {
        val type = Tab.typeFrom(tabId)

        if (type == null) {
            showSnackbar(this,
                ErrorInfo(IllegalStateException("Tab id not found: $tabId"),
                    UserAction.SOMETHING_ELSE, "Choosing tabs on settings"))
            return
        }

        when (type) {
            Tab.Type.KIOSK -> {
                val selectKioskFragment = SelectKioskFragment()
                selectKioskFragment.setOnSelectedListener(SelectKioskFragment.OnSelectedListener { serviceId: Int, kioskId: String?, kioskName: String? ->
                    addTab(KioskTab(serviceId, kioskId))
                })
                selectKioskFragment.show(parentFragmentManager, "select_kiosk")
                return
            }
            Tab.Type.CHANNEL -> {
                val selectChannelFragment = SelectChannelFragment()
                selectChannelFragment.setOnSelectedListener(SelectChannelFragment.OnSelectedListener { serviceId: Int, url: String?, name: String? ->
                    addTab(ChannelTab(serviceId, url, name))
                })
                selectChannelFragment.show(parentFragmentManager, "select_channel")
                return
            }
            Tab.Type.PLAYLIST -> {
                val selectPlaylistFragment = SelectPlaylistFragment()
                selectPlaylistFragment.setOnSelectedListener(
                    object : SelectPlaylistFragment.OnSelectedListener {
                        override fun onLocalPlaylistSelected(id: Long, name: String?) {
                            addTab(PlaylistTab(id, name))
                        }

                        override fun onRemotePlaylistSelected(
                                serviceId: Int, url: String?, name: String?
                        ) {
                            addTab(PlaylistTab(serviceId, url, name))
                        }
                    })
                selectPlaylistFragment.show(parentFragmentManager, "select_playlist")
                return
            }
            else -> addTab(type.tab)
        }
    }

    private fun getAvailableTabs(context: Context): Array<ChooseTabListItem> {
        val returnList = ArrayList<ChooseTabListItem>()

        for (type in Tab.Type.entries.toTypedArray()) {
            val tab = type.tab
            when (type) {
                Tab.Type.BLANK -> if (!tabList.contains(tab)) {
                    returnList.add(ChooseTabListItem(tab.tabId,
                        getString(R.string.blank_page_summary),
                        tab.getTabIconRes(context)))
                }
                Tab.Type.KIOSK -> returnList.add(ChooseTabListItem(tab.tabId,
                    getString(R.string.kiosk_page_summary),
                    R.drawable.ic_whatshot))
                Tab.Type.CHANNEL -> returnList.add(ChooseTabListItem(tab.tabId,
                    getString(R.string.channel_page_summary),
                    tab.getTabIconRes(context)))
                Tab.Type.DEFAULT_KIOSK -> if (!tabList.contains(tab)) {
                    returnList.add(ChooseTabListItem(tab.tabId,
                        getString(R.string.default_kiosk_page_summary),
                        R.drawable.ic_whatshot))
                }
                Tab.Type.PLAYLIST -> returnList.add(ChooseTabListItem(tab.tabId,
                    getString(R.string.playlist_page_summary),
                    tab.getTabIconRes(context)))
                else -> if (!tabList.contains(tab)) {
                    returnList.add(ChooseTabListItem(context, tab))
                }
            }
        }

        return returnList.toTypedArray<ChooseTabListItem>()
    }

    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
        /*//////////////////////////////////////////////////////////////////////////
    // List Handling
    ////////////////////////////////////////////////////////////////////////// */
        get() = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView,
                                                      viewSize: Int,
                                                      viewSizeOutOfBounds: Int,
                                                      totalSize: Int,
                                                      msSinceStartScroll: Long
            ): Int {
                val standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView, viewSize,
                    viewSizeOutOfBounds, totalSize, msSinceStartScroll)
                val minimumAbsVelocity = max(12.0,
                    abs(standardSpeed.toDouble())).toInt()
                return minimumAbsVelocity * sign(viewSizeOutOfBounds.toDouble()).toInt()
            }

            override fun onMove(recyclerView: RecyclerView,
                                source: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder
            ): Boolean {
                if (source.itemViewType != target.itemViewType
                        || selectedTabsAdapter == null) {
                    return false
                }

                val sourceIndex = source.bindingAdapterPosition
                val targetIndex = target.bindingAdapterPosition
                selectedTabsAdapter!!.swapItems(sourceIndex, targetIndex)
                return true
            }

            override fun isLongPressDragEnabled(): Boolean {
                return false
            }

            override fun isItemViewSwipeEnabled(): Boolean {
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder,
                                  swipeDir: Int
            ) {
                val position = viewHolder.bindingAdapterPosition
                tabList.removeAt(position)
                selectedTabsAdapter!!.notifyItemRemoved(position)

                if (tabList.isEmpty()) {
                    tabList.add(Tab.Type.BLANK.tab)
                    selectedTabsAdapter!!.notifyItemInserted(0)
                }
            }
        }

    private inner class SelectedTabsAdapter(context: Context?, private val itemTouchHelper: ItemTouchHelper?) :
        RecyclerView.Adapter<SelectedTabsAdapter.TabViewHolder>() {
        private val inflater: LayoutInflater = LayoutInflater.from(context)

        fun swapItems(fromPosition: Int, toPosition: Int) {
            Collections.swap(tabList, fromPosition, toPosition)
            notifyItemMoved(fromPosition, toPosition)
        }

        override fun onCreateViewHolder(
                parent: ViewGroup, viewType: Int
        ): TabViewHolder {
            val view = inflater.inflate(R.layout.list_choose_tabs, parent, false)
            return TabViewHolder(view)
        }

        override fun onBindViewHolder(
                holder: TabViewHolder,
                position: Int
        ) {
            holder.bind(position, holder)
        }

        override fun getItemCount(): Int {
            return tabList.size
        }

        inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tabIconView: AppCompatImageView = itemView.findViewById(R.id.tabIcon)
            private val tabNameView: TextView = itemView.findViewById(R.id.tabName)
            private val handle: ImageView = itemView.findViewById(R.id.handle)

            @SuppressLint("ClickableViewAccessibility")
            fun bind(position: Int, holder: TabViewHolder) {
                handle.setOnTouchListener(getOnTouchListener(holder))

                val tab = tabList[position]!!
                val type = Tab.typeFrom(tab.tabId) ?: return

                tabNameView.text = getTabName(type, tab)
                tabIconView.setImageResource(tab.getTabIconRes(requireContext()))
            }

            private fun getTabName(type: Tab.Type, tab: Tab): String {
                when (type) {
                    Tab.Type.BLANK -> return getString(R.string.blank_page_summary)
                    Tab.Type.DEFAULT_KIOSK -> return getString(R.string.default_kiosk_page_summary)
                    Tab.Type.KIOSK -> return (getNameOfServiceById((tab as KioskTab).kioskServiceId)
                            + "/" + tab.getTabName(requireContext()))
                    Tab.Type.CHANNEL -> return (getNameOfServiceById((tab as ChannelTab).channelServiceId)
                            + "/" + tab.getTabName(requireContext()))
                    Tab.Type.PLAYLIST -> {
                        val serviceId = (tab as PlaylistTab).playlistServiceId
                        val serviceName = if (serviceId == -1
                        ) getString(R.string.local)
                        else getNameOfServiceById(serviceId)
                        return serviceName + "/" + tab.getTabName(requireContext())
                    }
                    else -> return tab.getTabName(requireContext())?:""
                }
            }

            @SuppressLint("ClickableViewAccessibility")
            private fun getOnTouchListener(item: RecyclerView.ViewHolder): OnTouchListener {
                return OnTouchListener { view: View?, motionEvent: MotionEvent ->
                    if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (itemTouchHelper != null && itemCount > 1) {
                            itemTouchHelper.startDrag(item)
                            return@OnTouchListener true
                        }
                    }
                    false
                }
            }
        }
    }
}
