package org.schabi.newpipe.fragments.list.channel

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding4.view.clicks
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Action
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.FragmentChannelBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.fragments.detail.TabAdapter
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateBackgroundColor
import org.schabi.newpipe.ktx.animateTextColor
import org.schabi.newpipe.local.feed.notifications.NotificationHelper.Companion.areNewStreamsNotificationsEnabled
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ChannelTabHelper.getTranslationKey
import org.schabi.newpipe.util.ChannelTabHelper.showChannelTab
import org.schabi.newpipe.util.ExtractorHelper.getChannelInfo
import org.schabi.newpipe.util.Localization.shortSubscriberCount
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.NavigationHelper.openChannelFragment
import org.schabi.newpipe.util.NavigationHelper.openSettings
import org.schabi.newpipe.util.StateSaver.WriteRead
import org.schabi.newpipe.util.ThemeHelper.resolveColorFromAttr
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInApp
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInBrowser
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import org.schabi.newpipe.util.image.ImageStrategy.imageListToDbUrl
import org.schabi.newpipe.util.image.ImageStrategy.shouldLoadImages
import org.schabi.newpipe.util.image.PicassoHelper.cancelTag
import org.schabi.newpipe.util.image.PicassoHelper.loadAvatar
import org.schabi.newpipe.util.image.PicassoHelper.loadBanner
import java.util.*
import java.util.concurrent.TimeUnit

class ChannelFragment : BaseStateFragment<ChannelInfo>(), WriteRead {
    @JvmField
    @State
    var serviceId: Int = NO_SERVICE_ID

    @JvmField
    @State
    var name: String? = null

    @JvmField
    @State
    var url: String? = null

    private var currentInfo: ChannelInfo? = null
    private var currentWorker: Disposable? = null
    private val disposables = CompositeDisposable()
    private var subscribeButtonMonitor: Disposable? = null
    private var subscriptionManager: SubscriptionManager? = null
    private var lastTab = 0
    private var channelContentNotSupported = false

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
    private var binding: FragmentChannelBinding? = null
    private var tabAdapter: TabAdapter? = null

    private var menuRssButton: MenuItem? = null
    private var menuNotifyButton: MenuItem? = null
    private var channelSubscription: SubscriptionEntity? = null

