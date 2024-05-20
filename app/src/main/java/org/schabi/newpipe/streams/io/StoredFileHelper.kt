package org.schabi.newpipe.streams.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.nononsenseapps.filepicker.AbstractFilePickerActivity
import com.nononsenseapps.filepicker.Utils
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.settings.NewPipeSettings
import org.schabi.newpipe.streams.io.StoredDirectoryHelper.Companion.findFileSAFHelper
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.FilePickerActivityHelper
import org.schabi.newpipe.util.FilePickerActivityHelper.Companion.isOwnFileUri
import org.schabi.newpipe.util.Logd
import us.shandian.giga.io.FileStream
import us.shandian.giga.io.FileStreamSAF
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

class StoredFileHelper : Serializable {
    @Transient
    private var docFile: DocumentFile? = null

    @Transient
    private var docTree: DocumentFile? = null

    @Transient
    private var ioPath: Path? = null

    @Transient
    private var context: Context? = null

    protected var source: String? = null
    private var sourceTree: String? = null

    var tag: String? = null

    private var srcName: String? = null
    private var srcType: String? = null

    constructor(context: Context?, uri: Uri, mime: String?) {
        if (isOwnFileUri(context!!, uri)) {
            val ioFile = Utils.getFileForUri(uri)
            ioPath = ioFile.toPath()
            source = Uri.fromFile(ioFile).toString()
        } else {
            docFile = DocumentFile.fromSingleUri(context, uri)
            source = uri.toString()
        }

        this.context = context
        this.srcType = mime
    }

    constructor(parent: Uri?, filename: String?, mime: String?, tag: String?) {
        this.source = null // this instance will be "invalid" see invalidate()/isInvalid() methods

        this.srcName = filename
        this.srcType = mime ?: DEFAULT_MIME
        this.sourceTree = parent?.toString()

        this.tag = tag
    }

    internal constructor(context: Context?, tree: DocumentFile?, filename: String?, mime: String?, safe: Boolean) {
        this.docTree = tree
        this.context = context

        val res: DocumentFile?

        if (safe) {
            // no conflicts (the filename is not in use)
            res = docTree!!.createFile(mime!!, filename!!)
            if (res == null) throw IOException("Cannot create the file")
        } else {
            res = createSAF(context, mime, filename)
        }

        this.docFile = res

        this.source = docFile!!.uri.toString()
        this.sourceTree = docTree!!.uri.toString()

        this.srcName = docFile!!.name
        this.srcType = docFile!!.type
    }

    internal constructor(location: Path, filename: String?, mime: String?) {
        ioPath = location.resolve(filename)

        Files.deleteIfExists(ioPath)
        Files.createFile(ioPath)

        if (ioPath != null) source = Uri.fromFile(ioPath!!.toFile()).toString()
        sourceTree = Uri.fromFile(location.toFile()).toString()

        if (ioPath != null) srcName = ioPath!!.getFileName().toString()
        srcType = mime
    }

    constructor(context: Context?, parent: Uri?, path: Uri, tag: String?) {
        this.tag = tag
        this.source = path.toString()

        if (path.scheme == null || path.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true)) {
            this.ioPath = Paths.get(URI.create(this.source))
        } else {
            val file = DocumentFile.fromSingleUri(context!!, path) ?: throw IOException("SAF not available")

            this.context = context

            if (file.name == null) {
                this.source = null
                return
            } else {
                this.docFile = file
                takePermissionSAF()
            }
        }

        if (parent != null) {
            if (ContentResolver.SCHEME_FILE != parent.scheme) this.docTree = DocumentFile.fromTreeUri(context!!, parent)

            this.sourceTree = parent.toString()
        }

