package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.databinding.LayoutConnectionDetailBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionDetailHeaderBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionDetailRowBinding
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteJson
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.ui.connections.ConnectionFilter
import io.nekohasekai.sagernet.ui.connections.ConnectionInfo
import io.nekohasekai.sagernet.ui.route.RouteProfileActivity
import io.nekohasekai.sagernet.ui.route.RouteServers
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接详情: basic info, metadata and process info of one tracked connection. Tap or long-press a row to copy its
 * value; the menu copies everything (text or JSON) and turns the connection into a route rule ("添加到路由").
 */
class ConnectionDetailActivity : ThemedActivity(), SagerConnection.Callback {

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** Second-level labels that usually sit under a country code (example.co.uk, example.com.cn). */
        private val SECOND_LEVEL = setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go", "gob", "mil")
    }

    /** One line of the screen: a section header (value null) or a key / value row. */
    private data class Item(val key: Int, val value: String?) {
        val isHeader get() = value == null
    }

    private lateinit var binding: LayoutConnectionDetailBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private var service: ISagerNetService? = null
    private lateinit var connectionId: String
    private var current: ConnectionInfo? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** What is on screen, so the 1 s refresh updates values in place instead of rebuilding (keeps touches alive). */
    private var shownItems: List<Item> = emptyList()
    private val valueViews = ArrayList<TextView?>()

    private val routeEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val id = result.data?.getLongExtra(RouteProfileActivity.EXTRA_SAVED_ID, 0L) ?: 0L
        if (id == 0L) return@registerForActivityResult
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { RouteManager.get(id)?.name }.orEmpty()
            val bar = snackbar(getString(R.string.connection_route_saved, name))
            // Editing the active profile needs a reload like the route list does.
            if (id == DataStore.currentRouteId && DataStore.serviceState.started) {
                bar.setAction(R.string.apply) { SagerNet.reloadService() }
            }
            bar.show()
        }
    }

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
        when (item.itemId) {
            R.id.action_copy_all -> {
                val c = current ?: return true
                copy(getString(R.string.connection_details), allText(c))
            }

            R.id.action_copy_json -> {
                val c = current ?: return true
                copy("JSON", toJson(c).toString(2).replace("\\/", "/"))
            }

            R.id.action_add_to_route -> current?.let { showAddToRoute(it) }
            R.id.action_close_connection -> closeConnection()
            else -> return false
        }
        return true
    }

    private fun closeConnection() {
        val c = current
        if (c == null || !c.isActive) {
            snackbar(R.string.connection_already_closed).show()
            return
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
    }

    // --- content ---

    private fun packageOf(c: ConnectionInfo): String = c.packageName.ifBlank {
        if (c.userId >= 0) PackageCache[c.userId]?.firstOrNull().orEmpty() else ""
    }

    private fun items(c: ConnectionInfo): List<Item> {
        val out = ArrayList<Item>()
        fun header(title: Int) = out.add(Item(title, null))
        fun row(key: Int, value: String?) {
            if (!value.isNullOrBlank()) out.add(Item(key, value))
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

        val pkg = packageOf(c)
        if (c.userId >= 0 || c.userName.isNotBlank() || pkg.isNotBlank() || c.processPath.isNotBlank()) {
            header(R.string.connection_process_info)
            row(R.string.connection_user_id, if (c.userId >= 0) c.userId.toString() else null)
            row(R.string.connection_user_name, c.userName)
            row(R.string.connection_package_name, pkg)
            row(R.string.connection_process_path, c.processPath)
        }
        return out
    }

    private fun render(c: ConnectionInfo) {
        val list = items(c)
        val sameShape = list.size == shownItems.size &&
                list.indices.all { list[it].key == shownItems[it].key && list[it].isHeader == shownItems[it].isHeader }
        if (sameShape) {
            for (i in list.indices) {
                val v = list[i].value ?: continue
                val view = valueViews.getOrNull(i) ?: continue
                if (view.text?.toString() != v) view.text = v
            }
            shownItems = list
            return
        }

        val container = binding.connectionDetailContainer
        container.removeAllViews()
        valueViews.clear()
        val inflater = LayoutInflater.from(this)
        for ((index, item) in list.withIndex()) {
            if (item.isHeader) {
                LayoutConnectionDetailHeaderBinding.inflate(inflater, container, true).detailHeader.setText(item.key)
                valueViews.add(null)
                continue
            }
            val r = LayoutConnectionDetailRowBinding.inflate(inflater, container, true)
            r.detailKey.setText(item.key)
            r.detailValue.text = item.value
            valueViews.add(r.detailValue)
            // Read the value at touch time: the 1 s refresh may have changed it since inflation.
            val copyRow = View.OnClickListener {
                val value = shownItems.getOrNull(index)?.value ?: return@OnClickListener
                copy(getString(item.key), value)
            }
            r.root.setOnClickListener(copyRow)
            r.root.setOnLongClickListener {
                copyRow.onClick(it)
                true
            }
        }
        shownItems = list
    }

    private fun copy(label: String, text: String) {
        val ok = SagerNet.trySetPrimaryClip(text)
        Toast.makeText(
            this,
            if (ok) getString(R.string.connection_copied, label) else getString(R.string.copy_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun allText(c: ConnectionInfo): String = buildString {
        for (item in items(c)) {
            if (item.isHeader) {
                if (isNotEmpty()) append('\n')
                append("【").append(getString(item.key)).append("】\n")
            } else {
                append(getString(item.key)).append(": ").append(item.value).append('\n')
            }
        }
    }.trimEnd()

    private fun toJson(c: ConnectionInfo): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("status", if (c.isActive) "active" else "closed")
        put("createdAt", timeFormat.format(Date(c.createdAt)))
        if (!c.isActive) put("closedAt", timeFormat.format(Date(c.closedAt)))
        put("upload", c.upload)
        put("download", c.download)
        put("inbound", c.inbound)
        put("inboundType", c.inboundType)
        if (c.ipVersion > 0) put("ipVersion", c.ipVersion)
        put("network", c.network)
        put("source", c.source)
        put("destination", c.destination)
        if (c.domain.isNotBlank()) put("domain", c.domain)
        if (c.protocol.isNotBlank()) put("protocol", c.protocol)
        if (c.user.isNotBlank()) put("user", c.user)
        put("rule", c.rule)
        put("outbound", c.outbound)
        put("outboundType", c.outboundType)
        put("chain", JSONArray(c.chain))
        if (c.userId >= 0) put("userId", c.userId)
        if (c.userName.isNotBlank()) put("userName", c.userName)
        val pkg = packageOf(c)
        if (pkg.isNotBlank()) put("packageName", pkg)
        if (c.packageNames.size > 1) put("packageNames", JSONArray(c.packageNames))
        if (c.processPath.isNotBlank()) put("processPath", c.processPath)
    }

    // --- 添加到路由 ---

    /** A match the new rule can use: the label shown and how it fills the rule. */
    private class Match(val label: String, val fill: (RouteRule) -> Unit)

    private fun destinationHost(c: ConnectionInfo): String {
        val d = c.destination.trim()
        if (d.startsWith("[")) return d.substringAfter('[').substringBefore(']')
        val colons = d.count { it == ':' }
        return if (colons == 1) d.substringBefore(':') else d
    }

    private fun isIp(host: String) = IPV4.matches(host) || (host.contains(':') && host.all { it.isLetterOrDigit() || it == ':' || it == '.' })

    /** "a.b.example.com" -> "example.com", "x.example.co.uk" -> "example.co.uk". */
    private fun domainSuffix(domain: String): String {
        val labels = domain.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val keep = if (labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    private fun matches(c: ConnectionInfo): List<Match> {
        val out = ArrayList<Match>()
        val domain = c.domain.trim().trimEnd('.')
        if (domain.isNotEmpty() && !isIp(domain)) {
            out.add(Match(getString(R.string.connection_route_match_domain, domain)) { it.domain.add(domain) })
            val suffix = domainSuffix(domain)
            out.add(Match(getString(R.string.connection_route_match_suffix, suffix)) { it.domain_suffix.add(suffix) })
        }
        val host = destinationHost(c)
        if (host.isNotEmpty() && isIp(host)) {
            val cidr = if (host.contains(':')) "$host/128" else "$host/32"
            out.add(Match(getString(R.string.connection_route_match_ip, cidr)) { it.ip_cidr.add(cidr) })
        }
        val pkg = packageOf(c)
        if (pkg.isNotBlank()) {
            out.add(Match(getString(R.string.connection_route_match_package, pkg)) { it.package_name.add(pkg) })
        }
        val process = c.processPath.trim().substringAfterLast('/').substringAfterLast('\\')
        if (process.isNotEmpty()) {
            out.add(Match(getString(R.string.connection_route_match_process, process)) { it.process_name.add(process) })
        }
        return out
    }

    private fun showAddToRoute(c: ConnectionInfo) {
        val options = matches(c)
        if (options.isEmpty()) {
            snackbar(R.string.connection_route_nothing).show()
            return
        }
        lifecycleScope.launch {
            val (profile, servers) = withContext(Dispatchers.IO) { RouteManager.current() to RouteServers.list() }
            showAddToRouteDialog(options, profile.id, profile.name, profile.is_remote, servers)
        }
    }

    private fun showAddToRouteDialog(
        options: List<Match>,
        profileId: Long,
        profileName: String,
        remote: Boolean,
        servers: List<Pair<Long, String>>,
    ) {
        val pad = dp2px(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp2px(8), pad, 0)
        }
        fun label(text: CharSequence, top: Int, secondary: Boolean = false) = TextView(this).apply {
            this.text = text
            setPadding(0, dp2px(top), 0, dp2px(4))
            if (secondary) {
                textSize = 13f
            } else {
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }.also { content.addView(it) }

        label(buildString {
            append(getString(R.string.connection_route_target_profile, profileName))
            if (remote) append("\n").append(getString(R.string.connection_route_remote_warning))
        }, 0, secondary = true)

        label(getString(R.string.connection_route_match), 12)
        val matchGroup = RadioGroup(this)
        options.forEachIndexed { i, m ->
            matchGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = m.label
                isChecked = i == 0
            })
        }
        content.addView(matchGroup)

        label(getString(R.string.connection_route_outbound), 12)
        val outboundGroup = RadioGroup(this)
        var serverId = 0L
        val outbounds = listOf(
            R.string.connection_route_proxy to OutboundIds.PROXY,
            R.string.connection_route_direct to OutboundIds.DIRECT,
            R.string.connection_route_block to OutboundIds.BLOCK,
        )
        val outboundButtons = outbounds.mapIndexed { i, (res, _) ->
            RadioButton(this).apply {
                id = View.generateViewId()
                setText(res)
                isChecked = i == 0
            }.also { outboundGroup.addView(it) }
        }
        val serverButton = RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.connection_route_server)
        }
        outboundGroup.addView(serverButton)
        serverButton.setOnClickListener {
            if (servers.isEmpty()) {
                Toast.makeText(this, R.string.connection_route_no_servers, Toast.LENGTH_SHORT).show()
                outboundButtons[0].isChecked = true
                return@setOnClickListener
            }
            val checked = servers.indexOfFirst { it.first == serverId }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connection_route_server)
                .setSingleChoiceItems(servers.map { it.second }.toTypedArray(), checked) { d, which ->
                    serverId = servers[which].first
                    serverButton.text = getString(R.string.connection_route_server_selected, servers[which].second)
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (serverId == 0L) outboundButtons[0].isChecked = true
                }
                .setOnCancelListener { if (serverId == 0L) outboundButtons[0].isChecked = true }
                .show()
        }
        content.addView(outboundGroup)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_add_to_route)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.connection_route_continue) { _, _ ->
                val matchIndex = (0 until matchGroup.childCount)
                    .firstOrNull { (matchGroup.getChildAt(it) as RadioButton).isChecked } ?: 0
                val outbound = when {
                    serverButton.isChecked && serverId > 0L -> serverId
                    else -> outbounds.getOrNull(outboundButtons.indexOfFirst { it.isChecked })?.second
                        ?: OutboundIds.PROXY
                }
                val rule = RouteRule().apply {
                    action = "route"
                    outbound_id = outbound
                }
                options[matchIndex].fill(rule)
                routeEditor.launch(Intent(this, RouteProfileActivity::class.java).apply {
                    putExtra(RouteProfileActivity.EXTRA_PROFILE_ID, profileId)
                    putExtra(RouteProfileActivity.EXTRA_NEW_RULE, RouteJson.ruleToJson(rule))
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
