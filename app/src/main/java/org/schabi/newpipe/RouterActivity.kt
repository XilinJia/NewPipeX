package org.schabi.newpipe

//import androidx.lifecycle.Lifecycle.State.isAtLeast
//import androidx.lifecycle.Lifecycle.addObserver
//import androidx.lifecycle.Lifecycle.currentState
//import androidx.lifecycle.Lifecycle.removeObserver
import android.annotation.SuppressLint
import android.app.IntentService
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.math.MathUtils
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import icepick.Icepick
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.core.SingleOnSubscribe
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.DownloadLoadingDialogBinding
import org.schabi.newpipe.databinding.ListRadioIconItemBinding
import org.schabi.newpipe.databinding.SingleChoiceDialogViewBinding
import org.schabi.newpipe.ui.download.DownloadDialog
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.createNotification
import org.schabi.newpipe.error.ReCaptchaActivity
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.StreamingService.LinkType
import org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.exceptions.*
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.util.isNetworkRelated
import org.schabi.newpipe.ui.local.dialog.PlaylistDialog
import org.schabi.newpipe.player.PlayerType
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.ChannelTabHelper
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.ExtractorHelper.getChannelInfo
import org.schabi.newpipe.util.ExtractorHelper.getPlaylistInfo
import org.schabi.newpipe.util.ExtractorHelper.getStreamInfo
import org.schabi.newpipe.util.KEY_SERVICE_ID
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NavigationHelper.getIntentByLink
import org.schabi.newpipe.util.NavigationHelper.openSearch
import org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnExternalAudioPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnExternalVideoPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnMainPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnPopupPlayer
import org.schabi.newpipe.util.PermissionHelper
import org.schabi.newpipe.util.PermissionHelper.checkStoragePermissions
import org.schabi.newpipe.util.PermissionHelper.isPopupEnabledElseAsk
import org.schabi.newpipe.util.ThemeHelper.isLightThemeSelected
import org.schabi.newpipe.util.ThemeHelper.setDayNightMode
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInBrowser
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import org.schabi.newpipe.util.urlfinder.UrlFinder.Companion.firstUrlFromInput
import org.schabi.newpipe.ui.views.FocusOverlayView.Companion.setupFocusObserver
import java.io.Serializable
import java.lang.ref.WeakReference
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Get the url from the intent and open it in the chosen preferred player.
 */
@UnstableApi class RouterActivity : AppCompatActivity() {
    protected val disposables: CompositeDisposable = CompositeDisposable()

    @JvmField
    @State
    var currentServiceId: Int = -1

    @JvmField
    @State
    var currentLinkType: LinkType? = null

    @JvmField
    @State
    var selectedRadioPosition: Int = -1
    protected var selectedPreviously: Int = -1
    protected var currentUrl: String? = null
    private var currentService: StreamingService? = null
    private var selectionIsDownload = false
    private var selectionIsAddToPlaylist = false
    private var alertDialogChoice: AlertDialog? = null
    private var dismissListener: FragmentManager.FragmentLifecycleCallbacks? = null

    protected val themeWrapperContext: Context
        get() = ContextThemeWrapper(this, if (isLightThemeSelected(this)) R.style.LightTheme else R.style.DarkTheme)

