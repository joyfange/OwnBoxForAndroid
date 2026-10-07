package io.nekohasekai.sagernet.database

import android.os.Parcel
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.route.RouteJson
import io.nekohasekai.sagernet.route.RouteProfile
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup / restore of the route profiles (the "rules" part of an OwnBox backup).
 *
 * New backups carry `route_profiles` (one RouteJson profile object per entry) and `current_route_id`. Backups made
 * before the Throne routing port carry `rules`: base64 Parcels of the removed RuleEntity, which are decoded field by
 * field here and converted like the 10 -> 11 database migration does.
 */
object RouteBackup {

    const val KEY_PROFILES = "route_profiles"
    const val KEY_CURRENT = "current_route_id"
    const val KEY_LEGACY_RULES = "rules"

    fun export(into: JSONObject) {
        val profiles = JSONArray()
        for (p in RouteManager.all()) profiles.put(JSONObject(RouteJson.profileToJson(p)))
        into.put(KEY_PROFILES, profiles)
        into.put(KEY_CURRENT, DataStore.currentRouteId)
    }

    fun hasRoutes(content: JSONObject) = content.has(KEY_PROFILES) || content.has(KEY_LEGACY_RULES)

    /** Replaces every route profile with the backup's; returns false when the backup has no routing data. */
    fun restore(content: JSONObject): Boolean {
        val restored = ArrayList<RouteProfile>()
        var currentIndex = -1
        if (content.has(KEY_PROFILES)) {
            val arr = content.getJSONArray(KEY_PROFILES)
            val oldCurrent = content.optLong(KEY_CURRENT, 0L)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val p = RouteJson.profileFromJson(obj.toString())
                if (p.id == oldCurrent) currentIndex = restored.size
                p.id = 0L
                restored.add(p)
            }
        } else if (content.has(KEY_LEGACY_RULES)) {
            val arr = content.getJSONArray(KEY_LEGACY_RULES)
            val legacy = ArrayList<LegacyRuleMigration.LegacyRule>()
            for (i in 0 until arr.length()) {
                try {
                    legacy.add(decodeLegacyRule(Util.b64Decode(arr.getString(i))))
                } catch (e: Exception) {
                    Logs.w("legacy backup rule #$i unreadable", e)
                }
            }
            val main = RouteProfile.defaultProfile()
            for (rule in legacy.filter { it.enabled }) main.rules.addAll(LegacyRuleMigration.convert(rule))
            restored.add(main)
            currentIndex = 0
            val disabled = legacy.filterNot { it.enabled }
            if (disabled.isNotEmpty()) {
                restored.add(RouteProfile().apply {
                    name = "Default (disabled rules)"
                    rules.add(RouteProfile.defaultProfile().rules.first())
                    for (rule in disabled) rules.addAll(LegacyRuleMigration.convert(rule))
                })
            }
        } else {
            return false
        }
        if (restored.isEmpty()) restored.add(RouteProfile.defaultProfile())
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.routeDao.reset()
            for (p in restored) {
                p.rules.forEachIndexed { index, rule -> if (rule.name.isBlank()) rule.name = "rule_${index + 1}" }
                RouteManager.save(p)
            }
        }
        DataStore.currentRouteId = restored.getOrNull(currentIndex)?.id ?: restored.first().id
        return true
    }

    /**
     * The removed `@Parcelize data class RuleEntity` wrote its constructor properties in order: id (Long),
     * name, config (String), userOrder (Long), enabled (Boolean as Int), domains, ip, port, sourcePort, network,
     * source, protocol, ruleset (String), outbound (Long), packages (Set<String>: size then items).
     */
    private fun decodeLegacyRule(data: ByteArray): LegacyRuleMigration.LegacyRule {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            parcel.readLong() // id
            val name = parcel.readString().orEmpty()
            val config = parcel.readString().orEmpty()
            parcel.readLong() // userOrder
            val enabled = parcel.readInt() != 0
            val domains = parcel.readString().orEmpty()
            val ip = parcel.readString().orEmpty()
            val port = parcel.readString().orEmpty()
            val sourcePort = parcel.readString().orEmpty()
            val network = parcel.readString().orEmpty()
            val source = parcel.readString().orEmpty()
            val protocol = parcel.readString().orEmpty()
            val ruleset = parcel.readString().orEmpty()
            val outbound = parcel.readLong()
            val count = parcel.readInt()
            val packages = ArrayList<String>()
            repeat(count.coerceIn(0, 10000)) { parcel.readString()?.let { packages.add(it) } }
            return LegacyRuleMigration.LegacyRule(
                name, config, enabled, domains, ip, port, sourcePort, network, source, protocol, ruleset, outbound, packages
            )
        } finally {
            parcel.recycle()
        }
    }
}
