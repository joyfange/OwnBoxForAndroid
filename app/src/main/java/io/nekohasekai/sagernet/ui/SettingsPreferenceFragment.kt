package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import libcore.Libcore

class SettingsPreferenceFragment : PreferenceFragmentCompat(), OnPreferenceDataStoreChangeListener {

    // ---- 按 Wi‑Fi 自动开关：定位权限（读 Wi‑Fi 名称必需） ----
    private var pendingWifiAction: (() -> Unit)? = null

    private val fineLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestBackgroundLocationIfNeeded()
        } else {
            pendingWifiAction = null
            showWifiPermissionDenied()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                requireContext(),
                "没有「始终允许」定位时，App 在后台读不到 Wi‑Fi 名称，自动开关可能不生效",
                Toast.LENGTH_LONG
            ).show()
        }
        runPendingWifiAction()
    }

    private fun runPendingWifiAction() {
        val action = pendingWifiAction
        pendingWifiAction = null
        action?.invoke()
    }

    private fun ensureWifiPermissions(then: () -> Unit) {
        val ctx = requireContext()
        pendingWifiAction = then
        if (!io.nekohasekai.sagernet.bg.WifiAutoSwitch.hasLocationPermission(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("需要定位权限")
                .setMessage("Android 只在授予定位权限后才告诉 App 当前 Wi‑Fi 的名称。OwnBox 只用它判断是否连着信任的 Wi‑Fi，不会记录或上传位置。")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    fineLocationLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingWifiAction = null }
                .show()
            return
        }
        requestBackgroundLocationIfNeeded()
    }

    private fun requestBackgroundLocationIfNeeded() {
        val ctx = context ?: return
        if (io.nekohasekai.sagernet.bg.WifiAutoSwitch.hasBackgroundLocationPermission(ctx)) {
            runPendingWifiAction()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("允许在后台读取 Wi‑Fi")
            .setMessage("回家、出门时 App 通常在后台。请在接下来的页面里把定位权限改成「始终允许」，否则自动开关只在打开 App 时生效。")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (Build.VERSION.SDK_INT >= 29) {
                    backgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    runPendingWifiAction()
                }
            }
            .setNegativeButton("暂不") { _, _ -> runPendingWifiAction() }
            .show()
    }

    private fun showWifiPermissionDenied() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("没有定位权限")
            .setMessage("读不到 Wi‑Fi 名称，按 Wi‑Fi 自动开关无法工作。可以在系统设置 → 应用 → OwnBox → 权限里打开定位。")
            .setPositiveButton("去设置") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", ctx.packageName, null))
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun maybeWarnBatteryOptimization() {
        val ctx = context ?: return
        // 已不受电池优化限制，或之前已经提醒过（含厂商设置里放开、系统接口却读不到的机型）就不再弹
        if (io.nekohasekai.sagernet.bg.WifiAutoSwitch.isIgnoringBatteryOptimizations(ctx)) return
        if (DataStore.wifiBatteryPrompted) return
        DataStore.wifiBatteryPrompted = true
        MaterialAlertDialogBuilder(ctx)
            .setTitle("建议关闭电池优化")
            .setMessage("离开信任 Wi‑Fi 时要在后台自动重新连接，Android 12 起需要 OwnBox 不受电池优化限制；否则会发一条通知，点一下再连。")
            .setPositiveButton("去关闭") { _, _ ->
                runCatching {
                    @Suppress("BatteryLife")
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }
            .setNegativeButton("已经关了", null)
            .show()
    }

    private fun setupWifiAutoSwitch() {
        val autoSwitch = findPreference<SwitchPreference>(Key.WIFI_AUTO_SWITCH) ?: return
        val trusted = findPreference<EditTextPreference>(Key.WIFI_TRUSTED_SSIDS)
        val addCurrent = findPreference<Preference>(Key.WIFI_ADD_CURRENT)

        fun updateTrustedSummary(raw: String = DataStore.wifiTrustedSsids) {
            val list = io.nekohasekai.sagernet.bg.WifiAutoSwitch.trustedList(raw)
            trusted?.summary = if (list.isEmpty()) "未设置：不会自动断开" else list.joinToString("、")
        }
        updateTrustedSummary()

        autoSwitch.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            if (!enable) {
                runOnDefaultDispatcher { io.nekohasekai.sagernet.bg.WifiAutoSwitch.syncRegistration() }
                return@setOnPreferenceChangeListener true
            }
            ensureWifiPermissions {
                autoSwitch.isChecked = true
                runOnDefaultDispatcher {
                    io.nekohasekai.sagernet.bg.WifiAutoSwitch.syncRegistration()
                    io.nekohasekai.sagernet.bg.WifiAutoSwitch.onNetworkEvent(delayMs = 0L)
                }
                maybeWarnBatteryOptimization()
            }
            // 拿到权限后再真正打开
            false
        }

        trusted?.setOnPreferenceChangeListener { _, newValue ->
            val raw = io.nekohasekai.sagernet.bg.WifiAutoSwitch.trustedList(newValue as String).joinToString(", ")
            DataStore.wifiTrustedSsids = raw
            DataStore.wifiLastTrusted = ""
            trusted?.text = raw
            updateTrustedSummary(raw)
            io.nekohasekai.sagernet.bg.WifiAutoSwitch.onNetworkEvent(delayMs = 0L)
            false
        }

        addCurrent?.setOnPreferenceClickListener {
            ensureWifiPermissions {
                val ctx = context ?: return@ensureWifiPermissions
                val ssid = io.nekohasekai.sagernet.bg.WifiAutoSwitch.currentSsid(ctx)
                if (ssid == null) {
                    Toast.makeText(ctx, "读不到当前 Wi‑Fi：请确认已连上 Wi‑Fi 并打开系统定位", Toast.LENGTH_LONG).show()
                    return@ensureWifiPermissions
                }
                val list = io.nekohasekai.sagernet.bg.WifiAutoSwitch.trustedList().toMutableList()
                if (list.contains(ssid)) {
                    Toast.makeText(ctx, "「$ssid」已在信任列表里", Toast.LENGTH_SHORT).show()
                    return@ensureWifiPermissions
                }
                list.add(ssid)
                val raw = list.joinToString(", ")
                DataStore.wifiTrustedSsids = raw
                trusted?.text = raw
                updateTrustedSummary(raw)
                // 当前就在这个 Wi‑Fi 上：只记录状态，不立刻断开正在用的连接
                DataStore.wifiLastTrusted = "1"
                Toast.makeText(ctx, "已加入「$ssid」，下次连上它时自动断开", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private lateinit var isProxyApps: SwitchPreference

    private lateinit var globalCustomConfig: EditConfigPreference

    private fun tintPreferenceIcons(group: PreferenceGroup, color: Int) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                tintPreferenceIcons(pref, color)
            } else {
                pref.icon?.let { icon ->
                    val tinted = icon.mutate()
                    DrawableCompat.setTint(tinted, color)
                    pref.icon = tinted
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        DataStore.configurationStore.registerChangeListener(this)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        // 展开/收起分类时不做列表项动画：默认动画会先等下方分类「移动」完才淡入子项，
        // 下方还有分类的（如「模式与入站设置」）就会停顿一下。改为立即展开，
        // 只保留箭头旋转作为反馈，所有分类表现一致
        listView.itemAnimator = null
        listView.setItemViewCacheSize(24)
        setDivider(null)
        setDividerHeight(0)
        listView.clipToPadding = false
        listView.setPadding(0, dp2px(8), 0, dp2px(88))
    }

    override fun onDestroyView() {
        DataStore.configurationStore.unregisterChangeListener(this)
        super.onDestroyView()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.PROFILE_CARD_STYLE) {
            ExpandablePreferenceCategory.cachedCardStyle = null
            runOnMainDispatcher {
                listView?.adapter?.notifyDataSetChanged()
            }
        }
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val iconColor = Theme.getPrimaryColor(requireContext())
        tintPreferenceIcons(preferenceScreen, iconColor)
        val categoryUI = findPreference<ExpandablePreferenceCategory>("categoryUI")
        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        appTheme.isEnabled = true

        // ColorPickerPreference saves the base / accent selection, syncs night mode and recreates the activity itself.

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            val mode = (newTheme as String).toInt()
            DataStore.nightTheme = mode
            Theme.currentNightMode = mode
            Theme.applyNightTheme()
            activity?.recreate()
            true
        }
        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)!!
        appLanguage.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val disableMixedInbound = findPreference<SwitchPreference>(Key.DISABLE_MIXED_INBOUND)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val mixedAuthConfig = findPreference<Preference>(Key.MIXED_AUTH_CONFIG)!!
        val httpProxyBypass = findPreference<EditTextPreference>(Key.HTTP_PROXY_BYPASS)!!
        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)!!
        val strictRoute = findPreference<SwitchPreference>(Key.STRICT_ROUTE)!!
        val speedTestMode = findPreference<SimpleMenuPreference>(Key.SPEED_TEST_MODE)!!
        val speedTestTimeout = findPreference<EditTextPreference>(Key.SPEED_TEST_TIMEOUT_MS)!!
        val simpleDownloadURL = findPreference<EditTextPreference>(Key.SIMPLE_DOWNLOAD_URL)!!

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val enableTLSFragment = findPreference<SwitchPreference>(Key.ENABLE_TLS_FRAGMENT)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toInt()
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        httpProxyBypass.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        httpProxyBypass.summaryProvider = ListSummaryProvider(maxLines = 1)
        dnsHosts.summaryProvider = ListSummaryProvider(maxLines = 1)

        speedTestMode.setOnPreferenceChangeListener { _, newValue ->
            SpeedTestSettings.isValidMode(newValue.toString())
        }
        speedTestTimeout.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        speedTestTimeout.setOnPreferenceChangeListener { _, newValue ->
            val valid = SpeedTestSettings.isValidTimeout(newValue.toString())
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_timeout_invalid, Toast.LENGTH_SHORT).show()
            }
            valid
        }
        simpleDownloadURL.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        simpleDownloadURL.setOnPreferenceChangeListener { preference, newValue ->
            val value = newValue.toString().trim()
            val valid = SpeedTestSettings.isValidHttpUrl(value)
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_url_invalid, Toast.LENGTH_SHORT).show()
            } else if (value != newValue) {
                (preference as EditTextPreference).text = value
                return@setOnPreferenceChangeListener false
            }
            valid
        }

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        serviceMode.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        val hideFromRecentApps = findPreference<SwitchPreference>(Key.HIDE_FROM_RECENT_APPS)!!
        val enableClashAPI = findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        val categoryCore = findPreference<ExpandablePreferenceCategory>("categoryCore")
        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)!!
        categoryCore?.setChildVisibilityRule(Key.RULES_GEOSITE_URL) { DataStore.rulesProvider == 4 }
        categoryCore?.setChildVisibilityRule(Key.RULES_GEOIP_URL) { DataStore.rulesProvider == 4 }
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            categoryCore?.setChildVisibilityRule(Key.RULES_GEOSITE_URL) { provider == 4 }
            categoryCore?.setChildVisibilityRule(Key.RULES_GEOIP_URL) { provider == 4 }
            categoryCore?.updateChildVisibility(Key.RULES_GEOSITE_URL)
            categoryCore?.updateChildVisibility(Key.RULES_GEOIP_URL)
            true
        }

        // Routing (ported from ThroneForAndroid): the rule-set / remote route profile mirror and the remote route
        // profile auto update interval (minutes, below 30 = off).
        findPreference<SimpleMenuPreference>(Key.RULESET_MIRROR)?.setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }
        findPreference<EditTextPreference>(Key.ROUTE_AUTO_UPDATE)?.apply {
            setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            setOnPreferenceChangeListener { _, newValue ->
                val minutes = (newValue as? String)?.trim()?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
                if (minutes < 0) return@setOnPreferenceChangeListener false
                // The store is written after this returns, so schedule once it holds the new value.
                listView?.post { io.nekohasekai.sagernet.group.RemoteRouteUpdater.schedule() }
                true
            }
        }

        // 禁用混合入站：开启时代理端口/身份验证/绕过列表设置项变灰，端口摘要显示「已禁用」
        fun updateMixedPortState(disabled: Boolean = DataStore.disableMixedInbound) {
            mixedPort.isEnabled = !disabled
            mixedAuthConfig.isEnabled = !disabled
            httpProxyBypass.isEnabled = !disabled
            if (disabled) {
                mixedPort.summaryProvider = null
                mixedPort.summary = getString(R.string.mixed_inbound_disabled)
            } else {
                mixedPort.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
        }
        updateMixedPortState()
        disableMixedInbound.setOnPreferenceChangeListener { _, newValue ->
            val disabled = newValue as Boolean
            if (disabled && DataStore.serviceMode == Key.MODE_PROXY) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.disable_mixed_inbound_proxy_toast, DataStore.mixedPort),
                    Toast.LENGTH_LONG
                ).show()
            }
            updateMixedPortState(disabled)
            needReload()
            true
        }
        // 配置身份验证：弹窗编辑混合入站用户名/密码，两项均留空则不启用认证
        fun updateMixedAuthSummary() {
            mixedAuthConfig.summary = DataStore.mixedUsername.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.mixed_auth_enabled_sum, it) }
                ?: getString(R.string.mixed_auth_no_auth)
        }
        updateMixedAuthSummary()
        mixedAuthConfig.setOnPreferenceClickListener {
            val view = layoutInflater.inflate(R.layout.layout_mixed_auth_dialog, null)
            val usernameEdit = view.findViewById<EditText>(R.id.mixed_username_edit)
            val passwordEdit = view.findViewById<EditText>(R.id.mixed_password_edit)
            usernameEdit.setText(DataStore.mixedUsername)
            passwordEdit.setText(DataStore.mixedPassword)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mixed_auth_config)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.mixedUsername = usernameEdit.text.toString().trim()
                    DataStore.mixedPassword = passwordEdit.text.toString()
                    updateMixedAuthSummary()
                    needReload()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        setupWifiAutoSwitch()
        mixedPort.onPreferenceChangeListener = reloadListener
        httpProxyBypass.onPreferenceChangeListener = reloadListener
        dnsHosts.onPreferenceChangeListener = reloadListener
        strictRoute.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }

        val dualNetworkAcceleration = findPreference<SwitchPreference>(Key.DUAL_NETWORK_ACCELERATION)!!
        dualNetworkAcceleration.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.CONCURRENT_DIAL)?.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.AUTO_SELECT_LOWEST_LATENCY)?.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        val performancePriorityMode = findPreference<SwitchPreference>(Key.PERFORMANCE_PRIORITY_MODE)
        performancePriorityMode?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            SagerNet.updatePerformancePriorityMode(enabled)
            true
        }
        hideFromRecentApps.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            // needReload()
            true
        }

        enableTLSFragment.onPreferenceChangeListener = reloadListener

        // 恢复默认设置功能
        val resetSettings = findPreference<Preference>("resetSettings")!!
        resetSettings.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.confirm)
                setMessage(R.string.reset_settings_message)
                setNegativeButton(R.string.no, null)
                setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
            }.show()
            true
        }

        // 清理缓存功能
        val clearCache = findPreference<Preference>(Key.CLEAR_CACHE)!!
        clearCache.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.show()
            true
        }

        // 局域网共享
        val lanSharingPref = findPreference<Preference>("lanSharing")

        fun getLocalIps(): String {
            val ips = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.filter { !it.isLoopback && it.isUp }
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.mapNotNull { it.hostAddress }
                    ?.distinct()
            }.getOrNull() ?: emptyList()
            return when {
                ips.isEmpty() -> "192.168.43.1"
                ips.any { it.startsWith("192.168.43.") } -> ips.first { it.startsWith("192.168.43.") }
                else -> ips.joinToString(" / ")
            }
        }

        fun updateLanSharingSummary() {
            val enabled = DataStore.allowAccess
            if (enabled) {
                val localIp = getLocalIps()
                val port = DataStore.mixedPort
                lanSharingPref?.summary = getString(R.string.lan_sharing_enabled_sum, localIp, port)
            } else {
                lanSharingPref?.summary = getString(R.string.lan_sharing_disabled_sum)
            }
        }
        updateLanSharingSummary()

        lanSharingPref?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LanSharingActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        val lanSharingPref = findPreference<Preference>("lanSharing")
        if (lanSharingPref != null) {
            val enabled = DataStore.allowAccess
            if (enabled) {
                val ips = runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { !it.isLoopback && it.isUp }
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        ?.mapNotNull { it.hostAddress }
                        ?.distinct()
                }.getOrNull() ?: emptyList()
                val localIp = when {
                    ips.isEmpty() -> "192.168.43.1"
                    ips.any { it.startsWith("192.168.43.") } -> ips.first { it.startsWith("192.168.43.") }
                    else -> ips.joinToString(" / ")
                }
                lanSharingPref.summary = getString(R.string.lan_sharing_enabled_sum, localIp, DataStore.mixedPort)
            } else {
                lanSharingPref.summary = getString(R.string.lan_sharing_disabled_sum)
            }
        }
    }

    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))
            
            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }
            
            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.clear_cache_failed, e.message), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true
            
            for (child in children) {
                val childFile = File(dir, child)
                
                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (child in skipFiles) {
                    continue
                }
                
                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }
            
            return true
        }
        return false
    }

    class ListSummaryProvider(
        private val maxLines: Int,
    ) : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val lines = preference.text.orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            if (lines.isEmpty()) {
                return preference.context.getString(androidx.preference.R.string.not_set)
            }
            return if (lines.size > maxLines) {
                lines.take(maxLines).joinToString("\n", postfix = "\n...")
            } else {
                lines.joinToString("\n")
            }
        }

    }

}
