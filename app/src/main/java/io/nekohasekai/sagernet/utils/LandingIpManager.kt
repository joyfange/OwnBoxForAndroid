package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.util.Locale

data class LandingIpInfo(
    val ip: String,
    val country: String,
    val countryCode: String,
    val countryFlag: String,
    val city: String,
    val region: String,
    val isp: String,
    val org: String,
    val asn: String,
    val durationMs: Long,
    val queryTimestamp: Long = System.currentTimeMillis(),
) {
    val briefText: String
        get() = if (durationMs > 0) {
            "$countryFlag $countryCode $ip · 查询耗时 ${durationMs} 毫秒".trim()
        } else {
            "$countryFlag $countryCode $ip".trim()
        }

    val locationText: String
        get() {
            val parts = mutableListOf<String>()
            if (country.isNotBlank()) parts.add(country)
            if (city.isNotBlank() && city != country) parts.add(city)
            val base = parts.joinToString(" · ")
            return if (countryCode.isNotBlank()) "$countryFlag $base ($countryCode)" else "$countryFlag $base"
        }
}

object LandingIpManager {

    private const val CACHE_TTL_MS = 60_000L // 60 秒自动过期，确保节点切换与出网变动实时精准

    @Volatile
    private var currentCache: LandingIpInfo? = null

    @Volatile
    var cachedProfileId: Long = -1L
        private set

    /** Number of lookups in flight. A boolean was cleared by an older, superseded lookup while a newer one still
     *  ran, so the bar flashed the "(点击重试)" fallback in the middle of a query. */
    private val activeQueries = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Last confirmed exit per node (by profile id), kept across app restarts in app-private prefs. Opening the app
     * shows the node's last exit at once instead of "正在查询…"; a result younger than [FRESH_MS] is used as is,
     * an older one stays on screen while a single silent lookup refreshes it.
     */
    const val FRESH_MS = 10 * 60_000L
    private const val LAST_GOOD_TTL_MS = 24 * 60 * 60_000L
    private const val LAST_GOOD_MAX = 64
    private val lastGood = java.util.concurrent.ConcurrentHashMap<Long, LandingIpInfo>()

    @Volatile
    private var lastGoodLoaded = false

    private fun prefs() = io.nekohasekai.sagernet.SagerNet.application
        .getSharedPreferences("landing_ip_cache", android.content.Context.MODE_PRIVATE)

    private fun LandingIpInfo.toJson(): String = JSONObject().apply {
        put("ip", ip); put("country", country); put("cc", countryCode); put("city", city)
        put("region", region); put("isp", isp); put("org", org); put("asn", asn)
        put("ms", durationMs); put("ts", queryTimestamp)
    }.toString()

    private fun infoFromJson(raw: String): LandingIpInfo? = runCatching {
        val j = JSONObject(raw)
        val cc = j.optString("cc")
        LandingIpInfo(
            ip = j.getString("ip"),
            country = j.optString("country"),
            countryCode = cc,
            countryFlag = countryCodeToFlagEmoji(cc),
            city = j.optString("city"),
            region = j.optString("region"),
            isp = j.optString("isp"),
            org = j.optString("org"),
            asn = j.optString("asn"),
            durationMs = j.optLong("ms"),
            queryTimestamp = j.optLong("ts"),
        ).takeIf { it.ip.isNotBlank() }
    }.getOrNull()

    private fun ensureLastGoodLoaded() {
        if (lastGoodLoaded) return
        synchronized(lastGood) {
            if (lastGoodLoaded) return
            runCatching {
                val now = System.currentTimeMillis()
                for ((k, v) in prefs().all) {
                    val id = k.toLongOrNull() ?: continue
                    val info = infoFromJson(v as? String ?: continue) ?: continue
                    if (now - info.queryTimestamp < LAST_GOOD_TTL_MS) lastGood.putIfAbsent(id, info)
                }
            }
            lastGoodLoaded = true
        }
    }