    private val persistFragment: PersistentFragment
        get() {
            val fm = supportFragmentManager
            var persistFragment =
                fm.findFragmentByTag("PERSIST_FRAGMENT") as PersistentFragment?
            if (persistFragment == null) {
                persistFragment = PersistentFragment()
                fm.beginTransaction()
                    .add(persistFragment, "PERSIST_FRAGMENT")
                    .commitNow()
            }
            return persistFragment
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setDayNightMode(this)
        setTheme(if (isLightThemeSelected(this)) R.style.RouterActivityThemeLight else R.style.RouterActivityThemeDark)
        assureCorrectAppLanguage(this)

        // Pass-through touch events to background activities
        // so that our transparent window won't lock UI in the mean time
        // network request is underway before showing PlaylistDialog or DownloadDialog
        // (ref: https://stackoverflow.com/a/10606141)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        // Android never fails to impress us with a list of new restrictions per API.
        // Starting with S (Android 12) one of the prerequisite conditions has to be met
        // before the FLAG_NOT_TOUCHABLE flag is allowed to kick in:
        // @see WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE
        // For our present purpose it seems we can just set LayoutParams.alpha to 0
        // on the strength of "4. Fully transparent windows" without affecting the scrim of dialogs
        val params = window.attributes
        params.alpha = 0f
        window.attributes = params

        super.onCreate(savedInstanceState)
        Icepick.restoreInstanceState(this, savedInstanceState)

        // FragmentManager will take care to recreate (Playlist|Download)Dialog when screen rotates
        // We used to .setOnDismissListener(dialog -> finish()); when creating these DialogFragments
        // but those callbacks won't survive a config change
        // Try an alternate approach to hook into FragmentManager instead, to that effect
        // (ref: https://stackoverflow.com/a/44028453)
        val fm = supportFragmentManager
        if (dismissListener == null) {
            dismissListener = object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                    super.onFragmentDestroyed(fm, f)
                    if (f is DialogFragment && fm.fragments.isEmpty()) {
                        // No more DialogFragments, we're done
                        finish()
                    }
                }
            }
        }
        fm.registerFragmentLifecycleCallbacks(dismissListener!!, false)

        if (TextUtils.isEmpty(currentUrl)) {
            currentUrl = getUrl(intent)

            if (TextUtils.isEmpty(currentUrl)) {
                handleText()
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // we need to dismiss the dialog before leaving the activity or we get leaks
        alertDialogChoice?.dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    override fun onStart() {
        super.onStart()

        // Don't overlap the DialogFragment after rotating the screen
        // If there's no DialogFragment, we're either starting afresh
        // or we didn't make it to PlaylistDialog or DownloadDialog before the orientation change
        if (supportFragmentManager.fragments.isEmpty()) {
            // Start over from scratch
            handleUrl(currentUrl)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (dismissListener != null) supportFragmentManager.unregisterFragmentLifecycleCallbacks(dismissListener!!)

        disposables.clear()
    }

    override fun finish() {
        // allow the activity to recreate in case orientation changes
        if (!isChangingConfigurations) super.finish()
    }

    private fun handleUrl(url: String?) {
        disposables.add(Observable
            .fromCallable {
                try {
                    if (currentServiceId == -1) {
                        currentService = NewPipe.getServiceByUrl(url)
                        if (currentService != null) {
                            currentServiceId = currentService!!.getServiceId()
                            currentLinkType = currentService!!.getLinkTypeByUrl(url)
                        }
                        currentUrl = url
                    } else {
                        currentService = NewPipe.getService(currentServiceId)
                    }

                    // return whether the url was found to be supported or not
                    return@fromCallable currentLinkType != LinkType.NONE
                } catch (e: ExtractionException) {
                    // this can be reached only when the url is completely unsupported
                    return@fromCallable false
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ isUrlSupported: Boolean ->
                if (isUrlSupported) {
                    onSuccess()
                } else {
                    showUnsupportedUrlDialog(url)
                }
            }, { throwable: Throwable? ->
                handleError(this, ErrorInfo(throwable!!, UserAction.SHARE_TO_NEWPIPE, "Getting service from url: $url"))
            }))
    }

    protected fun showUnsupportedUrlDialog(url: String?) {
        val context = themeWrapperContext
        AlertDialog.Builder(context)
            .setTitle(R.string.unsupported_url)
            .setMessage(R.string.unsupported_url_dialog_message)
            .setIcon(R.drawable.ic_share)
            .setPositiveButton(R.string.open_in_browser) { dialog: DialogInterface?, which: Int -> openUrlInBrowser(this, url) }
            .setNegativeButton(R.string.share) { dialog: DialogInterface?, which: Int -> shareText(this, "", url) } // no subject
            .setNeutralButton(R.string.cancel, null)
            .setOnDismissListener { dialog: DialogInterface? -> finish() }
            .show()
    }

    protected fun onSuccess() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)

        val choiceChecker = ChoiceAvailabilityChecker(
            getChoicesForService(currentService, currentLinkType),
            preferences.getString(getString(R.string.preferred_open_action_key), getString(R.string.preferred_open_action_default))!!)

        // Check for non-player related choices
        if (choiceChecker.isAvailableAndSelected(R.string.show_info_key, R.string.download_key, R.string.add_to_playlist_key)) {
            handleChoice(choiceChecker.selectedChoiceKey)
            return
        }
        // Check if the choice is player related
        if (choiceChecker.isAvailableAndSelected(R.string.video_player_key, R.string.background_player_key, R.string.popup_player_key)) {
            val selectedChoice = choiceChecker.selectedChoiceKey

            val isExtVideoEnabled = preferences.getBoolean(getString(R.string.use_external_video_player_key), false)
            val isExtAudioEnabled = preferences.getBoolean(getString(R.string.use_external_audio_player_key), false)
            val isVideoPlayerSelected = selectedChoice == getString(R.string.video_player_key) || selectedChoice == getString(R.string.popup_player_key)
            val isAudioPlayerSelected = selectedChoice == getString(R.string.background_player_key)

            if (currentLinkType != LinkType.STREAM
                    && ((isExtAudioEnabled && isAudioPlayerSelected) || (isExtVideoEnabled && isVideoPlayerSelected))) {
                Toast.makeText(this, R.string.external_player_unsupported_link_type, Toast.LENGTH_LONG).show()
                handleChoice(getString(R.string.show_info_key))
                return
            }

            val capabilities = currentService!!.serviceInfo.mediaCapabilities

            // Check if the service supports the choice
            if ((isVideoPlayerSelected && capabilities.contains(MediaCapability.VIDEO))
                    || (isAudioPlayerSelected && capabilities.contains(MediaCapability.AUDIO))) {
                handleChoice(selectedChoice)
            } else {
                handleChoice(getString(R.string.show_info_key))
            }
            return
        }

        // Default / Ask always
        val availableChoices = choiceChecker.availableChoices
        when (availableChoices.size) {
            1 -> handleChoice(availableChoices[0].key)
            0 -> handleChoice(getString(R.string.show_info_key))
            else -> showDialog(availableChoices)
        }
    }

    /**
     * This is a helper class for checking if the choices are available and/or selected.
     */
    internal inner class ChoiceAvailabilityChecker(val availableChoices: List<AdapterChoiceItem>, val selectedChoiceKey: String) {
        fun isAvailableAndSelected(@StringRes vararg wantedKeys: Int): Boolean {
            return Arrays.stream(wantedKeys).anyMatch { wantedKey: Int -> this.isAvailableAndSelected(wantedKey) }
        }

        fun isAvailableAndSelected(@StringRes wantedKey: Int): Boolean {
            val wanted = getString(wantedKey)
            // Check if the wanted option is selected
            if (selectedChoiceKey != wanted) return false

            // Check if it's available
            return availableChoices.stream().anyMatch { item: AdapterChoiceItem -> wanted == item.key }
        }
    }

    private fun showDialog(choices: List<AdapterChoiceItem>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)

        val themeWrapperContext = themeWrapperContext
        val layoutInflater = LayoutInflater.from(themeWrapperContext)

        val binding = SingleChoiceDialogViewBinding.inflate(layoutInflater)
        val radioGroup = binding.list

        val dialogButtonsClickListener = DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
            val indexOfChild = radioGroup.indexOfChild(radioGroup.findViewById(radioGroup.checkedRadioButtonId))
            val choice = choices[indexOfChild]

            handleChoice(choice.key)

            // open future streams always like this one, because "always" button was used by user
            if (which == DialogInterface.BUTTON_POSITIVE) {
                preferences.edit()
                    .putString(getString(R.string.preferred_open_action_key), choice.key)
                    .apply()
            }
        }

        alertDialogChoice = AlertDialog.Builder(themeWrapperContext)
            .setTitle(R.string.preferred_open_action_share_menu_title)
            .setView(binding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.just_once, dialogButtonsClickListener)
            .setPositiveButton(R.string.always, dialogButtonsClickListener)
            .setOnDismissListener { dialog: DialogInterface? ->
                if (!selectionIsDownload && !selectionIsAddToPlaylist) finish()
            }
            .create()

        alertDialogChoice!!.setOnShowListener { dialog: DialogInterface? ->
            setDialogButtonsState(alertDialogChoice!!, radioGroup.checkedRadioButtonId != -1)
        }

        radioGroup.setOnCheckedChangeListener { group: RadioGroup?, checkedId: Int ->
            setDialogButtonsState(alertDialogChoice!!, true)
        }
        val radioButtonsClickListener = View.OnClickListener { v: View? ->
            val indexOfChild = radioGroup.indexOfChild(v)
            if (indexOfChild == -1) return@OnClickListener

            selectedPreviously = selectedRadioPosition
            selectedRadioPosition = indexOfChild
            if (selectedPreviously == selectedRadioPosition) {
                handleChoice(choices[selectedRadioPosition].key)
            }
        }

        var id = 12345
        for (item in choices) {
            val radioButton = ListRadioIconItemBinding.inflate(layoutInflater).root
            radioButton.text = item.description
            radioButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                AppCompatResources.getDrawable(themeWrapperContext, item.icon), null, null, null)
            radioButton.isChecked = false
            radioButton.id = id++
            radioButton.layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            radioButton.setOnClickListener(radioButtonsClickListener)
            radioGroup.addView(radioButton)
        }

        if (selectedRadioPosition == -1) {
            val lastSelectedPlayer = preferences.getString(getString(R.string.preferred_open_action_last_selected_key), null)
            if (!TextUtils.isEmpty(lastSelectedPlayer)) {
                for (i in choices.indices) {
                    val c = choices[i]
                    if (lastSelectedPlayer == c.key) {
                        selectedRadioPosition = i
                        break
                    }
                }
            }
        }

        selectedRadioPosition = MathUtils.clamp(selectedRadioPosition, -1, choices.size - 1)
        if (selectedRadioPosition != -1) {
            (radioGroup.getChildAt(selectedRadioPosition) as RadioButton).isChecked = true
        }
        selectedPreviously = selectedRadioPosition

        alertDialogChoice!!.show()

        if (isTv(this)) {
            setupFocusObserver(alertDialogChoice!!)
        }
    }

    private fun getChoicesForService(service: StreamingService?, linkType: LinkType?): List<AdapterChoiceItem> {
        val showInfo = AdapterChoiceItem(getString(R.string.show_info_key), getString(R.string.show_info), R.drawable.ic_info_outline)
        val videoPlayer = AdapterChoiceItem(getString(R.string.video_player_key), getString(R.string.video_player), R.drawable.ic_play_arrow)
        val backgroundPlayer = AdapterChoiceItem(getString(R.string.background_player_key), getString(R.string.background_player), R.drawable.ic_headset)
        val popupPlayer = AdapterChoiceItem(getString(R.string.popup_player_key), getString(R.string.popup_player), R.drawable.ic_picture_in_picture)

        val returnedItems: MutableList<AdapterChoiceItem> = ArrayList()
        returnedItems.add(showInfo) // Always present

        val capabilities = service!!.serviceInfo.mediaCapabilities

        if (linkType == LinkType.STREAM) {
            if (capabilities.contains(MediaCapability.VIDEO)) {
                returnedItems.add(videoPlayer)
                returnedItems.add(popupPlayer)
            }
            if (capabilities.contains(MediaCapability.AUDIO)) {
                returnedItems.add(backgroundPlayer)
            }
            // download is redundant for linkType CHANNEL AND PLAYLIST (till playlist downloading is
            // not supported )
            returnedItems.add(AdapterChoiceItem(getString(R.string.download_key), getString(R.string.download), R.drawable.ic_file_download))

            // Add to playlist is not necessary for CHANNEL and PLAYLIST linkType since those can
            // not be added to a playlist
            returnedItems.add(AdapterChoiceItem(getString(R.string.add_to_playlist_key), getString(R.string.add_to_playlist), R.drawable.ic_add))
        } else {
            // LinkType.NONE is never present because it's filtered out before
            // channels and playlist can be played as they contain a list of videos
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            val isExtVideoEnabled = preferences.getBoolean(getString(R.string.use_external_video_player_key), false)
            val isExtAudioEnabled = preferences.getBoolean(getString(R.string.use_external_audio_player_key), false)

            if (capabilities.contains(MediaCapability.VIDEO) && !isExtVideoEnabled) {
                returnedItems.add(videoPlayer)
                returnedItems.add(popupPlayer)
            }
            if (capabilities.contains(MediaCapability.AUDIO) && !isExtAudioEnabled) {
                returnedItems.add(backgroundPlayer)
            }
        }

        return returnedItems
    }


    private fun setDialogButtonsState(dialog: AlertDialog, state: Boolean) {
        val negativeButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        if (negativeButton == null || positiveButton == null) return

        negativeButton.isEnabled = state
        positiveButton.isEnabled = state
    }

    @OptIn(UnstableApi::class) private fun handleText() {
        val searchString = intent.getStringExtra(Intent.EXTRA_TEXT)
        val serviceId = intent.getIntExtra(KEY_SERVICE_ID, 0)
        val intent = Intent(themeWrapperContext, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        openSearch(themeWrapperContext, serviceId, searchString)
    }

    @OptIn(UnstableApi::class) private fun handleChoice(selectedChoiceKey: String) {
        val validChoicesList = Arrays.asList(*resources
            .getStringArray(R.array.preferred_open_action_values_list))
        if (validChoicesList.contains(selectedChoiceKey)) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(getString(
                    R.string.preferred_open_action_last_selected_key), selectedChoiceKey)
                .apply()
        }

        if (selectedChoiceKey == getString(R.string.popup_player_key) && !isPopupEnabledElseAsk(this)) {
            finish()
            return
        }

        if (selectedChoiceKey == getString(R.string.download_key)) {
            if (checkStoragePermissions(this, PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
                selectionIsDownload = true
                openDownloadDialog()
            }
            return
        }

        if (selectedChoiceKey == getString(R.string.add_to_playlist_key)) {
            selectionIsAddToPlaylist = true
            openAddToPlaylistDialog()
            return
        }

        // stop and bypass FetcherService if InfoScreen was selected since
        // StreamDetailFragment can fetch data itself
        if (selectedChoiceKey == getString(R.string.show_info_key) || canHandleChoiceLikeShowInfo(selectedChoiceKey)) {
            disposables.add(Observable
                .fromCallable { getIntentByLink(this, currentUrl!!) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ intent: Intent? ->
                    startActivity(intent)
                    finish()
                }, { throwable: Throwable? ->
                    handleError(this, ErrorInfo(throwable!!, UserAction.SHARE_TO_NEWPIPE, "Starting info activity: $currentUrl"))
                })
            )
            return
        }

        val intent = Intent(this, FetcherService::class.java)
        val choice = Choice(currentService!!.serviceId, currentLinkType, currentUrl, selectedChoiceKey)
        intent.putExtra(FetcherService.KEY_CHOICE, choice)
        startService(intent)

        finish()
    }

    private fun canHandleChoiceLikeShowInfo(selectedChoiceKey: String): Boolean {
        if (selectedChoiceKey != getString(R.string.video_player_key)) return false

        // "video player" can be handled like "show info" (because VideoDetailFragment can load
        // the stream instead of FetcherService) when...

        // ...Autoplay is enabled
        if (!PlayerHelper.isAutoplayAllowedByUser(themeWrapperContext)) return false

        val isExtVideoEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.use_external_video_player_key), false)
        // ...it's not done via an external player
        if (isExtVideoEnabled) return false

        // ...the player is not running or in normal Video-mode/type
        val playerType = PlayerHolder.instance?.type
        return playerType == null || playerType == PlayerType.MAIN
    }

    class PersistentFragment : Fragment() {
        private var weakContext: WeakReference<AppCompatActivity>? = null
        private val disposables = CompositeDisposable()
        private var running = 0

        @Synchronized
        private fun inFlight(started: Boolean) {
            if (started) {
                running++
            } else {
                running--
                if (running <= 0) {
                    activityContext.ifPresent { context: AppCompatActivity? ->
                        this@PersistentFragment.parentFragmentManager.beginTransaction().remove(this).commit()
                    }
                }
            }
        }

        override fun onAttach(activityContext: Context) {
            super.onAttach(activityContext)
            weakContext = WeakReference(activityContext as AppCompatActivity)
        }

        override fun onDetach() {
            super.onDetach()
            weakContext = null
        }

        @Suppress("deprecation")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            retainInstance = true
        }

        override fun onDestroy() {
            super.onDestroy()
            disposables.clear()
        }

        private val activityContext: Optional<AppCompatActivity?>
            /**
             * @return the activity context, if there is one and the activity is not finishing
             */
            get() = Optional.ofNullable(weakContext)
                .map { obj: WeakReference<AppCompatActivity>? -> obj!!.get() }
                .filter { context: AppCompatActivity? -> !(context?.isFinishing?:false) }

        // guard against IllegalStateException in calling DialogFragment.show() whilst in background
        // (which could happen, say, when the user pressed the home button while waiting for
        // the network request to return) when it internally calls FragmentTransaction.commit()
        // after the FragmentManager has saved its states (isStateSaved() == true)
        // (ref: https://stackoverflow.com/a/39813506)
        private fun runOnVisible(runnable: Consumer<AppCompatActivity?>) {
            activityContext.ifPresentOrElse({ context: AppCompatActivity? ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    context?.runOnUiThread {
                        runnable.accept(context)
                        inFlight(false)
                    }
                } else {
                    lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onResume(owner: LifecycleOwner) {
                            lifecycle.removeObserver(this)
                            this@PersistentFragment.activityContext.ifPresentOrElse({ context: AppCompatActivity? ->
                                context?.runOnUiThread {
                                    runnable.accept(context)
                                    inFlight(false)
                                }
                            },
                                { inFlight(false) }
                            )
                        }
                    })
                    // this trick doesn't seem to work on Android 10+ (API 29)
                    // which places restrictions on starting activities from the background
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !(context?.isChangingConfigurations?:false)) {
                        // try to bring the activity back to front if minimised
                        val i = Intent(context, RouterActivity::class.java)
                        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        startActivity(i)
                    }
                }
            }, { // this branch is executed if there is no activity context
                inFlight(false)
            }
            )
        }

        fun <T: Any> pleaseWait(single: Single<T>): Single<T> {
            // 'abuse' ambWith() here to cancel the toast for us when the wait is over
            return single.ambWith(Single.create(SingleOnSubscribe { emitter: SingleEmitter<T> ->
                activityContext.ifPresent { context: AppCompatActivity? ->
                    context?.runOnUiThread {
                        // Getting the stream info usually takes a moment
                        // Notifying the user here to ensure that no confusion arises
                        val toast = Toast.makeText(context, getString(R.string.processing_may_take_a_moment), Toast.LENGTH_LONG)
                        toast.show()
                        emitter.setCancellable { toast.cancel() }
                    }
                }
            }))
        }

        @SuppressLint("CheckResult")
        fun openDownloadDialog(currentServiceId: Int, currentUrl: String?) {
            inFlight(true)
            val loadingDialog = LoadingDialog(R.string.loading_metadata_title)
            loadingDialog.show(parentFragmentManager, "loadingDialog")
            disposables.add(getStreamInfo(currentServiceId, currentUrl!!, true)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose<StreamInfo> { single: Single<StreamInfo> -> this.pleaseWait(single) }
                .subscribe({ result: StreamInfo? ->
                    runOnVisible { ctx: AppCompatActivity? ->
                        loadingDialog.dismiss()
                        val fm = ctx!!.supportFragmentManager
                        val downloadDialog = DownloadDialog(ctx, result!!)
                        // dismiss listener to be handled by FragmentManager
                        downloadDialog.show(fm, "downloadDialog")
                    }
                }, { throwable: Throwable? ->
                    runOnVisible { ctx: AppCompatActivity? ->
                        loadingDialog.dismiss()
                        (ctx as RouterActivity?)!!.showUnsupportedUrlDialog(currentUrl)
                    }
                }))
        }

        fun openAddToPlaylistDialog(currentServiceId: Int, currentUrl: String?) {
            inFlight(true)
            disposables.add(getStreamInfo(currentServiceId, currentUrl!!, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose { single: Single<StreamInfo> -> this.pleaseWait(single) }
                .subscribe(
                    { info: StreamInfo? ->
                        activityContext.ifPresent { context: AppCompatActivity? ->
                            PlaylistDialog.createCorrespondingDialog(context, listOf(StreamEntity(info!!))
                            ) { playlistDialog: PlaylistDialog ->
                                runOnVisible { ctx: AppCompatActivity? ->
                                    // dismiss listener to be handled by FragmentManager
                                    val fm = ctx!!.supportFragmentManager
                                    playlistDialog.show(fm, "addToPlaylistDialog")
                                }
                            }
                        }
                    },
                    { throwable: Throwable? ->
                        runOnVisible { ctx: AppCompatActivity? ->
                            handleError(ctx, ErrorInfo(throwable!!, UserAction.REQUESTED_STREAM, "Tried to add $currentUrl to a playlist",
                                (ctx as RouterActivity?)!!.currentService!!.serviceId)
                            )
                        }
                    }
                )
            )
        }
    }

    private fun openAddToPlaylistDialog() {
        persistFragment.openAddToPlaylistDialog(currentServiceId, currentUrl)
    }

    private fun openDownloadDialog() {
        persistFragment.openDownloadDialog(currentServiceId, currentUrl)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (i in grantResults) {
            if (i == PackageManager.PERMISSION_DENIED) {
                finish()
                return
            }
        }
        if (requestCode == PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE) {
            openDownloadDialog()
        }
    }

    class AdapterChoiceItem internal constructor(val key: String, val description: String, @field:DrawableRes val icon: Int)

    class Choice internal constructor(val serviceId: Int, val linkType: LinkType?, val url: String?, val playerChoice: String) : Serializable {
        override fun toString(): String {
            return "$serviceId:$url > $linkType ::: $playerChoice"
        }
    }

    class FetcherService : IntentService(FetcherService::class.java.simpleName) {
        private var fetcher: Disposable? = null

        override fun onCreate() {
            super.onCreate()
//            startForeground(ID, createNotification().build())
            val intent = Intent(this, FetcherService::class.java)
            startService(intent)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
//                startForeground(ID, createNotification().build(), serviceType)
//            } else {
//                startForeground(ID, createNotification().build())
//            }
        }

        override fun onHandleIntent(intent: Intent?) {
            if (intent == null) return

            val serializable = intent.getSerializableExtra(KEY_CHOICE) as? Choice ?: return
            handleChoice(serializable)
        }

        fun handleChoice(choice: Choice) {
            var single: Single<out Info>? = null
            var userAction = UserAction.SOMETHING_ELSE

            when (choice.linkType) {
                LinkType.STREAM -> {
                    single = getStreamInfo(choice.serviceId, choice.url!!, false)
                    userAction = UserAction.REQUESTED_STREAM
                }
                LinkType.CHANNEL -> {
                    single = getChannelInfo(choice.serviceId, choice.url!!, false)
                    userAction = UserAction.REQUESTED_CHANNEL
                }
                LinkType.PLAYLIST -> {
                    single = getPlaylistInfo(choice.serviceId, choice.url!!, false)
                    userAction = UserAction.REQUESTED_PLAYLIST
                }
                else -> {}
            }
            if (single != null) {
                val finalUserAction = userAction
                val resultHandler = getResultHandler(choice)
                fetcher = single
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ info: Info ->
                        resultHandler.accept(info)
                        fetcher?.dispose()
                    }, { throwable: Throwable? ->
                        handleError(this, ErrorInfo(throwable!!, finalUserAction,
                            choice.url + " opened with " + choice.playerChoice, choice.serviceId))
                    })
            }
        }

        fun getResultHandler(choice: Choice): Consumer<Info> {
            return Consumer<Info> { info: Info ->
                val videoPlayerKey = getString(R.string.video_player_key)
                val backgroundPlayerKey = getString(R.string.background_player_key)
                val popupPlayerKey = getString(R.string.popup_player_key)

                val preferences = PreferenceManager.getDefaultSharedPreferences(this)
                val isExtVideoEnabled = preferences.getBoolean(getString(R.string.use_external_video_player_key), false)
                val isExtAudioEnabled = preferences.getBoolean(getString(R.string.use_external_audio_player_key), false)

                val playQueue: PlayQueue
                when (info) {
                    is StreamInfo -> {
                        when {
                            choice.playerChoice == backgroundPlayerKey && isExtAudioEnabled -> {
                                playOnExternalAudioPlayer(this, info)
                                return@Consumer
                            }
                            choice.playerChoice == videoPlayerKey && isExtVideoEnabled -> {
                                playOnExternalVideoPlayer(this, info)
                                return@Consumer
                            }
                            else -> playQueue = SinglePlayQueue(info)
                        }
                    }
                    is ChannelInfo -> {
                        val playableTab = info.tabs
                            .stream()
                            .filter(Predicate<ListLinkHandler> { obj: ListLinkHandler -> ChannelTabHelper.isStreamsTab(obj) })
                            .findFirst()

                        if (playableTab.isPresent) {
                            playQueue = ChannelTabPlayQueue(info.getServiceId(), playableTab.get())
                        } else {
                            return@Consumer  // there is no playable tab
                        }
                    }
                    is PlaylistInfo -> {
                        playQueue = PlaylistPlayQueue(info)
                    }
                    else -> {
                        return@Consumer
                    }
                }
                when (choice.playerChoice) {
                    videoPlayerKey -> {
                        playOnMainPlayer(this, playQueue, false)
                    }
                    backgroundPlayerKey -> {
                        playOnBackgroundPlayer(this, playQueue, true)
                    }
                    popupPlayerKey -> {
                        playOnPopupPlayer(this, playQueue, true)
                    }
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            fetcher?.dispose()
        }

        private fun createNotification(): NotificationCompat.Builder {
            return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentTitle(getString(R.string.preferred_player_fetcher_notification_title))
                .setContentText(getString(R.string.preferred_player_fetcher_notification_message))
        }

        companion object {
            const val KEY_CHOICE: String = "key_choice"
            private const val ID = 456
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun getUrl(intent: Intent): String? {
        var foundUrl: String? = null
        when {
            intent.data != null -> {
                // Called from another app
                foundUrl = intent.data.toString()
            }
            intent.getStringExtra(Intent.EXTRA_TEXT) != null -> {
                // Called from the share menu
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                foundUrl = firstUrlFromInput(extraText)
            }
        }

        return foundUrl
    }

    /**
     * This class contains a dialog which shows a loading indicator and has a customizable title.
     */
    class LoadingDialog
    /**
     * Create a new LoadingDialog.
     *
     *
     *
     * The dialog contains a loading indicator and has a customizable title.
     * <br></br>
     * Use `show()` to display the dialog to the user.
     *
     *
     * @param title an informative title shown in the dialog's toolbar
     */(@field:StringRes @param:StringRes private val title: Int) : DialogFragment() {
        private var dialogLoadingBinding: DownloadLoadingDialogBinding? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Logd(TAG, "onCreate() called with: savedInstanceState = [$savedInstanceState]")
            this.isCancelable = false
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            Logd(TAG, "onCreateView() called with: inflater = [$inflater], container = [$container], savedInstanceState = [$savedInstanceState]")
            return inflater.inflate(R.layout.download_loading_dialog, container)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            dialogLoadingBinding = DownloadLoadingDialogBinding.bind(view)
            initToolbar(dialogLoadingBinding!!.toolbarLayout.toolbar)
        }

        private fun initToolbar(toolbar: Toolbar) {
            Logd(TAG, "initToolbar() called with: toolbar = [$toolbar]")

            toolbar.title = requireContext().getString(title)
            toolbar.setNavigationOnClickListener { v: View? -> dismiss() }
        }

        override fun onDestroyView() {
            dialogLoadingBinding = null
            super.onDestroyView()
        }

        companion object {
            private const val TAG = "LoadingDialog"
        }
    }

    companion object {
        /**
         * @param context the context. It will be `finish()`ed at the end of the handling if it is
         * an instance of [RouterActivity].
         * @param errorInfo the error information
         */
        private fun handleError(context: Context?, errorInfo: ErrorInfo) {
            errorInfo.throwable?.printStackTrace()

            when {
                errorInfo.throwable is ReCaptchaException -> {
                    Toast.makeText(context, R.string.recaptcha_request_toast, Toast.LENGTH_LONG).show()
                    // Starting ReCaptcha Challenge Activity
                    val intent = Intent(context, ReCaptchaActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context!!.startActivity(intent)
                }
                errorInfo.throwable != null && errorInfo.throwable!!.isNetworkRelated -> {
                    Toast.makeText(context, R.string.network_error, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is AgeRestrictedContentException -> {
                    Toast.makeText(context, R.string.restricted_video_no_stream, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is GeographicRestrictionException -> {
                    Toast.makeText(context, R.string.georestricted_content, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is PaidContentException -> {
                    Toast.makeText(context, R.string.paid_content, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is PrivateContentException -> {
                    Toast.makeText(context, R.string.private_content, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is SoundCloudGoPlusContentException -> {
                    Toast.makeText(context, R.string.soundcloud_go_plus_content, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is YoutubeMusicPremiumContentException -> {
                    Toast.makeText(context, R.string.youtube_music_premium_content, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is ContentNotAvailableException -> {
                    Toast.makeText(context, R.string.content_not_available, Toast.LENGTH_LONG).show()
                }
                errorInfo.throwable is ContentNotSupportedException -> {
                    Toast.makeText(context, R.string.content_not_supported, Toast.LENGTH_LONG).show()
                }
                else -> {
                    createNotification(context!!, errorInfo)
                }
            }

            if (context is RouterActivity) {
                context.finish()
            }
        }
    }
}
