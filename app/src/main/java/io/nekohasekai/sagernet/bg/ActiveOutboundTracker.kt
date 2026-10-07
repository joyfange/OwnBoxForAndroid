package io.nekohasekai.sagernet.bg

import android.content.Context
import android.text.format.Formatter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

object ActiveOutboundTracker {

    @Volatile
    var activeLeafProfileId: Long = 0L
        private set

    @Volatile
    var activeLeafProfileName: String = ""
        private set

    fun reset() {
        activeLeafProfileId = 0L
        activeLeafProfileName = ""
        lastCheckAt = 0L
        lastCheckedProfileId = 0L
    }

    // --- Battery: the traffic loop and the notification ask about the current group on every tick (up to once a
    // second). Group rows, strategy flags and member ids change rarely, so they are cached for a few seconds instead
    // of re-reading (and deserialising every member of) the group from the database each time.
    private const val GROUP_CACHE_TTL_MS = 10_000L
    private const val MIN_CHECK_INTERVAL_MS = 2_000L

    private class CachedGroup(val group: ProxyGroup?, val at: Long)
    private class CachedMembers(val groupId: Long, val ids: List<Long>, val at: Long)

    private val groupCache = java.util.concurrent.ConcurrentHashMap<Long, CachedGroup>()
    @Volatile
    private var memberCache: CachedMembers? = null
    @Volatile
    private var lastCheckAt = 0L
    @Volatile
    private var lastCheckedProfileId = 0L

    private fun now() = android.os.SystemClock.elapsedRealtime()

    /** The group row, cached briefly (null when missing). */
    fun cachedGroup(groupId: Long): ProxyGroup? {
        val hit = groupCache[groupId]
        val t = now()
        if (hit != null && t - hit.at < GROUP_CACHE_TTL_MS) return hit.group
        val group = runCatching { SagerDatabase.groupDao.getById(groupId) }.getOrNull()
        groupCache[groupId] = CachedGroup(group, t)
        return group
    }

    private fun cachedMemberIds(groupId: Long): List<Long>? {
        val hit = memberCache
        val t = now()
        if (hit != null && hit.groupId == groupId && t - hit.at < GROUP_CACHE_TTL_MS) return hit.ids
        val ids = runCatching { SagerDatabase.proxyDao.getByGroup(groupId).map { it.id } }.getOrNull() ?: return null
        memberCache = CachedMembers(groupId, ids, t)
        return ids
    }

    /** Drops cached group data (after the user edits groups or switches profiles). */
    fun invalidateCache() {
        groupCache.clear()
        memberCache = null
        lastCheckAt = 0L
    }

