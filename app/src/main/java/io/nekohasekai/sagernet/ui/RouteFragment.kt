package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.RouteRepo
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteProfileItemBinding
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteShare
import io.nekohasekai.sagernet.route.RuleType
import io.nekohasekai.sagernet.ui.route.RouteImports
import io.nekohasekai.sagernet.route.RouteJson
import io.nekohasekai.sagernet.ui.route.RouteProfileActivity
import io.nekohasekai.sagernet.ui.route.RouteQuickSwitch
import io.nekohasekai.sagernet.ui.route.RouteTexts
import io.nekohasekai.sagernet.widget.ListListener
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.ui.route.confirmAction
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ported from ThroneForAndroid (ui/RouteFragment.kt); OwnBox drops Throne's "update rule-sets" (core call) and QR
 * scan entries, and its routing-settings item opens OwnBox's own settings screen.
 *
 * The route profiles (the desktop Routes dialog): tap to make one current, per-profile edit / clone / share /
 * update / delete, and the toolbar's new, import, download, update and repository actions.
 */
class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    private lateinit var list: RecyclerView
    private val adapter = ProfileAdapter()
    private val onRoutesChanged: () -> Unit = { reload() }
    private var progressDialog: AlertDialog? = null
    private var remoteUpdateJob: Job? = null

    /** Remote profiles whose update is running. */
    private val updating = HashSet<Long>()

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val id = result.data?.getLongExtra(RouteProfileActivity.EXTRA_SAVED_ID, 0L) ?: 0L
        // D15: editing the active profile prompts a reload like other config-affecting settings.
        if (id != 0L && id == DataStore.currentRouteId) needReload()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_route)
        toolbar?.inflateMenu(R.menu.route_menu)
        toolbar?.setOnMenuItemClickListener(this)
        list = view.findViewById(R.id.route_list)
        // As the old OwnBox route list: the root pads the navigation bar, the list pads OwnBox's bottom bar.
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        list.layoutManager = FixedLinearLayoutManager(list)
        list.adapter = adapter
        updateBottomPadding()
        RouteManager.addListener(onRoutesChanged)
        reload()
    }

    override fun onDestroyView() {
        RouteManager.removeListener(onRoutesChanged)
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroyView()
    }

    /** OwnBox's bottom stats bar overlaps the list unless it reserves room for it (MainActivity calls this). */
    fun updateBottomPadding() {
        if (!::list.isInitialized) return
        list.clipToPadding = false
        list.updatePadding(bottom = dp2px(if (DataStore.showBottomBar) 80 else 4))
    }

    private fun reload() {
        runOnLifecycleDispatcher {
            val profiles = RouteManager.all()
            val currentId = RouteManager.current().id
            onMainDispatcher {
                if (view != null) adapter.submit(profiles, currentId)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_route_new_structured -> newProfile(remote = false)
            R.id.action_route_new_remote -> newProfile(remote = true)
            R.id.action_route_import -> importFromClipboard()
            R.id.action_route_download -> downloadProfiles()
            R.id.action_route_update_all -> updateAll()
            R.id.action_route_refresh_repo -> refreshRepository()
            R.id.action_route_manage_assets -> startActivity(Intent(requireContext(), AssetsActivity::class.java))
            R.id.action_routing_settings -> (activity as? MainActivity)?.let {
                it.displayFragmentWithId(R.id.nav_settings)
                it.setCheckedItem(R.id.nav_settings)
            }

            else -> return false
        }
        return true
    }

    private fun message(title: Int, text: String) {
        val context = context ?: return
        RouteImports.showMessage(context, title, text)
    }

    private fun newProfile(remote: Boolean) {
        editor.launch(Intent(requireContext(), RouteProfileActivity::class.java).putExtra(RouteProfileActivity.EXTRA_REMOTE, remote))
    }

    private fun edit(id: Long) {
        editor.launch(Intent(requireContext(), RouteProfileActivity::class.java).putExtra(RouteProfileActivity.EXTRA_PROFILE_ID, id))
    }

    private fun select(profile: RouteProfile) {
        if (profile.is_raw) {
            snackbar(R.string.route_raw_not_usable).show()
            return
        }
        if (profile.id == adapter.currentId) return
        RouteQuickSwitch.switchTo(profile.id)
        adapter.setCurrent(profile.id)
        snackbar(getString(R.string.route_switched, profile.name)).show()
    }

    private fun clone(id: Long) = runOnLifecycleDispatcher {
        val profile = RouteManager.get(id) ?: return@runOnLifecycleDispatcher
        val copy = profile.copy().apply {
            this.id = 0L
            name = profile.name + " clone"
        }
        RouteManager.save(copy)
        onMainDispatcher {
            if (isAdded) snackbar(getString(R.string.route_cloned, copy.name)).show()
        }
    }

    /** ToShareLink; rules whose server is gone are left out, and app rules are Android-only, so both are pointed out. */
    private fun share(id: Long, qr: Boolean) = runOnLifecycleDispatcher {
        val profile = RouteManager.get(id) ?: return@runOnLifecycleDispatcher
        val names = HashMap<Long, String?>()
        val nameOf: (Long) -> String? = { server -> names.getOrPut(server) { RouteManager.profileName(server) } }
        val link = RouteShare.toShareLink(profile, nameOf)
        val shared = profile.rules.filter { rule ->
            val type = RuleType.ofId(rule.type)
            type != RuleType.ENDPOINT_PREFERRED_BY && !(type != RuleType.CUSTOM && rule.isEmpty())
        }
        val dropped = shared.count { it.toRuleJson(true, null, nameOf).isEmpty() }
        val appRules = shared.any { rule ->
            rule.package_name.any { it.isNotBlank() } && rule.toRuleJson(true, null, nameOf).isNotEmpty()
        }
        onMainDispatcher {
            if (!isAdded) return@onMainDispatcher
            val warnings = ArrayList<String>()
            if (dropped > 0) warnings.add(resources.getQuantityString(R.plurals.route_export_dropped, dropped, dropped))
            if (appRules) warnings.add(getString(R.string.route_export_package_note))
            if (qr) {
                QRCodeDialog(link, profile.name).showAllowingStateLoss(parentFragmentManager)
                if (warnings.isNotEmpty()) snackbar(warnings.joinToString("\n")).show()
                return@onMainDispatcher
            }
            if (!SagerNet.trySetPrimaryClip(link)) {
                snackbar(R.string.action_export_err).show()
            } else if (warnings.isEmpty()) {
                snackbar(R.string.route_link_copied).show()
            } else {
                message(R.string.route_export_warnings_title, getString(R.string.route_link_copied) + "\n\n" + warnings.joinToString("\n\n"))
            }
        }
    }

    private fun delete(profile: RouteProfile) {
        if (adapter.itemCount <= 1) {
            lastProfileError()
            return
        }
        fun doDelete() = runOnLifecycleDispatcher {
            val wasCurrent = DataStore.currentRouteId == profile.id
            val deleted = RouteManager.delete(profile.id)
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                if (!deleted) lastProfileError() else if (wasCurrent) needReload()
            }
        }
        requireContext().confirmAction(getString(R.string.confirm_remove_route_profile), profile.name, R.string.delete) {
            doDelete()
        }
    }

    private fun lastProfileError() = message(R.string.route_profile_invalid_title, getString(R.string.route_delete_last))

    // ------------------------------------------------------------------------------------------------ import

    /** The desktop Routes dialog's Import: the clipboard first, then a paste dialog (dialog_manage_routes.cpp:687-737). */
    private fun importFromClipboard() {
        val activity = requireActivity() as AppCompatActivity
        val clip = SagerNet.getClipboardText().trim()
        if (RouteImports.isRemoteRouteLink(clip)) {
            RouteImports.remoteEntries(activity, clip)?.let { RouteImports.promptRemote(activity, it) }
            return
        }
        if (clip.isEmpty()) {
            pasteDialog()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val imported = RouteImports.parse(clip)
            val profile = imported.profile
            if (profile == null) {
                pasteDialog()
                return@launch
            }
            val prompt = if (imported.legacyArray) {
                getString(R.string.route_import_clipboard_rules)
            } else {
                getString(R.string.route_import_clipboard_profile, RouteImports.nameOf(activity, profile))
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.route_import_clipboard_title)
                .setMessage(RouteImports.withNotes(activity, prompt, imported.warnings))
                .setPositiveButton(R.string.yes) { _, _ -> applyImported(profile, imported.legacyArray) }
                .setNegativeButton(R.string.no) { _, _ -> pasteDialog() }
                .show()
        }
    }

    private fun pasteDialog() {
        val activity = activity as? AppCompatActivity ?: return
        val layout = TextInputLayout(activity).apply { hint = getString(R.string.route_import_paste_hint) }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        layout.addView(input)
        val container = FrameLayout(activity).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.route_import_paste_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (RouteImports.isRemoteRouteLink(text)) {
                val entries = RouteImports.remoteEntries(activity, text) ?: return@setOnClickListener
                dialog.dismiss()
                RouteImports.promptRemote(activity, entries)
                return@setOnClickListener
            }
            activity.lifecycleScope.launch {
                val imported = RouteImports.parse(text)
                val profile = imported.profile
                if (profile == null) {
                    RouteImports.showMessage(activity, R.string.route_import_invalid_title, getString(R.string.route_import_invalid, imported.fatal))
                    return@launch
                }
                dialog.dismiss()
                if (imported.warnings.isNotEmpty()) {
                    RouteImports.showMessage(activity, R.string.route_import_warnings_title, imported.warnings.joinToString("\n"))
                }
                applyImported(profile, imported.legacyArray)
            }
        }
    }

    /** A legacy rule array has no name or default outbound, so it opens in the editor as a new profile. */
    private fun applyImported(profile: RouteProfile, legacyArray: Boolean) {
        val context = context ?: return
        if (legacyArray) {
            editor.launch(Intent(context, RouteProfileActivity::class.java).putExtra(
                RouteProfileActivity.EXTRA_PROFILE_JSON, RouteJson.profileToJson(profile)
            ))
            return
        }
        profile.name = RouteImports.nameOf(context, profile)
        runOnLifecycleDispatcher {
            RouteManager.save(profile)
            onMainDispatcher {
                if (isAdded) snackbar(getString(R.string.route_imported, profile.name)).show()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ remote

    private fun downloadProfiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            val bundles = withContext(Dispatchers.IO) { RouteRepo.bundles() }
            val activity = activity as? AppCompatActivity ?: return@launch
            if (bundles.isEmpty()) {
                message(R.string.route_download_profiles, getString(R.string.route_download_empty))
                return@launch
            }
            val countries = Array<CharSequence>(bundles.size) { bundles[it].country }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.route_download_profiles)
                .setItems(countries) { _, which -> RouteImports.promptRemote(activity, bundles[which].entries) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            val remotes = withContext(Dispatchers.IO) {
                RouteManager.all().filter { it.is_remote && it.remote_url.isNotBlank() }.map { it.id }
            }
            if (remotes.isEmpty()) {
                message(R.string.route_update_none_title, getString(R.string.route_update_none))
            } else {
                runRemoteUpdates(remotes)
            }
        }
    }

    /**
     * updateRemoteProfiles (dialog_manage_routes.cpp:825-872): one profile at a time with "Updating (i / n)",
     * cancellable between profiles, then the desktop's result texts. Every listed profile is fetched, auto update or not.
     */
    private fun runRemoteUpdates(ids: List<Long>) {
        if (remoteUpdateJob?.isActive == true) return
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        binding.progressCircular.isGone = true
        binding.progressLinear.isVisible = true
        binding.progressLinear.max = ids.size
        val cancelled = AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.route_update_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()
        progressDialog = dialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelled.set(true)
            it.isEnabled = false
            binding.progress.setText(R.string.route_update_cancelling)
        }
        val total = ids.size
        remoteUpdateJob = viewLifecycleOwner.lifecycleScope.launch {
            var updated = 0
            val failures = ArrayList<String>()
            var currentChanged = false
            for ((i, id) in ids.withIndex()) {
                if (cancelled.get()) break
                binding.progress.text = if (total <= 1) getString(R.string.route_updating) else getString(R.string.route_updating_n, i + 1, total)
                binding.progressLinear.setProgressCompat(i, true)
                updating.add(id)
                adapter.notifyProfileChanged(id)
                val result = withContext(Dispatchers.IO) {
                    val profile = RouteManager.get(id) ?: return@withContext null
                    profile.name to RemoteRouteUpdater.update(profile)
                }
                updating.remove(id)
                adapter.notifyProfileChanged(id)
                if (result == null) continue
                val error = result.second
                if (error == null) {
                    updated++
                    if (id == DataStore.currentRouteId) currentChanged = true
                } else {
                    failures.add(result.first + ": " + error)
                }
            }
            dialog.dismiss()
            progressDialog = null
            when {
                cancelled.get() -> message(
                    R.string.route_update_cancelled_title, getString(R.string.route_update_cancelled, updated, total, failures.size)
                )

                failures.isEmpty() -> message(
                    R.string.route_update_complete_title, resources.getQuantityString(R.plurals.route_update_complete, updated, updated)
                )

                else -> message(
                    R.string.route_update_errors_title,
                    getString(R.string.route_update_errors, updated, failures.size, failures.joinToString("\n"))
                )
            }
            if (currentChanged) needReload()
        }
    }

    private fun refreshRepository() {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        binding.progress.setText(R.string.route_repo_running)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.route_refresh_repo)
            .setView(binding.root)
            .setCancelable(false)
            .show()
        progressDialog = dialog
        viewLifecycleOwner.lifecycleScope.launch {
            val (error, ruleSets, bundles) = withContext(Dispatchers.IO) {
                val error = RouteRepo.refresh()
                Triple(error, RouteManager.catalog().size, RouteRepo.bundles().size)
            }
            dialog.dismiss()
            progressDialog = null
            if (error == null) {
                snackbar(getString(R.string.route_repo_done, ruleSets, bundles)).show()
            } else {
                message(R.string.route_refresh_repo, getString(R.string.route_repo_failed, error))
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ list

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileHolder>() {
        var profiles: List<RouteProfile> = emptyList()
        var currentId = 0L

        init {
            setHasStableIds(true)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun submit(profiles: List<RouteProfile>, currentId: Long) {
            this.profiles = profiles
            this.currentId = currentId
            notifyDataSetChanged()
        }

        fun setCurrent(id: Long) {
            val old = profiles.indexOfFirst { it.id == currentId }
            currentId = id
            if (old >= 0) notifyItemChanged(old)
            notifyProfileChanged(id)
        }

        fun notifyProfileChanged(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        override fun getItemCount() = profiles.size

        override fun getItemId(position: Int) = profiles[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ProfileHolder(LayoutRouteProfileItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: ProfileHolder, position: Int) {
            val profile = profiles[position]
            holder.bind(profile, profile.id == currentId)
        }
    }

    private inner class ProfileHolder(val binding: LayoutRouteProfileItemBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: RouteProfile, current: Boolean) {
            val context = itemView.context
            binding.profileName.text = profile.name
            binding.profileName.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            binding.activeMark.isVisible = current
            binding.profileSummary.text = if (profile.is_raw) getString(R.string.route_raw_summary) else getString(
                R.string.route_item_default_outbound, RouteTexts.outboundName(context, profile.default_outbound_id, emptyMap())
            ) + " · " + resources.getQuantityString(R.plurals.route_item_rules, profile.rules.size, profile.rules.size)
            binding.profileRemote.isVisible = profile.is_remote
            if (profile.is_remote) {
                val updated = if (profile.remote_last_update > 0) {
                    getString(R.string.route_item_remote_updated, RouteTexts.dateTime(context, profile.remote_last_update))
                } else {
                    getString(R.string.route_item_remote_never)
                }
                val auto = getString(if (profile.auto_update) R.string.route_item_auto_update_on else R.string.route_item_auto_update_off)
                binding.profileRemote.text = "$updated · $auto"
            }
            binding.updateProgress.isVisible = profile.id in updating
            itemView.setOnClickListener { select(profile) }
            binding.edit.setOnClickListener { edit(profile.id) }
            binding.options.setOnClickListener { showMenu(it, profile) }
        }

        private fun showMenu(anchor: View, profile: RouteProfile) {
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.route_item_menu, popup.menu)
            if (!profile.is_remote) popup.menu.removeItem(R.id.action_route_update)
            if (profile.is_raw) {
                popup.menu.findItem(R.id.action_route_edit)?.setTitle(R.string.route_raw_view)
                popup.menu.removeItem(R.id.action_route_clone)
                popup.menu.removeItem(R.id.action_route_share)
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_route_edit -> edit(profile.id)
                    R.id.action_route_clone -> clone(profile.id)
                    R.id.action_route_share_clipboard -> share(profile.id, qr = false)
                    R.id.action_route_share_qr -> share(profile.id, qr = true)
                    R.id.action_route_update -> runRemoteUpdates(listOf(profile.id))
                    R.id.action_route_delete -> delete(profile)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            popup.show()
        }
    }
}