    private fun rememberGood(profileId: Long, info: LandingIpInfo) {
        ensureLastGoodLoaded()
        lastGood[profileId] = info
        runCatching {
            val editor = prefs().edit()
            editor.putString(profileId.toString(), info.toJson())
            if (lastGood.size > LAST_GOOD_MAX) {
                lastGood.entries.sortedBy { it.value.queryTimestamp }.take(lastGood.size - LAST_GOOD_MAX).forEach {
                    lastGood.remove(it.key)
                    editor.remove(it.key.toString())
                }
            }
            editor.apply()
        }
    }

    /** The node's last confirmed exit, if younger than [maxAgeMs] (default: 24 h, for display). */
    fun getLastKnown(profileId: Long, maxAgeMs: Long = LAST_GOOD_TTL_MS): LandingIpInfo? {
        ensureLastGoodLoaded()
        val info = lastGood[profileId] ?: return null
        return if (System.currentTimeMillis() - info.queryTimestamp < maxAgeMs) info else null
    }

    /** Use a recent remembered result as the current one without a lookup. */
    fun adopt(profileId: Long, info: LandingIpInfo) {
        currentCache = info
        cachedProfileId = profileId
    }

    /** One lookup per node at a time: a second caller for the same node waits for the running one. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<Result<LandingIpInfo>>>()

    /**
     * Bumped by every forced query and by [clearCache]: a slower, older lookup (e.g. one started before the
     * strategy group settled on its node) must not overwrite the result of a newer one.
     */
    @Volatile
    private var generation: Int = 0

    fun clearCache() {
        generation++
        currentCache = null
        cachedProfileId = -1L
    }

    /** On disconnect: drop the session's current result; the per-node history stays for the next start. */
    fun clearAll() {
        clearCache()
    }

    fun getCachedInfo(): LandingIpInfo? = currentCache

    fun isCurrentlyQuerying(): Boolean = activeQueries.get() > 0

    fun updateCachedDuration(duration: Long) {
        currentCache = currentCache?.copy(durationMs = duration)
    }

