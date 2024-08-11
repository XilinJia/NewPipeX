package org.schabi.newpipe.settings

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.DownloaderImpl.Companion.instance
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.openActivity
import org.schabi.newpipe.error.ReCaptchaActivity
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.ui.local.history.HistoryRecordManager
import org.schabi.newpipe.util.InfoCache

class HistorySettingsFragment : BasePreferenceFragment() {
    private var cacheWipeKey: String? = null
    private var viewsHistoryClearKey: String? = null
    private var playbackStatesClearKey: String? = null
    private var searchHistoryClearKey: String? = null
    private var recordManager: HistoryRecordManager? = null
    private var disposables: CompositeDisposable? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        cacheWipeKey = getString(R.string.metadata_cache_wipe_key)
        viewsHistoryClearKey = getString(R.string.clear_views_history_key)
        playbackStatesClearKey = getString(R.string.clear_playback_states_key)
        searchHistoryClearKey = getString(R.string.clear_search_history_key)
        recordManager = HistoryRecordManager(requireContext())
        disposables = CompositeDisposable()

        val clearCookiePref = requirePreference(R.string.clear_cookie_key)
        clearCookiePref.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference: Preference? ->
            defaultPreferences.edit()
                .putString(getString(R.string.recaptcha_cookies_key), "").apply()
            instance!!.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY, "")
            Toast.makeText(activity, R.string.recaptcha_cookies_cleared,
                Toast.LENGTH_SHORT).show()
            clearCookiePref.isEnabled = false
            true
        }

        if (defaultPreferences.getString(getString(R.string.recaptcha_cookies_key), "")!!.isEmpty()) {
            clearCookiePref.isEnabled = false
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == cacheWipeKey) {
            InfoCache.instance.clearCache()
            Toast.makeText(requireContext(),
                R.string.metadata_cache_wipe_complete_notice, Toast.LENGTH_SHORT).show()
        } else if (preference.key == viewsHistoryClearKey) {
            openDeleteWatchHistoryDialog(requireContext(), recordManager, disposables)
        } else if (preference.key == playbackStatesClearKey) {
            openDeletePlaybackStatesDialog(requireContext(), recordManager, disposables)
        } else if (preference.key == searchHistoryClearKey) {
            openDeleteSearchHistoryDialog(requireContext(), recordManager, disposables)
        } else {
            return super.onPreferenceTreeClick(preference)
        }
        return true
    }

    companion object {
        private fun getDeletePlaybackStatesDisposable(
                context: Context, recordManager: HistoryRecordManager?
        ): Disposable {
            return recordManager!!.deleteCompleteStreamStateHistory()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { howManyDeleted: Int? ->
                        Toast.makeText(context,
                            R.string.watch_history_states_deleted, Toast.LENGTH_SHORT).show()
                    },
                    { throwable: Throwable? ->
                        openActivity(context,
                            ErrorInfo(throwable!!, UserAction.DELETE_FROM_HISTORY,
                                "Delete playback states"))
                    })
        }

        private fun getWholeStreamHistoryDisposable(
                context: Context, recordManager: HistoryRecordManager?
        ): Disposable {
            return recordManager!!.deleteWholeStreamHistory()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { howManyDeleted: Int? ->
                        Toast.makeText(context,
                            R.string.watch_history_deleted, Toast.LENGTH_SHORT).show()
                    },
                    { throwable: Throwable? ->
                        openActivity(context,
                            ErrorInfo(throwable!!, UserAction.DELETE_FROM_HISTORY,
                                "Delete from history"))
                    })
        }

        private fun getRemoveOrphanedRecordsDisposable(
                context: Context, recordManager: HistoryRecordManager?
        ): Disposable {
            return recordManager!!.removeOrphanedRecords()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { howManyDeleted: Int? -> },
                    { throwable: Throwable? ->
                        openActivity(context,
                            ErrorInfo(throwable!!, UserAction.DELETE_FROM_HISTORY,
                                "Clear orphaned records"))
                    })
        }

        private fun getDeleteSearchHistoryDisposable(
                context: Context, recordManager: HistoryRecordManager?
        ): Disposable {
            return recordManager!!.deleteCompleteSearchHistory()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { howManyDeleted: Int? ->
                        Toast.makeText(context,
                            R.string.search_history_deleted, Toast.LENGTH_SHORT).show()
                    },
                    { throwable: Throwable? ->
                        openActivity(context,
                            ErrorInfo(throwable!!, UserAction.DELETE_FROM_HISTORY,
                                "Delete search history"))
                    })
        }

        @JvmStatic
        fun openDeleteWatchHistoryDialog(context: Context,
                                         recordManager: HistoryRecordManager?,
                                         disposables: CompositeDisposable?
        ) {
            AlertDialog.Builder(context)
                .setTitle(R.string.delete_view_history_alert)
                .setNegativeButton(R.string.cancel,
                    (DialogInterface.OnClickListener { dialog: DialogInterface, which: Int -> dialog.dismiss() }))
                .setPositiveButton(R.string.delete,
                    (DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                        disposables!!.add(getDeletePlaybackStatesDisposable(context, recordManager))
                        disposables.add(getWholeStreamHistoryDisposable(context, recordManager))
                        disposables.add(getRemoveOrphanedRecordsDisposable(context, recordManager))
                    }))
                .show()
        }

        fun openDeletePlaybackStatesDialog(context: Context,
                                           recordManager: HistoryRecordManager?,
                                           disposables: CompositeDisposable?
        ) {
            AlertDialog.Builder(context)
                .setTitle(R.string.delete_playback_states_alert)
                .setNegativeButton(R.string.cancel,
                    (DialogInterface.OnClickListener { dialog: DialogInterface, which: Int -> dialog.dismiss() }))
                .setPositiveButton(R.string.delete,
                    (DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                        disposables!!.add(
                            getDeletePlaybackStatesDisposable(context, recordManager))
                    }))
                .show()
        }

        fun openDeleteSearchHistoryDialog(context: Context,
                                          recordManager: HistoryRecordManager?,
                                          disposables: CompositeDisposable?
        ) {
            AlertDialog.Builder(context)
                .setTitle(R.string.delete_search_history_alert)
                .setNegativeButton(R.string.cancel,
                    (DialogInterface.OnClickListener { dialog: DialogInterface, which: Int -> dialog.dismiss() }))
                .setPositiveButton(R.string.delete,
                    (DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                        disposables!!.add(
                            getDeleteSearchHistoryDisposable(context, recordManager))
                    }))
                .show()
        }
    }
}
