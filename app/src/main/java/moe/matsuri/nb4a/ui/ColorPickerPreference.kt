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

        val displayColor = when {
            Theme.isBlackTheme(context) -> Color.BLACK
            Theme.isWhiteTheme(context) -> Color.WHITE
            Theme.isLightGrayTheme(context) -> Color.parseColor("#F5F5F7")
            else -> context.getColorAttr(R.attr.colorPrimary)
        }

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
        val strokeColor = when (color) {
            Color.WHITE -> Color.parseColor("#CCCCCC")
            Color.parseColor("#F5F5F7") -> Color.parseColor("#CBD5E1")
            Color.BLACK -> Color.parseColor("#444444")
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
        val checkTint = if (color == Color.WHITE || color == Color.parseColor("#F5F5F7")) {
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
        (context as? Activity)?.let { ActivityCompat.recreate(it) }
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
        // What is actually on screen: night mode always shows the black base.
        val currentThemeId = when {
            nightActive -> Theme.BLACK
            DataStore.appTheme !in setOf(Theme.BLACK, Theme.WHITE, Theme.LIGHT_GRAY) -> Theme.LIGHT_GRAY
            else -> DataStore.appTheme
        }

        fun applyTheme(themeId: Int) {
            dialog.dismiss()
            if (themeId == currentThemeId) return
            persistInt(themeId)
            DataStore.appTheme = themeId
            // Theme.getTheme() forces the black base while night mode is in effect (night mode "on", or "follow
            // system" with a dark system), so picking a light base used to do nothing. Choosing a light base turns
            // night mode off so the choice applies; picking black again works with any night mode setting.
            if (themeId != Theme.BLACK && Theme.usingNightMode(context)) {
                DataStore.nightTheme = 2
                Theme.currentNightMode = 2
            }
            Theme.applyNightTheme()
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

        // 1. Core Base Themes Section
        rootLayout.addView(sectionTitle(context.getString(R.string.theme_base_section)))
        if (nightActive) {
            rootLayout.addView(TextView(context).apply {
                text = context.getString(R.string.theme_base_night_note)
                textSize = 11.5f
                setTextColor(context.getColorAttr(android.R.attr.textColorSecondary))
                setPadding(dp2px(4), 0, dp2px(4), dp2px(8))
            })
        }

        val baseThemes = listOf(
            PresetTheme(Theme.BLACK, "纯黑 (AMOLED Black)", Color.BLACK, "纯黑底色 #000000 · 极致省电高对比"),
            PresetTheme(Theme.WHITE, "纯白 (Pure White)", Color.WHITE, "纯白底色 #FFFFFF · 极简黑白高反差"),
            PresetTheme(Theme.LIGHT_GRAY, "浅灰 (Light Gray)", Color.parseColor("#F5F5F7"), "柔灰底色 #F5F5F7 · 优雅层次悬浮感")
        )

        for (base in baseThemes) {
            val isSelected = currentThemeId == base.id
            val card = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp2px(8))
                }
                radius = dp2px(12).toFloat()
                cardElevation = 0f
                strokeWidth = if (isSelected) dp2px(2) else dp2px(1)
                strokeColor = if (isSelected) context.getColorAttr(R.attr.colorPrimary) else Color.parseColor("#25888888")
                setCardBackgroundColor(if (isSelected) Color.parseColor("#0F2196F3") else Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    applyTheme(base.id)
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10))

                    val iv = ImageView(context).apply {
                        val sz = dp2px(36)
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            marginEnd = dp2px(12)
                        }
                        setImageDrawable(getColorBadgeDrawable(context.resources, base.color, isSelected))
                    }
                    addView(iv)

                    val textCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                        val titleView = TextView(context).apply {
                            text = base.name
                            textSize = 14f
                            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                            setTextColor(context.getColorAttr(android.R.attr.textColorPrimary))
                        }
                        addView(titleView)

                        val subtitleView = TextView(context).apply {
                            text = base.subtitle
                            textSize = 11.5f
                            setTextColor(context.getColorAttr(android.R.attr.textColorSecondary))
                            setPadding(0, dp2px(2), 0, 0)
                        }
                        addView(subtitleView)
                    }
                    addView(textCol)
                }
                addView(row)
            }
            rootLayout.addView(card)
        }

        // 2. Accent colours (independent of the base above)
        rootLayout.addView(sectionTitle(context.getString(R.string.theme_accent_section)).apply {
            setPadding(dp2px(4), dp2px(12), 0, dp2px(8))
        })

        val dark = Theme.isBlackTheme(context)
        val currentAccent = DataStore.accentTheme
        val noneColor = when (currentThemeId) {
            Theme.BLACK -> Color.WHITE
            Theme.WHITE -> Color.parseColor("#212121")
            else -> Color.parseColor("#1F2937")
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
