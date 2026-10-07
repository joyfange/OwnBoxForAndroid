package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.route.json.JsonArray
import io.nekohasekai.sagernet.route.json.JsonInput
import io.nekohasekai.sagernet.route.json.JsonObject
import io.nekohasekai.sagernet.route.json.JsonValues
import io.nekohasekai.sagernet.route.json.JsonWriter

/** The desktop's route share formats (RouteProfile.cpp:395-580). */
object RouteShare {
    const val ROUTE_LINK_PREFIX = "throne://route/"
    const val REMOTE_ROUTE_LINK_PREFIX = "throne://remoteroute/"
    private const val KIND = "throne-route-profile"

    /** Keys whose numbers the desktop writes (port lists, ip_version, override_port) and must read back (D12). */
    private val NUMERIC_KEYS = setOf("port", "source_port", "ip_version", "override_port")

    class Imported(val profile: RouteProfile?, val fatal: String, val warnings: List<String>, val legacyArray: Boolean)

    class RemoteEntry(val url: String, val name: String)

    /** ToShareObject (RouteProfile.cpp:395-440) for a structured profile; rules whose server is missing are left out. */
    fun toShareObject(p: RouteProfile, profileName: (Long) -> String?): JsonObject {
        val root = JsonObject()
        root["kind"] = KIND
        root["v"] = 1
        root["name"] = p.name
        root["default_outbound"] = OutboundIds.toName(p.default_outbound_id)
        val rules = JsonArray()
        for (rule in p.rules) {
            val type = RuleType.ofId(rule.type)
            if (type == RuleType.ENDPOINT_PREFERRED_BY) continue
            if (type != RuleType.CUSTOM && rule.isEmpty()) continue
            val obj = rule.toRuleJson(true, null, profileName)
            if (obj.isEmpty()) continue
            obj["name"] = rule.name
            obj["type"] = type.token
            rules.add(obj)
        }
        root["rules"] = rules
        return root
    }

    /** ToShareLink: compact key-sorted JSON, base64url without padding. */
    fun toShareLink(p: RouteProfile, profileName: (Long) -> String?): String =
        ROUTE_LINK_PREFIX + Base64Strict.encode(JsonWriter.write(toShareObject(p, profileName)), urlSafe = true, padding = false)

    /**
     * FromShareInput (RouteProfile.cpp:448-539): a throne://route link (any case), share JSON, base64url or standard
     * base64 of it with or without padding, or a legacy rule array. Raw profiles are refused; endpoints and endpoint
     * rules are dropped with a warning (D11). Rule outbound names resolve through [profileIdByName].
     */
    fun fromShareInput(text: String, profileIdByName: (String) -> Long?): Imported {
        var input = text.trim()
        if (input.isEmpty()) return failure("Empty input")
        if (input.startsWith(ROUTE_LINK_PREFIX, ignoreCase = true)) {
            input = UrlText.deepLinkPayload(input, ROUTE_LINK_PREFIX.length)
            if (input.isEmpty()) return failure("Deep link has no data")
        }
        val doc = parseDocument(input)
            ?: parseDocument(String(Base64Strict.decodeLenient(input, urlSafe = true), Charsets.UTF_8))
            ?: parseDocument(String(Base64Strict.decodeLenient(input, urlSafe = false), Charsets.UTF_8))
            ?: return failure("Input is not valid JSON, base64, or a Throne route link")
        val warnings = ArrayList<String>()
        return when (doc) {
            is JsonObject -> fromObject(doc, warnings, profileIdByName)
            is JsonArray -> fromLegacyArray(doc, warnings, profileIdByName)
            else -> failure("Unsupported input")
        }
    }

