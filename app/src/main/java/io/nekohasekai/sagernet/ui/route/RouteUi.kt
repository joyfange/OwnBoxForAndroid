package io.nekohasekai.sagernet.ui.route

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/*
 * Small helpers the routing screens ported from ThroneForAndroid rely on (Throne's ktx/Dialogs.kt and
 * widget/WindowInsetsListeners.kt), implemented on top of what OwnBox already has.
 */

/** A confirmation dialog: cancel, or [action] which runs [onConfirm]. */
internal fun Context.confirmAction(title: CharSequence, message: CharSequence?, @StringRes action: Int, onConfirm: () -> Unit) {
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(action) { _, _ -> onConfirm() }
        .show()
}

/**
 * Bottom padding for the navigation bar (and the keyboard with [ime]) on top of the view's own padding; with
 * [horizontal] also the left / right system bar insets (landscape with side navigation).
 */
internal fun View.applyListInsets(ime: Boolean = false, horizontal: Boolean = false) {
    (this as? ViewGroup)?.clipToPadding = false
    val base = paddingBottom
    val baseLeft = paddingLeft
    val baseRight = paddingRight
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        var types = WindowInsetsCompat.Type.navigationBars()
        if (ime) types = types or WindowInsetsCompat.Type.ime()
        val bars = insets.getInsets(types)
        if (horizontal) {
            val sides = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = baseLeft + sides.left, right = baseRight + sides.right, bottom = base + bars.bottom)
        } else {
            v.updatePadding(bottom = base + bars.bottom)
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
