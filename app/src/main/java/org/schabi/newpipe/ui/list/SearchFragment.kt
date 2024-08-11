package org.schabi.newpipe.ui.list

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.util.Log
import android.view.*
import android.view.View.OnFocusChangeListener
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.collection.SparseArrayCompat
import androidx.core.text.HtmlCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentSearchBinding
import org.schabi.newpipe.databinding.ItemSearchSuggestionBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.error.ReCaptchaActivity
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.peertube.linkHandler.PeertubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.ui.BackPressable
import org.schabi.newpipe.ui.ktx.AnimationType
import org.schabi.newpipe.ui.ktx.animate
import org.schabi.newpipe.util.isInterruptedCaused
import org.schabi.newpipe.ui.local.history.HistoryRecordManager
import org.schabi.newpipe.settings.NewPipeSettings.showLocalSearchSuggestions
import org.schabi.newpipe.settings.NewPipeSettings.showRemoteSearchSuggestions
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.ExtractorHelper.getMoreSearchItems
import org.schabi.newpipe.util.ExtractorHelper.searchFor
import org.schabi.newpipe.util.ExtractorHelper.showMetaInfoInTextView
import org.schabi.newpipe.util.ExtractorHelper.suggestionsFor
import org.schabi.newpipe.util.KeyboardUtil.hideKeyboard
import org.schabi.newpipe.util.KeyboardUtil.showKeyboard
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.NavigationHelper.getIntentByLink
import org.schabi.newpipe.util.NavigationHelper.gotoMainFragment
import org.schabi.newpipe.util.ServiceHelper.getTranslatedFilterString
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

class SearchFragment : BaseListFragment<SearchInfo, InfoItemsPage<InfoItem>>(), BackPressable {

    private val suggestionPublisher = PublishSubject.create<String>()

    @JvmField
    @State
    var filterItemCheckedId: Int = -1

    @JvmField
    @State
    var serviceId: Int = NO_SERVICE_ID

    // these three represents the current search query
    @JvmField
    @State
    var searchString: String? = null

    /**
     * No content filter should add like contentFilter = all
     * be aware of this when implementing an extractor.
     */
    @JvmField
    @State
    var contentFilter: Array<String?> = arrayOfNulls(0)

    @JvmField
    @State
    var sortFilter: String? = null

    // these represents the last search
    @JvmField
    @State
    var lastSearchedString: String? = null

    @JvmField
    @State
    var searchSuggestion: String? = null

    @JvmField
    @State
    var isCorrectedSearch: Boolean = false

    @JvmField
    @State
    var metaInfo: Array<MetaInfo>? = null

    @JvmField
    @State
    var wasSearchFocused: Boolean = false

    private val menuItemToFilterName = SparseArrayCompat<String>()
    private var service: StreamingService? = null
    private var nextPage: Page? = null
    private var showLocalSuggestions = true
    private var showRemoteSuggestions = true

    private var searchDisposable: Disposable? = null
    private var suggestionDisposable: Disposable? = null
    private val disposables = CompositeDisposable()

    private var suggestionListAdapter: SuggestionListAdapter? = null
    private var historyRecordManager: HistoryRecordManager? = null

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
    private var searchBinding: FragmentSearchBinding? = null

    private var searchToolbarContainer: View? = null
    private var searchEditText: EditText? = null
    private var searchClear: View? = null

    private var suggestionsPanelVisible = false

    /*//////////////////////////////////////////////////////////////////////// */
    /**
     * TextWatcher to remove rich-text formatting on the search EditText when pasting content
     * from the clipboard.
     */
    private var textWatcher: TextWatcher? = null

