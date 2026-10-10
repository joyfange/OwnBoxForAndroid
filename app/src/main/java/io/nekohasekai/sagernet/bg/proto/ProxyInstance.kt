package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    companion object {
        private const val URL_TEST_SYNC_INTERVAL_MS = 15_000L
    }

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    // 按应用流量统计
    @Volatile
    private var appTrafficRecorder: AppTrafficRecorder? = null
    @Volatile
    private var closing = false

    private var urlTestSyncJob: kotlinx.coroutines.Job? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        // 完整配置含节点密码/UUID/Clash 密钥，只在调试版写日志（写入时也会打码）
        if (notTmp && BuildConfig.DEBUG) Logs.d(config.config)
        if (notTmp && BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(content)
        }
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
            if (!closing && notTmp && service != null) urlTestSyncJob = startUrlTestSync()
            if (!closing && notTmp && service != null && io.nekohasekai.sagernet.database.DataStore.appTrafficStatistics) {
                appTrafficRecorder = runCatching { AppTrafficRecorder(box).also { it.start() } }
                    .onFailure { Logs.w(it) }.getOrNull()
            }
        }
    }

    /**
     * Policy-group (urltest) results written back to the profile list, after Exclave's observatory write-back.
     * Every [URL_TEST_SYNC_INTERVAL_MS] the core is asked for results newer than the previous pass; a node whose
     * delay changed gets the same ping/status fields a manual test writes, and the UI is told once per pass
     * (it updates those cards in place, without re-sorting). Results restored from the last run are skipped:
     * only tests made while this instance runs count, so a fresher manual result is never overwritten by an
     * older automatic one. Failed tests are not reported by the core and leave the card as it was.
     */
    private fun startUrlTestSync() = runOnDefaultDispatcher {
        var since = System.currentTimeMillis()
        val lastWritten = HashMap<Long, Int>()
        while (!closing) {
            kotlinx.coroutines.delay(URL_TEST_SYNC_INTERVAL_MS)
            if (closing) break
            try {
                val raw = box.urlTestResultsSince(since)
                if (raw.isNullOrBlank()) continue
                val tagToId = HashMap<String, Long>()
                // keys are profile ids; a group-type balancer member is keyed by its negated id
                config.profileTagMap.forEach { (key, tag) ->
                    if (tag.isNotBlank() && key != 0L) tagToId[tag] = kotlin.math.abs(key)
                }
                val results = org.json.JSONObject(raw)
                val changed = ArrayList<Long>()
                val keys = results.keys()
                while (keys.hasNext()) {
                    val tag = keys.next()
                    val item = results.optJSONObject(tag) ?: continue
                    val delay = item.optInt("d", 0)
                    val time = item.optLong("t", 0L)
                    if (time > since) since = time
                    val id = tagToId[tag] ?: continue
                    if (delay <= 0 || lastWritten[id] == delay) continue
                    SagerDatabase.proxyDao.updatePingResult(id, 1, delay, null)
                    lastWritten[id] = delay
                    changed.add(id)
                }
                if (changed.isNotEmpty() && !closing) {
                    val ids = changed.toLongArray()
                    service?.data?.binder?.broadcast { it.cbUrlTestUpdate(ids) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    override fun close() {
        closing = true
        urlTestSyncJob?.cancel()
        urlTestSyncJob = null
        // 在内核关闭前补记最后一段应用流量（关闭后连接表就没了）
        appTrafficRecorder?.let { recorder ->
            appTrafficRecorder = null
            runCatching {
                runBlocking { kotlinx.coroutines.withTimeoutOrNull(2_000L) { recorder.stop() } }
            }.onFailure { Logs.w(it) }
        }
        var closeError: Throwable? = null
        try {
            super.close()
        } catch (error: Throwable) {
            closeError = error
        }
        try {
            runBlocking {
                looper?.stop()
                looper = null
            }
        } catch (error: Throwable) {
            if (closeError == null) {
                closeError = error
            } else if (closeError !== error) {
                closeError?.addSuppressed(error)
            }
        }
        closeError?.let { throw it }
    }
}