    /**
     * FromRemoteRoutesLink (RouteProfile.cpp:541-580): null when [text] is not a throne://remoteroute link; for an
     * invalid one an [IllegalArgumentException] carries the desktop's error text, so a returned list is never empty.
     */
    fun fromRemoteRoutesLink(text: String): List<RemoteEntry>? {
        val input = text.trim()
        if (!input.startsWith(REMOTE_ROUTE_LINK_PREFIX, ignoreCase = true)) return null
        val base64 = UrlText.deepLinkPayload(input, REMOTE_ROUTE_LINK_PREFIX.length)
        if (base64.isEmpty()) throw IllegalArgumentException("Deep link has no data")
        val data = Base64Strict.decodeToString(base64)
        if (data.isEmpty()) throw IllegalArgumentException("Base64 is invalid.")
        val out = ArrayList<RemoteEntry>()
        for (line in data.split('\n')) {
            val entry = line.trim()
            if (!entry.startsWith("http://", ignoreCase = true) && !entry.startsWith("https://", ignoreCase = true)) continue
            val hash = entry.indexOf('#')
            val url = if (hash >= 0) entry.substring(0, hash) else entry
            val host = UrlText.parse(url)?.host.orEmpty()
            if (host.isEmpty()) continue
            val name = if (hash >= 0) UrlText.percentDecode(entry.substring(hash + 1)) else ""
            out.add(RemoteEntry(url, name.ifEmpty { host }))
        }
        if (out.isEmpty()) throw IllegalArgumentException("The link did not contain any valid http(s) routing profile URLs.")
        return out
    }

    private fun failure(fatal: String) = Imported(null, fatal, emptyList(), false)

    private fun parseDocument(text: String): Any? {
        if (text.isBlank()) return null
        val value = JsonInput.parseValue(text)
        return if (value is JsonObject || value is JsonArray) value else null
    }

    private fun fromObject(root: JsonObject, warnings: MutableList<String>, profileIdByName: (String) -> Long?): Imported {
        if (root.string("kind") != KIND) return failure("Unrecognized route object")
        if (root.bool("raw")) return failure("raw routing profiles are not supported on Android")
        val profile = RouteProfile()
        profile.name = root.string("name")
        profile.default_outbound_id = OutboundIds.fromName(root.string("default_outbound")) ?: OutboundIds.PROXY
        if (root.array("endpoints").isNotEmpty()) warnings.add("endpoints are not supported on Android and were dropped")
        var fallbackNum = 1
        for (value in root.array("rules")) {
            if (value !is JsonObject) continue
            val type = RuleType.ofToken(value.string("type"))
            val name = value.string("name")
            if (type == RuleType.ENDPOINT_PREFERRED_BY) {
                warnings.add("endpoint rule \"$name\" dropped: endpoints are not supported on Android")
                continue
            }
            val rule = parseRuleObject(value, warnings, profileIdByName)
            rule.type = type.id
            rule.name = name.ifEmpty { "rule_" + fallbackNum++ }
            profile.rules.add(rule)
        }
        return Imported(profile, "", warnings, false)
    }

    /** parseJsonArray (RouteProfile.cpp:224-245): the legacy bare rule array; every rule is custom. */
    private fun fromLegacyArray(arr: JsonArray, warnings: MutableList<String>, profileIdByName: (String) -> Long?): Imported {
        if (arr.isEmpty()) return failure("Input is not a valid json array")
        val profile = RouteProfile()
        var ruleId = 1
        for (item in arr) {
            if (item !is JsonObject) return failure("expected array of json objects but have member of type '${qtJsonType(item)}'")
            val rule = parseRuleObject(item, warnings, profileIdByName)
            val name = item.string("name")
            rule.name = name.ifEmpty { "imported rule #" + ruleId++ }
            profile.rules.add(rule)
        }
        return Imported(profile, "", warnings, true)
    }