        this.srcName = this.name
        this.srcType = this.type
    }


    @get:Throws(IOException::class)
    val stream: SharpStream
        get() {
            assertValid()

            return if (docFile == null) FileStream(ioPath!!.toFile())
            else FileStreamSAF(context!!.contentResolver, docFile!!.uri)
        }

    val isDirect: Boolean
        /**
         * Indicates whether it's using the `java.io` API.
         *
         * @return `true` for Java I/O API, otherwise, `false` for Storage Access Framework
         */
        get() {
            assertValid()
            return docFile == null
        }

    val isInvalid: Boolean
        get() = source == null

    val uri: Uri
        get() {
            assertValid()
            return if (docFile == null) Uri.fromFile(ioPath!!.toFile()) else docFile!!.uri
        }

    val parentUri: Uri?
        get() {
            assertValid()
            return if (sourceTree == null) null else Uri.parse(sourceTree)
        }

    @Throws(IOException::class)
    fun truncate() {
        assertValid()

        stream.use { fs ->
            fs.setLength(0)
        }
    }

    fun delete(): Boolean {
        if (source == null) return true

        if (docFile == null) {
            try {
                return Files.deleteIfExists(ioPath)
            } catch (e: IOException) {
                Log.e(TAG, "Exception while deleting $ioPath", e)
                return false
            }
        }

        val res = docFile!!.delete()

        try {
            val flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context!!.contentResolver.releasePersistableUriPermission(docFile!!.uri, flags)
        } catch (ex: Exception) {
            // nothing to do
        }

        return res
    }

    fun length(): Long {
        assertValid()

        if (docFile == null) {
            try {
                return Files.size(ioPath)
            } catch (e: IOException) {
                Log.e(TAG, "Exception while getting the size of $ioPath", e)
                return 0
            }
        } else {
            return docFile!!.length()
        }
    }

    fun canWrite(): Boolean {
        if (source == null) return false

        return if (docFile == null) Files.isWritable(ioPath) else docFile!!.canWrite()
    }

    val name: String?
        get() {
            when {
                source == null -> {
                    return srcName
                }
                docFile == null -> {
                    return ioPath!!.fileName.toString()
                }
                else -> {
                    val name = docFile!!.name
                    return name ?: srcName
                }
            }
        }

    val type: String?
        get() {
            if (source == null || docFile == null) return srcType

            val type = docFile!!.type
            return type ?: srcType
        }

    fun existsAsFile(): Boolean {
        if (source == null || (docFile == null && ioPath == null)) {
            Logd(TAG, "existsAsFile called but something is null: source = [${if (source == null) "null => storage is invalid" else source}], docFile = [$docFile], ioPath = [$ioPath]")
            return false
        }

        // WARNING: DocumentFile.exists() and DocumentFile.isFile() methods are slow
        // docFile.isVirtual() means it is non-physical?
        return if (docFile == null) Files.isRegularFile(ioPath) else (docFile!!.exists() && docFile!!.isFile)
    }

    fun create(): Boolean {
        assertValid()
        val result: Boolean

        when {
            docFile == null -> {
                try {
                    Files.createFile(ioPath)
                    result = true
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while creating $ioPath", e)
                    return false
                }
            }
            docTree == null -> {
                result = false
            }
            else -> {
                if (!docTree!!.canRead() || !docTree!!.canWrite()) return false

                try {
                    docFile = createSAF(context, srcType, srcName)
                    if (docFile?.name == null) return false
                    result = true
                } catch (e: IOException) {
                    return false
                }
            }
        }

        if (result) {
            source = (if (docFile == null) Uri.fromFile(ioPath!!.toFile()) else docFile!!.uri).toString()
            srcName = name
            srcType = type
        }

        return result
    }

    fun invalidate() {
        if (source == null) return

        srcName = name
        srcType = type

        source = null

        docTree = null
        docFile = null
        ioPath = null
        context = null
    }

    fun equals(storage: StoredFileHelper): Boolean {
        if (this === storage) return true

        // note: do not compare tags, files can have the same parent folder
        //if (stringMismatch(this.tag, storage.tag)) return false;
        if (stringMismatch(getLowerCase(this.sourceTree), getLowerCase(this.sourceTree))) return false

        if (this.isInvalid || storage.isInvalid) {
            if (this.srcName == null || (storage.srcName == null) || (this.srcType == null) || (storage.srcType == null)) return false
            return (srcName.equals(storage.srcName, ignoreCase = true) && srcType.equals(storage.srcType, ignoreCase = true))
        }

        if (this.isDirect != storage.isDirect) return false
        if (this.isDirect) return this.ioPath == storage.ioPath

        return DocumentsContract.getDocumentId(docFile!!.uri).equals(DocumentsContract.getDocumentId(storage.docFile!!.uri), ignoreCase = true)
    }

    override fun toString(): String {
        return if (source == null) {
            "[Invalid state] name=$srcName  type=$srcType  tag=$tag"
        } else {
            ("sourceFile=$source  treeSource=${if (sourceTree == null) "" else sourceTree}  tag=$tag")
        }
    }

    private fun assertValid() {
        checkNotNull(source) { "In invalid state" }
    }

    @Throws(IOException::class)
    private fun takePermissionSAF() {
        try {
            context!!.contentResolver.takePersistableUriPermission(docFile!!.uri, StoredDirectoryHelper.PERMISSION_FLAGS)
        } catch (e: Exception) {
            if (docFile?.name == null) throw IOException(e)
        }
    }

    @Throws(IOException::class)
    private fun createSAF(ctx: Context?, mime: String?, filename: String?): DocumentFile {
        var res = findFileSAFHelper(ctx, docTree!!, filename!!)

        if (res != null && res.exists() && res.isDirectory) {
            if (!res.delete()) throw IOException("Directory with the same name found but cannot delete")
            res = null
        }

        if (res == null) {
            res = docTree!!.createFile((if (srcType == null) DEFAULT_MIME else mime)!!, filename)
            if (res == null) throw IOException("Cannot create the file")
        }

        return res
    }

    private fun getLowerCase(str: String?): String? {
        return str?.lowercase(Locale.getDefault())
    }

    private fun stringMismatch(str1: String?, str2: String?): Boolean {
        if (str1 == null && str2 == null) return false
        if ((str1 == null) != (str2 == null)) return true

        return str1 != str2
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG: String = StoredFileHelper::class.java.simpleName

        private const val serialVersionUID = 0L
        const val DEFAULT_MIME: String = "application/octet-stream"

        @JvmStatic
        @Throws(IOException::class)
        fun deserialize(storage: StoredFileHelper, context: Context?): StoredFileHelper {
            val treeUri = if (storage.sourceTree == null) null else Uri.parse(storage.sourceTree)

            if (storage.isInvalid) return StoredFileHelper(treeUri, storage.srcName, storage.srcType, storage.tag)

            val instance = StoredFileHelper(context, treeUri, Uri.parse(storage.source), storage.tag)

            // under SAF, if the target document is deleted, conserve the filename and mime
            if (instance.srcName == null) instance.srcName = storage.srcName
            if (instance.srcType == null) instance.srcType = storage.srcType

            return instance
        }

        @JvmStatic
        fun getPicker(ctx: Context, mimeType: String): Intent {
            Logd(TAG, "getPicker mimeType: $mimeType")
            return if (NewPipeSettings.useStorageAccessFramework(ctx)) {
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .putExtra("android.content.extra.SHOW_ADVANCED", true)
                    .setType(mimeType)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or StoredDirectoryHelper.PERMISSION_FLAGS)
            } else {
                Intent(ctx, FilePickerActivityHelper::class.java)
                    .putExtra(AbstractFilePickerActivity.EXTRA_ALLOW_MULTIPLE, false)
                    .putExtra(AbstractFilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true)
                    .putExtra(AbstractFilePickerActivity.EXTRA_SINGLE_CLICK, true)
                    .putExtra(AbstractFilePickerActivity.EXTRA_MODE, AbstractFilePickerActivity.MODE_FILE)
            }
        }

        @JvmStatic
        fun getPicker(ctx: Context, mimeType: String, initialPath: Uri?): Intent {
            return applyInitialPathToPickerIntent(ctx, getPicker(ctx, mimeType), initialPath, null)
        }

        @JvmStatic
        fun getNewPicker(ctx: Context, filename: String?, mimeType: String, initialPath: Uri?): Intent {
            val i: Intent
            if (NewPipeSettings.useStorageAccessFramework(ctx)) {
                i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .putExtra("android.content.extra.SHOW_ADVANCED", true)
                    .setType(mimeType)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or StoredDirectoryHelper.PERMISSION_FLAGS)
                if (filename != null) {
                    i.putExtra(Intent.EXTRA_TITLE, filename)
                }
            } else {
                i = Intent(ctx, FilePickerActivityHelper::class.java)
                    .putExtra(AbstractFilePickerActivity.EXTRA_ALLOW_MULTIPLE, false)
                    .putExtra(AbstractFilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true)
                    .putExtra(AbstractFilePickerActivity.EXTRA_ALLOW_EXISTING_FILE, true)
                    .putExtra(AbstractFilePickerActivity.EXTRA_MODE, AbstractFilePickerActivity.MODE_NEW_FILE)
            }
            return applyInitialPathToPickerIntent(ctx, i, initialPath, filename)
        }

        private fun applyInitialPathToPickerIntent(ctx: Context, intent: Intent, initialPath: Uri?, filename: String?): Intent {
            if (NewPipeSettings.useStorageAccessFramework(ctx)) {
                if (initialPath == null) return intent // nothing to do, no initial path provided

                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialPath)
                } else {
                    intent // can't set initial path on API < 26
                }
            } else {
                if (initialPath == null && filename == null) return intent // nothing to do, no initial path and no file name provided

                var file: File?
                file = if (initialPath == null) {
                    // The only way to set the previewed filename in non-SAF FilePicker is to set a
                    // starting path ending with that filename. So when the initialPath is null but
                    // filename isn't just default to the external storage directory.
                    Environment.getExternalStorageDirectory()
                } else {
                    try {
                        Utils.getFileForUri(initialPath)
                    } catch (ignored: Throwable) {
                        // getFileForUri() can't decode paths to 'storage', fallback to this
                        File(initialPath.toString())
                    }
                }

                // remove any filename at the end of the path (get the parent directory in that case)
                if (!file!!.exists() || !file.isDirectory) {
                    file = file.parentFile
                    if (file == null || !file.exists()) {
                        // default to the external storage directory in case of an invalid path
                        file = Environment.getExternalStorageDirectory()
                    }
                    // else: file is surely a directory
                }

                if (filename != null) {
                    // append a filename so that the non-SAF FilePicker shows it as the preview
                    file = File(file, filename)
                }

                return intent
                    .putExtra(AbstractFilePickerActivity.EXTRA_START_PATH, file!!.absolutePath)
            }
        }
    }
}
