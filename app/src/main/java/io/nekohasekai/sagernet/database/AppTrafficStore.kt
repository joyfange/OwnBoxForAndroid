package io.nekohasekai.sagernet.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.nekohasekai.sagernet.ktx.app
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按「天 × 应用」累计经过 OwnBox 的流量。
 *
 * 独立的小 SQLite 库（不进 Room 主库，不需要迁移）。:bg 进程写、主进程读，
 * 使用默认回滚日志模式，SQLite 的文件锁保证跨进程安全。
 */
object AppTrafficStore {

    const val UNKNOWN = "unknown"

    data class Usage(
        val key: String,
        val upload: Long,
        val download: Long,
        val direct: Long,
    ) {
        val total: Long get() = upload + download
        val proxied: Long get() = (total - direct).coerceAtLeast(0L)
    }

    private val dayFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    fun dayOf(millis: Long): String = dayFormat.get()!!.format(Date(millis))

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "app_traffic.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS usage (" +
                        "day TEXT NOT NULL, app TEXT NOT NULL, " +
                        "up INTEGER NOT NULL DEFAULT 0, down INTEGER NOT NULL DEFAULT 0, " +
                        "direct INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, app))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS usage_day ON usage(day)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val helper by lazy { Helper(app) }

    /** deltas: day -> app -> [upload, download, direct] */
    @Synchronized
    fun add(deltas: Map<String, Map<String, LongArray>>) {
        if (deltas.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for ((day, apps) in deltas) {
                for ((appKey, v) in apps) {
                    if (v[0] == 0L && v[1] == 0L) continue
                    val seed = ContentValues().apply {
                        put("day", day)
                        put("app", appKey)
                    }
                    db.insertWithOnConflict("usage", null, seed, SQLiteDatabase.CONFLICT_IGNORE)
                    db.execSQL(
                        "UPDATE usage SET up = up + ?, down = down + ?, direct = direct + ? WHERE day = ? AND app = ?",
                        arrayOf<Any>(v[0], v[1], v[2], day, appKey)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** [fromDay, toDay] 闭区间内每个应用的合计，按总量从大到小。 */
    fun appsBetween(fromDay: String, toDay: String): List<Usage> {
        val result = ArrayList<Usage>()
        helper.readableDatabase.rawQuery(
            "SELECT app, SUM(up), SUM(down), SUM(direct) FROM usage WHERE day >= ? AND day <= ? " +
                    "GROUP BY app ORDER BY SUM(up) + SUM(down) DESC",
            arrayOf(fromDay, toDay)
        ).use { c ->
            while (c.moveToNext()) {
                result.add(Usage(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3)))
            }
        }
        return result
    }

    /** [fromDay, toDay] 闭区间内每天的合计（没有数据的天不出现）。 */
    fun dailyTotals(fromDay: String, toDay: String): Map<String, Long> {
        val result = HashMap<String, Long>()
        helper.readableDatabase.rawQuery(
            "SELECT day, SUM(up) + SUM(down) FROM usage WHERE day >= ? AND day <= ? GROUP BY day",
            arrayOf(fromDay, toDay)
        ).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = c.getLong(1)
        }
        return result
    }

    @Synchronized
    fun clear() {
        helper.writableDatabase.delete("usage", null, null)
    }

    /** 只保留最近 400 天，防止无限增长。 */
    @Synchronized
    fun prune(keepDays: Int = 400) {
        val cutoff = dayOf(System.currentTimeMillis() - keepDays * 86_400_000L)
        runCatching { helper.writableDatabase.delete("usage", "day < ?", arrayOf(cutoff)) }
    }
}
