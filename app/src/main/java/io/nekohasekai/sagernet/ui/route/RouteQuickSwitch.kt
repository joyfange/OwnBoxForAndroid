package io.nekohasekai.sagernet.ui.route

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.route.RouteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The routing-profile quick switch (the desktop Routing menu's profile list), ported from ThroneForAndroid without
 * its "Enable Warp" toggle (OwnBox has no WARP integration).
 */
object RouteQuickSwitch {

    /** A single-choice list of the route profiles; picking another one makes it current. */
    fun show(fragment: Fragment, onSwitched: ((RouteProfile) -> Unit)? = null) {
        fragment.lifecycleScope.launch {
            val (profiles, currentId) = withContext(Dispatchers.IO) {
                RouteManager.usable() to RouteManager.current().id
            }
            val context = fragment.context ?: return@launch
            val names = Array<CharSequence>(profiles.size) { profiles[it].name.ifBlank { "#" + profiles[it].id } }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.route_profile_quick_switch)
                .setSingleChoiceItems(names, profiles.indexOfFirst { it.id == currentId }) { dialog, which ->
                    dialog.dismiss()
                    val picked = profiles[which]
                    if (picked.id == DataStore.currentRouteId) return@setSingleChoiceItems
                    switchTo(picked.id)
                    fragment.snackbar(fragment.getString(R.string.route_switched, names[which])).show()
                    onSwitched?.invoke(picked)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** D15: current_route_id is global; a running service reloads at once, as the desktop restarts the profile. */
    fun switchTo(id: Long) {
        DataStore.currentRouteId = id
        if (DataStore.serviceState.started) SagerNet.reloadService()
    }
}
