package org.schabi.newpipe.util

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.loader.content.Loader
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import com.nononsenseapps.filepicker.AbstractFilePickerFragment
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.FilePickerFragment
import org.schabi.newpipe.R
import java.io.File

class FilePickerActivityHelper : FilePickerActivity() {
    private var currentFragment: CustomFilePickerFragment? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        if (ThemeHelper.isLightThemeSelected(this)) this.setTheme(R.style.FilePickerThemeLight)
        else this.setTheme(R.style.FilePickerThemeDark)

        super.onCreate(savedInstanceState)
    }

    override fun onBackPressed() {
        // If at top most level, normal behaviour
        if (currentFragment!!.isBackTop()) super.onBackPressed()
        else currentFragment!!.goUp()    // Else go up
    }

    override fun getFragment(startPath: String?, mode: Int, allowMultiple: Boolean, allowCreateDir: Boolean, allowExistingFile: Boolean, singleClick: Boolean)
    : AbstractFilePickerFragment<File> {

        val fragment = CustomFilePickerFragment()
        fragment.setArgs(startPath ?: Environment.getExternalStorageDirectory().path, mode, allowMultiple, allowCreateDir, allowExistingFile, singleClick)
        currentFragment = fragment
        return currentFragment!!
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Internal
    ////////////////////////////////////////////////////////////////////////// */
    class CustomFilePickerFragment : FilePickerFragment() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val viewHolder = super.onCreateViewHolder(parent, viewType)

            val view = viewHolder.itemView.findViewById<View>(android.R.id.text1)
            if (view is TextView) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.file_picker_items_text_size))

            return viewHolder
        }

        override fun onClickOk(view: View) {
            if (mode == MODE_NEW_FILE && newFileName.isEmpty()) {
                if (mToast != null) mToast.cancel()

                mToast = Toast.makeText(activity, R.string.file_name_empty_error, Toast.LENGTH_SHORT)
                mToast.show()
                return
            }

            super.onClickOk(view)
        }

        override fun isItemVisible(file: File): Boolean {
            if (file.isDirectory && file.isHidden) return true

            return super.isItemVisible(file)
        }

        val backTop: File
            get() {
                if (arguments == null) return Environment.getExternalStorageDirectory()

                val path = requireArguments().getString(KEY_START_PATH, "/")
                if (path.contains(Environment.getExternalStorageDirectory().path)) return Environment.getExternalStorageDirectory()

                return getPath(path)
            }

        fun isBackTop(): Boolean {
            return compareFiles(mCurrentPath, backTop) == 0 || compareFiles(mCurrentPath, File("/")) == 0
        }

        override fun onLoadFinished(loader: Loader<SortedList<File>>, data: SortedList<File>) {
            super.onLoadFinished(loader, data)
            layoutManager.scrollToPosition(0)
        }
    }

    companion object {
        @JvmStatic
        fun isOwnFileUri(context: Context, uri: Uri): Boolean {
            if (uri.authority == null) return false

            return uri.authority!!.startsWith(context.packageName)
        }
    }
}