    /** parse_rule_object (RouteProfile.cpp:190-222) plus the D12 fixes: numbers, warp-bypass/block names, reject_method. */
    private fun parseRuleObject(obj: JsonObject, warnings: MutableList<String>, profileIdByName: (String) -> Long?): RouteRule {
        val rule = RouteRule()
        for (key in obj.sortedKeys()) {
            if (key == "name" || key == "type") continue
            when (val value = obj[key]) {
                is JsonArray -> if (key != "outbound") setField(rule, key, value.map { itemText(key, it) })
                is String -> if (key == "outbound") {
                    rule.outbound_id = OutboundIds.fromName(value) ?: profileIdByName(value) ?: run {
                        warnings.add("outbound \"$value\" not found, using proxy")
                        OutboundIds.PROXY
                    }
                } else {
                    setField(rule, key, listOf(value))
                }

                is Boolean -> if (key != "outbound") setField(rule, key, listOf(if (value) "true" else "false"))
                is Long, is Double -> if (key == "outbound") {
                    val id = JsonValues.toInt(value).toLong()
                    rule.outbound_id = when (id) {
                        OutboundIds.PROXY, OutboundIds.DIRECT, OutboundIds.BLOCK, OutboundIds.WARP_BYPASS -> id
                        else -> {
                            warnings.add("outbound id $id not found, using proxy")
                            OutboundIds.PROXY
                        }
                    }
                } else if (key in NUMERIC_KEYS) {
                    setField(rule, key, listOf(numberText(value)))
                }

                else -> {}
            }
        }
        return rule
    }

    private fun itemText(key: String, item: Any): String = when {
        item is String -> item
        (item is Long || item is Double) && key in NUMERIC_KEYS -> numberText(item)
        else -> ""
    }

    private fun numberText(value: Any): String = when (value) {
        is Long -> value.toString()
        is Double -> JsonWriter.formatDouble(value)
        else -> ""
    }

    /** set_field_value (RouteRule.cpp:472-584); `reject_method` is accepted besides the desktop's `method`. */
    private fun setField(rule: RouteRule, key: String, values: List<String>) {
        val scalar = values.firstOrNull()?.trim() ?: ""
        val list = values.map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() }
        when (key) {
            "ip_version" -> rule.ip_version = scalar
            "network" -> rule.network = scalar
            "protocol" -> rule.protocol = scalar
            "inbound" -> rule.inbound = list
            "domain" -> rule.domain = list
            "domain_suffix" -> rule.domain_suffix = list
            "domain_keyword" -> rule.domain_keyword = list
            "domain_regex" -> rule.domain_regex = list
            "source_ip_cidr" -> rule.source_ip_cidr = list
            "source_ip_is_private" -> rule.source_ip_is_private = scalar == "true"
            "ip_cidr" -> rule.ip_cidr = list
            "ip_is_private" -> rule.ip_is_private = scalar == "true"
            "source_port" -> rule.source_port = list
            "source_port_range" -> rule.source_port_range = list
            "port" -> rule.port = list
            "port_range" -> rule.port_range = list
            "process_name" -> rule.process_name = list
            "process_path" -> rule.process_path = list
            "process_path_regex" -> rule.process_path_regex = list
            "package_name" -> rule.package_name = list
            "wifi_ssid" -> rule.wifi_ssid = list
            "wifi_bssid" -> rule.wifi_bssid = list
            "rule_set" -> rule.rule_set = list
            "invert" -> rule.invert = scalar == "true"
            "action" -> rule.action = scalar
            "method", "reject_method" -> rule.reject_method = scalar
            "no_drop" -> rule.no_drop = scalar == "true"
            "override_address" -> rule.override_address = scalar
            "override_port" -> rule.override_port = scalar
            "tls_spoof" -> rule.tls_spoof = scalar
            "tls_spoof_method" -> rule.tls_spoof_method = scalar
            "override_destination" -> rule.sniff_override_dest = scalar == "true"
            "strategy" -> rule.strategy = scalar
        }
    }

    /** QJsonValue::Type as the desktop prints it in the legacy-array error. */
    private fun qtJsonType(value: Any?): Int = when (value) {
        is Boolean -> 0x1
        is Long, is Double -> 0x2
        is String -> 0x3
        is JsonArray -> 0x4
        is JsonObject -> 0x5
        else -> 0x0
    }
}
