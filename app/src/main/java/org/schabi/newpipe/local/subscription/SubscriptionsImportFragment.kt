package org.schabi.newpipe.local.subscription

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.util.LinkifyCompat
import icepick.State
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor.ContentSource
import org.schabi.newpipe.local.subscription.ImportConfirmationDialog.Companion.show
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard.launchSafe
import org.schabi.newpipe.streams.io.StoredFileHelper.Companion.getPicker
import org.schabi.newpipe.util.KEY_SERVICE_ID
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.ServiceHelper.getImportInstructions
import org.schabi.newpipe.util.ServiceHelper.getImportInstructionsHint
import org.schabi.newpipe.util.ServiceHelper.getNameOfServiceById

class SubscriptionsImportFragment : BaseFragment() {
    @JvmField
    @State
    var currentServiceId: Int = NO_SERVICE_ID

    private var supportedSources: List<ContentSource>? = null
    private var relatedUrl: String? = null

    @StringRes
    private var instructionsString = 0

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
    private var infoTextView: TextView? = null
    private var inputText: EditText? = null
    private var inputButton: Button? = null

    private val requestImportFileLauncher =
        registerForActivityResult<Intent, ActivityResult>(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            this.requestImportFileResult(result)
        }

    private fun setInitialData(serviceId: Int) {
        this.currentServiceId = serviceId
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle
    ///////////////////////////////////////////////////////////////////////////
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupServiceVariables()
        if (supportedSources!!.isEmpty() && currentServiceId != NO_SERVICE_ID) {
            showSnackbar(requireActivity(),
                ErrorInfo(arrayOf(), UserAction.SUBSCRIPTION_IMPORT_EXPORT,
                    getNameOfServiceById(currentServiceId),
                    "Service does not support importing subscriptions",
                    R.string.general_error))
            requireActivity().finish()
        }
    }

    override fun onResume() {
        super.onResume()
        setTitle(getString(R.string.import_title))
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_import, container, false)
    }

    /*/////////////////////////////////////////////////////////////////////////
    // Fragment Views
    ///////////////////////////////////////////////////////////////////////// */
    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        inputButton = rootView!!.findViewById(R.id.input_button)
        inputText = rootView.findViewById(R.id.input_text)

        infoTextView = rootView.findViewById(R.id.info_text_view)

        // TODO: Support services that can import from more than one source
        //  (show the option to the user)
        if (supportedSources!!.contains(ContentSource.CHANNEL_URL)) {
            inputButton?.setText(R.string.import_title)
            inputText?.setVisibility(View.VISIBLE)
            inputText?.setHint(getImportInstructionsHint(currentServiceId))
        } else {
            inputButton?.setText(R.string.import_file_title)
        }

        if (instructionsString != 0) {
            if (TextUtils.isEmpty(relatedUrl)) {
                setInfoText(getString(instructionsString))
            } else {
                setInfoText(getString(instructionsString, relatedUrl))
            }
        } else {
            setInfoText("")
        }

        val supportActionBar = activity?.supportActionBar
        if (supportActionBar != null) {
            supportActionBar.setDisplayShowTitleEnabled(true)
            setTitle(getString(R.string.import_title))
        }
    }

    override fun initListeners() {
        super.initListeners()
        inputButton!!.setOnClickListener { v: View? -> onImportClicked() }
    }

    private fun onImportClicked() {
        if (inputText!!.visibility == View.VISIBLE) {
            val value = inputText!!.text.toString()
            if (!value.isEmpty()) {
                onImportUrl(value)
            }
        } else {
            onImportFile()
        }
    }

    fun onImportUrl(value: String?) {
        show(this, Intent(activity, SubscriptionsImportService::class.java)
            .putExtra(SubscriptionsImportService.KEY_MODE, SubscriptionsImportService.CHANNEL_URL_MODE)
            .putExtra(SubscriptionsImportService.KEY_VALUE, value)
            .putExtra(KEY_SERVICE_ID, currentServiceId))
    }

    fun onImportFile() {
        launchSafe(
            requestImportFileLauncher,  // leave */* mime type to support all services
            // with different mime types and file extensions
            getPicker(requireActivity(), "*/*"),
            TAG,
            context
        )
    }

    private fun requestImportFileResult(result: ActivityResult) {
        if (result.data == null) {
            return
        }

        if (result.resultCode == Activity.RESULT_OK && result.data!!.data != null) {
            show(this,
                Intent(activity, SubscriptionsImportService::class.java)
                    .putExtra(SubscriptionsImportService.KEY_MODE, SubscriptionsImportService.INPUT_STREAM_MODE)
                    .putExtra(SubscriptionsImportService.KEY_VALUE, result.data!!.data)
                    .putExtra(KEY_SERVICE_ID, currentServiceId))
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Subscriptions
    ///////////////////////////////////////////////////////////////////////////
    private fun setupServiceVariables() {
        if (currentServiceId != NO_SERVICE_ID) {
            try {
                val extractor = NewPipe.getService(currentServiceId)
                    .subscriptionExtractor
                supportedSources = extractor.supportedSources
                relatedUrl = extractor.relatedUrl
                instructionsString = getImportInstructions(currentServiceId)
                return
            } catch (ignored: ExtractionException) {
            }
        }

        supportedSources = emptyList()
        relatedUrl = null
        instructionsString = 0
    }

    private fun setInfoText(infoString: String) {
        infoTextView!!.text = infoString
        LinkifyCompat.addLinks(infoTextView!!, Linkify.WEB_URLS)
    }

    companion object {
        fun getInstance(serviceId: Int): SubscriptionsImportFragment {
            val instance = SubscriptionsImportFragment()
            instance.setInitialData(serviceId)
            return instance
        }
    }
}