    fun countryCodeToFlagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "🌐"
        val code = countryCode.uppercase()
        if (!code[0].isLetter() || !code[1].isLetter()) return "🌐"
        val firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    private fun localizeCountry(countryCode: String, fallbackName: String): String {
        if (countryCode.length != 2) return fallbackName
        return runCatching {
            Locale("", countryCode.uppercase()).getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallbackName
    }

    /**
     * 查询失败时的占位文字。之前会按节点名“猜”国旗（"us" 能匹配到 Russia、"de" 能匹配到 Sweden 之类），
     * 经常猜错，现在不再猜，只显示中性的地球图标。
     */
    @Suppress("UNUSED_PARAMETER")
    fun getProfileFallbackDisplay(profileId: Long): String = "🌐 落地 IP 未知 (点击重试)"

    /** :bg 进程里的落地 IP 专用探测入口（libcore/landing_probe.go），文件位于应用私有目录。 */
    private data class Probe(val port: Int, val token: String)

    private fun readProbe(): Probe? = runCatching {
        val lines = java.io.File(io.nekohasekai.sagernet.SagerNet.application.filesDir, "landing_probe")
            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val port = lines[0].toInt()
        val token = lines[1]
        if (port in 1..65535 && token.length >= 32) Probe(port, token) else null
    }.getOrNull()

    /**
     * 每次查询都经 :bg 进程的探测入口、用「当前那个具体节点」直接拨号：
     * 不走分流规则、不经过策略组（不会推进轮询、不会改变当前节点），探测入口不可用就直接失败，绝不直连。
     * [session] 让同一次查询的所有查询源固定从同一个节点出去。
     */
    private fun createHttpClient(session: String): libcore.HTTPClient {
        val probe = readProbe() ?: throw IllegalStateException("landing probe unavailable")
        return Libcore.newHttpClient().apply {
            modernTLS()
            // 冷连接经慢节点：TCP + 节点握手 + 到查询站的 TLS，整体 5 s 封顶
            setTimeout(5000)
            trySocks5(probe.port, "${probe.token}:$session", probe.token)
        }
    }

    private fun fetchIpWhoIs(ua: String, startTime: Long, session: String): LandingIpInfo? {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient(session)
            val req = client.newRequest().apply {
                setURL("https://ipwho.is/")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            if (json.optBoolean("success", false)) {
                val ip = json.optString("ip").trim()
                if (ip.isNotBlank()) {
                    val countryCode = json.optString("country_code").uppercase()
                    val rawCountry = json.optString("country")
                    val country = localizeCountry(countryCode, rawCountry)
                    val flag = countryCodeToFlagEmoji(countryCode)
                    val city = json.optString("city")
                    val region = json.optString("region")
                    val conn = json.optJSONObject("connection")
                    val isp = conn?.optString("isp").orEmpty()
                    val org = conn?.optString("org").orEmpty()
                    val asnNum = conn?.optInt("asn", 0) ?: 0
                    val asn = if (asnNum > 0) "AS$asnNum $org".trim() else org
                    val cost = System.currentTimeMillis() - startTime

                    return LandingIpInfo(
                        ip = ip,
                        country = country,
                        countryCode = countryCode,
                        countryFlag = flag,
                        city = city,
                        region = region,
                        isp = isp,
                        org = org,
                        asn = asn,
                        durationMs = cost,
                    )
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }
        return null
    }

    private fun fetchIpSb(ua: String, startTime: Long, session: String): LandingIpInfo? {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient(session)
            val req = client.newRequest().apply {
                setURL("https://api.ip.sb/geoip")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            val ip = json.optString("ip").trim()
            if (ip.isNotBlank()) {
                val countryCode = json.optString("country_code").uppercase()
                val rawCountry = json.optString("country")
                val country = localizeCountry(countryCode, rawCountry)
                val flag = countryCodeToFlagEmoji(countryCode)
                val city = json.optString("city")
                val region = json.optString("region")
                val isp = json.optString("isp")
                val asnOrg = json.optString("asn_organization")
                val asnNum = json.optInt("asn", 0)
                val asn = if (asnNum > 0) "AS$asnNum $asnOrg".trim() else asnOrg
                val cost = System.currentTimeMillis() - startTime

                return LandingIpInfo(
                    ip = ip,
                    country = country,
                    countryCode = countryCode,
                    countryFlag = flag,
                    city = city,
                    region = region,
                    isp = isp,
                    org = json.optString("organization"),
                    asn = asn,
                    durationMs = cost,
                )
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }
        return null
    }

    suspend fun queryLandingIp(
        profileId: Long,
        forceRefresh: Boolean = false,
        onUpdate: ((LandingIpInfo) -> Unit)? = null,
    ): Result<LandingIpInfo> = withContext(Dispatchers.IO) {
        if (!DataStore.serviceState.connected) {
            return@withContext Result.failure(IllegalStateException("VPN not connected"))
        }

        val now = System.currentTimeMillis()
        if (forceRefresh) {
            // keep showing the current result until the new one lands; only older lookups are invalidated
            generation++
        } else {
            val cache = currentCache
            if (cache != null && cachedProfileId == profileId && (now - cache.queryTimestamp < CACHE_TTL_MS)) {
                return@withContext Result.success(cache)
            }
        }

        if (!forceRefresh) {
            inFlight[profileId]?.takeIf { it.isActive }?.let { running ->
                val r = runCatching { running.await() }.getOrElse { Result.failure(it) }
                if (r.isSuccess) onUpdate?.invoke(r.getOrThrow())
                return@withContext r
            }
        }
        val mine = kotlinx.coroutines.CompletableDeferred<Result<LandingIpInfo>>()
        inFlight[profileId] = mine
        try {
            val result = runLookup(profileId, onUpdate)
            mine.complete(result)
            result
        } catch (e: Throwable) {
            mine.complete(Result.failure(e))
            throw e
        } finally {
            inFlight.remove(profileId, mine)
        }
    }

    private suspend fun runLookup(
        profileId: Long,
        onUpdate: ((LandingIpInfo) -> Unit)?,
    ): Result<LandingIpInfo> = withContext(Dispatchers.IO) {

        val myGeneration = generation
        fun publish(info: LandingIpInfo): Boolean {
            if (myGeneration != generation) return false
            currentCache = info
            cachedProfileId = profileId
            rememberGood(profileId, info)
            onUpdate?.invoke(info)
            return true
        }

        activeQueries.incrementAndGet()
        val startTime = System.currentTimeMillis()
        val ua = USER_AGENT.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        try {
            coroutineScope {
                val resultChannel = Channel<LandingIpInfo>(Channel.UNLIMITED)
                // The lookups are blocking native calls that coroutine cancellation cannot interrupt. Run them
                // outside this scope so the answer is published at the deadline instead of after the slowest one.
                val lookups = CoroutineScope(Dispatchers.IO + SupervisorJob())

                // 查询源分级启动：先只用一个（带归属地/运营商的 ipwho.is）；约 1.5 s 没结果（或它已失败）再加第二个。
                // 之前 5 个源同时打，轮询/随机策略下各走各的节点，IP 和节点对不上，也白白多开连接。
                val session = java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())
                val firstDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                val firstOk = java.util.concurrent.atomic.AtomicBoolean(false)
                val firstJob = lookups.launch {
                    val info = withTimeoutOrNull(4800L) { fetchIpWhoIs(ua, startTime, session) }
                    if (info != null) {
                        firstOk.set(true)
                        resultChannel.trySend(info)
                    }
                    firstDone.complete(Unit)
                }
                val secondJob = lookups.launch {
                    withTimeoutOrNull(1500L) { firstDone.await() }
                    // 第一个已成功就不再发第二个
                    if (firstOk.get()) return@launch
                    val info = withTimeoutOrNull(4800L - (System.currentTimeMillis() - startTime).coerceAtLeast(0L)) {
                        fetchIpSb(ua, startTime, session)
                    }
                    if (info != null) resultChannel.trySend(info)
                }

                // both sources finished (e.g. both failed fast): stop waiting instead of sitting out the deadline
                lookups.launch {
                    firstJob.join()
                    secondJob.join()
                    resultChannel.close()
                }

                var winningInfo: LandingIpInfo? = null
                val deadline = System.currentTimeMillis() + 5200L
                while (System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                    val received = withTimeoutOrNull(remaining) { resultChannel.receiveCatching().getOrNull() }
                    if (received != null) {
                        val isDetailed = received.isp.isNotBlank() && received.isp != "Cloudflare Edge"
                        if (isDetailed) {
                            winningInfo = received
                            publish(received)
                            break
                        } else {
                            if (winningInfo == null) {
                                winningInfo = received
                                publish(received)
                            }
                            // 毫秒级等待是否有更高精度全量详细信息返回（如运营商/城市）
                            val detailedRemaining = 600L.coerceAtMost(deadline - System.currentTimeMillis())
                            val second = withTimeoutOrNull(detailedRemaining) { resultChannel.receiveCatching().getOrNull() }
                            if (second != null && second.isp.isNotBlank() && second.isp != "Cloudflare Edge") {
                                winningInfo = second
                                publish(second)
                            }
                            break
                        }
                    } else {
                        break
                    }
                }
                // Stop the remaining lookups: their results are no longer used.
                lookups.cancel()
                resultChannel.close()

                when {
                    winningInfo == null -> Result.failure(Exception("无法获取落地 IP 信息"))
                    myGeneration != generation -> currentCache?.let { Result.success(it) }
                        ?: Result.failure(Exception("落地 IP 查询已被更新的查询取代"))
                    else -> Result.success(winningInfo)
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            activeQueries.decrementAndGet()
        }
    }
}
