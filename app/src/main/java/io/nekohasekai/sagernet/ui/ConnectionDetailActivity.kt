package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.databinding.LayoutConnectionDetailBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionDetailHeaderBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionDetailRowBinding
import io.nekohasekai.sagernet.ui.connections.ConnectionFilter
import io.nekohasekai.sagernet.ui.connections.ConnectionInfo
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 连接详情: basic info, metadata and process info of one tracked connection. */
class ConnectionDetailActivity : ThemedActivity(), SagerConnection.Callback {

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
    }

    private lateinit var binding: LayoutConnectionDetailBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private var service: ISagerNetService? = null
    private lateinit var connectionId: String
    private var current: ConnectionInfo? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID) ?: run {
            finish()
            return
        }
        binding = LayoutConnectionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.connection_details)
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.connection_detail_menu)
        toolbar.setOnMenuItemClickListener(::onMenuItemClick)
        ViewCompat.setOnApplyWindowInsetsListener(binding.connectionDetailScroll, ListListener)

        connection.connect(this, this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(1000L)
                }
            }
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    private suspend fun refresh() {
        val svc = service ?: return
        val found = withContext(Dispatchers.IO) {
            ConnectionInfo.parseSnapshot(runCatching { svc.queryConnections(ConnectionFilter.ALL) }.getOrNull())
                .firstOrNull { it.id == connectionId }
        }
        if (found != null) current = found
        current?.let { render(it) }
    }

    private fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_close_connection) return false
        val c = current
        if (c == null || !c.isActive) {
            snackbar(R.string.connection_already_closed).show()
            return true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_close)
            .setMessage(c.displayDestination)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { service?.closeConnection(c.id) } }
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    private fun render(c: ConnectionInfo) {
        val container = binding.connectionDetailContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        fun header(title: Int) {
            LayoutConnectionDetailHeaderBinding.inflate(inflater, container, true).detailHeader.setText(title)
        }

        fun row(key: Int, value: String?) {
            if (value.isNullOrBlank()) return
            val r = LayoutConnectionDetailRowBinding.inflate(inflater, container, true)
            r.detailKey.setText(key)
            r.detailValue.text = value
        }

        header(R.string.connection_basic_info)
        row(
            R.string.connection_status,
            getString(if (c.isActive) R.string.connection_status_active else R.string.connection_status_closed)
        )
        row(R.string.connection_created_at, timeFormat.format(Date(c.createdAt)))
        if (!c.isActive) row(R.string.connection_closed_at, timeFormat.format(Date(c.closedAt)))
        row(R.string.connection_upload, ConnectionsFragment.formatSize(this, c.upload))
        row(R.string.connection_download, ConnectionsFragment.formatSize(this, c.download))

        header(R.string.connection_metadata)
        row(R.string.connection_inbound, c.inbound)
        row(R.string.connection_inbound_type, c.inboundType)
        row(R.string.connection_ip_version, if (c.ipVersion > 0) "IPv${c.ipVersion}" else null)
        row(R.string.connection_network, c.network.uppercase())
        row(R.string.connection_source, c.source)
        row(R.string.connection_destination, c.destination)
        row(R.string.connection_domain, c.domain)
        row(R.string.connection_protocol, c.protocol)
        row(R.string.connection_rule, c.rule)
        row(R.string.connection_outbound, c.displayChain)
        row(R.string.connection_outbound_type, c.outboundType)

        val pkg = c.packageName.ifBlank {
            if (c.userId >= 0) PackageCache[c.userId]?.firstOrNull().orEmpty() else ""
        }
        if (c.userId >= 0 || c.userName.isNotBlank() || pkg.isNotBlank() || c.processPath.isNotBlank()) {
            header(R.string.connection_process_info)
            row(R.string.connection_user_id, if (c.userId >= 0) c.userId.toString() else null)
            row(R.string.connection_user_name, c.userName)
            row(R.string.connection_package_name, pkg)
            row(R.string.connection_process_path, c.processPath)
        }
    }

    // --- SagerConnection.Callback ---

    override fun onServiceConnected(service: ISagerNetService) {
        this.service = service
        lifecycleScope.launch { refresh() }
    }

    override fun onServiceDisconnected() {
        service = null
    }

    override fun onBinderDied() {
        service = null
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
}
