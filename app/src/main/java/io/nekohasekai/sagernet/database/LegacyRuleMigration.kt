package io.nekohasekai.sagernet.database

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * Database 10 -> 11: OwnBox's flat `rules` table (the old RuleEntity) becomes Throne's route profiles.
 *
 * The old rule semantics were: domain list OR ip list OR app list (each emitted as its own sing-box rule), with
 * port / network / source / protocol filters applied to every part, and the advanced fields kept as JSON in
 * `config`. The new rows reproduce that: address conditions and app conditions become separate rules when a rule had
 * both, and a multi-value protocol list (Throne keeps a single protocol) is split into one rule per protocol.
 *
 * Old outbound ids: 0 = proxy, -1 = bypass, -2 = block, >0 = a server profile id; they map to
 * [OutboundIds.PROXY] / [OutboundIds.DIRECT] / [OutboundIds.BLOCK] / the same id.
 *
 * Old domain entries: `geosite:x` / `geosite-x` -> rule_set `geosite-x`, `full:` -> domain, `domain:` ->
 * domain_suffix, `regexp:` -> domain_regex, `keyword:` -> domain_keyword, anything else -> domain_suffix.
 * Old IP entries: `geoip:private` -> ip_is_private, `geoip:x` -> rule_set `geoip-x`, else ip_cidr.
 * Old rule-set entries `rsip:<url>` / `rssite:<url>` -> rule_set `<url>`.
 */
internal object LegacyRuleMigration {

    private const val PROFILES_SQL =
        "CREATE TABLE IF NOT EXISTS `route_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL DEFAULT '', `default_outbound_id` INTEGER NOT NULL DEFAULT -1, " +
            "`is_remote` INTEGER NOT NULL DEFAULT 0, `remote_url` TEXT NOT NULL DEFAULT '', " +
            "`auto_update` INTEGER NOT NULL DEFAULT 0, `remote_last_update` INTEGER NOT NULL DEFAULT 0, " +
            "`is_raw` INTEGER NOT NULL DEFAULT 0, `raw_route` TEXT NOT NULL DEFAULT '', " +
            "`prevent_modifications` INTEGER NOT NULL DEFAULT 0, `endpoint_profile_ids` TEXT NOT NULL DEFAULT '[]', " +
            "`inner_hop_endpoint_ids` TEXT NOT NULL DEFAULT '[]')"

