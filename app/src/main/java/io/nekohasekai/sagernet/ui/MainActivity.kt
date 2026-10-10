package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import libcore.Libcore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var currentMainFragment: ToolbarFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)
        val animateInitialControls = savedInstanceState == null

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        val accentColor = Theme.accentColor(this)
        val fabBgColor = accentColor ?: Theme.palette(this).fab
        binding.fab.backgroundTintList = ColorStateList.valueOf(fabBgColor)
        binding.fab.imageTintList = ColorStateList.valueOf(
            if (ColorUtils.calculateContrast(Color.WHITE, fabBgColor or 0xFF000000.toInt()) < 3.0) Color.BLACK
            else Color.WHITE
        )
        // The navigation bar takes the colour of whatever sits right above it (see updateNavigationBar): a fully
        // transparent bar is drawn with a white scrim by some ROMs (HyperOS / MIUI) on light themes, and on dark
        // themes it let a hidden stats bar peek through.
        updateNavigationBar()
        if (!Theme.isBlackTheme(this)) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        binding.drawerLayout.addDrawerListener(drawerNavigator)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            currentMainFragment = visiblePage()
        }
        schedulePagePrewarm()
        prewarmRuleSets()
        onBackPressedDispatcher.addCallback {
            val fragment = currentMainFragment ?: visiblePage()
            if (fragment?.onBackPressed() == true) return@addCallback
            if (fragment is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (DataStore.hapticFeedback) it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (DataStore.serviceState.canStop) SagerNet.stopService() else connect.launch(
                null
            )
        }
        binding.stats.setOnClickListener {
            if (DataStore.hapticFeedback) it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (DataStore.serviceState.connected) binding.stats.testConnection()
        }
        binding.stats.setOnLongClickListener {
            if (DataStore.hapticFeedback) it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, TrafficChartActivity::class.java))
            true
        }

        setContentView(binding.root)
        currentMainFragment = visiblePage() ?: currentMainFragment
        if (!animateInitialControls) {
            syncMainControls(showWhenConnected = false, animate = false)
        }
        changeState(
            BaseService.State.Idle,
            animate = false,
            animateControls = animateInitialControls,
        )
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        val isPreRelease = isPreview && (BuildConfig.PRE_VERSION_NAME.contains("preview", true) || BuildConfig.PRE_VERSION_NAME.contains("beta", true) || BuildConfig.PRE_VERSION_NAME.contains("alpha", true))
        if (isPreRelease && DataStore.previewHintDismissedVersion != BuildConfig.PRE_VERSION_NAME) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.preview_hint_dont_show_again) { _, _ ->
                    DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }

        checkClipboardOnResume()
        binding.stats.refreshDisplay()
        refreshNavMenu(DataStore.enableClashAPI)
    }

    private var lastPromptedClipboard: String = ""

    private fun checkClipboardOnResume() {
        try {
            val text = SagerNet.getClipboardText().trim()
            if (text.isBlank() || text == lastPromptedClipboard || text.length < 8) return
            val looksLikeProxy = text.startsWith("vless://", ignoreCase = true) ||
                    text.startsWith("vmess://", ignoreCase = true) ||
                    text.startsWith("ss://", ignoreCase = true) ||
                    text.startsWith("ssr://", ignoreCase = true) ||
                    text.startsWith("trojan://", ignoreCase = true) ||
                    text.startsWith("hysteria://", ignoreCase = true) ||
                    text.startsWith("hysteria2://", ignoreCase = true) ||
                    text.startsWith("hy2://", ignoreCase = true) ||
                    text.startsWith("tuic://", ignoreCase = true) ||
                    text.startsWith("sn://", ignoreCase = true) ||
                    text.startsWith("clash://", ignoreCase = true)

            if (!looksLikeProxy) return
            lastPromptedClipboard = text

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_import)
                .setMessage(R.string.import_clipboard_prompt)
                .setPositiveButton(R.string.action_import) { _, _ ->
                    runOnDefaultDispatcher {
                        try {
                            val rawProxies = io.nekohasekai.sagernet.group.RawUpdater.parseRaw(text)
                            val currentGroup = DataStore.currentGroup()
                            val targetId = DataStore.selectedGroupForImport()
                            val targetGroup = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.getById(targetId)
                            val shouldDeduplicate = (targetGroup?.subscription?.deduplication == true) || (currentGroup.subscription?.deduplication == true)
                            val proxies = if (shouldDeduplicate) rawProxies?.deduplicateProxies() else rawProxies
                            if (!proxies.isNullOrEmpty()) {
                                proxies.forEach { profile ->
                                    ProfileManager.createProfile(targetId, profile)
                                }
                                onMainDispatcher {
                                    displayFragmentWithId(R.id.nav_configuration)
                                    snackbar(resources.getQuantityString(R.plurals.added, proxies.size, proxies.size)).show()
                                }
                            }
                        } catch (e: io.nekohasekai.sagernet.ktx.SubscriptionFoundException) {
                            if (e.link.startsWith("sn://")) {
                                importSubscription(android.net.Uri.parse(e.link))
                            } else {
                                onMainDispatcher {
                                    val subscriptionLink = android.net.Uri.parse(e.link).getQueryParameter("url") ?: e.link
                                    startActivity(Intent(this@MainActivity, GroupSettingsActivity::class.java).apply {
                                        putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                        putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                    })
                                }
                            }
                        } catch (e: Exception) {
                            io.nekohasekai.sagernet.ktx.Logs.w(e)
                            onMainDispatcher {
                                snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            io.nekohasekai.sagernet.ktx.Logs.w(e)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment = visiblePage()
        if (restoredFragment != null && restoredFragment !== currentMainFragment) {
            currentMainFragment = restoredFragment
            syncMainControls(
                fragment = restoredFragment,
                showWhenConnected = DataStore.serviceState == BaseService.State.Connected,
                animate = false,
            )
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        navigation.menu.findItem(R.id.nav_dashboard)?.isVisible = clashApi
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        if (group.name.isNullOrBlank()) {
            val candidate = group.subscription?.link?.takeIf { it.isNotBlank() }?.let {
                io.nekohasekai.sagernet.group.RawUpdater.extractAirportName(it)
            }
            group.name = candidate ?: ("Subscription #" + System.currentTimeMillis())
        }

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://t.me/OwnBoxs")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    fun isCurrentFragment(@IdRes id: Int): Boolean {
        val current = currentMainFragment ?: visiblePage()
        return when (id) {
            R.id.nav_configuration -> current is ConfigurationFragment
            R.id.nav_group -> current is GroupFragment
            R.id.nav_route -> current is RouteFragment
            R.id.nav_settings -> current is SettingsFragment
            R.id.nav_dashboard -> current is WebviewFragment
            R.id.nav_tools -> current is ToolsFragment
            R.id.nav_logcat -> current is LogcatFragment
            R.id.nav_connections -> current is ConnectionsFragment
            R.id.nav_docs -> current is DocsFragment
            R.id.nav_about -> current is AboutFragment
            else -> false
        }
    }

    fun setCheckedItem(@IdRes id: Int) {
        val menu = navigation.menu
        fun uncheckAll(m: Menu) {
            for (i in 0 until m.size()) {
                val item = m.getItem(i)
                if (item.hasSubMenu()) {
                    item.subMenu?.let { uncheckAll(it) }
                }
                item.isChecked = false
            }
        }
        uncheckAll(menu)
        menu.findItem(id)?.isChecked = true
    }

    private var statsBarColor = 0
    private var statsBarVisible = false

    /** The stats bar recoloured itself (theme change). */
    fun onStatsBarColorChanged(color: Int) {
        statsBarColor = color
        updateNavigationBar()
    }

    /** The stats bar slid in or out: the navigation bar follows it. */
    fun onStatsBarVisibilityChanged(visible: Boolean) {
        if (statsBarVisible == visible) return
        statsBarVisible = visible
        updateNavigationBar()
    }

    /**
     * Paints the navigation bar in the colour right above it, the stats bar when it is up and the page background
     * otherwise, so the bottom of the screen is one surface with no white or black strip, and keeps its icons
     * readable on that colour.
     */
    private fun updateNavigationBar() {
        val color = (if (statsBarVisible && statsBarColor != 0) statsBarColor
        else getColorAttr(android.R.attr.colorBackground)) or 0xFF000000.toInt()
        if (window.navigationBarColor != color) window.navigationBarColor = color
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val light = ColorUtils.calculateLuminance(color) > 0.45
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (controller.isAppearanceLightNavigationBars != light) controller.isAppearanceLightNavigationBars = light
    }

    /** Page picked in the drawer that still has to be built; shown once the drawer has closed. */
    @IdRes
    private var pendingNavigationId = 0

    private val drawerNavigator = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerClosed(drawerView: View) {
            runPendingNavigation()
        }
    }

    private fun runPendingNavigation() {
        val id = pendingNavigationId
        if (id == 0) return
        pendingNavigationId = 0
        if (!isFinishing && !isDestroyed && !isCurrentFragment(id)) displayFragmentWithId(id)
    }

    /**
     * A page already built (kept alive, see [KEPT_PAGES]) is shown at once, while the drawer slides shut over it.
     * A page that still has to be inflated waits until the drawer has closed, so its inflation never lands on the
     * frames of the close animation (that was the stutter when opening Route or Settings).
     */
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        val drawer = binding.drawerLayout
        if (isCurrentFragment(id)) {
            pendingNavigationId = 0
            drawer.closeDrawers()
            return true
        }
        if (id == R.id.nav_faq || !drawer.isDrawerVisible(GravityCompat.START) || isPageReady(id)) {
            pendingNavigationId = 0
            val shown = displayFragmentWithId(id)
            drawer.closeDrawer(GravityCompat.START)
            return shown
        }
        pendingNavigationId = id
        setCheckedItem(id)
        drawer.closeDrawer(GravityCompat.START)
        return true
    }

    private fun pageTag(@IdRes id: Int) = "page:$id"

    /** The page on screen: the one fragment in the holder that is added and not hidden. */
    private fun visiblePage(): ToolbarFragment? = supportFragmentManager.fragments.lastOrNull {
        it.id == R.id.fragment_holder && it.isAdded && !it.isHidden
    } as? ToolbarFragment

    /**
     * Rebuild the kept profile list only when a setting that changes how it is drawn actually changed (card style,
     * layout, tabs, addresses...). It used to be rebuilt after every visit to Settings, so going back to it from
     * Settings re-inflated the whole pager and every group list: the stutter when opening 配置 again.
     */
    private var configurationStale = false

    private fun isPageReady(@IdRes id: Int): Boolean {
        if (id !in KEPT_PAGES) return false
        if (id == R.id.nav_configuration && configurationStale) return false
        return supportFragmentManager.findFragmentByTag(pageTag(id))?.view != null
    }

    private fun newPage(@IdRes id: Int): ToolbarFragment? = when (id) {
        R.id.nav_configuration -> ConfigurationFragment()
        R.id.nav_group -> GroupFragment()
        R.id.nav_route -> RouteFragment()
        R.id.nav_settings -> SettingsFragment()
        R.id.nav_dashboard -> WebviewFragment()
        R.id.nav_tools -> ToolsFragment()
        R.id.nav_logcat -> LogcatFragment()
        R.id.nav_connections -> ConnectionsFragment()
        R.id.nav_docs -> DocsFragment()
        R.id.nav_about -> AboutFragment()
        else -> null
    }

    /**
     * Shows page [id]. The main pages are kept (hidden, not destroyed) when you leave them, so going back to them
     * is instant; live pages (logs, connections, dashboard) are removed so they stop polling in the background.
     */
    private fun showPage(@IdRes id: Int): Boolean {
        val fm = supportFragmentManager
        val tag = pageTag(id)
        var target = fm.findFragmentByTag(tag) as? ToolbarFragment
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        var stale: Fragment? = null
        if (target != null && id == R.id.nav_configuration && configurationStale) {
            stale = target
            tx.remove(target)
            target = null
        }
        if (id == R.id.nav_configuration) configurationStale = false
        val reuse = target != null
        if (target == null) target = newPage(id) ?: return false
        tx.setCustomAnimations(R.anim.page_enter, 0)
        val leaving = ArrayList<Fragment>()
        for (f in fm.fragments) {
            if (f === target || f === stale || f.id != R.id.fragment_holder) continue
            val keptId = KEPT_PAGES.firstOrNull { pageTag(it) == f.tag }
            if (keptId == null) leaving.add(f)
            if (!f.isHidden) tx.hide(f)
        }
        // Live pages (logs, connections, dashboard WebView) are hidden now and destroyed once the new page's fade-in
        // is over: tearing a WebView down in the same frame as showing 配置 was a visible hitch.
        if (leaving.isNotEmpty()) binding.root.postDelayed({
            if (isFinishing || isDestroyed || fm.isStateSaved) return@postDelayed
            val tx2 = fm.beginTransaction().setReorderingAllowed(true)
            var any = false
            for (f in leaving) if (f.isAdded && f.isHidden && f !== currentMainFragment) {
                tx2.remove(f); any = true
            }
            if (any) tx2.commitAllowingStateLoss()
        }, 320L)
        if (reuse) tx.show(target) else tx.add(R.id.fragment_holder, target, tag)
        tx.commitAllowingStateLoss()
        // a page built while hidden may have missed the window insets (toolbar / navigation-bar padding)
        if (reuse) target.view?.let { androidx.core.view.ViewCompat.requestApplyInsets(it) }
        currentMainFragment = target
        syncMainControls(target, showWhenConnected = false, animate = true)
        updateTrafficSubscription()
        return true
    }

    /**
     * Converts the geo rule-sets the selected node's config uses into .srs files while the app is idle. On a fresh
     * install (or after a rule database update) the start used to do this itself, and the start button spun for a
     * couple of turns on the first tap. Files already up to date are only checked, so this is cheap afterwards.
     */
    private fun prewarmRuleSets() {
        runOnDefaultDispatcher {
            kotlinx.coroutines.delay(PREWARM_DELAY_MS)
            if (DataStore.serviceState != BaseService.State.Stopped) return@runOnDefaultDispatcher
            runCatching {
                val profile = io.nekohasekai.sagernet.database.SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                    ?: return@runCatching
                Libcore.prewarmGeoRuleSets(io.nekohasekai.sagernet.fmt.buildConfig(profile).config)
            }.onFailure { Logs.w(it) }
        }
    }

    /**
     * Builds Route and Settings in the background once the main screen is idle, hidden, so their first visit is as
     * instant as any later one.
     */
    private fun schedulePagePrewarm() {
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            android.os.Looper.myQueue().addIdleHandler(object : android.os.MessageQueue.IdleHandler {
                val queue = ArrayDeque(PREWARM_PAGES)
                override fun queueIdle(): Boolean {
                    if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return false
                    val id = queue.removeFirstOrNull() ?: run {
                        // Load the system WebView once while idle, so the first open of the sing-box dashboard does
                        // not pay the WebView start-up (often the better part of a second) on tap.
                        if (DataStore.enableClashAPI) runCatching {
                            android.webkit.WebSettings.getDefaultUserAgent(this@MainActivity)
                        }
                        return false
                    }
                    val fm = supportFragmentManager
                    if (fm.findFragmentByTag(pageTag(id)) == null && !isCurrentFragment(id)) {
                        val page = newPage(id) ?: return true
                        fm.beginTransaction()
                            .setReorderingAllowed(true)
                            .add(R.id.fragment_holder, page, pageTag(id))
                            .hide(page)
                            .commitAllowingStateLoss()
                    }
                    return true // one more idle pass: the empty-queue branch warms the WebView, then stops
                }
            })
        }, PREWARM_DELAY_MS)
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        currentMainFragment = fragment
        val tx = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        for (f in supportFragmentManager.fragments) if (f.id == R.id.fragment_holder) tx.remove(f)
        tx.add(R.id.fragment_holder, fragment).commitAllowingStateLoss()
        if (binding.drawerLayout.isDrawerVisible(GravityCompat.START)) binding.drawerLayout.closeDrawers()
        syncMainControls(fragment, showWhenConnected = false, animate = true)
    }

    private fun syncMainControls(
        fragment: Any? = currentMainFragment ?: visiblePage(),
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        val showControls = fragment is ConfigurationFragment || DataStore.showBottomBar
        binding.stats.useExternalScrollDriver = fragment is ConfigurationFragment
        binding.stats.syncMainControls(
            showControls,
            DataStore.serviceState,
            showWhenConnected,
            animate,
        )
        binding.fab.animate().cancel()
        if (showControls) {
            binding.fab.translationY = 0f
            binding.fab.translationX = 0f
            binding.fab.show()
        } else {
            binding.fab.hideProgress()
            binding.fabProgress.hide()
            binding.fabProgress.visibility = View.INVISIBLE
            if (animate && binding.fab.isLaidOut) {
                binding.fab.hide()
            } else {
                binding.fab.visibility = View.INVISIBLE
            }
        }
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment ?: visiblePage()
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun driveBottomBar(scrollDy: Int) {
        binding.stats.onListScrolled(scrollDy)
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        if (id == R.id.nav_faq) {
            launchCustomTab("https://t.me/OwnBoxs")
            return false
        }
        if (!showPage(id)) return false
        setCheckedItem(id)
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        animateControls: Boolean = animate,
    ) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()
        io.nekohasekai.sagernet.widget.OwnBoxWidgetProvider.updateWidgets(this)

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        syncMainControls(
            showWhenConnected = state == BaseService.State.Connected,
            animate = animateControls,
        )
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
        binding.stats.onActiveLeafUpdate(stats.activeLeafId)
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
        // the landing IP follows from Key.PROFILE_ID below; a second forced lookup here doubled every switch
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (isDestroyed || isFinishing) return@runOnMainDispatcher
            when (key) {
                Key.SERVICE_MODE -> onBinderDied()
                Key.GROUP_LAYOUT_MODE, Key.PROFILE_CARD_STYLE, Key.SHOW_SUBSCRIPTION_INFO_CARD,
                Key.SHOW_ALL_GROUPS_TAB, Key.ALL_GROUPS_ORDER, Key.ALWAYS_SHOW_ADDRESS -> {
                    if (currentMainFragment !is ConfigurationFragment) configurationStale = true
                }
                Key.PROFILE_ID -> {
                    LandingIpManager.clearCache()
                    if (DataStore.serviceState.connected && DataStore.showLandingIp) {
                        binding.stats.onProfileSwitched()
                    }
                }
                Key.SHOW_BOTTOM_BAR -> {
                    syncMainControls(
                        showWhenConnected = DataStore.showBottomBar,
                        animate = true,
                    )
                    // kept (hidden) pages need the new padding too
                    for (fragment in supportFragmentManager.fragments) when (fragment) {
                        is GroupFragment -> fragment.updateBottomPadding()
                        is RouteFragment -> fragment.updateBottomPadding()
                    }
                }
                Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                    if (DataStore.serviceState.canStop) {
                        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                            SagerNet.reloadService()
                        }.show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(trafficConnectionId())
        super.onStart()
    }

    /**
     * Live speed and per-profile traffic are only drawn on the profile list (its rows and the stats bar). On every
     * other page the service is told the UI is in the background, so its traffic loop drops from the 1 s
     * foreground rate to the slow rate instead of waking the CPU every second for numbers nobody sees.
     */
    private fun trafficConnectionId(): Int =
        if ((currentMainFragment ?: visiblePage()) is ConfigurationFragment) SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
        else SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND

    private fun updateTrafficSubscription() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        connection.updateConnectionId(trafficConnectionId())
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
        if (!DataStore.performancePriorityMode) {
            Libcore.forceGc()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment = currentMainFragment ?: visiblePage()
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }


    companion object {
        /** Pages kept alive (hidden) when you leave them. */
        private val KEPT_PAGES = setOf(
            R.id.nav_configuration, R.id.nav_group, R.id.nav_route, R.id.nav_settings,
            R.id.nav_tools, R.id.nav_docs, R.id.nav_about,
        )
        private val PREWARM_PAGES = listOf(R.id.nav_route, R.id.nav_settings, R.id.nav_group)
        private const val PREWARM_DELAY_MS = 1500L
    }
}
