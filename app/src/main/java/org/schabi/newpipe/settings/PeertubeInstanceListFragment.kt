package org.schabi.newpipe.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.*
import com.grack.nanojson.JsonWriter
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogEditTextBinding
import org.schabi.newpipe.databinding.FragmentInstanceListBinding
import org.schabi.newpipe.databinding.ItemInstanceBinding
import org.schabi.newpipe.extractor.services.peertube.PeertubeInstance
import org.schabi.newpipe.util.KEY_MAIN_PAGE_CHANGE
import org.schabi.newpipe.util.PeertubeHelper.currentInstance
import org.schabi.newpipe.util.PeertubeHelper.getInstanceList
import org.schabi.newpipe.util.PeertubeHelper.selectInstance
import org.schabi.newpipe.util.ThemeHelper.setTitleToAppCompatActivity
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class PeertubeInstanceListFragment : Fragment() {
    private var selectedInstance: PeertubeInstance? = null
    private var savedInstanceListKey: String? = null
    private var instanceListAdapter: InstanceListAdapter? = null

    private var binding: FragmentInstanceListBinding? = null
    private var sharedPreferences: SharedPreferences? = null

    private var disposables: CompositeDisposable? = CompositeDisposable()

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        savedInstanceListKey = getString(R.string.peertube_instance_list_key)
        selectedInstance = currentInstance

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        binding = FragmentInstanceListBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(rootView: View,
                               savedInstanceState: Bundle?
    ) {
        super.onViewCreated(rootView, savedInstanceState)

        binding!!.instanceHelpTV.text = getString(R.string.peertube_instance_url_help,
            getString(R.string.peertube_instance_list_url))
        binding!!.addInstanceButton.setOnClickListener { v: View? -> showAddItemDialog(requireContext()) }
        binding!!.instances.layoutManager = LinearLayoutManager(requireContext())

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(binding!!.instances)

        instanceListAdapter = InstanceListAdapter(requireContext(), itemTouchHelper)
        binding!!.instances.adapter = instanceListAdapter
        instanceListAdapter!!.submitList(getInstanceList(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        setTitleToAppCompatActivity(activity,
            getString(R.string.peertube_instance_url_title))
    }

    override fun onPause() {
        super.onPause()
        saveChanges()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (disposables != null) {
            disposables!!.clear()
        }
        disposables = null
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateOptionsMenu(menu: Menu,
                                     inflater: MenuInflater
    ) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_chooser_fragment, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_item_restore_default) {
            restoreDefaults()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun selectInstance(instance: PeertubeInstance?) {
        selectedInstance = selectInstance(instance!!, requireContext())
        sharedPreferences!!.edit().putBoolean(KEY_MAIN_PAGE_CHANGE, true).apply()
    }

    private fun saveChanges() {
        val jsonWriter = JsonWriter.string().`object`().array("instances")
        for (instance in instanceListAdapter!!.currentList) {
            jsonWriter.`object`()
            jsonWriter.value("name", instance!!.name)
            jsonWriter.value("url", instance.url)
            jsonWriter.end()
        }
        val jsonToSave = jsonWriter.end().end().done()
        sharedPreferences!!.edit().putString(savedInstanceListKey, jsonToSave).apply()
    }

    private fun restoreDefaults() {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle(R.string.restore_defaults)
            .setMessage(R.string.restore_defaults_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { dialog: DialogInterface?, which: Int ->
                sharedPreferences!!.edit().remove(savedInstanceListKey).apply()
                selectInstance(PeertubeInstance.DEFAULT_INSTANCE)
                instanceListAdapter!!.submitList(getInstanceList(context))
            }
            .show()
    }

    private fun showAddItemDialog(c: Context) {
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater)
        dialogBinding.dialogEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        dialogBinding.dialogEditText.setHint(R.string.peertube_instance_add_help)

        AlertDialog.Builder(c)
            .setTitle(R.string.peertube_instance_add_title)
            .setIcon(R.drawable.ic_placeholder_peertube)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { dialog1: DialogInterface?, which: Int ->
                val url = dialogBinding.dialogEditText.text.toString()
                addInstance(url)
            }
            .show()
    }

    private fun addInstance(url: String) {
        val cleanUrl = cleanUrl(url) ?: return
        binding!!.loadingProgressBar.visibility = View.VISIBLE
        val disposable = Single.fromCallable {
            val instance = PeertubeInstance(cleanUrl)
            instance.fetchInstanceMetaData()
            instance
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ instance: PeertubeInstance ->
                binding!!.loadingProgressBar.visibility = View.GONE
                add(instance)
            }, { e: Throwable? ->
                binding!!.loadingProgressBar.visibility = View.GONE
                Toast.makeText(activity, R.string.peertube_instance_add_fail,
                    Toast.LENGTH_SHORT).show()
            })
        disposables!!.add(disposable)
    }

    private fun cleanUrl(url: String): String? {
        var cleanUrl = url.trim { it <= ' ' }
        // if protocol not present, add https
        if (!cleanUrl.startsWith("http")) {
            cleanUrl = "https://$cleanUrl"
        }
        // remove trailing slash
        cleanUrl = cleanUrl.replace("/$".toRegex(), "")
        // only allow https
        if (!cleanUrl.startsWith("https://")) {
            Toast.makeText(activity, R.string.peertube_instance_add_https_only,
                Toast.LENGTH_SHORT).show()
            return null
        }
        // only allow if not already exists
        for (instance in instanceListAdapter!!.currentList) {
            if (instance!!.url == cleanUrl) {
                Toast.makeText(activity, R.string.peertube_instance_add_exists,
                    Toast.LENGTH_SHORT).show()
                return null
            }
        }
        return cleanUrl
    }

    private fun add(instance: PeertubeInstance) {
        val list = ArrayList(instanceListAdapter!!.currentList)
        list.add(instance)
        instanceListAdapter!!.submitList(list)
    }

    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
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
                val minimumAbsVelocity = max(12.0, abs(standardSpeed.toDouble())).toInt()
                return minimumAbsVelocity * sign(viewSizeOutOfBounds.toDouble()).toInt()
            }

            override fun onMove(recyclerView: RecyclerView,
                                source: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder
            ): Boolean {
                if (source.itemViewType != target.itemViewType
                        || instanceListAdapter == null) {
                    return false
                }

                val sourceIndex = source.bindingAdapterPosition
                val targetIndex = target.bindingAdapterPosition
                instanceListAdapter!!.swapItems(sourceIndex, targetIndex)
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
                // do not allow swiping the selected instance
                if (instanceListAdapter!!.currentList[position]!!.url
                        == selectedInstance!!.url) {
                    instanceListAdapter!!.notifyItemChanged(position)
                    return
                }
                val list = ArrayList(
                    instanceListAdapter!!.currentList)
                list.removeAt(position)

                if (list.isEmpty()) {
                    list.add(selectedInstance)
                }

                instanceListAdapter!!.submitList(list)
            }
        }

    /*//////////////////////////////////////////////////////////////////////////
    // List Handling
    ////////////////////////////////////////////////////////////////////////// */
    private inner class InstanceListAdapter(context: Context?, private val itemTouchHelper: ItemTouchHelper?) :
        ListAdapter<PeertubeInstance, InstanceListAdapter.TabViewHolder>(PeertubeInstanceCallback()) {
        private val inflater: LayoutInflater = LayoutInflater.from(context)
        private var lastChecked: RadioButton? = null

        fun swapItems(fromPosition: Int, toPosition: Int) {
            val list = ArrayList(currentList)
            Collections.swap(list, fromPosition, toPosition)
            submitList(list)
        }

        override fun onCreateViewHolder(parent: ViewGroup,
                                        viewType: Int
        ): TabViewHolder {
            return TabViewHolder(ItemInstanceBinding.inflate(inflater,
                parent, false))
        }

        override fun onBindViewHolder(holder: TabViewHolder,
                                      position: Int
        ) {
            holder.bind(position)
        }

        inner class TabViewHolder(private val itemBinding: ItemInstanceBinding) : RecyclerView.ViewHolder(
            binding!!.root) {
            @SuppressLint("ClickableViewAccessibility")
            fun bind(position: Int) {
                itemBinding.handle.setOnTouchListener { view: View?, motionEvent: MotionEvent ->
                    if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (itemTouchHelper != null && itemCount > 1) {
                            itemTouchHelper.startDrag(this)
                            return@setOnTouchListener true
                        }
                    }
                    false
                }

                val instance = getItem(position)
                itemBinding.instanceName.text = instance!!.name
                itemBinding.instanceUrl.text = instance.url
                itemBinding.selectInstanceRB.setOnCheckedChangeListener(null)
                if (selectedInstance!!.url == instance.url) {
                    if (lastChecked != null && lastChecked !== itemBinding.selectInstanceRB) {
                        lastChecked!!.isChecked = false
                    }
                    itemBinding.selectInstanceRB.isChecked = true
                    lastChecked = itemBinding.selectInstanceRB
                }
                itemBinding.selectInstanceRB.setOnCheckedChangeListener { buttonView: CompoundButton?, isChecked: Boolean ->
                    if (isChecked) {
                        selectInstance(instance)
                        if (lastChecked != null && lastChecked !== itemBinding.selectInstanceRB) {
                            lastChecked!!.isChecked = false
                        }
                        lastChecked = itemBinding.selectInstanceRB
                    }
                }
                itemBinding.instanceIcon.setImageResource(R.drawable.ic_placeholder_peertube)
            }
        }
    }

    private class PeertubeInstanceCallback : DiffUtil.ItemCallback<PeertubeInstance>() {
        override fun areItemsTheSame(oldItem: PeertubeInstance,
                                     newItem: PeertubeInstance
        ): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: PeertubeInstance,
                                        newItem: PeertubeInstance
        ): Boolean {
            return oldItem.name == newItem.name && oldItem.url == newItem.url
        }
    }
}
