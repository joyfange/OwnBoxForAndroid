package io.nekohasekai.sagernet.bg

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.QuickEnableShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 Wi‑Fi 自动开关。
 *
 * - 连上「信任的 Wi‑Fi」（用户自己加的，如家里）时，如果代理正在运行就断开，并记下是自动断开的。
 * - 离开信任 Wi‑Fi（换到别的 Wi‑Fi 或移动数据）时，只把「由它自动断开」的连接接回来；
 *   用户本来就关着的不会被擅自打开。
 * - 只在信任状态发生变化时动作：在家里手动打开代理，不会被立刻关掉。
 *
 * 网络变化由系统通过 PendingIntent 投递（registerNetworkCallback(request, PendingIntent)），
 * 进程不在也能被唤醒；代理运行时 :bg 进程里的默认网络监听也会触发一次判断。
 */
object WifiAutoSwitch {

    private const val ACTION_NETWORK_EVENT = "io.nekohasekai.sagernet.WIFI_AUTO_NETWORK_EVENT"
    private const val CHANNEL_ID = "wifi-auto"
    private const val NOTIFICATION_ID = 0x5A11
    private const val REQUEST_WIFI = 31
    private const val REQUEST_CELLULAR = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** 解析用户填写的信任列表：逗号、分号或换行分隔，忽略首尾空格和引号。 */
    fun trustedList(raw: String = DataStore.wifiTrustedSsids): List<String> =
        raw.split(',', ';', '\n', '，', '；')
            .map { it.trim().removeSurrounding("\"").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 当前连接的 Wi‑Fi 名称；不在 Wi‑Fi 上或系统不给（缺定位权限/定位关闭）时返回 null。 */
    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val onWifi = cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (!onWifi) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val info = wifi.connectionInfo ?: return null
        val ssid = info.ssid?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "0x") return null
        return ssid
    }

    /** 按开关状态注册或取消系统网络回调。可在任意进程、任意时刻重复调用。 */
    fun syncRegistration(context: Context = app) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val wifiIntent = pendingIntent(context, REQUEST_WIFI)
        val cellularIntent = pendingIntent(context, REQUEST_CELLULAR)
        runCatching { cm.unregisterNetworkCallback(wifiIntent) }
        runCatching { cm.unregisterNetworkCallback(cellularIntent) }
        if (!DataStore.wifiAutoSwitch) {
            DataStore.wifiAutoPaused = false
            DataStore.wifiLastTrusted = ""
            return
        }
        if (Build.VERSION.SDK_INT < 23) return
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                wifiIntent
            )
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellularIntent
            )
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun pendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, WifiAutoReceiver::class.java)
            .setAction(ACTION_NETWORK_EVENT)
            .setPackage(context.packageName)
        // 系统要往里塞 Network 等附加信息，S+ 必须是可变的；显式组件，外部无法改写目标
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /** 网络变了：稍等系统把 Wi‑Fi 信息准备好再判断，连续事件合并处理。 */
    fun onNetworkEvent(context: Context = app, delayMs: Long = 1500L) {
        if (!DataStore.wifiAutoSwitch) return
        scope.launch {
            delay(delayMs)
            evaluate(context.applicationContext)
        }
    }

    suspend fun evaluate(context: Context) = mutex.withLock {
        if (!DataStore.wifiAutoSwitch) return@withLock
        val trustedSet = trustedList()
        if (trustedSet.isEmpty()) return@withLock
        val ssid = currentSsid(context)
        val trusted = ssid != null && trustedSet.any { it.equals(ssid, ignoreCase = false) }
        val nowState = if (trusted) "1" else "0"
        val state = DataStore.serviceState

        // 用户在自动断开期间自己又打开了：以用户为准，不再自动接回
        if (DataStore.wifiAutoPaused && state.started) DataStore.wifiAutoPaused = false

        val lastState = DataStore.wifiLastTrusted
        if (lastState == nowState) return@withLock
        DataStore.wifiLastTrusted = nowState
        if (lastState.isEmpty()) {
            // 第一次判断只记录状态，不动开关，避免刚打开功能就被断开
            return@withLock
        }

        if (trusted) {
            if (state.canStop) {
                Logs.i("WifiAutoSwitch: trusted Wi-Fi joined, stopping service")
                DataStore.wifiAutoPaused = true
                SagerNet.stopService()
            }
        } else if (DataStore.wifiAutoPaused) {
            DataStore.wifiAutoPaused = false
            if (!state.started && !state.canStop) {
                Logs.i("WifiAutoSwitch: left trusted Wi-Fi, starting service")
                tryStart(context)
            }
        }
    }

    private fun tryStart(context: Context) {
        if (DataStore.selectedProxy <= 0L) return
        if (DataStore.serviceMode == Key.MODE_VPN &&
            android.net.VpnService.prepare(context) != null
        ) {
            notifyStartFailed(context)
            return
        }
        try {
            SagerNet.startService()
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务可能被拒（未关闭电池优化时）
            Logs.w(e)
            notifyStartFailed(context)
        }
    }

    private fun notifyStartFailed(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Wi‑Fi 自动开关", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, QuickEnableShortcut::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_throne_tile)
                .setContentTitle("已离开信任的 Wi‑Fi")
                .setContentText("系统不允许后台自动连接，点这里重新连接")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }.onFailure { Logs.w(it) }
    }
}

class WifiAutoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                delay(1500L)
                WifiAutoSwitch.evaluate(context.applicationContext)
            } catch (e: Exception) {
                Logs.w(e)
            } finally {
                pending.finish()
            }
        }
    }
}
