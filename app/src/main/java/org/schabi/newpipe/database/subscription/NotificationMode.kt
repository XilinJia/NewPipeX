package org.schabi.newpipe.database.subscription

import androidx.annotation.IntDef
import org.schabi.newpipe.database.subscription.NotificationMode

@IntDef(NotificationMode.DISABLED, NotificationMode.ENABLED)
@Retention(AnnotationRetention.SOURCE)
annotation class NotificationMode {
    companion object {
        const val DISABLED: Int = 0
        const val ENABLED: Int = 1 //other values reserved for the future
    }
}