    private const val RULES_SQL =
        "CREATE TABLE IF NOT EXISTS `route_rules` (`route_profile_id` INTEGER NOT NULL, " +
            "`rule_order` INTEGER NOT NULL, `name` TEXT NOT NULL DEFAULT '', `type` INTEGER NOT NULL DEFAULT 0, " +
            "`ip_version` TEXT NOT NULL DEFAULT '', `network` TEXT NOT NULL DEFAULT '', " +
            "`protocol` TEXT NOT NULL DEFAULT '', `inbound_json` TEXT NOT NULL DEFAULT '[]', " +
            "`domain_json` TEXT NOT NULL DEFAULT '[]', `domain_suffix_json` TEXT NOT NULL DEFAULT '[]', " +
            "`domain_keyword_json` TEXT NOT NULL DEFAULT '[]', `domain_regex_json` TEXT NOT NULL DEFAULT '[]', " +
            "`source_ip_cidr_json` TEXT NOT NULL DEFAULT '[]', `source_ip_is_private` INTEGER NOT NULL DEFAULT 0, " +
            "`ip_cidr_json` TEXT NOT NULL DEFAULT '[]', `ip_is_private` INTEGER NOT NULL DEFAULT 0, " +
            "`source_port_json` TEXT NOT NULL DEFAULT '[]', `source_port_range_json` TEXT NOT NULL DEFAULT '[]', " +
            "`port_json` TEXT NOT NULL DEFAULT '[]', `port_range_json` TEXT NOT NULL DEFAULT '[]', " +
            "`process_name_json` TEXT NOT NULL DEFAULT '[]', `process_path_json` TEXT NOT NULL DEFAULT '[]', " +
            "`process_path_regex_json` TEXT NOT NULL DEFAULT '[]', `package_name_json` TEXT NOT NULL DEFAULT '[]', " +
            "`rule_set_json` TEXT NOT NULL DEFAULT '[]', `invert` INTEGER NOT NULL DEFAULT 0, " +
            "`outbound_id` INTEGER NOT NULL DEFAULT -2, `action` TEXT NOT NULL DEFAULT 'route', " +
            "`reject_method` TEXT NOT NULL DEFAULT '', `no_drop` INTEGER NOT NULL DEFAULT 0, " +
            "`override_address` TEXT NOT NULL DEFAULT '', `override_port` TEXT NOT NULL DEFAULT '', " +
            "`sniffers_json` TEXT NOT NULL DEFAULT '[]', `sniff_override_dest` INTEGER NOT NULL DEFAULT 0, " +
            "`strategy` TEXT NOT NULL DEFAULT '', `wifi_ssid_json` TEXT NOT NULL DEFAULT '[]', " +
            "`wifi_bssid_json` TEXT NOT NULL DEFAULT '[]', `tls_spoof` TEXT NOT NULL DEFAULT '', " +
            "`tls_spoof_method` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`route_profile_id`, `rule_order`), " +
            "FOREIGN KEY(`route_profile_id`) REFERENCES `route_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** One row of the old `rules` table. */
    class LegacyRule(
        val name: String,
        val config: String,
        val enabled: Boolean,
        val domains: String,
        val ip: String,
        val port: String,
        val sourcePort: String,
        val network: String,
        val source: String,
        val protocol: String,
        val ruleset: String,
        val outbound: Long,
        val packages: List<String>,
    )

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PROFILES_SQL)
        db.execSQL(RULES_SQL)
        val legacy = try {
            readLegacy(db)
        } catch (e: Exception) {
            Logs.w("legacy rules unreadable, starting from the default route profile", e)
            emptyList()
        }
        val enabled = legacy.filter { it.enabled }
        val disabled = legacy.filterNot { it.enabled }

        val main = RouteProfile.defaultProfile()
        if (legacy.isNotEmpty()) {
            for (rule in enabled) main.rules.addAll(convert(rule))
        }
        insertProfile(db, main)
        if (disabled.isNotEmpty()) {
            val off = RouteProfile().apply {
                name = "Default (disabled rules)"
                rules.add(RouteProfile.defaultProfile().rules.first())
                for (rule in disabled) rules.addAll(convert(rule))
            }
            insertProfile(db, off)
        }
        db.execSQL("DROP TABLE IF EXISTS `rules`")
    }

    private fun readLegacy(db: SupportSQLiteDatabase): List<LegacyRule> {
        val exists = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'rules'").use { it.moveToFirst() }
        if (!exists) return emptyList()
        val out = ArrayList<LegacyRule>()
        db.query("SELECT * FROM `rules` ORDER BY `userOrder`, `id`").use { c ->
            while (c.moveToNext()) {
                out.add(
                    LegacyRule(
                        name = c.str("name"),
                        config = c.str("config"),
                        enabled = c.long("enabled") != 0L,
                        domains = c.str("domains"),
                        ip = c.str("ip"),
                        port = c.str("port"),
                        sourcePort = c.str("sourcePort"),
                        network = c.str("network"),
                        source = c.str("source"),
                        protocol = c.str("protocol"),
                        ruleset = c.str("ruleset"),
                        outbound = c.long("outbound"),
                        packages = c.str("packages").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    )
                )
            }
        }
        return out
    }

    private fun Cursor.str(column: String): String {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) "" else getString(i) ?: ""
    }

    private fun Cursor.long(column: String): Long {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    /** Old list fields were newline or comma separated (listByLineOrComma). */
    private fun list(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /** One old rule as one or more Throne rules with the same effect. */
    fun convert(old: LegacyRule): List<RouteRule> {
        val base = RouteRule()
        base.name = old.name.trim()
        base.outbound_id = when {
            old.outbound == 0L -> OutboundIds.PROXY
            old.outbound == -1L -> OutboundIds.DIRECT
            old.outbound == -2L -> OutboundIds.BLOCK
            old.outbound > 0L -> old.outbound
            else -> OutboundIds.PROXY
        }

        // Common filters (applied to every generated part, like applyCommonFilters did).
        for (p in list(old.port)) if (':' in p) base.port_range.add(p) else base.port.add(p)
        for (p in list(old.sourcePort)) if (':' in p) base.source_port_range.add(p) else base.source_port.add(p)
        val networks = list(old.network).map { it.lowercase() }.distinct()
        if (networks.size == 1) base.network = networks[0]
        base.source_ip_cidr.addAll(list(old.source))

        // Advanced fields kept as JSON in `config`.
        applyConfig(base, old.config)

        // Address conditions.
        val address = RouteRule()
        for (entry in list(old.domains)) {
            when {
                entry.startsWith("geosite:") -> address.rule_set.add("geosite-" + entry.removePrefix("geosite:"))
                entry.startsWith("geosite-") -> address.rule_set.add(entry)
                entry.startsWith("full:") -> address.domain.add(entry.removePrefix("full:").lowercase())
                entry.startsWith("domain:") -> address.domain_suffix.add(entry.removePrefix("domain:").lowercase())
                entry.startsWith("regexp:") -> address.domain_regex.add(entry.removePrefix("regexp:"))
                entry.startsWith("keyword:") -> address.domain_keyword.add(entry.removePrefix("keyword:").lowercase())
                else -> address.domain_suffix.add(entry.lowercase())
            }
        }
        for (entry in list(old.ip)) {
            when {
                entry == "geoip:private" || entry == "geoip-private" -> address.ip_is_private = true
                entry.startsWith("geoip:") -> address.rule_set.add("geoip-" + entry.removePrefix("geoip:"))
                entry.startsWith("geoip-") -> address.rule_set.add(entry)
                else -> address.ip_cidr.add(entry)
            }
        }
        for (entry in list(old.ruleset)) {
            val url = entry.removePrefix("rsip:").removePrefix("rssite:").trim()
            if (url.isNotEmpty()) address.rule_set.add(url)
        }
        // Advanced domain lists in config extend the address part.
        address.domain.addAll(base.domain); base.domain.clear()
        address.domain_suffix.addAll(base.domain_suffix); base.domain_suffix.clear()
        address.domain_keyword.addAll(base.domain_keyword); base.domain_keyword.clear()
        address.domain_regex.addAll(base.domain_regex); base.domain_regex.clear()
        if (base.ip_is_private) {
            address.ip_is_private = true
            base.ip_is_private = false
        }
        val hasAddress = address.domain.isNotEmpty() || address.domain_suffix.isNotEmpty() ||
            address.domain_keyword.isNotEmpty() || address.domain_regex.isNotEmpty() ||
            address.ip_cidr.isNotEmpty() || address.rule_set.isNotEmpty() || address.ip_is_private
        val hasApps = old.packages.isNotEmpty()

        val parts = ArrayList<RouteRule>()
        if (hasAddress) parts.add(base.copy().also {
            it.domain = address.domain.distinct().toMutableList()
            it.domain_suffix = address.domain_suffix.distinct().toMutableList()
            it.domain_keyword = address.domain_keyword.distinct().toMutableList()
            it.domain_regex = address.domain_regex.distinct().toMutableList()
            it.ip_cidr = address.ip_cidr.distinct().toMutableList()
            it.ip_is_private = address.ip_is_private
            it.rule_set = address.rule_set.distinct().toMutableList()
        })
        if (hasApps) parts.add(base.copy().also { it.package_name = old.packages.distinct().toMutableList() })
        if (parts.isEmpty()) parts.add(base.copy())

        val protocols = list(old.protocol).map { it.lowercase() }.distinct()
        val out = ArrayList<RouteRule>()
        for (part in parts) {
            if (protocols.isEmpty()) {
                out.add(part)
            } else {
                for (protocol in protocols) out.add(part.copy().also { it.protocol = protocol })
            }
        }
        if (out.size > 1 && base.name.isNotEmpty()) {
            out.forEachIndexed { i, r -> r.name = if (i == 0) base.name else "${base.name} (${i + 1})" }
        }
        return out
    }

    private fun applyConfig(rule: RouteRule, config: String) {
        if (config.isBlank()) return
        val obj = try {
            JSONObject(config)
        } catch (e: Exception) {
            Logs.w("legacy rule config is not JSON, ignored: ${e.message}")
            return
        }

        fun strings(key: String): List<String> = when (val v = obj.opt(key)) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }.filter { it.isNotBlank() }
            is String -> listOf(v).filter { it.isNotBlank() }
            else -> emptyList()
        }
        rule.domain.addAll(strings("domain"))
        rule.domain_suffix.addAll(strings("domain_suffix"))
        rule.domain_keyword.addAll(strings("domain_keyword"))
        rule.domain_regex.addAll(strings("domain_regex"))
        rule.inbound.addAll(strings("inbound"))
        rule.sniffers.addAll(strings("sniffer"))
        if (obj.optBoolean("ip_is_private")) rule.ip_is_private = true
        if (obj.optBoolean("source_ip_is_private")) rule.source_ip_is_private = true
        if (obj.optBoolean("invert")) rule.invert = true
        if (obj.optBoolean("no_drop")) rule.no_drop = true
        obj.optString("action").takeIf { it.isNotBlank() }?.let { rule.action = it }
        obj.optString("method").takeIf { it.isNotBlank() }?.let { rule.reject_method = it }
        obj.optString("strategy").takeIf { it.isNotBlank() }?.let { rule.strategy = it }
        obj.opt("ip_version")?.toString()?.takeIf { it == "4" || it == "6" }?.let { rule.ip_version = it }
        obj.optString("override_address").takeIf { it.isNotBlank() }?.let { rule.override_address = it }
        obj.opt("override_port")?.toString()?.takeIf { it.isNotBlank() }?.let { rule.override_port = it }
    }

    private fun insertProfile(db: SupportSQLiteDatabase, p: RouteProfile) {
        val e = RouteProfileEntity.of(p)
        val cv = ContentValues().apply {
            put("name", e.name)
            put("default_outbound_id", e.defaultOutboundId)
            put("is_remote", if (e.isRemote) 1 else 0)
            put("remote_url", e.remoteUrl)
            put("auto_update", if (e.autoUpdate) 1 else 0)
            put("remote_last_update", e.remoteLastUpdate)
            put("is_raw", if (e.isRaw) 1 else 0)
            put("raw_route", e.rawRoute)
            put("prevent_modifications", if (e.preventModifications) 1 else 0)
            put("endpoint_profile_ids", e.endpointProfileIds)
            put("inner_hop_endpoint_ids", e.innerHopEndpointIds)
        }
        val id = db.insert("route_profiles", SQLiteDatabase.CONFLICT_ABORT, cv)
        p.rules.forEachIndexed { index, rule ->
            if (rule.name.isBlank()) rule.name = "rule_${index + 1}"
            db.insert("route_rules", SQLiteDatabase.CONFLICT_ABORT, ruleValues(RouteRuleEntity.of(id, index, rule)))
        }
    }

    private fun ruleValues(r: RouteRuleEntity) = ContentValues().apply {
        put("route_profile_id", r.routeProfileId)
        put("rule_order", r.ruleOrder)
        put("name", r.name)
        put("type", r.type)
        put("ip_version", r.ipVersion)
        put("network", r.network)
        put("protocol", r.protocol)
        put("inbound_json", r.inboundJson)
        put("domain_json", r.domainJson)
        put("domain_suffix_json", r.domainSuffixJson)
        put("domain_keyword_json", r.domainKeywordJson)
        put("domain_regex_json", r.domainRegexJson)
        put("source_ip_cidr_json", r.sourceIpCidrJson)
        put("source_ip_is_private", if (r.sourceIpIsPrivate) 1 else 0)
        put("ip_cidr_json", r.ipCidrJson)
        put("ip_is_private", if (r.ipIsPrivate) 1 else 0)
        put("source_port_json", r.sourcePortJson)
        put("source_port_range_json", r.sourcePortRangeJson)
        put("port_json", r.portJson)
        put("port_range_json", r.portRangeJson)
        put("process_name_json", r.processNameJson)
        put("process_path_json", r.processPathJson)
        put("process_path_regex_json", r.processPathRegexJson)
        put("package_name_json", r.packageNameJson)
        put("rule_set_json", r.ruleSetJson)
        put("invert", if (r.invert) 1 else 0)
        put("outbound_id", r.outboundId)
        put("action", r.action)
        put("reject_method", r.rejectMethod)
        put("no_drop", if (r.noDrop) 1 else 0)
        put("override_address", r.overrideAddress)
        put("override_port", r.overridePort)
        put("sniffers_json", r.sniffersJson)
        put("sniff_override_dest", if (r.sniffOverrideDest) 1 else 0)
        put("strategy", r.strategy)
        put("wifi_ssid_json", r.wifiSsidJson)
        put("wifi_bssid_json", r.wifiBssidJson)
        put("tls_spoof", r.tlsSpoof)
        put("tls_spoof_method", r.tlsSpoofMethod)
    }
}
