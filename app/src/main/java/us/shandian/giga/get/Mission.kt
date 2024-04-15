package us.shandian.giga.get

import org.schabi.newpipe.streams.io.StoredFileHelper
import java.io.Serializable
import java.util.*

abstract class Mission : Serializable {
    /**
     * Source url of the resource
     */
    @JvmField
    var source: String? = null

    /**
     * Length of the current resource
     */
    open var length: Long = 0

    /**
     * creation timestamp (and maybe unique identifier)
     */
    @JvmField
    var timestamp: Long = 0

    /**
     * pre-defined content type
     */
    @JvmField
    var kind: Char = 0.toChar()

    /**
     * The downloaded file
     */
    @JvmField
    var storage: StoredFileHelper? = null

    /**
     * Delete the downloaded file
     *
     * @return `true] if and only if the file is successfully deleted, otherwise, { false}`
     */
    open fun delete(): Boolean {
        if (storage != null) return storage!!.delete()
        return true
    }

    /**
     * Indicate if this mission is deleted whatever is stored
     */
    @JvmField
    @Transient
    var deleted: Boolean = false

    override fun toString(): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return "[" + calendar.time.toString() + "] " + (if (storage!!.isInvalid) storage!!.name else storage!!.uri)
    }

    companion object {
        private const val serialVersionUID = 1L // last bump: 27 march 2019
    }
}
