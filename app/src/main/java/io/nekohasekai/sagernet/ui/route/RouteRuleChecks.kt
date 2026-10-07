package io.nekohasekai.sagernet.ui.route

import android.content.Context
import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RuleSetCatalog
import io.nekohasekai.sagernet.route.RuleSets
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The rule editor's input checks, matching what sing-box accepts for each field. */
internal object RouteRuleChecks {

    /** An IPv4/IPv6 address or CIDR (sing-box takes both in ip_cidr / source_ip_cidr). */
    fun isAddressOrCidr(text: String): Boolean {
        val t = text.trim()
        val ipv6 = t.contains(':')
        return if (t.contains('/')) isCidr(t, ipv6) else isCidr(t + if (ipv6) "/128" else "/32", ipv6)
    }

    /** IPv4 / IPv6 CIDR check (Throne's SettingValidators.isCidr), on literal addresses only (no DNS lookups). */
    private fun isCidr(text: String, ipv6: Boolean): Boolean {
        val parts = text.trim().split('/')
        if (parts.size != 2) return false
        val prefix = parts[1].trim().toIntOrNull() ?: return false
        val address = parts[0].trim()
        return if (ipv6) {
            address.contains(':') && isIpv6Literal(address) && prefix in 0..128
        } else {
            !address.contains(':') && isIpv4Literal(address) && prefix in 0..32
        }
    }

    private fun isIpv4Literal(s: String): Boolean {
        val octets = s.split('.')
        if (octets.size != 4) return false
        return octets.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } && o.toInt() <= 255 }
    }

    private fun isIpv6Literal(s: String): Boolean {
        if (s.isEmpty() || s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
        var t = s
        var groupsNeeded = 8
        // An embedded IPv4 tail (::ffff:1.2.3.4) takes the last two groups.
        if (t.contains('.')) {
            val idx = t.lastIndexOf(':')
            if (idx < 0 || !isIpv4Literal(t.substring(idx + 1))) return false
            t = t.substring(0, idx + 1) + "0"
            groupsNeeded = 7
        }
        val doubleColon = t.indexOf("::")
        if (doubleColon >= 0 && t.indexOf("::", doubleColon + 1) >= 0) return false
        fun groups(part: String): List<String>? {
            if (part.isEmpty()) return emptyList()
            val g = part.split(':')
            return if (g.all { it.isNotEmpty() && it.length <= 4 }) g else null
        }
        return if (doubleColon >= 0) {
            val head = groups(t.substring(0, doubleColon)) ?: return false
            val tail = groups(t.substring(doubleColon + 2)) ?: return false
            head.size + tail.size < groupsNeeded
        } else {
            val all = groups(t) ?: return false
            all.size == groupsNeeded
        }
    }

    fun isPort(text: String): Boolean = portNumber(text.trim()) != null

    /** `start:end`, `:end` or `start:` (route/rule/rule_item_port_range.go). */
    fun isPortRange(text: String): Boolean {
        val t = text.trim()
        val colon = t.indexOf(':')
        if (colon < 0 || colon != t.lastIndexOf(':')) return false
        val start = t.substring(0, colon).let { if (it.isEmpty()) 0 else portNumber(it) ?: return false }
        val end = t.substring(colon + 1).let { if (it.isEmpty()) 65535 else portNumber(it) ?: return false }
        return start <= end
    }

    private fun portNumber(s: String): Int? =
        if (s.isNotEmpty() && s.length <= 5 && s.all { it in '0'..'9' }) s.toInt().takeIf { it <= 65535 } else null

    /** Why [pattern] would fail as a Go regular expression, or null. */
    fun regexError(context: Context, pattern: String): String? {
        if (hasUnsupportedConstruct(pattern)) return context.getString(R.string.route_rule_regex_unsupported)
        return try {
            Pattern.compile(pattern.replace("(?P<", "(?<"))
            null
        } catch (e: PatternSyntaxException) {
            e.description ?: e.message ?: pattern
        }
    }

    /**
     * RE2 (sing-box) has no lookarounds, atomic groups or backreferences, which java.util.regex compiles. Escapes
     * and character classes are skipped so a literal "(?=" inside `[...]` or after a backslash does not count.
     */
    private fun hasUnsupportedConstruct(p: String): Boolean {
        var i = 0
        while (i < p.length) {
            when (p[i]) {
                '\\' -> {
                    if (i + 1 < p.length && p[i + 1] in '1'..'9') return true
                    i += 2
                    continue
                }

                '[' -> {
                    var j = i + 1
                    if (j < p.length && p[j] == '^') j++
                    if (j < p.length && p[j] == ']') j++
                    while (j < p.length && p[j] != ']') j += if (p[j] == '\\') 2 else 1
                    i = j + 1
                    continue
                }

                '(' -> if (p.startsWith("(?", i)) {
                    val rest = p.substring(i + 2)
                    if (rest.startsWith("=") || rest.startsWith("!") || rest.startsWith("<=") ||
                        rest.startsWith("<!") || rest.startsWith(">")
                    ) return true
                }
            }
            i++
        }
        return false
    }

    /** The rule-set names [catalog] does not know; URLs are not checked. An empty catalog cannot judge anything. */
    fun unknownRuleSets(entries: List<String>, catalog: RuleSetCatalog): List<String> {
        if (catalog.size == 0) return emptyList()
        return entries.map { it.trim() }
            .filter { it.isNotEmpty() && !RuleSets.isUrl(it) && catalog.urlOf(it) == null }
            .distinct()
    }

    /** One line per invalid field of [rule]; empty when every value is acceptable. */
    fun problems(context: Context, rule: RouteRule): List<String> {
        val out = ArrayList<String>()
        fun entries(@StringRes label: Int, values: List<String>, valid: (String) -> Boolean) {
            val bad = values.map { it.trim() }.filter { it.isNotEmpty() && !valid(it) }
            if (bad.isNotEmpty()) {
                out.add(context.getString(R.string.route_rule_invalid_entries, context.getString(label), bad.joinToString(", ")))
            }
        }

        fun regexes(@StringRes label: Int, values: List<String>) {
            for (value in values.map { it.trim() }.filter { it.isNotEmpty() }) {
                val error = regexError(context, value) ?: continue
                out.add(context.getString(R.string.route_rule_invalid_regex, context.getString(label), value, error))
            }
        }

        entries(R.string.route_rule_ip_cidr, rule.ip_cidr, ::isAddressOrCidr)
        entries(R.string.route_rule_source_ip_cidr, rule.source_ip_cidr, ::isAddressOrCidr)
        entries(R.string.route_rule_port, rule.port, ::isPort)
        entries(R.string.route_rule_source_port, rule.source_port, ::isPort)
        entries(R.string.route_rule_port_range, rule.port_range, ::isPortRange)
        entries(R.string.route_rule_source_port_range, rule.source_port_range, ::isPortRange)
        entries(R.string.route_rule_override_port, listOf(rule.override_port), ::isPort)
        regexes(R.string.route_rule_domain_regex, rule.domain_regex)
        regexes(R.string.route_rule_process_path_regex, rule.process_path_regex)
        return out
    }

    /** The match attributes, i.e. everything that narrows which connections the rule applies to. */
    fun hasConditions(rule: RouteRule): Boolean = rule.nonDefaultAttributes().any { it !in ACTION_OPTIONS }

    private val ACTION_OPTIONS = setOf(
        "invert", "override_address", "override_port", "tls_spoof", "tls_spoof_method", "reject_method", "no_drop",
        "sniff_override_dest", "strategy",
    )
}
