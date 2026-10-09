package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.AppTrafficStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.connections.ConnectionFilter
import io.nekohasekai.sagernet.ui.connections.ConnectionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 运行期间定时读取内核的连接快照（含刚关闭的连接），把每条连接新增的上传/下载
 * 记到它所属的应用名下，按天累计写入 [AppTrafficStore]。
 *
 * 只读内核已有的连接表，不改动转发路径；每 5 秒一次，每 30 秒落盘一次，停止时补记最后一段。
 */
class AppTrafficRecorder(private val box: libcore.BoxInstance) {

    companion object {
        // 亮屏 10 秒读一次连接表；熄屏 60 秒一次，避免频繁唤醒 CPU
        private const val POLL_SCREEN_ON_MS = 10_000L
        private const val POLL_SCREEN_OFF_MS = 60_000L
        // 攒够 60 秒（亮屏）或每次熄屏轮询后落盘
        private const val FLUSH_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 连接 id -> 已计入的 [上传, 下载] */
    private val counted = HashMap<String, LongArray>()

    /** day -> app -> [上传, 下载, 直连] */
    private val pending = HashMap<String, HashMap<String, LongArray>>()

    fun start() {
        AppTrafficStore.prune()
        job = scope.launch {
            var lastFlush = System.currentTimeMillis()
            while (isActive) {
                val interactive = runCatching { io.nekohasekai.sagernet.SagerNet.power.isInteractive }
                    .getOrDefault(true)
                delay(if (interactive) POLL_SCREEN_ON_MS else POLL_SCREEN_OFF_MS)
                runCatching { poll() }.onFailure { Logs.w(it) }
                val now = System.currentTimeMillis()
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    lastFlush = now
                    flush()
                }
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        runCatching { poll() }
        flush()
    }

    @Synchronized
    private fun poll() {
        val list = ConnectionInfo.parseSnapshot(box.queryConnections(ConnectionFilter.ALL))
        if (list.isEmpty()) return
        val day = AppTrafficStore.dayOf(System.currentTimeMillis())
        val alive = HashSet<String>(list.size)
        for (c in list) {
            alive += c.id
            val prev = counted[c.id]
            var up = c.upload - (prev?.get(0) ?: 0L)
            var down = c.download - (prev?.get(1) ?: 0L)
            if (up < 0L) up = c.upload
            if (down < 0L) down = c.download
            counted[c.id] = longArrayOf(c.upload, c.download)
            if (up == 0L && down == 0L) continue
            val key = appKey(c)
            val slot = pending.getOrPut(day) { HashMap() }.getOrPut(key) { LongArray(3) }
            slot[0] += up
            slot[1] += down
            if (c.outboundType.equals("direct", ignoreCase = true)) slot[2] += up + down
        }
        // 内核的已关闭列表有上限，滚出去的连接不会再出现，忘掉即可
        counted.keys.retainAll(alive)
    }

    private fun appKey(c: ConnectionInfo): String {
        c.packageNames.firstOrNull { it.isNotBlank() }?.let { return it }
        if (c.processPath.isNotBlank()) return "proc:" + c.processPath.substringAfterLast('/')
        return AppTrafficStore.UNKNOWN
    }

    private fun flush() {
        val snapshot: Map<String, Map<String, LongArray>> = synchronized(this) {
            if (pending.isEmpty()) return
            val copy = HashMap<String, Map<String, LongArray>>(pending)
            pending.clear()
            copy
        }
        runCatching { AppTrafficStore.add(snapshot) }.onFailure { Logs.w(it) }
    }
}
