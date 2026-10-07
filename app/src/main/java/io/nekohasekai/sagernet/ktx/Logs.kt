package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.DataStore
import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    /**
     * 日志会被导出/分享，所以写入前统一打码：订阅链接里的 token、分享链接里的账密/UUID、
     * 配置 JSON 里的 password/secret/uuid、Authorization 头都替换成 ***。
     */
    private val redactions = listOf(
        Regex("""(?i)([?&](?:token|access_token|key|apikey|api_key|secret|password|passwd|pass|auth|sid|uuid|sub|t)=)[^&#\s"'<>]+""") to "$1***",
        Regex("""(?i)\b(vmess|ssr)://[A-Za-z0-9+/=_-]{8,}""") to "$1://***",
        Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\s"'<>]+@""") to "$1***@",
        Regex("""(?i)("(?:secret|password|uuid|private_key|pre_shared_key|psk|auth|auth_str|token|obfs_password|username|user|reserved)"\s*:\s*)("[^"]*"|\[[^\]]*\])""") to "$1\"***\"",
        Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]+""") to "$1***",
    )

    fun redact(message: String): String {
        var out = message
        for ((re, rep) in redactions) out = re.replace(out, rep)
        return out
    }

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // 级别语义与 ConfigBuilder 的 sing-box log.level 映射一致：
    // 0=panic 1=warn 2=info 3=debug 4=trace。
    // 本通道（Kotlin -> JNI nekoLogPrintln -> Go std log）官方不过滤，
    // 必须在源头按 DataStore.logLevel 门控，否则 warn 档也会冒出 debug 日志。
    // 读取失败（如 DataStore 未就绪）时放行，避免吞掉关键日志。
    private fun enabled(required: Int): Boolean {
        return runCatching { DataStore.logLevel >= required }.getOrDefault(true)
    }

    fun d(message: String) {
        if (!enabled(3)) return
        Libcore.nekoLogPrintln(redact("[Debug] [${mkTag()}] $message"))
    }

    fun d(message: String, exception: Throwable) {
        if (!enabled(3)) return
        Libcore.nekoLogPrintln(redact("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString()))
    }

    fun i(message: String) {
        if (!enabled(2)) return
        Libcore.nekoLogPrintln(redact("[Info] [${mkTag()}] $message"))
    }

    fun i(message: String, exception: Throwable) {
        if (!enabled(2)) return
        Libcore.nekoLogPrintln(redact("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString()))
    }

    fun w(message: String) {
        if (!enabled(1)) return
        Libcore.nekoLogPrintln(redact("[Warning] [${mkTag()}] $message"))
    }

    fun w(message: String, exception: Throwable) {
        if (!enabled(1)) return
        Libcore.nekoLogPrintln(redact("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString()))
    }

    fun w(exception: Throwable) {
        if (!enabled(1)) return
        Libcore.nekoLogPrintln(redact("[Warning] [${mkTag()}] " + exception.stackTraceToString()))
    }

    fun e(message: String) {
        Libcore.nekoLogPrintln(redact("[Error] [${mkTag()}] $message"))
    }

    fun e(message: String, exception: Throwable) {
        Libcore.nekoLogPrintln(redact("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString()))
    }

    fun e(exception: Throwable) {
        Libcore.nekoLogPrintln(redact("[Error] [${mkTag()}] " + exception.stackTraceToString()))
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}