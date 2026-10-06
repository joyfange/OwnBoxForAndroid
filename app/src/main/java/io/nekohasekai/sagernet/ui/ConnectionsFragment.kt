package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutConnectionItemBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionsBinding
import io.nekohasekai.sagernet.ui.connections.ConnectionFilter
import io.nekohasekai.sagernet.ui.connections.ConnectionInfo
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connections (连接) viewer, modelled on the sing-box / Throne connection list.
 * Polls the running core about once per second while the screen is visible.
 */
class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections),
    Toolbar.OnMenuItemClickListener {

    companion object {
        const val SORT_TIME = 0
        const val SORT_UPLOAD = 1
        const val SORT_DOWNLOAD = 2
        const val SORT_TOTAL = 3
        const val SORT_HOST = 4

        private const val REFRESH_INTERVAL_MS = 1000L

        fun formatSpeed(context: Context, bytesPerSecond: Long): String =
            Formatter.formatFileSize(context, bytesPerSecond.coerceAtLeast(0)) + "/s"

        fun formatSize(context: Context, bytes: Long): String =
            Formatter.formatFileSize(context, bytes.coerceAtLeast(0))

        /** "tun/tun-in" like the sing-box clients; just the type when the tag is empty. */
        fun inboundLabel(c: ConnectionInfo): String = when {
            c.inbound.isBlank() -> c.inboundType
            c.inboundType.isBlank() -> c.inbound
            else -> c.inboundType + "/" + c.inbound
        }

        fun outboundLabel(c: ConnectionInfo): String {
            val chain = c.displayChain
            return if (c.outbound.equals("direct", true) || c.outboundType.equals("direct", true)) {
                if (chain.equals(c.outbound, true)) "DIRECT" else chain
            } else chain
        }
    }

    private lateinit var binding: LayoutConnectionsBinding
    private lateinit var adapter: ConnectionAdapter

    private var filter = ConnectionFilter.ACTIVE
    private var sortMode = SORT_TIME
    private var sortDescending = true

    /** id -> (upload, download, sampledAtMillis) of the previous poll, for per-connection speed. */
    private val lastSample = HashMap<String, Triple<Long, Long, Long>>()
    private val speeds = HashMap<String, Pair<Long, Long>>()

    private val service: ISagerNetService?
        get() = (activity as? MainActivity)?.connection?.service

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutConnectionsBinding.bind(view)
        toolbar.setTitle(R.string.menu_connections)
        toolbar.inflateMenu(R.menu.connections_menu)
        toolbar.setOnMenuItemClickListener(this)
        updateSubtitle()

        adapter = ConnectionAdapter()
        binding.connectionList.layoutManager = LinearLayoutManager(requireContext())
        binding.connectionList.adapter = adapter
        binding.connectionList.itemAnimator = null
        ViewCompat.setOnApplyWindowInsetsListener(binding.connectionList, ListListener)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun refresh() {
        val currentFilter = filter
        val list = withContext(Dispatchers.IO) {
            val svc = service
            if (svc == null || !DataStore.serviceState.started) {
                emptyList()
            } else {
                ConnectionInfo.parseSnapshot(runCatching { svc.queryConnections(currentFilter) }.getOrNull())
            }
        }
        val now = System.currentTimeMillis()
        val alive = HashSet<String>(list.size)
        for (c in list) {
            alive += c.id
            val previous = lastSample[c.id]
            if (previous != null && c.isActive) {
                val elapsed = (now - previous.third).coerceAtLeast(1)
                speeds[c.id] = Pair(
                    (c.upload - previous.first).coerceAtLeast(0) * 1000 / elapsed,
                    (c.download - previous.second).coerceAtLeast(0) * 1000 / elapsed,
                )
            } else {
                speeds[c.id] = Pair(0L, 0L)
            }
            lastSample[c.id] = Triple(c.upload, c.download, now)
        }
        lastSample.keys.retainAll(alive)
        speeds.keys.retainAll(alive)
        if (!isAdded) return
        val sorted = sort(list)
        adapter.submitList(sorted)
        binding.connectionEmpty.isVisible = sorted.isEmpty()
    }

    private fun sort(list: List<ConnectionInfo>): List<ConnectionInfo> {
        val comparator: Comparator<ConnectionInfo> = when (sortMode) {
            SORT_UPLOAD -> compareBy { it.upload }
            SORT_DOWNLOAD -> compareBy { it.download }
            SORT_TOTAL -> compareBy { it.upload + it.download }
            SORT_HOST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayDestination }
            else -> compareBy { it.createdAt }
        }
        return list.sortedWith(if (sortDescending) comparator.reversed() else comparator)
    }

    private fun updateSubtitle() {
        toolbar.subtitle = getString(
            when (filter) {
                ConnectionFilter.CLOSED -> R.string.connection_status_closed
                ConnectionFilter.ALL -> R.string.connections_filter_all
                else -> R.string.connection_status_active
            }
        )
    }

    private fun refreshNow() {
        viewLifecycleOwner.lifecycleScope.launch { refresh() }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_filter_active, R.id.action_filter_closed, R.id.action_filter_all -> {
                item.isChecked = true
                filter = when (item.itemId) {
                    R.id.action_filter_closed -> ConnectionFilter.CLOSED
                    R.id.action_filter_all -> ConnectionFilter.ALL
                    else -> ConnectionFilter.ACTIVE
                }
                updateSubtitle()
                refreshNow()
            }

            R.id.action_connections_sort -> showSortDialog()

            R.id.action_close_all_connections -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.connections_close_all)
                    .setMessage(R.string.connections_close_all_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { service?.closeAllConnections() }
                            }
                            refresh()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            else -> return false
        }
        return true
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.connections_sort_time),
            getString(R.string.connections_sort_upload),
            getString(R.string.connections_sort_download),
            getString(R.string.connections_sort_total),
            getString(R.string.connections_sort_host),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_sort)
            .setSingleChoiceItems(labels, sortMode) { dialog, which ->
                // Picking the active mode again flips the order.
                if (which == sortMode) sortDescending = !sortDescending else sortMode = which
                dialog.dismiss()
                refreshNow()
            }
            .setNeutralButton(
                if (sortDescending) R.string.connections_sort_ascending else R.string.connections_sort_descending
            ) { _, _ ->
                sortDescending = !sortDescending
                refreshNow()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val iconCache = HashMap<String, Drawable?>()

    private fun iconFor(context: Context, c: ConnectionInfo): Drawable? {
        val pkg = c.packageName.ifBlank {
            if (c.userId >= 0) PackageCache[c.userId]?.firstOrNull().orEmpty() else ""
        }
        if (pkg.isBlank()) return null
        return iconCache.getOrPut(pkg) {
            runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
    }

    private object Diff : DiffUtil.ItemCallback<ConnectionInfo>() {
        override fun areItemsTheSame(oldItem: ConnectionInfo, newItem: ConnectionInfo) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ConnectionInfo, newItem: ConnectionInfo) = false
    }

    private inner class ConnectionAdapter : ListAdapter<ConnectionInfo, ConnectionHolder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ConnectionHolder(
            LayoutConnectionItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: ConnectionHolder, position: Int) =
            holder.bind(getItem(position))
    }

    private inner class ConnectionHolder(private val item: LayoutConnectionItemBinding) :
        RecyclerView.ViewHolder(item.root) {

        @SuppressLint("SetTextI18n")
        fun bind(c: ConnectionInfo) {
            val context = item.root.context
            item.connectionTitle.text = "${c.network.uppercase()} ${c.displayDestination}"
            val (up, down) = speeds[c.id] ?: Pair(0L, 0L)
            item.connectionUpload.text =
                "↑ ${formatSpeed(context, up)} | ${formatSize(context, c.upload)}"
            item.connectionDownload.text =
                "↓ ${formatSpeed(context, down)} | ${formatSize(context, c.download)}"
            item.connectionInbound.text = inboundLabel(c)
            item.connectionOutbound.text = outboundLabel(c)
            if (c.isActive) {
                item.connectionStatus.setText(R.string.connection_status_active)
                item.connectionStatus.setTextColor(context.getColor(R.color.connection_active))
            } else {
                item.connectionStatus.setText(R.string.connection_status_closed)
                item.connectionStatus.setTextColor(context.getColor(R.color.connection_closed))
            }
            val icon = iconFor(context, c)
            item.connectionAppIcon.setImageDrawable(
                icon ?: AppCompatResources.getDrawable(context, R.drawable.ic_navigation_apps)
            )
            item.connectionCard.setOnClickListener {
                startActivity(
                    Intent(context, ConnectionDetailActivity::class.java)
                        .putExtra(ConnectionDetailActivity.EXTRA_CONNECTION_ID, c.id)
                )
            }
        }
    }
}