    fun getStrategyDisplayName(profile: ProxyEntity): String {
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.requireBean() as? BalancerBean
            return when (bean?.strategy) {
                BalancerBean.STRATEGY_LEAST_PING -> "最低延迟"
                BalancerBean.STRATEGY_LEAST_LOAD -> "最低负载"
                BalancerBean.STRATEGY_RANDOM -> "随机"
                BalancerBean.STRATEGY_ROUND_ROBIN, BalancerBean.STRATEGY_ROUND_ROBIN_LEGACY -> "轮询"
                BalancerBean.STRATEGY_FAILOVER -> "故障转移"
                BalancerBean.STRATEGY_STABLE -> "最稳定"
                BalancerBean.STRATEGY_CONSISTENT_HASH, BalancerBean.STRATEGY_CONSISTENT_HASH_CAMEL -> "一致性哈希"
                else -> "策略组"
            }
        }
        val group = cachedGroup(profile.groupId)
        if (group != null) {
            if (runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false)) return "自动测速"
            if (runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)) return "负载均衡"
        }
        val isGlobal = runCatching { DataStore.globalMode }.getOrDefault(false)
        return if (isGlobal) "全局模式" else "规则分流"
    }

    fun onProfileSwitched(newProfile: ProxyEntity) {
        val oldId = activeLeafProfileId
        reset()
        invalidateCache()
        if (oldId > 0L) {
            runOnDefaultDispatcher {
                ProfileManager.postUpdate(oldId, true)
                ProfileManager.postUpdate(newProfile.id, true)
            }
        }
    }

    fun updateActiveLeaf(candidateId: Long, candidateName: String) {
        activeLeafProfileId = candidateId
        activeLeafProfileName = candidateName
    }

    fun getActiveLeafNodeDisplay(profile: ProxyEntity): String? {
        val isBalancer = profile.type == ProxyEntity.TYPE_BALANCER
        val group = cachedGroup(profile.groupId)
        val isGroupStrategy = group != null && (
            runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)
        )
        if (!isBalancer && !isGroupStrategy) return null

        val leafId = activeLeafProfileId
        if (leafId > 0L && leafId != profile.id) {
            val name = activeLeafProfileName.takeIf { it.isNotBlank() }
                ?: runCatching { SagerDatabase.proxyDao.getById(leafId)?.displayName() }.getOrNull()
            return name?.takeIf { it.isNotBlank() }
        }
        return null
    }

    data class NotificationTextBundle(
        val title: String,
        val collapsedText: String,
        val bigText: String,
    )

    fun formatNotificationTitle(
        profile: ProxyEntity,
        isGlobalMode: Boolean? = null
    ): String {
        val isBalancer = profile.type == ProxyEntity.TYPE_BALANCER
        val group = cachedGroup(profile.groupId)
        val isGroupStrategy = group != null && (
            runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)
        )
        val isStrategy = isBalancer || isGroupStrategy

        if (isStrategy) {
            val leafNode = getActiveLeafNodeDisplay(profile)
            val showGroup = runCatching { DataStore.showGroupInNotification }.getOrDefault(true)
            if (!showGroup && !leafNode.isNullOrBlank()) {
                // 用户关闭了“显示分组”：策略组下直接以当前连上的节点名作为主标题
                return leafNode
            }
            val baseName = if (isBalancer) profile.displayName() else (group?.displayName() ?: profile.displayName())
            if (!leafNode.isNullOrBlank()) {
                return if (baseName.isNotBlank() && !baseName.contains(leafNode)) {
                    "$baseName · $leafNode"
                } else {
                    leafNode
                }
            }
            return baseName
        }

        return runCatching { ServiceNotification.genTitle(profile) }.getOrDefault(profile.displayName())
    }

    fun buildNotificationTexts(
        profile: ProxyEntity?,
        leafNode: String?,
        strategyName: String,
        groupName: String?,
        showGroup: Boolean,
        showDirectSpeed: Boolean,
        proxySpeed: String,
        directSpeed: String,
    ): NotificationTextBundle {
        if (profile == null) {
            return NotificationTextBundle(
                title = "",
                collapsedText = "代理: $proxySpeed",
                bigText = "代理: $proxySpeed" + if (showDirectSpeed) "\n直连: $directSpeed" else ""
            )
        }

        val isBalancer = profile.type == ProxyEntity.TYPE_BALANCER
        val isGroupStrategy = groupName != null && (
            runCatching { DataStore.isGroupUrlTest(profile.groupId) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(profile.groupId) }.getOrDefault(false)
        )
        val isStrategy = isBalancer || isGroupStrategy

        val title: String
        val collapsedText: String
        val bigContent: String

        if (isStrategy) {
            if (showGroup) {
                // Template 1: 策略组 + 显示组名开启
                val baseGroupName = if (isBalancer) profile.displayName() else (groupName ?: profile.displayName())
                title = if (!leafNode.isNullOrBlank()) {
                    if (baseGroupName.isNotBlank() && !baseGroupName.contains(leafNode)) {
                        "$baseGroupName · $leafNode"
                    } else {
                        leafNode
                    }
                } else {
                    baseGroupName
                }
                collapsedText = "代理: $proxySpeed"
                bigContent = buildString {
                    append("代理: ").append(proxySpeed)
                    if (showDirectSpeed) {
                        append("\n直连: ").append(directSpeed)
                    }
                }
            } else {
                // Template 2: 策略组 + 显示组名关闭
                title = if (!leafNode.isNullOrBlank()) leafNode else profile.displayName()
                collapsedText = "代理: $proxySpeed"
                bigContent = buildString {
                    append("代理: ").append(proxySpeed)
                    if (showDirectSpeed) {
                        append("\n直连: ").append(directSpeed)
                    }
                }
            }
        } else {
            val profileName = profile.displayName()
            if (showGroup && !groupName.isNullOrBlank()) {
                // Template 3: 普通单节点 + 显示组名开启
                title = if (!profileName.startsWith("$groupName · ")) {
                    "$groupName · $profileName"
                } else {
                    profileName
                }
            } else {
                // Template 4: 普通单节点 + 显示组名关闭
                title = profileName
            }
            collapsedText = "代理: $proxySpeed"
            bigContent = buildString {
                append("代理: ").append(proxySpeed)
                if (showDirectSpeed) {
                    append("\n直连: ").append(directSpeed)
                }
            }
        }

        return NotificationTextBundle(
            title = title,
            collapsedText = collapsedText,
            bigText = bigContent
        )
    }

    fun formatNotificationSubText(
        context: Context,
        stats: SpeedDisplayData,
        profile: ProxyEntity,
    ): String {
        val trafficStr = context.getString(
            R.string.traffic,
            Formatter.formatFileSize(context, stats.txTotal),
            Formatter.formatFileSize(context, stats.rxTotal)
        )
        val strategyStr = getStrategyDisplayName(profile)
        return "$trafficStr · $strategyStr"
    }

    private fun queryClashNowTag(groupTag: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            // tags are node / group names (Chinese, spaces, emoji): encode them or the URL is invalid
            val encoded = URLEncoder.encode(groupTag, "UTF-8").replace("+", "%20")
            val url = URL("http://127.0.0.1:9090/proxies/$encoded")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 300
                readTimeout = 300
                requestMethod = "GET"
                val secret = DataStore.clashApiSecret
                if (secret.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $secret")
                }
            }
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val content = reader.use { it.readText() }
                val json = JSONObject(content)
                json.optString("now").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun checkAndUpdate(data: BaseService.Data): Boolean {
        val proxy = data.proxy ?: return false
        if (!proxy.isInitialized()) return false
        val profile = proxy.profile
        // Throttle: the node a strategy group routes through cannot be told apart faster than its URL test runs
        // anyway, and each check may hit the local Clash API. Re-check at most every 2 s (immediately on a switch).
        val t = now()
        if (profile.id == lastCheckedProfileId && t - lastCheckAt < MIN_CHECK_INTERVAL_MS) return false
        lastCheckedProfileId = profile.id
        lastCheckAt = t
        val isBalancer = profile.type == ProxyEntity.TYPE_BALANCER
        val group = cachedGroup(profile.groupId)
        val isGroupStrategy = group != null && (
            runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)
        )

        if (!isBalancer && !isGroupStrategy) {
            if (activeLeafProfileId != profile.id) {
                activeLeafProfileId = profile.id
                activeLeafProfileName = profile.displayName()
                return true
            }
            return false
        }

        // Strategy group: resolve active member
        val balancerMembers = runCatching { proxy.safeConfig?.balancerMemberMap?.get(profile.id) }.getOrNull()
        val memberMap = balancerMembers
            ?: if (isGroupStrategy) cachedMemberIds(group!!.id) else null

        if (memberMap.isNullOrEmpty()) {
            if (activeLeafProfileId != 0L) {
                reset()
                return true
            }
            return false
        }

        val config = proxy.safeConfig
        val tagMap = runCatching { config?.profileTagMap }.getOrNull().orEmpty()
        val nestedMembers = runCatching { config?.balancerMemberMap }.getOrNull().orEmpty()
        // Every node this group can route through, nested groups included (a group of groups, e.g. 自动 -> 日本 ->
        // 日本 01). The old code stopped at the first level and named the inner group, not the node.
        val reachable = HashSet<Long>()
        fun collect(ids: List<Long>, depth: Int) {
            for (id in ids) if (reachable.add(id) && depth < 6) nestedMembers[id]?.let { collect(it, depth + 1) }
        }
        collect(memberMap, 0)

        fun idForTag(tag: String?): Long? {
            if (tag.isNullOrBlank()) return null
            return tagMap.entries.firstOrNull { it.value == tag }?.key?.let { abs(it) }?.takeIf { it in reachable }
        }

        val groupTag = runCatching { tagMap[profile.id] }.getOrNull()?.takeIf { it.isNotBlank() && isBalancer }
            ?: if (isGroupStrategy) "proxy" else profile.displayName()

        // 1. Ask the core directly: it follows every nested group down to the real node, in process, and works
        //    whether or not the Clash API is enabled. This is the node the traffic actually leaves through.
        var candidateId: Long? = runCatching { proxy.box.getActiveOutboundTag(groupTag) }.getOrNull()
            ?.takeIf { it != groupTag }
            ?.let { idForTag(it) }

        // 2. Clash API, followed down nested groups the same way.
        if (candidateId == null) {
            var tag: String? = groupTag
            var depth = 0
            while (tag != null && depth < 6) {
                val next = queryClashNowTag(tag) ?: break
                if (next == tag) break
                tag = next
                depth++
            }
            if (depth > 0) candidateId = idForTag(tag)
        }

        // 3. The member that is moving traffic right now.
        if (candidateId == null) {
            candidateId = proxy.looper?.getActiveTransmittingMember(memberMap)?.takeIf { it > 0L }
        }

        // 4. Unknown: keep the last known node, and never guess the first member. Guessing showed a node in the
        //    notification (and keyed the landing IP to it) that the traffic was not using.
        if (candidateId == null && activeLeafProfileId !in reachable && activeLeafProfileId != 0L) {
            reset()
            lastCheckedProfileId = profile.id
            lastCheckAt = t
            return true
        }

        if (candidateId != null && candidateId > 0L && candidateId != activeLeafProfileId) {
            val oldId = activeLeafProfileId
            activeLeafProfileId = candidateId
            val ent = runCatching { SagerDatabase.proxyDao.getById(candidateId) }.getOrNull()
            activeLeafProfileName = ent?.displayName() ?: ""
            Logs.i("ActiveOutboundTracker: active node changed $oldId -> $candidateId ($activeLeafProfileName)")

            runOnDefaultDispatcher {
                if (oldId > 0L) ProfileManager.postUpdate(oldId, true)
                ProfileManager.postUpdate(candidateId, true)
                ProfileManager.postUpdate(profile.id, true)
            }
            return true
        }

        return false
    }

}
