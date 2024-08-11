package org.schabi.newpipe.ui.local.dialog

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.database.NewPipeDatabase.getInstance
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.ui.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.player.PlayerManager
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.util.StateSaver.WriteRead
import org.schabi.newpipe.util.StateSaver.onDestroy
import org.schabi.newpipe.util.StateSaver.tryToRestore
import org.schabi.newpipe.util.StateSaver.tryToSave
import java.util.*
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.Stream

abstract class PlaylistDialog : DialogFragment(), WriteRead {
    /*//////////////////////////////////////////////////////////////////////////
    // Getter + Setter
    ////////////////////////////////////////////////////////////////////////// */
    @JvmField
    var onDismissListener: DialogInterface.OnDismissListener? = null

    var streamEntities: List<StreamEntity>? = null
        protected set

    private var savedState: org.schabi.newpipe.util.SavedState? = null

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedState = tryToRestore(savedInstanceState, this)
    }

    override fun onDestroy() {
        super.onDestroy()
        onDestroy(savedState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        //remove title
        val window = dialog.window
        window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (onDismissListener != null) {
            onDismissListener!!.onDismiss(dialog)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    ////////////////////////////////////////////////////////////////////////// */
    override fun generateSuffix(): String {
        val size = if (streamEntities == null) 0 else streamEntities!!.size
        return ".$size.list"
    }

    override fun writeTo(objectsToSave: Queue<Any>?) {
        objectsToSave!!.add(streamEntities)
    }

    override fun readFrom(savedObjects: Queue<Any>) {
        streamEntities = savedObjects.poll() as List<StreamEntity>
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (activity != null) {
            savedState = tryToSave(requireActivity().isChangingConfigurations, savedState, outState, this)
        }
    }

    companion object {
        /*//////////////////////////////////////////////////////////////////////////
    // Dialog creation
    ////////////////////////////////////////////////////////////////////////// */
        /**
         * Creates a [PlaylistAppendDialog] when playlists exists,
         * otherwise a [PlaylistCreationDialog].
         *
         * @param context        context used for accessing the database
         * @param streamEntities used for crating the dialog
         * @param onExec         execution that should occur after a dialog got created, e.g. showing it
         * @return the disposable that was created
         */
        fun createCorrespondingDialog(
                context: Context?,
                streamEntities: List<StreamEntity>?,
                onExec: Consumer<PlaylistDialog>
        ): Disposable {
            return LocalPlaylistManager(getInstance(context!!))
                .hasPlaylists()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { hasPlaylists: Boolean ->
                    onExec.accept(if (hasPlaylists
                    ) PlaylistAppendDialog.newInstance(streamEntities)
                    else PlaylistCreationDialog.newInstance(streamEntities))
                }
        }

        /**
         * Creates a [PlaylistAppendDialog] when playlists exists,
         * otherwise a [PlaylistCreationDialog]. If the player's play queue is null or empty, no
         * dialog will be created.
         *
         * @param playerManager          the player from which to extract the context and the play queue
         * @param fragmentManager the fragment manager to use to show the dialog
         * @return the disposable that was created
         */
        @JvmStatic
        fun showForPlayQueue(
                playerManager: PlayerManager,
                fragmentManager: FragmentManager
        ): Disposable {
            val streamEntities = Stream.of(playerManager.playQueue)
                .filter { obj: PlayQueue? -> Objects.nonNull(obj) }
                .flatMap { playQueue: PlayQueue? -> playQueue!!.streams.stream() }
                .map { item: PlayQueueItem? ->
                    StreamEntity(
                        item!!)
                }
                .collect(Collectors.toList())
            if (streamEntities.isEmpty()) {
                return Disposable.empty()
            }

            return createCorrespondingDialog(playerManager.context, streamEntities
            ) { dialog: PlaylistDialog -> dialog.show(fragmentManager, "PlaylistDialog") }
        }
    }
}
