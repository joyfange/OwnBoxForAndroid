package io.nekohasekai.sagernet.ui

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.AppTrafficStore
import io.nekohasekai.sagernet.databinding.ActivityAppTrafficBinding
import io.nekohasekai.sagernet.databinding.LayoutAppTrafficItemBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.UsageDonutView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * 按天 / 按月查看每个应用经过 OwnBox 的流量：环形占比、每日柱状趋势、应用排行。
 */
class AppTrafficActivity : ThemedActivity() {

    private lateinit var binding: ActivityAppTrafficBinding

    private var monthMode = false
    /** 当前查看的日期（按月模式下只用年、月）。 */
    private val cursor: Calendar = Calendar.getInstance()
    private var loadJob: Job? = null

    private val labelCache = HashMap<String, String>()
    private val iconCache = HashMap<String, Drawable?>()

    private val palette = intArrayOf(
        0xFF6C8CFF.toInt(), // 蓝
        0xFF2DD4BF.toInt(), // 青
        0xFFFFB547.toInt(), // 橙
        0xFFFF6B8B.toInt(), // 粉
        0xFFA78BFA.toInt(), // 紫
    )
    private val otherColor = 0xFF8A94A6.toInt()

    private data class Snapshot(
        val apps: List<AppTrafficStore.Usage>,
        val trend: List<Long>,
        val trendLabels: List<String>,
        val trendSelected: Int,
        val labels: Map<String, String>,
        val icons: Map<String, Drawable?>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppTrafficBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.app_traffic_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_app_traffic) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("清空应用流量统计？")
                    .setMessage("所有日期的记录都会删除，无法恢复。")
                    .setPositiveButton("清空") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { runCatching { AppTrafficStore.clear() } }
                            reload()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            } else false
        }

        val primary = Theme.getPrimaryColor(this)
        val textPrimary = getColorAttr(android.R.attr.textColorPrimary)
        val textSecondary = getColorAttr(android.R.attr.textColorSecondary)
        binding.donut.setColors(
            trackColor = ColorUtils.setAlphaComponent(textSecondary, 0x22),
            valueColor = textPrimary,
            labelColor = textSecondary,
        )
        binding.barChart.setColors(
            accentColor = primary,
            labelTextColor = textSecondary,
            gridLineColor = ColorUtils.setAlphaComponent(textSecondary, 0x1F),
        )
        binding.proxyRatio.setIndicatorColor(primary)
        binding.proxyRatio.trackColor = ColorUtils.setAlphaComponent(palette[1], 0x99)

        binding.periodToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val month = checkedId == R.id.btn_month
            if (month != monthMode) {
                monthMode = month
                reload()
            }
        }
        binding.btnPrev.setOnClickListener { shift(-1) }
        binding.btnNext.setOnClickListener { shift(1) }
        binding.barChart.onBarSelected = { index -> onTrendBarSelected(index) }

        reload()
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        // 回到页面时刷新今天的数字（后台每 30 秒落盘一次）；第一次进入时 onCreate 已加载
        if (resumedOnce && isToday()) reload(animate = false)
        resumedOnce = true
    }

    private fun isToday(): Boolean {
        val now = Calendar.getInstance()
        return now.get(Calendar.YEAR) == cursor.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == cursor.get(Calendar.DAY_OF_YEAR)
    }

    private fun shift(step: Int) {
        val next = cursor.clone() as Calendar
        if (monthMode) next.add(Calendar.MONTH, step) else next.add(Calendar.DAY_OF_MONTH, step)
        if (next.after(Calendar.getInstance())) {
            // 不翻到未来；按月模式下翻到本月即停在今天
            if (!monthMode) return
            val now = Calendar.getInstance()
            if (next.get(Calendar.YEAR) > now.get(Calendar.YEAR) ||
                (next.get(Calendar.YEAR) == now.get(Calendar.YEAR) && next.get(Calendar.MONTH) > now.get(Calendar.MONTH))
            ) return
            next.timeInMillis = now.timeInMillis
        }
        cursor.timeInMillis = next.timeInMillis
        reload()
    }

    /** 按天：柱子是以所选日期结尾的 7 天；按月：柱子是这个月的每一天。 */
    private fun onTrendBarSelected(index: Int) {
        if (monthMode) {
            // 在按月视图点某一天：切到按天查看那一天
            val day = cursor.clone() as Calendar
            day.set(Calendar.DAY_OF_MONTH, index + 1)
            if (day.after(Calendar.getInstance())) return
            cursor.timeInMillis = day.timeInMillis
            monthMode = false
            binding.periodToggle.check(R.id.btn_day)
            reload()
        } else {
            val day = cursor.clone() as Calendar
            day.add(Calendar.DAY_OF_MONTH, index - 6)
            cursor.timeInMillis = day.timeInMillis
            reload(animateTrend = false)
        }
    }

    private fun dayKey(c: Calendar) = AppTrafficStore.dayOf(c.timeInMillis)

    private fun reload(animate: Boolean = true, animateTrend: Boolean = animate) {
        updatePeriodTitle()
        val month = monthMode
        val base = cursor.clone() as Calendar
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { runCatching { load(base, month) }.getOrNull() }
                ?: return@launch
            render(snapshot, month, animate, animateTrend)
        }
    }

    @Synchronized
    private fun load(base: Calendar, month: Boolean): Snapshot {
        val trendValues = ArrayList<Long>()
        val trendLabels = ArrayList<String>()
        val from: String
        val to: String
        val selected: Int
        if (month) {
            val first = base.clone() as Calendar
            first.set(Calendar.DAY_OF_MONTH, 1)
            val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
            val last = first.clone() as Calendar
            last.set(Calendar.DAY_OF_MONTH, days)
            from = dayKey(first)
            to = dayKey(last)
            val totals = AppTrafficStore.dailyTotals(from, to)
            val d = first.clone() as Calendar
            for (i in 1..days) {
                trendValues.add(totals[dayKey(d)] ?: 0L)
                trendLabels.add(i.toString())
                d.add(Calendar.DAY_OF_MONTH, 1)
            }
            val now = Calendar.getInstance()
            selected = if (now.get(Calendar.YEAR) == first.get(Calendar.YEAR) &&
                now.get(Calendar.MONTH) == first.get(Calendar.MONTH)
            ) now.get(Calendar.DAY_OF_MONTH) - 1 else -1
        } else {
            from = dayKey(base)
            to = from
            val start = base.clone() as Calendar
            start.add(Calendar.DAY_OF_MONTH, -6)
            val totals = AppTrafficStore.dailyTotals(dayKey(start), to)
            val d = start.clone() as Calendar
            val weekdays = arrayOf("日", "一", "二", "三", "四", "五", "六")
            for (i in 0..6) {
                trendValues.add(totals[dayKey(d)] ?: 0L)
                trendLabels.add(
                    if (i == 6) "${d.get(Calendar.MONTH) + 1}/${d.get(Calendar.DAY_OF_MONTH)}"
                    else "周" + weekdays[d.get(Calendar.DAY_OF_WEEK) - 1]
                )
                d.add(Calendar.DAY_OF_MONTH, 1)
            }
            selected = 6
        }
        val apps = AppTrafficStore.appsBetween(from, to)
        val labels = HashMap<String, String>()
        val icons = HashMap<String, Drawable?>()
        for (usage in apps.take(60)) {
            labels[usage.key] = labelCache.getOrPut(usage.key) { resolveLabel(usage.key) }
            icons[usage.key] = if (iconCache.containsKey(usage.key)) iconCache[usage.key]
            else resolveIcon(usage.key).also { iconCache[usage.key] = it }
        }
        return Snapshot(apps, trendValues, trendLabels, selected, labels, icons)
    }

    private fun resolveLabel(key: String): String = when {
        key == AppTrafficStore.UNKNOWN -> "未识别的应用"
        key == "android" -> "Android 系统"
        key.startsWith("proc:") -> key.removePrefix("proc:")
        else -> runCatching {
            val info = packageManager.getApplicationInfo(key, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(key)
    }

    private fun resolveIcon(key: String): Drawable? = when {
        key == AppTrafficStore.UNKNOWN || key.startsWith("proc:") -> null
        else -> try {
            packageManager.getApplicationIcon(key)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun updatePeriodTitle() {
        val y = cursor.get(Calendar.YEAR)
        val m = cursor.get(Calendar.MONTH) + 1
        binding.periodTitle.text = if (monthMode) {
            "${y}年${m}月"
        } else {
            val d = cursor.get(Calendar.DAY_OF_MONTH)
            val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            val suffix = if (isToday()) "今天" else weekdays[cursor.get(Calendar.DAY_OF_WEEK) - 1]
            "${y}年${m}月${d}日 $suffix"
        }
        val now = Calendar.getInstance()
        binding.btnNext.isEnabled = if (monthMode) {
            cursor.get(Calendar.YEAR) < now.get(Calendar.YEAR) ||
                    (cursor.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cursor.get(Calendar.MONTH) < now.get(Calendar.MONTH))
        } else !isToday()
        binding.btnNext.alpha = if (binding.btnNext.isEnabled) 1f else 0.3f
        binding.trendTitle.text = if (monthMode) "本月每日用量" else "近 7 天"
    }

    private fun render(s: Snapshot, month: Boolean, animate: Boolean, animateTrend: Boolean) {
        val upload = s.apps.sumOf { it.upload }
        val download = s.apps.sumOf { it.download }
        val total = upload + download
        val direct = s.apps.sumOf { it.direct }

        // 环形图：前 5 个应用 + 其他
        val slices = ArrayList<UsageDonutView.Slice>()
        s.apps.take(palette.size).forEachIndexed { i, u -> slices.add(UsageDonutView.Slice(u.total, palette[i])) }
        val rest = s.apps.drop(palette.size).sumOf { it.total }
        if (rest > 0L) slices.add(UsageDonutView.Slice(rest, otherColor))
        binding.donut.setData(slices, formatBytes(total), if (month) "本月总流量" else "当日总流量", animate)

        binding.statUpload.text = formatBytes(upload)
        binding.statDownload.text = formatBytes(download)
        binding.statApps.text = s.apps.size.toString()

        val proxied = (total - direct).coerceAtLeast(0L)
        val proxyPermille = if (total > 0L) (proxied * 1000L / total).toInt() else 0
        binding.proxyRatio.setProgressCompat(proxyPermille, animate)
        binding.proxyText.text = "代理 ${formatBytes(proxied)} · ${percent(proxied, total)}"
        binding.directText.text = "直连 ${formatBytes(direct)} · ${percent(direct, total)}"

        binding.barChart.setData(s.trend, s.trendLabels, s.trendSelected, ::formatBytes, animateTrend)

        // 应用排行
        binding.appList.removeAllViews()
        binding.emptyText.isVisible = s.apps.isEmpty()
        val top = s.apps.firstOrNull()?.total ?: 0L
        val inflater = LayoutInflater.from(this)
        val textSecondary = getColorAttr(android.R.attr.textColorSecondary)
        s.apps.take(60).forEachIndexed { i, u ->
            val row = LayoutAppTrafficItemBinding.inflate(inflater, binding.appList, false)
            val color = if (i < palette.size) palette[i] else otherColor
            row.rank.text = (i + 1).toString()
            row.rank.setTextColor(if (i < 3) color else textSecondary)
            row.name.text = s.labels[u.key] ?: u.key
            row.total.text = formatBytes(u.total)
            val icon = s.icons[u.key]
            if (icon != null) {
                row.icon.setImageDrawable(icon)
                row.icon.imageTintList = null
            } else {
                row.icon.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_baseline_data_usage_24))
                row.icon.imageTintList = ColorStateList.valueOf(color)
            }
            row.bar.setIndicatorColor(color)
            row.bar.trackColor = ColorUtils.setAlphaComponent(color, 0x26)
            val permille = if (top > 0L) (u.total * 1000L / top).toInt().coerceAtLeast(8) else 0
            row.bar.setProgressCompat(permille, animate)
            row.detail.text = "↑ ${formatBytes(u.upload)}  ↓ ${formatBytes(u.download)} · 代理 ${percent(u.proxied, u.total)} · 占 ${percent(u.total, total)}"
            binding.appList.addView(row.root)
        }
    }

    private fun percent(part: Long, whole: Long): String {
        if (whole <= 0L) return "0%"
        val p = part * 100.0 / whole
        return if (p > 0 && p < 1) "<1%" else String.format(Locale.US, "%.0f%%", p)
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30))
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20))
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / (1L shl 10))
            else -> "$bytes B"
        }
    }
}
