package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 快捷方式安全门。
 *
 * 之前 QuickToggle/QuickEnable/QuickDisable 三个 Activity 都是 exported 且不校验调用方，
 * 任何 App 都能不带权限地 startActivity 关掉 VPN 或带 "profile" 参数切换节点。
 *
 * 现在：
 *  - 真正执行开关/切换的 Activity 全部 exported=false。
 *    API 25+ 的静态快捷方式、API 26+ 的固定快捷方式都由系统以本应用身份启动，不需要 exported。
 *  - 只有两个入口仍然 exported：
 *    1. [QuickToggleShortcutCreator]：响应 ACTION_CREATE_SHORTCUT，只返回快捷方式描述，不执行任何操作；
 *    2. [ShortcutTrampoline]：仅给 API 25 及以下的老式桌面快捷方式使用（桌面用自己的身份 startActivity），
 *       必须携带本机安装时随机生成的令牌才会转发，外部 App 无法伪造。
 */
object ShortcutGuard {
    const val EXTRA_TOKEN = "ownbox_shortcut_token"
    const val EXTRA_PROFILE = "profile"

    @Volatile
    private var cached: String? = null

    @Synchronized
    fun token(context: Context): String {
        cached?.let { return it }
        val dir = if (Build.VERSION.SDK_INT >= 21) context.noBackupFilesDir else context.filesDir
        val file = File(dir, "shortcut_token")
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrEmpty() && existing.length >= 32) {
            cached = existing
            return existing
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = bytes.joinToString("") { "%02x".format(it) }
        // 先写临时文件再改名，避免主进程与 :bg 进程同时生成时读到半截内容
        val tmp = File(dir, "shortcut_token.${android.os.Process.myPid()}.tmp")
        tmp.writeText(value)
        if (!file.exists()) tmp.renameTo(file) else tmp.delete()
        val final = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.length >= 32 } ?: value
        cached = final
        return final
    }

    fun isValid(context: Context, intent: Intent?): Boolean {
        val given = intent?.getStringExtra(EXTRA_TOKEN) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token(context).toByteArray())
    }

    /**
     * 返回给桌面的快捷方式 Intent：API 26+ 直接指向不导出的 [QuickToggleShortcut]（系统以本应用身份启动）；
     * 更老的系统走带令牌的 [ShortcutTrampoline]。
     */
    fun toggleIntent(context: Context, profileId: Long = -1L): Intent {
        val target = if (Build.VERSION.SDK_INT >= 26) {
            Intent(context, QuickToggleShortcut::class.java)
        } else {
            Intent(context, ShortcutTrampoline::class.java).putExtra(EXTRA_TOKEN, token(context))
        }
        return target.setAction(Intent.ACTION_MAIN).apply {
            if (profileId >= 0) putExtra(EXTRA_PROFILE, profileId)
        }
    }
}

/** 仅响应 ACTION_CREATE_SHORTCUT，自身不执行任何开关操作。 */
class QuickToggleShortcutCreator : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_CREATE_SHORTCUT) {
            setResult(
                RESULT_OK,
                androidx.core.content.pm.ShortcutManagerCompat.createShortcutResultIntent(
                    this,
                    androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "toggle")
                        .setIntent(ShortcutGuard.toggleIntent(this))
                        .setIcon(
                            androidx.core.graphics.drawable.IconCompat.createWithResource(
                                this, R.drawable.ic_qu_shadowsocks_launcher
                            )
                        )
                        .setShortLabel(getString(R.string.quick_toggle))
                        .build()
                )
            )
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}

/** 老式桌面快捷方式入口：令牌正确才转发给不导出的 [QuickToggleShortcut]。 */
class ShortcutTrampoline : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ShortcutGuard.isValid(this, intent)) {
            val forward = Intent(this, QuickToggleShortcut::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val profile = intent.getLongExtra(ShortcutGuard.EXTRA_PROFILE, -1L)
            if (profile >= 0) forward.putExtra(ShortcutGuard.EXTRA_PROFILE, profile)
            startActivity(forward)
        } else {
            io.nekohasekai.sagernet.ktx.Logs.w("ShortcutTrampoline: rejected launch without valid token")
        }
        finish()
    }
}