    private fun setInitialData(sid: Int, u: String, title: String) {
        this.serviceId = sid
        this.url = u
        this.name = if (!TextUtils.isEmpty(title)) title else ""
    }


    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscriptionManager = SubscriptionManager(requireActivity())
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        binding = FragmentChannelBinding.inflate(inflater, container, false)
        Log.d(TAG, "onCreateView")
        return binding!!.root
    }

    // called from onViewCreated in BaseFragment.onViewCreated
    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        tabAdapter = TabAdapter(childFragmentManager)
        binding!!.viewPager.adapter = tabAdapter
        binding!!.tabLayout.setupWithViewPager(binding!!.viewPager)

        setTitle(name!!)
        binding!!.channelTitleView.text = name
        if (!shouldLoadImages()) {
            // do not waste space for the banner if it is not going to be loaded
            binding!!.channelBannerImage.setImageDrawable(null)
        }
    }

    override fun initListeners() {
        super.initListeners()

        val openSubChannel = View.OnClickListener { v: View? ->
            if (!TextUtils.isEmpty(
                        currentInfo!!.parentChannelUrl)) {
                try {
                    openChannelFragment(fM!!, currentInfo!!.serviceId,
                        currentInfo!!.parentChannelUrl,
                        currentInfo!!.parentChannelName)
                } catch (e: Exception) {
                    showUiErrorSnackbar(this, "Opening channel fragment", e)
                }
            } else if (DEBUG) {
                Log.i(TAG, "Can't open parent channel because we got no channel URL")
            }
        }
        binding!!.subChannelAvatarView.setOnClickListener(openSubChannel)
        binding!!.subChannelTitleView.setOnClickListener(openSubChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentWorker?.dispose()
        disposables.clear()
        binding = null
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateOptionsMenu(menu: Menu,
                                     inflater: MenuInflater
    ) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_channel, menu)

        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]")
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menuRssButton = menu.findItem(R.id.menu_item_rss)
        menuNotifyButton = menu.findItem(R.id.menu_item_notify)
        updateNotifyButton(channelSubscription)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_item_notify -> {
                val value = !item.isChecked
                item.setEnabled(false)
                setNotify(value)
            }
            R.id.action_settings -> openSettings(requireContext())
            R.id.menu_item_rss -> if (currentInfo != null) {
                openUrlInApp(requireContext(), currentInfo!!.feedUrl)
            }
            R.id.menu_item_openInBrowser -> if (currentInfo != null) {
                openUrlInBrowser(requireContext(), currentInfo!!.originalUrl)
            }
            R.id.menu_item_share -> if (currentInfo != null) {
                shareText(requireContext(), name!!, currentInfo!!.originalUrl, currentInfo!!.avatars)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Channel Subscription
    ////////////////////////////////////////////////////////////////////////// */
    private fun monitorSubscription(info: ChannelInfo) {
        val onError = Consumer { throwable: Throwable? ->
            binding!!.channelSubscribeButton.animate(false, 100)
            showSnackBarError(ErrorInfo(throwable!!, UserAction.SUBSCRIPTION_GET, "Get subscription status", currentInfo))
        }

        val observable = subscriptionManager!!
            .subscriptionTable()
            .getSubscriptionFlowable(info.serviceId, info.url)
            .toObservable()

        disposables.add(observable
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(getSubscribeUpdateMonitor(info), onError))

        disposables.add(observable
            .map { obj: List<SubscriptionEntity?> -> obj.isEmpty() }
            .distinctUntilChanged()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ isEmpty: Boolean? -> updateSubscribeButton(!isEmpty!!) }, onError))

        disposables.add(observable
            .map { obj: List<SubscriptionEntity?> -> obj.isEmpty() }
            .distinctUntilChanged()
            .skip(1) // channel has just been opened
            .filter { x: Boolean? -> areNewStreamsNotificationsEnabled(requireContext()) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ isEmpty: Boolean? ->
                if (!isEmpty!!) {
                    showNotifySnackbar()
                }
            }, onError))
    }

    private fun mapOnSubscribe(subscription: SubscriptionEntity): Function<Any, Any> {
        return Function { o: Any ->
            subscriptionManager?.insertSubscription(subscription)
            o
        }
    }

    private fun mapOnUnsubscribe(subscription: SubscriptionEntity?): Function<Any, Any> {
        return Function { o: Any ->
            subscriptionManager?.deleteSubscription(subscription!!)
            o
        }
    }

    private fun updateSubscription(info: ChannelInfo) {
        if (DEBUG) {
            Log.d(TAG, "updateSubscription() called with: info = [$info]")
        }
        val onComplete = Action {
            if (DEBUG) {
                Log.d(TAG, "Updated subscription: " + info.url)
            }
        }

        val onError = Consumer { throwable: Throwable ->
            showSnackBarError(ErrorInfo(throwable, UserAction.SUBSCRIPTION_UPDATE,
                "Updating subscription for " + info.url, info))
        }

        disposables.add(subscriptionManager!!.updateChannelInfo(info)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(onComplete, onError))
    }

    private fun monitorSubscribeButton(action: Function<Any, Any>): Disposable {
        val onNext = Consumer { o: Any ->
            if (DEBUG) {
                Log.d(TAG, "Changed subscription status to this channel!")
            }
        }

        val onError = Consumer { throwable: Throwable ->
            showSnackBarError(ErrorInfo(throwable, UserAction.SUBSCRIPTION_CHANGE,
                "Changing subscription for " + currentInfo!!.url, currentInfo))
        }

        /* Emit clicks from main thread unto io thread */
        return binding!!.channelSubscribeButton.clicks()
            .subscribeOn(AndroidSchedulers.mainThread())
            .observeOn(Schedulers.io())
            .debounce(BUTTON_DEBOUNCE_INTERVAL.toLong(), TimeUnit.MILLISECONDS) // Ignore rapid clicks
            .map(action)
            .subscribe(onNext, onError)
    }

    private fun getSubscribeUpdateMonitor(info: ChannelInfo): Consumer<List<SubscriptionEntity>> {
        return Consumer { subscriptionEntities: List<SubscriptionEntity> ->
            if (DEBUG) {
                Log.d(TAG, "subscriptionManager.subscriptionTable.doOnNext() called with: "
                        + "subscriptionEntities = [" + subscriptionEntities + "]")
            }
            subscribeButtonMonitor?.dispose()

            if (subscriptionEntities.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "No subscription to this channel!")
                }
                val channel = SubscriptionEntity()
                channel.serviceId = info.serviceId
                channel.url = info.url
                channel.setData(info.name,
                    imageListToDbUrl(info.avatars),
                    info.description,
                    info.subscriberCount)
                channelSubscription = null
                updateNotifyButton(null)
                subscribeButtonMonitor = monitorSubscribeButton(mapOnSubscribe(channel))
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Found subscription to this channel!")
                }
                channelSubscription = subscriptionEntities[0]
                updateNotifyButton(channelSubscription)
                subscribeButtonMonitor = monitorSubscribeButton(mapOnUnsubscribe(channelSubscription))
            }
        }
    }

    private fun updateSubscribeButton(isSubscribed: Boolean) {
        if (DEBUG) {
            Log.d(TAG, "updateSubscribeButton() called with: "
                    + "isSubscribed = [" + isSubscribed + "]")
        }

        val isButtonVisible = (binding!!.channelSubscribeButton.visibility == View.VISIBLE)
        val backgroundDuration = if (isButtonVisible) 300 else 0
        val textDuration = if (isButtonVisible) 200 else 0

        val subscribedBackground = ContextCompat
            .getColor(requireActivity(), R.color.subscribed_background_color)
        val subscribedText = ContextCompat.getColor(requireActivity(), R.color.subscribed_text_color)
        val subscribeBackground =
            ColorUtils.blendARGB(resolveColorFromAttr(requireActivity(), R.attr.colorPrimary), subscribedBackground, 0.35f)
        val subscribeText = ContextCompat.getColor(requireActivity(), R.color.subscribe_text_color)

        if (isSubscribed) {
            binding!!.channelSubscribeButton.setText(R.string.subscribed_button_title)
            binding!!.channelSubscribeButton.animateBackgroundColor(backgroundDuration.toLong(), subscribeBackground, subscribedBackground)
            binding!!.channelSubscribeButton.animateTextColor(textDuration.toLong(), subscribeText, subscribedText)
        } else {
            binding!!.channelSubscribeButton.setText(R.string.subscribe_button_title)
            binding!!.channelSubscribeButton.animateBackgroundColor(backgroundDuration.toLong(), subscribedBackground, subscribeBackground)
            binding!!.channelSubscribeButton.animateTextColor(textDuration.toLong(), subscribedText, subscribeText)
        }

        binding!!.channelSubscribeButton.animate(true, 100, AnimationType.LIGHT_SCALE_AND_ALPHA)
    }

    private fun updateNotifyButton(subscription: SubscriptionEntity?) {
        if (menuNotifyButton == null) return

        if (subscription != null) {
            menuNotifyButton!!.setEnabled(areNewStreamsNotificationsEnabled(requireContext()))
            menuNotifyButton!!.setChecked(subscription.notificationMode == NotificationMode.ENABLED)
        }
        menuNotifyButton!!.setVisible(subscription != null)
    }

    private fun setNotify(isEnabled: Boolean) {
        disposables.add(
            subscriptionManager!!
                .updateNotificationMode(currentInfo!!.serviceId, currentInfo!!.url,
                    if (isEnabled) NotificationMode.ENABLED else NotificationMode.DISABLED)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe()
        )
    }

    /**
     * Show a snackbar with the option to enable notifications on new streams for this channel.
     */
    private fun showNotifySnackbar() {
        Snackbar.make(binding!!.root, R.string.you_successfully_subscribed, Snackbar.LENGTH_LONG)
            .setAction(R.string.get_notified) { v: View? -> setNotify(true) }
            .setActionTextColor(Color.YELLOW)
            .show()
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
    private fun updateTabs() {
        tabAdapter!!.clearAllItems()

        if (currentInfo != null && !channelContentNotSupported) {
            val context = requireContext()
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)

            for (linkHandler in currentInfo!!.tabs) {
                val tab = linkHandler.contentFilters[0]
                if (showChannelTab(context, preferences, tab)) {
                    val channelTabFragment = ChannelTabFragment.getInstance(serviceId, linkHandler, name)
                    channelTabFragment.useAsFrontPage(useAsFrontPage)
                    tabAdapter!!.addFragment(channelTabFragment, context.getString(getTranslationKey(tab)))
                }
            }

            if (showChannelTab(context, preferences, R.string.show_channel_tabs_about)) {
                tabAdapter!!.addFragment(ChannelAboutFragment.getInstance(currentInfo), context.getString(R.string.channel_tab_about))
            }
        }

        tabAdapter!!.notifyDataSetUpdate()

        for (i in 0 until tabAdapter!!.count) {
            binding!!.tabLayout.getTabAt(i)!!.setText(tabAdapter!!.getItemTitle(i))
        }

        // Restore previously selected tab
        val ltab = binding!!.tabLayout.getTabAt(lastTab)
        if (ltab != null) {
            binding!!.tabLayout.selectTab(ltab)
        }
    }


    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    ////////////////////////////////////////////////////////////////////////// */
    override fun generateSuffix(): String {
        return ""
    }

    override fun writeTo(objectsToSave: Queue<Any>?) {
        objectsToSave!!.add(currentInfo)
        objectsToSave.add(if (binding == null) 0 else binding!!.tabLayout.selectedTabPosition)
    }

    override fun readFrom(savedObjects: Queue<Any>) {
        currentInfo = savedObjects.poll() as ChannelInfo
        lastTab = savedObjects.poll() as Int
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (binding != null) {
            outState.putInt("LastTab", binding!!.tabLayout.selectedTabPosition)
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        lastTab = savedInstanceState.getInt("LastTab", 0)
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun doInitialLoadLogic() {
        if (currentInfo == null) {
            startLoading(false)
        } else {
            handleResult(currentInfo!!)
        }
    }

    public override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)

        currentInfo = null
        updateTabs()
        currentWorker?.dispose()

        runWorker(forceLoad)
    }

    private fun runWorker(forceLoad: Boolean) {
        currentWorker = getChannelInfo(serviceId, url!!, forceLoad)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result: ChannelInfo ->
                isLoading.set(false)
                handleResult(result)
            }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, UserAction.REQUESTED_CHANNEL, url ?: "No URL", serviceId))
            })
    }

    override fun showLoading() {
        super.showLoading()
        cancelTag(PICASSO_CHANNEL_TAG)
        binding!!.channelSubscribeButton.animate(false, 100)
    }

    override fun handleResult(result: ChannelInfo) {
        super.handleResult(result)
        currentInfo = result
        setInitialData(result.serviceId, result.originalUrl, result.name)

        if (shouldLoadImages() && !result.banners.isEmpty()) {
            loadBanner(result.banners).tag(PICASSO_CHANNEL_TAG).into(binding!!.channelBannerImage)
        } else {
            // do not waste space for the banner, if the user disabled images or there is not one
            binding!!.channelBannerImage.setImageDrawable(null)
        }

        loadAvatar(result.avatars).tag(PICASSO_CHANNEL_TAG).into(binding!!.channelAvatarView)
        loadAvatar(result.parentChannelAvatars).tag(PICASSO_CHANNEL_TAG).into(binding!!.subChannelAvatarView)

        binding!!.channelTitleView.text = result.name
        binding!!.channelSubscriberView.visibility = View.VISIBLE
        if (result.subscriberCount >= 0) {
            binding!!.channelSubscriberView.text = shortSubscriberCount(requireActivity(), result.subscriberCount)
        } else {
            binding!!.channelSubscriberView.setText(R.string.subscribers_count_not_available)
        }

        if (!TextUtils.isEmpty(currentInfo!!.parentChannelName)) {
            binding!!.subChannelTitleView.text = String.format(getString(R.string.channel_created_by), currentInfo!!.parentChannelName)
            binding!!.subChannelTitleView.visibility = View.VISIBLE
            binding!!.subChannelAvatarView.visibility = View.VISIBLE
        }

        if (menuRssButton != null) {
            menuRssButton!!.setVisible(!TextUtils.isEmpty(result.feedUrl))
        }

        channelContentNotSupported = false
        for (throwable in result.errors) {
            if (throwable is ContentNotSupportedException) {
                channelContentNotSupported = true
                showContentNotSupportedIfNeeded()
                break
            }
        }

        disposables.clear()
        subscribeButtonMonitor?.dispose()

        updateTabs()
        updateSubscription(result)
        monitorSubscription(result)
    }

    private fun showContentNotSupportedIfNeeded() {
        // channelBinding might not be initialized when handleResult() is called
        // (e.g. after rotating the screen, #6696)
        if (!channelContentNotSupported || binding == null) return

        binding!!.errorContentNotSupported.visibility = View.VISIBLE
        binding!!.channelKaomoji.text = "(︶︹︺)"
        binding!!.channelKaomoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 45f)
    }

    companion object {
        private const val BUTTON_DEBOUNCE_INTERVAL = 100
        private const val PICASSO_CHANNEL_TAG = "PICASSO_CHANNEL_TAG"

        fun getInstance(serviceId: Int, url: String, name: String): ChannelFragment {
            val instance = ChannelFragment()
            instance.setInitialData(serviceId, url, name)
            return instance
        }
    }
}
