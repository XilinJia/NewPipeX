package org.schabi.newpipe.settings.custom

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.player.notification.NotificationConstants
import java.util.stream.IntStream

class NotificationActionsPreference(context: Context?, attrs: AttributeSet?) : Preference(
    context!!, attrs) {
    private var notificationSlots: Array<NotificationSlot>? = null
    private var compactSlots: MutableList<Int>? = null


    init {
        layoutResource = R.layout.settings_notification
    }


    ////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ////////////////////////////////////////////////////////////////////////////
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (holder.itemView.findViewById<View>(R.id.summary) as TextView)
                .setText(R.string.notification_actions_summary_android13)
        }

        holder.itemView.isClickable = false
        setupActions(holder.itemView)
    }

    override fun onDetached() {
        super.onDetached()
        saveChanges()
        // set package to this app's package to prevent the intent from being seen outside
        context.sendBroadcast(Intent(NotificationConstants.ACTION_RECREATE_NOTIFICATION)
            .setPackage(App.PACKAGE_NAME))
    }


    ////////////////////////////////////////////////////////////////////////////
    // Setup
    ////////////////////////////////////////////////////////////////////////////
    private fun setupActions(view: View) {
        if (sharedPreferences == null) return
        compactSlots = ArrayList(NotificationConstants.getCompactSlotsFromPreferences(context, sharedPreferences!!))
        notificationSlots = IntStream.range(0, 5)
            .mapToObj<NotificationSlot> { i: Int ->
                NotificationSlot(context, sharedPreferences!!, i, view,
                    compactSlots!!.contains(i)) { i: Int, checkBox: CheckBox -> this.onToggleCompactSlot(i, checkBox) }
            }
            .toArray<NotificationSlot> { size -> arrayOfNulls<NotificationSlot>(size) }
    }

    private fun onToggleCompactSlot(i: Int, checkBox: CheckBox) {
        if (checkBox.isChecked) {
            compactSlots!!.remove(i)
        } else if (compactSlots!!.size < 3) {
            compactSlots!!.add(i)
        } else {
            Toast.makeText(context,
                R.string.notification_actions_at_most_three,
                Toast.LENGTH_SHORT).show()
            return
        }

        checkBox.toggle()
    }


    ////////////////////////////////////////////////////////////////////////////
    // Saving
    ////////////////////////////////////////////////////////////////////////////
    private fun saveChanges() {
        if (compactSlots != null && notificationSlots != null) {
            val editor = sharedPreferences!!.edit()

            for (i in 0..2) {
                editor.putInt(context.getString(
                    NotificationConstants.SLOT_COMPACT_PREF_KEYS[i]),
                    (if (i < compactSlots!!.size) compactSlots!![i] else -1))
            }

            for (i in 0..4) {
                editor.putInt(context.getString(NotificationConstants.SLOT_PREF_KEYS[i]),
                    notificationSlots!![i].selectedAction)
            }

            editor.apply()
        }
    }
}
