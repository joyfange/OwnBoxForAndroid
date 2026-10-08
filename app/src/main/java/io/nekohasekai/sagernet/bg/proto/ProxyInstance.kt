package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

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
            if (!closing && notTmp && service != null && io.nekohasekai.sagernet.database.DataStore.appTrafficStatistics) {
                appTrafficRecorder = runCatching { AppTrafficRecorder(box).also { it.start() } }
                    .onFailure { Logs.w(it) }.getOrNull()
            }
        }
    }

    override fun close() {
        closing = true
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
