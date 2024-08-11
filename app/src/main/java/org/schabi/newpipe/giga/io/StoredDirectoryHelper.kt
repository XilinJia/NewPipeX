package org.schabi.newpipe.giga.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.nononsenseapps.filepicker.AbstractFilePickerActivity
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.settings.NewPipeSettings
import org.schabi.newpipe.util.FilePickerActivityHelper
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.stream.Collectors

class StoredDirectoryHelper(context: Context, path: Uri, @JvmField val tag: String) {
    private var ioTree: Path? = null
    private val docTree: DocumentFile?

    private val context: Context

    init {
        this.context = context
        this.docTree = DocumentFile.fromTreeUri(context, path)

        if (ContentResolver.SCHEME_FILE.equals(path.scheme, ignoreCase = true)) {
            ioTree = Paths.get(URI.create(path.toString()))
        } else {
            try {
                this.context.contentResolver.takePersistableUriPermission(path, PERMISSION_FLAGS)
            } catch (e: Exception) {
                throw IOException(e)
            }

            if (this.docTree == null) {
                throw IOException("Failed to create the tree from Uri")
            }
        }
    }

    fun createFile(filename: String, mime: String): StoredFileHelper? {
        return createFile(filename, mime, false)
    }

    fun createUniqueFile(name: String, mime: String): StoredFileHelper? {
        val matches: MutableList<String> = ArrayList()
        val filename = splitFilename(name)
        val lcFileName = filename[0].lowercase(Locale.getDefault())

        if (docTree == null) {
            try {
                Files.list(ioTree).use { stream ->
                    matches.addAll(stream.map { path: Path ->
                        path.fileName.toString().lowercase(Locale.getDefault())
                    }
                        .filter { fileName: String -> fileName.startsWith(lcFileName) }
                        .collect(Collectors.toList()))
                }
            } catch (e: IOException) {
                Log.e(TAG, "Exception while traversing $ioTree", e)
            }
        } else {
            // warning: SAF file listing is very slow
            val docTreeChildren = DocumentsContract.buildChildDocumentsUriUsingTree(
                docTree.uri, DocumentsContract.getDocumentId(docTree.uri))

            val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val selection = "(LOWER(" + DocumentsContract.Document.COLUMN_DISPLAY_NAME + ") LIKE ?%"
            val cr = context.contentResolver

            cr.query(docTreeChildren, projection, selection,
                arrayOf(lcFileName), null).use { cursor ->
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        addIfStartWith(matches, lcFileName, cursor.getString(0))
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            return createFile(name, mime, true)
        }

        // check if the filename is in use
        var lcName: String? = name.lowercase(Locale.getDefault())
        for (testName in matches) {
            if (testName == lcName) {
                lcName = null
                break
            }
        }

        // create file if filename not in use
        if (lcName != null) return createFile(name, mime, true)

        matches.sortWith { obj: String, anotherString: String? ->
            obj.compareTo(anotherString!!)
        }

        for (i in 1..999) {
            if (Collections.binarySearch(matches, makeFileName(lcFileName, i, filename[1])) < 0) {
                return createFile(makeFileName(filename[0], i, filename[1]), mime, true)
            }
        }

        return createFile(System.currentTimeMillis().toString() + filename[1], mime, false)
    }

    private fun createFile(filename: String, mime: String, safe: Boolean): StoredFileHelper? {
        val storage: StoredFileHelper

        try {
            storage = if (docTree == null && ioTree != null) {
                StoredFileHelper(ioTree!!, filename, mime)
            } else {
                StoredFileHelper(context, docTree, filename, mime, safe)
            }
        } catch (e: IOException) {
            return null
        }

        storage.tag = tag

        return storage
    }

    val uri: Uri
        get() = docTree?.uri ?: Uri.fromFile(ioTree!!.toFile())

    fun exists(): Boolean {
        return docTree?.exists() ?: Files.exists(ioTree)
    }

    val isDirect: Boolean
        /**
         * Indicates whether it's using the `java.io` API.
         *
         * @return `true` for Java I/O API, otherwise, `false` for Storage Access Framework
         */
        get() = docTree == null

    /**
     * Only using Java I/O. Creates the directory named by this abstract pathname, including any
     * necessary but nonexistent parent directories.
     * Note that if this operation fails it may have succeeded in creating some of the necessary
     * parent directories.
     *
     * @return `true` if and only if the directory was created,
     * along with all necessary parent directories or already exists; `false`
     * otherwise
     */
    fun mkdirs(): Boolean {
        if (docTree == null) {
            try {
                Files.createDirectories(ioTree)
            } catch (e: IOException) {
                Log.e(TAG, "Error while creating directories at $ioTree", e)
            }
            return Files.exists(ioTree)
        }

        if (docTree.exists()) return true

        try {
            var parent: DocumentFile?
            var child = docTree.name

            while (true) {
                parent = docTree.parentFile
                if (parent == null || child == null) break

                if (parent.exists()) return true

                parent.createDirectory(child)

                child = parent.name // for the next iteration
            }
        } catch (ignored: Exception) {
            // no more parent directories or unsupported by the storage provider
        }

        return false
    }

    fun findFile(filename: String): Uri? {
        if (docTree == null) {
            val res = ioTree!!.resolve(filename)
            return if (Files.exists(res)) Uri.fromFile(res.toFile()) else null
        }

        val res = findFileSAFHelper(context, docTree, filename)
        return res?.uri
    }

    fun canWrite(): Boolean {
        return docTree?.canWrite() ?: Files.isWritable(ioTree)
    }

    val isInvalidSafStorage: Boolean
        /**
         * @return `false` if the storage is direct, or the SAF storage is valid; `true` if
         * SAF access to this SAF storage is denied (e.g. the user clicked on `Android settings ->
         * Apps & notifications -> NewPipe -> Storage & cache -> Clear access`);
         */
        get() = docTree != null && docTree.name == null

    override fun toString(): String {
        return (docTree?.uri ?: Uri.fromFile(ioTree!!.toFile())).toString()
    }

    companion object {
        private val TAG: String = StoredDirectoryHelper::class.java.simpleName
        const val PERMISSION_FLAGS: Int = (Intent.FLAG_GRANT_READ_URI_PERMISSION
                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        ////////////////////
        //      Utils
        ///////////////////
        private fun addIfStartWith(list: MutableList<String>, base: String,
                                   str: String
        ) {
            if (Utils.isNullOrEmpty(str)) return

            val lowerStr = str.lowercase(Locale.getDefault())
            if (lowerStr.startsWith(base)) {
                list.add(lowerStr)
            }
        }

        /**
         * Splits the filename into the name and extension.
         *
         * @param filename The filename to split
         * @return A String array with the name at index 0 and extension at index 1
         */
        private fun splitFilename(filename: String): Array<String> {
            val dotIndex = filename.lastIndexOf('.')

            if (dotIndex < 0 || (dotIndex == filename.length - 1)) {
                return arrayOf(filename, "")
            }

            return arrayOf(filename.substring(0, dotIndex), filename.substring(dotIndex))
        }

        private fun makeFileName(name: String, idx: Int, ext: String): String {
            return "$name($idx)$ext"
        }

        /**
         * Fast (but not enough) file/directory finder under the storage access framework.
         *
         * @param context  The context
         * @param tree     Directory where search
         * @param filename Target filename
         * @return A [DocumentFile] contain the reference, otherwise, null
         */
        @JvmStatic
        fun findFileSAFHelper(context: Context?, tree: DocumentFile,
                              filename: String
        ): DocumentFile? {
            if (context == null) return tree.findFile(filename) // warning: this is very slow

            if (!tree.canRead()) return null // missing read permission

            val name = 0
            val documentId = 1

            // LOWER() SQL function is not supported
            val selection = DocumentsContract.Document.COLUMN_DISPLAY_NAME + " = ?"

            //final String selection = COLUMN_DISPLAY_NAME + " LIKE ?%";
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree.uri,
                DocumentsContract.getDocumentId(tree.uri))
            val projection =
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Root.COLUMN_DOCUMENT_ID)
            val contentResolver = context.contentResolver

            val lowerFilename = filename.lowercase(Locale.getDefault())

            contentResolver.query(childrenUri, projection, selection,
                arrayOf<String>(lowerFilename), null).use { cursor ->
                if (cursor == null) return null

                while (cursor.moveToNext()) {
                    if (cursor.isNull(name)
                            || !cursor.getString(name).lowercase(Locale.getDefault()).startsWith(lowerFilename)) {
                        continue
                    }

                    return DocumentFile.fromSingleUri(context,
                        DocumentsContract.buildDocumentUriUsingTree(tree.uri, cursor.getString(documentId)))
                }
            }
            return null
        }

        @JvmStatic
        fun getPicker(ctx: Context): Intent {
            return if (NewPipeSettings.useStorageAccessFramework(ctx)) {
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra("android.content.extra.SHOW_ADVANCED", true)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or PERMISSION_FLAGS)
            } else {
                Intent(ctx, FilePickerActivityHelper::class.java)
                    .putExtra(AbstractFilePickerActivity.EXTRA_ALLOW_MULTIPLE, false)
                    .putExtra(AbstractFilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true)
                    .putExtra(AbstractFilePickerActivity.EXTRA_MODE, AbstractFilePickerActivity.MODE_DIR)
            }
        }
    }
}
