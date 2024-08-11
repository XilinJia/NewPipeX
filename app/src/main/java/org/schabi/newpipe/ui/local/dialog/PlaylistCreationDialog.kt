package org.schabi.newpipe.ui.local.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.schabi.newpipe.database.NewPipeDatabase.getInstance
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.DialogEditTextBinding
import org.schabi.newpipe.ui.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.util.ThemeHelper.getDialogTheme

class PlaylistCreationDialog : PlaylistDialog() {
    /*//////////////////////////////////////////////////////////////////////////
    // Dialog
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (streamEntities == null) {
            return super.onCreateDialog(savedInstanceState)
        }

        val dialogBinding =
            DialogEditTextBinding.inflate(layoutInflater)
        dialogBinding.root.context.setTheme(getDialogTheme(requireContext()))
        dialogBinding.dialogEditText.setHint(R.string.name)
        dialogBinding.dialogEditText.inputType = InputType.TYPE_CLASS_TEXT

        val dialogBuilder = AlertDialog.Builder(requireContext(),
            getDialogTheme(requireContext()))
            .setTitle(R.string.create_playlist)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { dialogInterface: DialogInterface?, i: Int ->
                val name = dialogBinding.dialogEditText.text.toString()
                val playlistManager =
                    LocalPlaylistManager(getInstance(requireContext()))
                val successToast = Toast.makeText(activity,
                    R.string.playlist_creation_success,
                    Toast.LENGTH_SHORT)
                if (streamEntities != null) playlistManager.createPlaylist(name, streamEntities!!)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { longs: List<Long?>? -> successToast.show() }
            }
        return dialogBuilder.create()
    }

    companion object {
        /**
         * Create a new instance of [PlaylistCreationDialog].
         *
         * @param streamEntities    a list of [StreamEntity] to be added to playlists
         * @return a new instance of [PlaylistCreationDialog]
         */
        fun newInstance(streamEntities: List<StreamEntity>?): PlaylistCreationDialog {
            val dialog = PlaylistCreationDialog()
            dialog.streamEntities = streamEntities
            return dialog
        }
    }
}
