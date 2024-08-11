package org.schabi.newpipe.settings.custom

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ListRadioIconItemBinding
import org.schabi.newpipe.databinding.SingleChoiceDialogViewBinding
import org.schabi.newpipe.player.notification.NotificationConstants
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.ThemeHelper.resolveColorFromAttr
import org.schabi.newpipe.ui.views.FocusOverlayView.Companion.setupFocusObserver
import java.util.*
import java.util.function.BiConsumer

internal class NotificationSlot(private val context: Context,
                                prefs: SharedPreferences,
                                private val i: Int,
                                parentView: View,
                                isCompactSlotChecked: Boolean,
                                private val onToggleCompactSlot: BiConsumer<Int, CheckBox>
) {
    @get:NotificationConstants.Action
    @NotificationConstants.Action
    var selectedAction: Int
        private set

    private var icon: ImageView? = null
    private var summary: TextView? = null

    init {
        selectedAction = Objects.requireNonNull(prefs).getInt(
            context.getString(NotificationConstants.SLOT_PREF_KEYS[i]),
            NotificationConstants.SLOT_DEFAULTS[i])
        val view = parentView.findViewById<View>(SLOT_ITEMS[i])

        // only show the last two notification slots on Android 13+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || i >= 3) {
            setupSelectedAction(view)
            setupTitle(view)
            setupCheckbox(view, isCompactSlotChecked)
        } else {
            view.visibility = View.GONE
        }
    }

    fun setupTitle(view: View) {
        (view.findViewById<View>(R.id.notificationActionTitle) as TextView)
            .setText(SLOT_TITLES[i])
        view.findViewById<View>(R.id.notificationActionClickableArea)
            .setOnClickListener { v: View? -> openActionChooserDialog() }
    }

    fun setupCheckbox(view: View, isCompactSlotChecked: Boolean) {
        val compactSlotCheckBox = view.findViewById<CheckBox>(R.id.notificationActionCheckBox)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // there are no compact slots to customize on Android 13+
            compactSlotCheckBox.visibility = View.GONE
            view.findViewById<View>(R.id.notificationActionCheckBoxClickableArea).visibility = View.GONE
            return
        }

        compactSlotCheckBox.isChecked = isCompactSlotChecked
        view.findViewById<View>(R.id.notificationActionCheckBoxClickableArea)
            .setOnClickListener { v: View? -> onToggleCompactSlot.accept(i, compactSlotCheckBox) }
    }

    fun setupSelectedAction(view: View) {
        icon = view.findViewById(R.id.notificationActionIcon)
        summary = view.findViewById(R.id.notificationActionSummary)
        updateInfo()
    }

    fun updateInfo() {
        if (NotificationConstants.ACTION_ICONS[selectedAction] == 0) {
            icon!!.setImageDrawable(null)
        } else {
            icon!!.setImageDrawable(AppCompatResources.getDrawable(context,
                NotificationConstants.ACTION_ICONS[selectedAction]))
        }

        summary!!.text = NotificationConstants.getActionName(context, selectedAction)
    }

    fun openActionChooserDialog() {
        val inflater = LayoutInflater.from(context)
        val binding =
            SingleChoiceDialogViewBinding.inflate(inflater)

        val alertDialog = AlertDialog.Builder(context)
            .setTitle(SLOT_TITLES[i])
            .setView(binding.root)
            .setCancelable(true)
            .create()

        val radioButtonsClickListener = View.OnClickListener { v: View ->
            selectedAction = NotificationConstants.ALL_ACTIONS[v.id]
            updateInfo()
            alertDialog.dismiss()
        }

        for (id in NotificationConstants.ALL_ACTIONS.indices) {
            val action = NotificationConstants.ALL_ACTIONS[id]
            val radioButton = ListRadioIconItemBinding.inflate(inflater)
                .root

            // if present set action icon with correct color
            val iconId = NotificationConstants.ACTION_ICONS[action]
            if (iconId != 0) {
                radioButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconId, 0)

                val color = ColorStateList.valueOf(resolveColorFromAttr(context, android.R.attr.textColorPrimary))
                TextViewCompat.setCompoundDrawableTintList(radioButton, color)
            }

            radioButton.text = NotificationConstants.getActionName(context, action)
            radioButton.isChecked = action == selectedAction
            radioButton.id = id
            radioButton.layoutParams = RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            radioButton.setOnClickListener(radioButtonsClickListener)
            binding.list.addView(radioButton)
        }
        alertDialog.show()

        if (isTv(context)) {
            setupFocusObserver(alertDialog)
        }
    }

    companion object {
        private val SLOT_ITEMS = intArrayOf(
            R.id.notificationAction0,
            R.id.notificationAction1,
            R.id.notificationAction2,
            R.id.notificationAction3,
            R.id.notificationAction4,
        )

        private val SLOT_TITLES = intArrayOf(
            R.string.notification_action_0_title,
            R.string.notification_action_1_title,
            R.string.notification_action_2_title,
            R.string.notification_action_3_title,
            R.string.notification_action_4_title,
        )
    }
}
