package io.nekohasekai.sagernet.ui.connections

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tracked connection as reported by libcore `BoxInstance.queryConnections`
 * (sing-box common/trafficcontrol TrackerMetadata, see libcore/connections.go).
 */
data class ConnectionInfo(
    val id: String,
    val inbound: String,
    val inboundType: String,
    val ipVersion: Int,
    val network: String,
    val source: String,
    val destination: String,
    val domain: String,
    val protocol: String,
    val user: String,
    val createdAt: Long,
    val closedAt: Long,
    val upload: Long,
    val download: Long,
    val rule: String,
    val outbound: String,
    val outboundType: String,
    val chain: List<String>,
    val userId: Int,
    val userName: String,
    val processPath: String,
    val packageNames: List<String>,
) {
    val isActive: Boolean get() = closedAt == 0L

    /** "host:port" preferring the sniffed / requested domain over the raw IP. */
    val displayDestination: String
        get() {
            if (domain.isBlank()) return destination
            val port = destination.substringAfterLast(':', "")
            return if (port.isNotEmpty() && port.all { it.isDigit() }) "$domain:$port" else domain
        }

    /** Chain from the first hop to the final outbound, e.g. "proxy → node". */
    val displayChain: String
        get() = chain.filter { it.isNotBlank() }.joinToString(" → ").ifBlank { outbound }

    val packageName: String get() = packageNames.firstOrNull().orEmpty()

    companion object {
        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
        }

        fun fromJson(o: JSONObject) = ConnectionInfo(
            id = o.optString("id"),
            inbound = o.optString("inbound"),
            inboundType = o.optString("inboundType"),
            ipVersion = o.optInt("ipVersion"),
            network = o.optString("network"),
            source = o.optString("source"),
            destination = o.optString("destination"),
            domain = o.optString("domain"),
            protocol = o.optString("protocol"),
            user = o.optString("user"),
            createdAt = o.optLong("createdAt"),
            closedAt = o.optLong("closedAt"),
            upload = o.optLong("upload"),
            download = o.optLong("download"),
            rule = o.optString("rule"),
            outbound = o.optString("outbound"),
            outboundType = o.optString("outboundType"),
            chain = o.optJSONArray("chain").strings(),
            userId = o.optInt("userId", -1),
            userName = o.optString("userName"),
            processPath = o.optString("processPath"),
            packageNames = o.optJSONArray("packageNames").strings(),
        )

        fun parseSnapshot(json: String?): List<ConnectionInfo> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONObject(json).optJSONArray("connections") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

object ConnectionFilter {
    const val ACTIVE = 1
    const val CLOSED = 2
    const val ALL = 3
}