    /**
     * Set wasLoading to true so when the fragment onResume is called, the initial search is done.
     */
    private fun setSearchOnResume() {
        wasLoading.set(true)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onAttach(context: Context) {
        super.onAttach(context)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        showLocalSuggestions = showLocalSearchSuggestions(requireActivity(), prefs)
        showRemoteSuggestions = showRemoteSearchSuggestions(requireActivity(), prefs)

        suggestionListAdapter = SuggestionListAdapter()
        historyRecordManager = HistoryRecordManager(context)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        searchBinding = FragmentSearchBinding.bind(rootView)
        super.onViewCreated(rootView, savedInstanceState)
        showSearchOnStart()
        initSearchListeners()
    }

    private fun updateService() {
        try {
            service = NewPipe.getService(serviceId)
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Getting service for id $serviceId", e)
        }
    }

    override fun onStart() {

        Logd(TAG, "onStart() called")

        super.onStart()

        updateService()
    }

    override fun onPause() {
        super.onPause()

        wasSearchFocused = searchEditText!!.hasFocus()

        searchDisposable?.dispose()
        suggestionDisposable?.dispose()

        disposables.clear()
        hideKeyboardSearch()
    }

    override fun onResume() {

        Logd(TAG, "onResume() called")

        super.onResume()

        if (suggestionDisposable == null || suggestionDisposable!!.isDisposed) {
            initSuggestionObserver()
        }

        if (!TextUtils.isEmpty(searchString)) {
            if (wasLoading.getAndSet(false)) {
                search(searchString, contentFilter, sortFilter)
                return
            } else if (infoListAdapter!!.itemsList.isEmpty()) {
                if (savedState == null) {
                    search(searchString, contentFilter, sortFilter)
                    return
                } else if (!isLoading.get() && !wasSearchFocused && lastPanelError == null) {
                    infoListAdapter!!.clearStreamItemList()
                    showEmptyState()
                }
            }
        }

        handleSearchSuggestion()

        showMetaInfoInTextView(if (metaInfo == null) null else Arrays.asList(*metaInfo!!),
            searchBinding!!.searchMetaInfoTextView, searchBinding!!.searchMetaInfoSeparator, disposables)

        if (TextUtils.isEmpty(searchString) || wasSearchFocused) {
            showKeyboardSearch()
            showSuggestionsPanel()
        } else {
            hideKeyboardSearch()
            hideSuggestionsPanel()
        }
        wasSearchFocused = false
    }

    override fun onDestroyView() {

        Logd(TAG, "onDestroyView() called")

        unsetSearchListeners()

        searchBinding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        searchDisposable?.dispose()
        suggestionDisposable?.dispose()
        disposables.clear()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == ReCaptchaActivity.RECAPTCHA_REQUEST) {
            if (resultCode == Activity.RESULT_OK && !TextUtils.isEmpty(searchString)) {
                search(searchString, contentFilter, sortFilter)
            } else {
                Log.e(TAG, "ReCaptcha failed")
            }
        } else {
            Log.e(TAG, "Request code from activity not supported [$requestCode]")
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        searchBinding!!.suggestionsList.adapter = suggestionListAdapter
        // animations are just strange and useless, since the suggestions keep changing too much
        searchBinding!!.suggestionsList.itemAnimator = null
        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView,
                                          viewHolder: RecyclerView.ViewHolder
            ): Int {
                return getSuggestionMovementFlags(viewHolder)
            }

            override fun onMove(recyclerView: RecyclerView,
                                viewHolder: RecyclerView.ViewHolder,
                                viewHolder1: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, i: Int) {
                onSuggestionItemSwiped(viewHolder)
            }
        }).attachToRecyclerView(searchBinding!!.suggestionsList)

        searchToolbarContainer = requireActivity().findViewById(R.id.toolbar_search_container)
        searchEditText = searchToolbarContainer!!.findViewById(R.id.toolbar_search_edit_text)
        searchClear = searchToolbarContainer!!.findViewById(R.id.toolbar_search_clear)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    ////////////////////////////////////////////////////////////////////////// */
    override fun writeTo(objectsToSave: Queue<Any>?) {
        super.writeTo(objectsToSave)
        objectsToSave?.add(nextPage)
    }

    @Throws(Exception::class)
    override fun readFrom(savedObjects: Queue<Any>) {
        super.readFrom(savedObjects)
        nextPage = savedObjects.poll() as Page
    }

    override fun onSaveInstanceState(bundle: Bundle) {
        searchString = if (searchEditText != null) searchEditText!!.text.toString() else searchString
        super.onSaveInstanceState(bundle)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init's
    ////////////////////////////////////////////////////////////////////////// */
    override fun reloadContent() {
        if (!TextUtils.isEmpty(searchString)
                || (searchEditText != null && !TextUtils.isEmpty(searchEditText!!.text))) {
            search(if (!TextUtils.isEmpty(searchString)) searchString
            else searchEditText!!.text.toString(), this.contentFilter, "")
        } else {
            if (searchEditText != null) {
                searchEditText!!.setText("")
                showKeyboardSearch()
            }
            hideErrorPanel()
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateOptionsMenu(menu: Menu,
                                     inflater: MenuInflater
    ) {
        super.onCreateOptionsMenu(menu, inflater)

        val supportActionBar = activity?.supportActionBar
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        var itemId = 0
        var isFirstItem = true
        val c = context

        if (service == null) {
            Log.w(TAG, "onCreateOptionsMenu() called with null service")
            updateService()
        }

        for (filter in service!!.searchQHFactory.availableContentFilter) {
            when (filter) {
                YoutubeSearchQueryHandlerFactory.MUSIC_SONGS -> {
                    val musicItem = menu.add(2,
                        itemId++,
                        0,
                        "YouTube Music")
                    musicItem.setEnabled(false)
                }
                PeertubeSearchQueryHandlerFactory.SEPIA_VIDEOS -> {
                    val sepiaItem = menu.add(2,
                        itemId++,
                        0,
                        "Sepia Search")
                    sepiaItem.setEnabled(false)
                }
            }
            menuItemToFilterName.put(itemId, filter)
            val item = menu.add(1,
                itemId++,
                0,
                getTranslatedFilterString(filter, c!!))
            if (isFirstItem) {
                item.setChecked(true)
                isFirstItem = false
            }
        }
        menu.setGroupCheckable(1, true, true)

        restoreFilterChecked(menu, filterItemCheckedId)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val filter = listOf(menuItemToFilterName[item.itemId])
        changeContentFilter(item, filter)
        return true
    }

    private fun restoreFilterChecked(menu: Menu, itemId: Int) {
        if (itemId != -1) {
            val item = menu.findItem(itemId) ?: return
            item.setChecked(true)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Search
    ////////////////////////////////////////////////////////////////////////// */
    private fun showSearchOnStart() {

        Logd(TAG, "showSearchOnStart() called, searchQuery → "
                + searchString
                + ", lastSearchedQuery → "
                + lastSearchedString)

        searchEditText!!.setText(searchString)

        if (searchString.isNullOrEmpty() || searchEditText!!.text.isNullOrEmpty()) {
            searchToolbarContainer!!.translationX = 100f
            searchToolbarContainer!!.alpha = 0.0f
            searchToolbarContainer!!.visibility = View.VISIBLE
            searchToolbarContainer!!.animate()
                .translationX(0f)
                .alpha(1.0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            searchToolbarContainer!!.translationX = 0f
            searchToolbarContainer!!.alpha = 1.0f
            searchToolbarContainer!!.visibility = View.VISIBLE
        }
    }

    private fun initSearchListeners() {

        Logd(TAG, "initSearchListeners() called")

        searchClear!!.setOnClickListener { v: View ->

            Logd(TAG, "onClick() called with: v = [$v]")

            if (TextUtils.isEmpty(searchEditText!!.text)) {
                gotoMainFragment(fM!!)
                return@setOnClickListener
            }

            searchBinding!!.correctSuggestion.visibility = View.GONE

            searchEditText!!.setText("")
            suggestionListAdapter!!.submitList(null)
            showKeyboardSearch()
        }

        TooltipCompat.setTooltipText(searchClear!!, getString(R.string.clear))

        searchEditText!!.setOnClickListener { v: View ->

            Logd(TAG, "onClick() called with: v = [$v]")

            if ((showLocalSuggestions || showRemoteSuggestions) && !isErrorPanelVisible) {
                showSuggestionsPanel()
            }
            if (isTv(requireContext())) {
                showKeyboardSearch()
            }
        }

        searchEditText!!.onFocusChangeListener = OnFocusChangeListener { v: View, hasFocus: Boolean ->
            Logd(TAG, "onFocusChange() called with: v = [$v], hasFocus = [$hasFocus]")
            if ((showLocalSuggestions || showRemoteSuggestions) && hasFocus && !isErrorPanelVisible) showSuggestionsPanel()
        }

        suggestionListAdapter!!.setListener(object : SuggestionListAdapter.OnSuggestionItemSelected {
            override fun onSuggestionItemSelected(item: SuggestionItem?) {
                if (item != null) {
                    search(item.query, arrayOfNulls(0), "")
                    searchEditText!!.setText(item.query)
                }
            }

            override fun onSuggestionItemInserted(item: SuggestionItem?) {
                searchEditText?.setText(item?.query)
                searchEditText?.setSelection(searchEditText!!.text.length)
            }

            override fun onSuggestionItemLongClick(item: SuggestionItem?) {
                if (item?.fromHistory == true) {
                    showDeleteSuggestionDialog(item)
                }
            }
        })

        if (textWatcher != null) {
            searchEditText!!.removeTextChangedListener(textWatcher)
        }
        textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                // Do nothing, old text is already clean
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                // Changes are handled in afterTextChanged; CharSequence cannot be changed here.
            }

            override fun afterTextChanged(s: Editable) {
                // Remove rich text formatting
                for (span in s.getSpans(0, s.length, CharacterStyle::class.java)) {
                    s.removeSpan(span)
                }

                val newText = searchEditText!!.text.toString()
                suggestionPublisher.onNext(newText)
            }
        }
        searchEditText!!.addTextChangedListener(textWatcher)
        searchEditText!!.setOnEditorActionListener { v: TextView, actionId: Int, event: KeyEvent? ->
            Logd(TAG, "onEditorAction() called with: v = [$v], actionId = [$actionId], event = [$event]")

            if (actionId == EditorInfo.IME_ACTION_PREVIOUS) {
                hideKeyboardSearch()
            } else if (event != null && (event.keyCode == KeyEvent.KEYCODE_ENTER || event.action == EditorInfo.IME_ACTION_SEARCH)) {
                search(searchEditText!!.text.toString(), arrayOfNulls(0), "")
                return@setOnEditorActionListener true
            }
            false
        }

        if (suggestionDisposable == null || suggestionDisposable!!.isDisposed) initSuggestionObserver()
    }

    private fun unsetSearchListeners() {
        Logd(TAG, "unsetSearchListeners() called")

        searchClear!!.setOnClickListener(null)
        searchClear!!.setOnLongClickListener(null)
        searchEditText!!.setOnClickListener(null)
        searchEditText!!.onFocusChangeListener = null
        searchEditText!!.setOnEditorActionListener(null)

        if (textWatcher != null) searchEditText!!.removeTextChangedListener(textWatcher)

        textWatcher = null
    }

    private fun showSuggestionsPanel() {
        Logd(TAG, "showSuggestionsPanel() called")

        suggestionsPanelVisible = true
        searchBinding!!.suggestionsPanel.animate(true, 200,
            AnimationType.LIGHT_SLIDE_AND_ALPHA)
    }

    private fun hideSuggestionsPanel() {
        Logd(TAG, "hideSuggestionsPanel() called")

        suggestionsPanelVisible = false
        searchBinding!!.suggestionsPanel.animate(false, 200,
            AnimationType.LIGHT_SLIDE_AND_ALPHA)
    }

    private fun showKeyboardSearch() {
        Logd(TAG, "showKeyboardSearch() called")

        showKeyboard(activity, searchEditText)
    }

    private fun hideKeyboardSearch() {

        Logd(TAG, "hideKeyboardSearch() called")

        hideKeyboard(activity, searchEditText)
    }

    private fun showDeleteSuggestionDialog(item: SuggestionItem) {
        if (activity == null || historyRecordManager == null || searchEditText == null) return

        val query = item.query
        AlertDialog.Builder(requireActivity())
            .setTitle(query)
            .setMessage(R.string.delete_item_search_history)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { dialog: DialogInterface?, which: Int ->
                val onDelete = historyRecordManager!!.deleteSearchHistory(query)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { howManyDeleted: Int? ->
                            suggestionPublisher.onNext(searchEditText!!.text.toString())
                        },
                        { throwable: Throwable? ->
                            showSnackBarError(ErrorInfo(throwable!!, UserAction.DELETE_FROM_HISTORY, "Deleting item failed"))
                        })
                disposables.add(onDelete)
            }
            .show()
    }

    override fun onBackPressed(): Boolean {
        if (suggestionsPanelVisible && !infoListAdapter!!.itemsList.isEmpty() && !isLoading.get()) {
            hideSuggestionsPanel()
            hideKeyboardSearch()
            searchEditText!!.setText(lastSearchedString)
            return true
        }
        return false
    }


    private fun getLocalSuggestionsObservable(query: String?, similarQueryLimit: Int): Observable<MutableList<SuggestionItem>> {
        return historyRecordManager!!
            .getRelatedSearches(query!!, similarQueryLimit, 25)
            .toObservable()
            .map(Function<List<String>, MutableList<SuggestionItem>> { searchHistoryEntries: List<String> ->
                searchHistoryEntries.stream()
                    .map { entry: String -> SuggestionItem(true, entry) }
                    .collect(Collectors.toList())
            })
    }

    private fun getRemoteSuggestionsObservable(query: String): Observable<MutableList<SuggestionItem>> {
        return suggestionsFor(serviceId, query)
            .toObservable()
            .map { strings: List<String> ->
                val result: MutableList<SuggestionItem> = ArrayList()
                for (entry in strings) {
                    result.add(SuggestionItem(false, entry))
                }
                result
            }
    }

    private fun initSuggestionObserver() {
        Logd(TAG, "initSuggestionObserver() called")

        suggestionDisposable?.dispose()

        suggestionDisposable = suggestionPublisher
            .debounce(SUGGESTIONS_DEBOUNCE.toLong(), TimeUnit.MILLISECONDS)
            .startWithItem(if (searchString == null) "" else searchString)
            .switchMap<Notification<MutableList<SuggestionItem>>> { query: String ->
                // Only show remote suggestions if they are enabled in settings and
                // the query length is at least THRESHOLD_NETWORK_SUGGESTION
                val shallShowRemoteSuggestionsNow =
                    (showRemoteSuggestions && query.length >= THRESHOLD_NETWORK_SUGGESTION)
                when {
                    showLocalSuggestions && shallShowRemoteSuggestionsNow -> {
                        return@switchMap Observable.zip<MutableList<SuggestionItem>, MutableList<SuggestionItem>, MutableList<SuggestionItem>>(
                            getLocalSuggestionsObservable(query, 3),
                            getRemoteSuggestionsObservable(query)
                        ) { local: MutableList<SuggestionItem>, remote: MutableList<SuggestionItem> ->
                            remote.removeIf { remoteItem: SuggestionItem ->
                                local.stream().anyMatch { localItem: SuggestionItem -> localItem == remoteItem }
                            }
                            local.addAll(remote)
                            local
                        }
                            .materialize()
                    }
                    showLocalSuggestions -> {
                        return@switchMap getLocalSuggestionsObservable(query, 25)
                            .materialize()
                    }
                    shallShowRemoteSuggestionsNow -> {
                        return@switchMap getRemoteSuggestionsObservable(query)
                            .materialize()
                    }
                    else -> {
                        return@switchMap Single.fromCallable<MutableList<SuggestionItem>> { mutableListOf() }
                            .toObservable()
                            .materialize()
                    }
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { listNotification: Notification<MutableList<SuggestionItem>> ->
                    if (listNotification.isOnNext) {
                        if (listNotification.value != null) {
                            handleSuggestions(listNotification.value!!)
                        }
                    } else if (listNotification.isOnError && listNotification.error != null && !listNotification.error!!.isInterruptedCaused) {
                        showSnackBarError(ErrorInfo(listNotification.error!!,
                            UserAction.GET_SUGGESTIONS, searchString!!, serviceId))
                    }
                }, { throwable: Throwable? ->
                    showSnackBarError(ErrorInfo(
                        throwable!!, UserAction.GET_SUGGESTIONS, searchString!!, serviceId))
                })
    }

    override fun doInitialLoadLogic() {
        // no-op
    }

    private fun search(theSearchString: String?,
                       theContentFilter: Array<String?>,
                       theSortFilter: String?
    ) {

        Logd(TAG, "search() called with: query = [$theSearchString]")

        if (theSearchString.isNullOrEmpty()) return

        try {
            val streamingService = NewPipe.getServiceByUrl(theSearchString)
            if (streamingService != null) {
                showLoading()
                disposables.add(Observable
                    .fromCallable {
                        getIntentByLink(requireActivity(),
                            streamingService, theSearchString)
                    }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ intent: Intent? ->
                        fM!!.popBackStackImmediate()
                        requireActivity().startActivity(intent)
                    }, { throwable: Throwable? -> showTextError(getString(R.string.unsupported_url)) }))
                return
            }
        } catch (ignored: Exception) {
            // Exception occurred, it's not a url
        }

        lastSearchedString = this.searchString
        this.searchString = theSearchString
        infoListAdapter!!.clearStreamItemList()
        hideSuggestionsPanel()
        showMetaInfoInTextView(null, searchBinding!!.searchMetaInfoTextView,
            searchBinding!!.searchMetaInfoSeparator, disposables)
        hideKeyboardSearch()

        disposables.add(historyRecordManager!!.onSearched(serviceId, theSearchString)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { ignored: Long? -> },
                { throwable: Throwable? ->
                    showSnackBarError(ErrorInfo(
                        throwable!!, UserAction.SEARCHED,
                        theSearchString, serviceId))
                }
            ))
        suggestionPublisher.onNext(theSearchString)
        startLoading(false)
    }

    public override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)
        disposables.clear()
        searchDisposable?.dispose()

        searchDisposable = searchFor(serviceId, searchString, Arrays.asList(*contentFilter), sortFilter)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnEvent { searchResult: SearchInfo?, throwable: Throwable? -> isLoading.set(false) }
            .subscribe({ result: SearchInfo -> this.handleResult(result) },
                { exception: Throwable -> this.onItemError(exception) })
    }

    override fun loadMoreItems() {
        if (!Page.isValid(nextPage)) return

        isLoading.set(true)
        showListFooter(true)
        searchDisposable?.dispose()

        searchDisposable = getMoreSearchItems(
            serviceId,
            searchString,
            Arrays.asList(*contentFilter),
            sortFilter,
            nextPage)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnEvent({ nextItemsResult: InfoItemsPage<InfoItem>?, throwable: Throwable? -> isLoading.set(false) })
            .subscribe({ result: InfoItemsPage<InfoItem> ->
                this.handleNextItems(result)
            }, { exception: Throwable -> this.onItemError(exception) })
    }

    override fun hasMoreItems(): Boolean {
        return Page.isValid(nextPage)
    }

    override fun onItemSelected(selectedItem: InfoItem) {
        super.onItemSelected(selectedItem)
        hideKeyboardSearch()
    }

    private fun onItemError(exception: Throwable) {
        if (exception is NothingFoundException) {
            infoListAdapter!!.clearStreamItemList()
            showEmptyState()
        } else {
            showError(ErrorInfo(exception, UserAction.SEARCHED, searchString!!, serviceId))
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun changeContentFilter(item: MenuItem, theContentFilter: List<String?>) {
        filterItemCheckedId = item.itemId
        item.setChecked(true)

        contentFilter = theContentFilter.toTypedArray<String?>()

        if (!searchString.isNullOrEmpty()) {
            search(searchString, contentFilter, sortFilter)
        }
    }

    private fun setQuery(theServiceId: Int,
                         theSearchString: String,
                         theContentFilter: Array<String?>,
                         theSortFilter: String
    ) {
        serviceId = theServiceId
        searchString = theSearchString
        contentFilter = theContentFilter
        sortFilter = theSortFilter
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Suggestion Results
    ////////////////////////////////////////////////////////////////////////// */
    fun handleSuggestions(suggestions: List<SuggestionItem>) {

        Logd(TAG, "handleSuggestions() called with: suggestions = [$suggestions]")

        suggestionListAdapter!!.submitList(suggestions) { searchBinding!!.suggestionsList.scrollToPosition(0) }

        if (suggestionsPanelVisible && isErrorPanelVisible) {
            hideLoading()
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun hideLoading() {
        super.hideLoading()
        showListFooter(false)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Search Results
    ////////////////////////////////////////////////////////////////////////// */
    override fun handleResult(result: SearchInfo) {
        val exceptions = result.errors
        if (!exceptions.isEmpty() && !(exceptions.size == 1 && exceptions[0] is NothingFoundException)) {
            showSnackBarError(ErrorInfo(result.errors, UserAction.SEARCHED, searchString!!, serviceId))
        }

        searchSuggestion = result.searchSuggestion
        isCorrectedSearch = result.isCorrectedSearch

        // List<MetaInfo> cannot be bundled without creating some containers
        metaInfo = result.metaInfo.toTypedArray<MetaInfo>()
        showMetaInfoInTextView(result.metaInfo, searchBinding!!.searchMetaInfoTextView,
            searchBinding!!.searchMetaInfoSeparator, disposables)

        handleSearchSuggestion()

        lastSearchedString = searchString
        nextPage = result.nextPage

        if (infoListAdapter!!.itemsList.isEmpty()) {
            if (!result.relatedItems.isEmpty()) {
                infoListAdapter!!.addInfoItemList(result.relatedItems)
            } else {
                infoListAdapter!!.clearStreamItemList()
                showEmptyState()
                return
            }
        }

        super.handleResult(result)
    }

    private fun handleSearchSuggestion() {
        if (TextUtils.isEmpty(searchSuggestion)) {
            searchBinding!!.correctSuggestion.visibility = View.GONE
        } else {
            val helperText =
                getString(if (isCorrectedSearch) R.string.search_showing_result_for else R.string.did_you_mean)

            val highlightedSearchSuggestion = "<b><i>" + Html.escapeHtml(searchSuggestion) + "</i></b>"
            val text = String.format(helperText, highlightedSearchSuggestion)
            searchBinding!!.correctSuggestion.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)

            searchBinding!!.correctSuggestion.setOnClickListener { v: View? ->
                searchBinding!!.correctSuggestion.visibility = View.GONE
                search(searchSuggestion, contentFilter, sortFilter)
                searchEditText!!.setText(searchSuggestion)
            }

            searchBinding!!.correctSuggestion.setOnLongClickListener { v: View? ->
                searchEditText!!.setText(searchSuggestion)
                searchEditText!!.setSelection(searchSuggestion!!.length)
                showKeyboardSearch()
                true
            }

            searchBinding!!.correctSuggestion.visibility = View.VISIBLE
        }
    }

    override fun handleNextItems(result: InfoItemsPage<InfoItem>) {
        showListFooter(false)
        infoListAdapter!!.addInfoItemList(result.items)
        nextPage = result.nextPage

        if (!result.errors.isEmpty()) {
            showSnackBarError(ErrorInfo(result.errors, UserAction.SEARCHED,
                "\"" + searchString + "\" → pageUrl: " + nextPage!!.getUrl() + ", "
                        + "pageIds: " + nextPage!!.getIds() + ", "
                        + "pageCookies: " + nextPage!!.getCookies(),
                serviceId))
        }
        super.handleNextItems(result)
    }

    override fun handleError() {
        super.handleError()
        hideSuggestionsPanel()
        hideKeyboardSearch()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Suggestion item touch helper
    ////////////////////////////////////////////////////////////////////////// */
    fun getSuggestionMovementFlags(viewHolder: RecyclerView.ViewHolder): Int {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) {
            return 0
        }

        val item = suggestionListAdapter!!.currentList[position]
        return if (item.fromHistory) ItemTouchHelper.Callback.makeMovementFlags(0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) else 0
    }

    fun onSuggestionItemSwiped(viewHolder: RecyclerView.ViewHolder) {
        val position = viewHolder.bindingAdapterPosition
        val query = suggestionListAdapter!!.currentList[position].query
        val onDelete = historyRecordManager!!.deleteSearchHistory(query)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { howManyDeleted: Int? ->
                    suggestionPublisher
                        .onNext(searchEditText!!.text.toString())
                },
                { throwable: Throwable? ->
                    showSnackBarError(ErrorInfo(
                        throwable!!,
                        UserAction.DELETE_FROM_HISTORY, "Deleting item failed"))
                })
        disposables.add(onDelete)
    }

    class SuggestionItem(@JvmField val fromHistory: Boolean, @JvmField val query: String) {
        override fun equals(o: Any?): Boolean {
            if (o is SuggestionItem) return query == o.query
            return false
        }

        override fun hashCode(): Int {
            return query.hashCode()
        }

        override fun toString(): String {
            return "[$fromHistory→$query]"
        }
    }

    class SuggestionListAdapter : ListAdapter<SuggestionItem, SuggestionListAdapter.SuggestionItemHolder>(
        SuggestionItemCallback()) {

        private var listener: OnSuggestionItemSelected? = null

        fun setListener(listener: OnSuggestionItemSelected?) {
            this.listener = listener
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) : SuggestionItemHolder {
            return SuggestionItemHolder(ItemSearchSuggestionBinding
                .inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: SuggestionItemHolder, position: Int) {
            val currentItem = getItem(position)
            holder.updateFrom(currentItem)
            holder.itemBinding.suggestionSearch.setOnClickListener { v: View? ->
                listener?.onSuggestionItemSelected(currentItem)
            }
            holder.itemBinding.suggestionSearch.setOnLongClickListener { v: View? ->
                listener?.onSuggestionItemLongClick(currentItem)
                true
            }
            holder.itemBinding.suggestionInsert.setOnClickListener { v: View? ->
                listener?.onSuggestionItemInserted(currentItem)
            }
        }

        interface OnSuggestionItemSelected {
            fun onSuggestionItemSelected(item: SuggestionItem?)

            fun onSuggestionItemInserted(item: SuggestionItem?)

            fun onSuggestionItemLongClick(item: SuggestionItem?)
        }

        class SuggestionItemHolder internal constructor(val itemBinding: ItemSearchSuggestionBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {
            fun updateFrom(item: SuggestionItem?) {
                itemBinding.itemSuggestionIcon.setImageResource(if (item!!.fromHistory) R.drawable.ic_history
                else R.drawable.ic_search)
                itemBinding.itemSuggestionQuery.text = item.query
            }
        }

        private class SuggestionItemCallback : DiffUtil.ItemCallback<SuggestionItem>() {
            override fun areItemsTheSame(oldItem: SuggestionItem, newItem: SuggestionItem): Boolean {
                return oldItem.fromHistory == newItem.fromHistory && oldItem.query == newItem.query
            }

            override fun areContentsTheSame(oldItem: SuggestionItem, newItem: SuggestionItem): Boolean {
                return true // items' contents never change; the list of items themselves does
            }
        }
    }

    companion object {
        /*//////////////////////////////////////////////////////////////////////////
    // Search
    ////////////////////////////////////////////////////////////////////////// */
        /**
         * The suggestions will only be fetched from network if the query meet this threshold (>=).
         * (local ones will be fetched regardless of the length)
         */
        private const val THRESHOLD_NETWORK_SUGGESTION = 1

        /**
         * How much time have to pass without emitting a item (i.e. the user stop typing)
         * to fetch/show the suggestions, in milliseconds.
         */
        private const val SUGGESTIONS_DEBOUNCE = 120 //ms
        fun getInstance(serviceId: Int, searchString: String): SearchFragment {
            val searchFragment = SearchFragment()
            searchFragment.setQuery(serviceId, searchString, arrayOfNulls(0), "")

            if (searchString.isNotEmpty()) {
                searchFragment.setSearchOnResume()
            }

            return searchFragment
        }
    }
}
