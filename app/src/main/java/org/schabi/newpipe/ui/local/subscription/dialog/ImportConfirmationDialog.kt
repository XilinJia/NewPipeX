package org.schabi.newpipe.ui.local.subscription.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import icepick.Icepick
import icepick.State
import org.schabi.newpipe.R
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage

class ImportConfirmationDialog : DialogFragment() {
    @JvmField
    @State
    var resultServiceIntent: Intent? = null

    fun setResultServiceIntent(resultServiceIntent: Intent?) {
        this.resultServiceIntent = resultServiceIntent
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        assureCorrectAppLanguage(context!!)
        return AlertDialog.Builder(requireContext())
            .setMessage(R.string.import_network_expensive_warning)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { dialogInterface: DialogInterface?, i: Int ->
                if (resultServiceIntent != null && context != null) context!!.startService(resultServiceIntent)
                dismiss()
            }
            .create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkNotNull(resultServiceIntent) { "Result intent is null" }

        Icepick.restoreInstanceState(this, savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    companion object {
        @JvmStatic
        fun show(fragment: Fragment, resultServiceIntent: Intent) {
            val confirmationDialog = ImportConfirmationDialog()
            confirmationDialog.setResultServiceIntent(resultServiceIntent)
            confirmationDialog.show(fragment.parentFragmentManager, null)
        }
    }
}
