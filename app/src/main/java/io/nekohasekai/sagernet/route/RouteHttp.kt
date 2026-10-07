package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util

/**
 * Plain-text downloads for the routing screens (remote route profiles, the routeprofiles snapshot), the OwnBox
 * counterpart of Throne's ktx.fetchText. Like the subscription updater it goes through the running core's default
 * outbound when connected and falls back to the direct network.
 */
object RouteHttp {

    class Result(val body: String)

    fun fetchText(url: String): Result {
        fun execute(useProxy: Boolean): String {
            val client = Libcore.newHttpClient().apply {
                if (useProxy) tryProxyOutbound()
                if (DataStore.appTLSVersion == "1.3") restrictedTLS()
            }
            try {
                val request = client.newRequest().apply {
                    if (DataStore.allowInsecureOnRequest) allowInsecure()
                    setURL(url)
                }
                return Util.getStringBox(request.execute().contentString)
            } finally {
                runCatching { client.close() }
            }
        }
        if (DataStore.serviceState.connected) {
            try {
                return Result(execute(useProxy = true))
            } catch (e: Throwable) {
                Logs.w("route fetch via proxy failed (${e.readableMessage}), retrying direct")
            }
        }
        return Result(execute(useProxy = false))
    }
}
