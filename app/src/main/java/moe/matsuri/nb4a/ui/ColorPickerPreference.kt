package moe.matsuri.nb4a.ui

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import kotlin.math.roundToInt

class ColorPickerPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(context, attrs, defStyle) {

    data class PresetTheme(
        val id: Int,
        val name: String,
        val color: Int,
        val subtitle: String? = null
    )

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout
        widgetFrame.removeAllViews()

        val displayColor = Theme.palette(context).background

        val factor = context.resources.displayMetrics.density
        val size = (44 * factor).roundToInt()
        val widgetIv = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageDrawable(getColorBadgeDrawable(context.resources, displayColor, false))
        }
        widgetFrame.addView(widgetIv)
        widgetFrame.visibility = View.VISIBLE
    }

    private fun getColorBadgeDrawable(res: Resources, color: Int, isSelected: Boolean): Drawable {
        val factor = res.displayMetrics.density
        val lum = ColorUtils.calculateLuminance(color or 0xFF000000.toInt())
        val strokeColor = when {
            lum > 0.85 -> Color.parseColor("#CBD5E1")
            lum < 0.05 -> Color.parseColor("#444444")
            else -> Color.parseColor("#33000000")
        }

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * factor).roundToInt().coerceAtLeast(1), strokeColor)
        }

        if (!isSelected) {
            return circle
        }

        val checkmark = ResourcesCompat.getDrawable(res, R.drawable.ic_baseline_check_circle_24, null)!!.mutate()
        val checkTint = if (ColorUtils.calculateContrast(Color.WHITE, color or 0xFF000000.toInt()) < 2.2) {
            Color.parseColor("#212121")
        } else {
            Color.WHITE
        }
        DrawableCompat.setTint(checkmark, checkTint)

        val checkInset = (8 * factor).roundToInt()
        val layer = LayerDrawable(arrayOf(circle, checkmark))
        layer.setLayerInset(1, checkInset, checkInset, checkInset, checkInset)
        return layer
    }

    private fun recreateHost() {
        val host = context as? Activity ?: return
        if (host is io.nekohasekai.sagernet.ui.ThemedActivity) {
            host.switchThemeSmoothly()
        } else {
            io.nekohasekai.sagernet.utils.Theme.applyNightTheme()
            ActivityCompat.recreate(host)
        }
    }

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(context.getColorAttr(R.attr.primaryOrTextSecondary))
        setTypeface(null, Typeface.BOLD)
        setPadding(dp2px(4), dp2px(4), 0, dp2px(8))
    }

    override fun onClick() {
        super.onClick()

        lateinit var dialog: AlertDialog

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(10), dp2px(16), dp2px(16))
        }

        val scroll = NestedScrollView(context).apply {
            addView(rootLayout)
        }

        val nightActive = Theme.usingNightMode(context)
        // What is actually on screen (night mode shows black instead of a light base).
        val currentThemeId = Theme.currentBaseId(context).let {
            // below Android 11 a custom base is shown as its closest preset; keep its custom tile selected
            val selected = Theme.selectedBaseId()
            when {
                selected == Theme.CUSTOM_BASE -> Theme.CUSTOM_BASE
                selected == Theme.CUSTOM_BASE_LIGHT && !nightActive -> Theme.CUSTOM_BASE_LIGHT
                else -> it
            }
        }

        fun applyTheme(themeId: Int, customColor: Int? = null) {
            dialog.dismiss()
            var colorChanged = false
            if (customColor != null) {
                // each custom base keeps its own colour, pushed into its light or dark range
                if (themeId == Theme.CUSTOM_BASE_LIGHT) {
                    val c = Theme.toLightBase(customColor)
                    colorChanged = c != Theme.customBaseColor(Theme.CUSTOM_BASE_LIGHT)
                    DataStore.baseCustomLightColor = c
                } else {
                    val c = Theme.toDarkBase(customColor)
                    colorChanged = c != Theme.customBaseColor(Theme.CUSTOM_BASE)
                    DataStore.baseCustomColor = c
                }
            }
            if (themeId == currentThemeId && !colorChanged) return
            persistInt(themeId)
            DataStore.appTheme = themeId
            // Night mode shows black instead of a light base, so picking a light base turns night mode off;
            // dark bases work with any night mode setting.
            val pickedDark = themeId == Theme.CUSTOM_BASE || Theme.baseOf(themeId)?.dark == true
            if (!pickedDark && Theme.usingNightMode(context)) {
                DataStore.nightTheme = 2
                Theme.currentNightMode = 2
            }
            callChangeListener(themeId)
            notifyChanged()
            recreateHost()
        }

        fun applyAccent(accentId: Int, customColor: Int? = null) {
            dialog.dismiss()
            if (customColor != null) DataStore.accentCustomColor = customColor
            if (accentId == DataStore.accentTheme && customColor == null) return
            DataStore.accentTheme = accentId
            notifyChanged()
            recreateHost()
        }

        // 1. Base themes: preview tiles (background, a card and the primary colour), light then dark.
        rootLayout.addView(sectionTitle(context.getString(R.string.theme_base_section)))
        if (nightActive) {
            rootLayout.addView(TextView(context).apply {
                text = context.getString(R.string.theme_base_night_note)
                textSize = 11.5f
                setTextColor(context.getColorAttr(android.R.attr.textColorSecondary))
                setPadding(dp2px(4), 0, dp2px(4), dp2px(8))
            })
        }

        val selectedStroke = Theme.accentColor(context) ?: context.getColorAttr(android.R.attr.textColorPrimary)

        fun baseTile(id: Int, label: String, bg: Int, card: Int, primary: Int, isCustom: Boolean): View {
            val selected = currentThemeId == id
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp2px(4)
                    marginEnd = dp2px(4)
                }
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener {
                    if (isCustom) {
                        val light = id == Theme.CUSTOM_BASE_LIGHT
                        AccentColorPickerDialog.show(
                            context, Theme.customBaseColor(id),
                            titleRes = if (light) R.string.base_custom_light_title else R.string.base_custom_dark_title,
                            noteRes = if (light) R.string.color_picker_base_light_note else R.string.color_picker_base_dark_note,
                            fallbackNoteRes = R.string.color_picker_base_fallback_note,
                        ) { picked -> applyTheme(id, picked) }
                    } else {
                        applyTheme(id)
                    }
                }

                val preview = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(58))
                    background = GradientDrawable().apply {
                        cornerRadius = dp2px(12).toFloat()
                        setColor(bg)
                        val lum = ColorUtils.calculateLuminance(bg)
                        setStroke(
                            if (selected) dp2px(2) else dp2px(1),
                            when {
                                selected -> selectedStroke
                                lum > 0.8 -> Color.parseColor("#D5DAE1")
                                else -> Color.parseColor("#33888888")
                            }
                        )
                    }
                    // a mini card
                    addView(View(context).apply {
                        layoutParams = FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(20)).apply {
                            gravity = Gravity.BOTTOM
                            setMargins(dp2px(8), 0, dp2px(8), dp2px(8))
                        }
                        background = GradientDrawable().apply {
                            cornerRadius = dp2px(6).toFloat()
                            setColor(if (card == bg) ColorUtils.blendARGB(bg, if (ColorUtils.calculateLuminance(bg) > 0.5) Color.BLACK else Color.WHITE, 0.06f) else card)
                        }
                    })
                    // primary dot (palette icon on the custom tile, check when selected)
                    addView(ImageView(context).apply {
                        val sz = dp2px(18)
                        layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                            gravity = Gravity.TOP or Gravity.END
                            setMargins(0, dp2px(7), dp2px(8), 0)
                        }
                        val dot = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(primary)
                        }
                        val iconRes = when {
                            selected -> R.drawable.ic_baseline_check_circle_24
                            isCustom -> R.drawable.ic_baseline_color_lens_24
                            else -> 0
                        }
                        val icon = if (iconRes != 0) ResourcesCompat.getDrawable(context.resources, iconRes, null)?.mutate() else null
                        if (icon != null) {
                            DrawableCompat.setTint(
                                icon,
                                if (ColorUtils.calculateContrast(Color.WHITE, primary or 0xFF000000.toInt()) >= 2.2) Color.WHITE else Color.parseColor("#212121")
                            )
                            val layer = LayerDrawable(arrayOf<Drawable>(dot, icon))
                            val inset = dp2px(2)
                            layer.setLayerInset(1, inset, inset, inset, inset)
                            setImageDrawable(layer)
                        } else {
                            setImageDrawable(dot)
                        }
                    })
                }
                addView(preview)
                addView(TextView(context).apply {
                    text = label
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp2px(4), 0, dp2px(8))
                    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(
                        context.getColorAttr(
                            if (selected) android.R.attr.textColorPrimary else android.R.attr.textColorSecondary
                        )
                    )
                })
            }
        }

        fun addTileGrid(tiles: List<View>, columns: Int = 4) {
            var line: LinearLayout? = null
            tiles.forEachIndexed { i, tile ->
                if (i % columns == 0) {
                    line = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    rootLayout.addView(line)
                }
                line?.addView(tile)
            }
            val rem = tiles.size % columns
            if (rem != 0) repeat(columns - rem) {
                line?.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                        marginStart = dp2px(4)
                        marginEnd = dp2px(4)
                    }
                })
            }
        }

        fun subTitle(res: Int) = TextView(context).apply {
            text = context.getString(res)
            textSize = 11.5f
            setTextColor(context.getColorAttr(android.R.attr.textColorSecondary))
            setPadding(dp2px(4), dp2px(2), 0, dp2px(6))
        }

        fun presetTile(base: Theme.Base) = baseTile(
            base.id, context.getString(base.title),
            ContextCompat.getColor(context, base.background),
            ContextCompat.getColor(context, base.card),
            ContextCompat.getColor(context, base.primary),
            false,
        )

        fun customTile(id: Int, label: Int): View {
            val colors = Theme.customBaseColors(Theme.customBaseColor(id))
            return baseTile(
                id, context.getString(label),
                colors.getValue(R.color.base_custom_bg),
                colors.getValue(R.color.base_custom_card),
                colors.getValue(R.color.base_custom_primary),
                true,
            )
        }

        // a custom tile at the end of each group: one keeps a light colour, the other a dark one
        rootLayout.addView(subTitle(R.string.theme_base_light))
        addTileGrid(Theme.BASES.filter { !it.dark }.map { presetTile(it) } +
                customTile(Theme.CUSTOM_BASE_LIGHT, R.string.base_custom_light))
        rootLayout.addView(subTitle(R.string.theme_base_dark))
        addTileGrid(Theme.BASES.filter { it.dark }.map { presetTile(it) } +
                customTile(Theme.CUSTOM_BASE, R.string.base_custom_dark))

        // 2. Accent colours (independent of the base above)
        rootLayout.addView(sectionTitle(context.getString(R.string.theme_accent_section)).apply {
            setPadding(dp2px(4), dp2px(12), 0, dp2px(8))
        })

        val dark = Theme.isBlackTheme(context)
        val currentAccent = DataStore.accentTheme
        val noneColor = Theme.palette(context).let {
            when (it.id) {
                Theme.BLACK -> Color.WHITE
                Theme.WHITE -> Color.parseColor("#212121")
                Theme.LIGHT_GRAY -> Color.parseColor("#1F2937")
                else -> it.primary
            }
        }

        class Swatch(val id: Int, val label: String, val color: Int)

        val swatches = ArrayList<Swatch>()
        swatches.add(Swatch(Theme.ACCENT_NONE, context.getString(R.string.accent_default), noneColor))
        for (accent in Theme.ACCENTS) {
            swatches.add(
                Swatch(
                    accent.id, context.getString(accent.title),
                    ContextCompat.getColor(context, if (dark) accent.darkColor else accent.lightColor)
                )
            )
        }
        swatches.add(Swatch(Theme.CUSTOM, context.getString(R.string.accent_custom), DataStore.accentCustomColor))

        val columns = 5
        var row: LinearLayout? = null
        swatches.forEachIndexed { index, swatch ->
            if (index % columns == 0) {
                val newRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp2px(6) }
                }
                rootLayout.addView(newRow)
                row = newRow
            }
            val selected = swatch.id == currentAccent
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, dp2px(4), 0, dp2px(4))
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
                setOnClickListener {
                    if (swatch.id == Theme.CUSTOM) {
                        AccentColorPickerDialog.show(context, DataStore.accentCustomColor) { picked ->
                            applyAccent(Theme.CUSTOM, picked)
                        }
                    } else {
                        applyAccent(swatch.id)
                    }
                }
                addView(ImageView(context).apply {
                    val sz = dp2px(36)
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    setImageDrawable(getColorBadgeDrawable(context.resources, swatch.color, selected))
                    if (swatch.id == Theme.CUSTOM && !selected) {
                        // mark the custom slot with the palette icon so it reads as "pick a colour"
                        val icon = ResourcesCompat.getDrawable(
                            context.resources, R.drawable.ic_baseline_color_lens_24, null
                        )?.mutate()
                        if (icon != null) {
                            DrawableCompat.setTint(
                                icon,
                                if (ColorUtils.calculateContrast(Color.WHITE, swatch.color or 0xFF000000.toInt()) >= 3.0) Color.WHITE else Color.BLACK
                            )
                            val inset = dp2px(9)
                            val layer = LayerDrawable(arrayOf(getColorBadgeDrawable(context.resources, swatch.color, false), icon))
                            layer.setLayerInset(1, inset, inset, inset, inset)
                            setImageDrawable(layer)
                        }
                    }
                })
                addView(TextView(context).apply {
                    text = swatch.label
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dp2px(2), dp2px(4), dp2px(2), 0)
                    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(
                        context.getColorAttr(
                            if (selected) android.R.attr.textColorPrimary else android.R.attr.textColorSecondary
                        )
                    )
                })
            }
            row?.addView(cell)
        }
        // pad the last row so cells keep their width
        val remainder = swatches.size % columns
        if (remainder != 0) {
            repeat(columns - remainder) {
                row?.addView(android.view.View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
