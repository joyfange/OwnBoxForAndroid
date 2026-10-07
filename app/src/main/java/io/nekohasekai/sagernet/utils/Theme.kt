package io.nekohasekai.sagernet.utils

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.OwnBoxColorOverrides
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import kotlin.math.abs

object Theme {

    const val MONET = 0
    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21
    const val VERDANT_MINT = 22
    const val WHITE = 23
    const val LIGHT_GRAY = 24
    const val CUSTOM = 99

    /** No accent: keep the base theme's own (monochrome) primary colour. */
    const val ACCENT_NONE = 0

    /**
     * An accent palette applied over the base theme (black / white / light gray). [light] is used on the light bases,
     * [dark] on the black base.
     */
    class Accent(
        val id: Int,
        @StringRes val title: Int,
        @StyleRes val light: Int,
        @StyleRes val dark: Int,
        @androidx.annotation.ColorRes val lightColor: Int,
        @androidx.annotation.ColorRes val darkColor: Int,
    )

    val ACCENTS = listOf(
        Accent(BLUE, R.string.accent_blue, R.style.ThemeOverlay_OwnBox_Accent_Blue, R.style.ThemeOverlay_OwnBox_Accent_Blue_Dark, R.color.accent_blue_light, R.color.accent_blue_dark),
        Accent(INDIGO, R.string.accent_indigo, R.style.ThemeOverlay_OwnBox_Accent_Indigo, R.style.ThemeOverlay_OwnBox_Accent_Indigo_Dark, R.color.accent_indigo_light, R.color.accent_indigo_dark),
        Accent(PURPLE, R.string.accent_purple, R.style.ThemeOverlay_OwnBox_Accent_Purple, R.style.ThemeOverlay_OwnBox_Accent_Purple_Dark, R.color.accent_purple_light, R.color.accent_purple_dark),
        Accent(PINK, R.string.accent_pink, R.style.ThemeOverlay_OwnBox_Accent_Pink, R.style.ThemeOverlay_OwnBox_Accent_Pink_Dark, R.color.accent_pink_light, R.color.accent_pink_dark),
        Accent(RED, R.string.accent_red, R.style.ThemeOverlay_OwnBox_Accent_Red, R.style.ThemeOverlay_OwnBox_Accent_Red_Dark, R.color.accent_red_light, R.color.accent_red_dark),
        Accent(ORANGE, R.string.accent_orange, R.style.ThemeOverlay_OwnBox_Accent_Orange, R.style.ThemeOverlay_OwnBox_Accent_Orange_Dark, R.color.accent_orange_light, R.color.accent_orange_dark),
        Accent(AMBER, R.string.accent_amber, R.style.ThemeOverlay_OwnBox_Accent_Amber, R.style.ThemeOverlay_OwnBox_Accent_Amber_Dark, R.color.accent_amber_light, R.color.accent_amber_dark),
        Accent(GREEN, R.string.accent_green, R.style.ThemeOverlay_OwnBox_Accent_Green, R.style.ThemeOverlay_OwnBox_Accent_Green_Dark, R.color.accent_green_light, R.color.accent_green_dark),
        Accent(TEAL, R.string.accent_teal, R.style.ThemeOverlay_OwnBox_Accent_Teal, R.style.ThemeOverlay_OwnBox_Accent_Teal_Dark, R.color.accent_teal_light, R.color.accent_teal_dark),
        Accent(CYAN, R.string.accent_cyan, R.style.ThemeOverlay_OwnBox_Accent_Cyan, R.style.ThemeOverlay_OwnBox_Accent_Cyan_Dark, R.color.accent_cyan_light, R.color.accent_cyan_dark),
        Accent(BROWN, R.string.accent_brown, R.style.ThemeOverlay_OwnBox_Accent_Brown, R.style.ThemeOverlay_OwnBox_Accent_Brown_Dark, R.color.accent_brown_light, R.color.accent_brown_dark),
        Accent(BLUE_GREY, R.string.accent_blue_grey, R.style.ThemeOverlay_OwnBox_Accent_BlueGrey, R.style.ThemeOverlay_OwnBox_Accent_BlueGrey_Dark, R.color.accent_blue_grey_light, R.color.accent_blue_grey_dark),
    )

    fun accentOf(id: Int): Accent? = ACCENTS.firstOrNull { it.id == id }

    /** The preset whose light colour has the closest hue (custom colour fallback below Android 11). */
    fun closestAccent(context: Context, @ColorInt color: Int): Accent {
        val target = FloatArray(3)
        Color.colorToHSV(color, target)
        val hsv = FloatArray(3)
        // greyish colours map to blue grey; everything else to the preset with the nearest hue
        if (target[1] < 0.2f) return accentOf(BLUE_GREY) ?: ACCENTS.first()
        return ACCENTS.filter { it.id != BLUE_GREY }.minByOrNull {
            Color.colorToHSV(ContextCompat.getColor(context, it.lightColor), hsv)
            val d = abs(hsv[0] - target[0])
            minOf(d, 360f - d)
        } ?: ACCENTS.first()
    }

    /** Colours written into the custom accent's colour resources (R.color.accent_custom*) for [color]. */
    fun customAccentColors(@ColorInt color: Int, dark: Boolean): Map<Int, Int> {
        val opaque = color or 0xFF000000.toInt()
        val onColor = if (ColorUtils.calculateContrast(Color.WHITE, opaque) >= 3.0) Color.WHITE else Color.BLACK
        val variant = ColorUtils.blendARGB(opaque, Color.BLACK, 0.2f)
        val container = if (dark) ColorUtils.blendARGB(opaque, Color.BLACK, 0.72f) else ColorUtils.blendARGB(opaque, Color.WHITE, 0.82f)
        return mapOf(
            R.color.accent_custom to opaque,
            R.color.accent_custom_variant to variant,
            R.color.accent_custom_on to onColor,
            R.color.accent_custom_container to container,
        )
    }

    /**
     * Applies the selected accent on top of the base theme already set on [context]. The custom colour is installed
     * by overriding the custom accent's colour resources (Android 11+, activities only); elsewhere the closest preset
     * is used instead.
     */
    fun applyAccent(context: Context) {
        val dark = isBlackTheme(context)
        when (val id = DataStore.accentTheme) {
            ACCENT_NONE -> return
            CUSTOM -> {
                val color = DataStore.accentCustomColor
                val installed = context is android.app.Activity &&
                        OwnBoxColorOverrides.apply(context, customAccentColors(color, dark))
                if (installed) {
                    context.theme.applyStyle(R.style.ThemeOverlay_OwnBox_Accent_Custom, true)
                } else {
                    val preset = closestAccent(context, color)
                    context.theme.applyStyle(if (dark) preset.dark else preset.light, true)
                }
            }

            else -> {
                val accent = accentOf(id) ?: return
                context.theme.applyStyle(if (dark) accent.dark else accent.light, true)
            }
        }
    }

    /** The accent colour currently in effect, or null when no accent is selected. */
    @ColorInt
    fun accentColor(context: Context): Int? {
        val id = DataStore.accentTheme
        if (id == ACCENT_NONE) return null
        if (id == CUSTOM) return context.getColorAttr(R.attr.colorPrimary)
        val accent = accentOf(id) ?: return null
        return ContextCompat.getColor(context, if (isBlackTheme(context)) accent.darkColor else accent.lightColor)
    }

    private fun defaultTheme() = LIGHT_GRAY

    fun getClosestThemeForColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val sat = hsv[1]
        val value = hsv[2]

        return when {
            sat < 0.18f && value >= 0.88f -> WHITE
            sat < 0.18f && value <= 0.25f -> BLACK
            else -> LIGHT_GRAY
        }
    }

    fun getSystemWallpaperColor(context: Context): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val colors = wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    val primary = colors?.primaryColor?.toArgb()
                    if (primary != null && primary != 0) {
                        return primary
                    }
                }
                val sysAccent = context.getColor(android.R.color.system_accent1_600)
                if (sysAccent != 0) return sysAccent
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun apply(context: Context) {
        context.setTheme(getTheme(context))
        applyAccent(context)
        if (!isWhiteTheme(context) && !isLightGrayTheme(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme && context is android.app.Activity) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme(context))
        applyAccent(context)
        if (!isWhiteTheme(context) && !isLightGrayTheme(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme && context is android.app.Activity) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun getTheme(context: Context = app): Int {
        if (usingNightMode(context)) {
            return R.style.Theme_SagerNet_Black
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                val closest = getClosestThemeForColor(wallpaperColor)
                if (closest == WHITE) R.style.Theme_SagerNet_White else getTheme(closest)
            } else {
                getTheme(MONET)
            }
        } else {
            getTheme(DataStore.appTheme)
        }
    }

    fun getDialogTheme(context: Context = app): Int {
        if (usingNightMode(context)) {
            return R.style.Theme_SagerNet_Dialog_Black
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                val closest = getClosestThemeForColor(wallpaperColor)
                if (closest == WHITE) R.style.Theme_SagerNet_Dialog_White else getDialogTheme(closest)
            } else {
                getDialogTheme(MONET)
            }
        } else {
            getDialogTheme(DataStore.appTheme)
        }
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            BLACK -> R.style.Theme_SagerNet_Black
            WHITE -> R.style.Theme_SagerNet_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_LightGray
            else -> {
                if (DataStore.appTheme !in setOf(BLACK, WHITE, LIGHT_GRAY)) {
                    DataStore.appTheme = LIGHT_GRAY
                }
                R.style.Theme_SagerNet_LightGray
            }
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            BLACK -> R.style.Theme_SagerNet_Dialog_Black
            WHITE -> R.style.Theme_SagerNet_Dialog_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_Dialog_LightGray
            else -> {
                if (DataStore.appTheme !in setOf(BLACK, WHITE, LIGHT_GRAY)) {
                    DataStore.appTheme = LIGHT_GRAY
                }
                R.style.Theme_SagerNet_Dialog_LightGray
            }
        }
    }

    fun isSystemNight(context: Context = app): Boolean {
        val ctxUiMode = (context as? android.app.Activity)?.resources?.configuration?.uiMode
            ?: context.resources?.configuration?.uiMode
        if (ctxUiMode != null && (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val sysUiMode = android.content.res.Resources.getSystem().configuration.uiMode
        return (sysUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun isWhiteTheme(context: Context = app): Boolean = !usingNightMode(context) && DataStore.appTheme == WHITE
    fun isLightGrayTheme(context: Context = app): Boolean = !usingNightMode(context) && DataStore.appTheme == LIGHT_GRAY
    fun isBlackTheme(context: Context = app): Boolean = usingNightMode(context) || DataStore.appTheme == BLACK

    fun getPrimaryColor(context: Context): Int {
        accentColor(context)?.let { return it }
        if (usingNightMode(context) || isBlackTheme(context)) {
            return Color.WHITE
        }
        if (isWhiteTheme(context)) {
            return Color.parseColor("#212121")
        }
        if (isLightGrayTheme(context)) {
            return Color.parseColor("#1F2937")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                return wallpaperColor
            }
        }
        return context.getColorAttr(R.attr.colorPrimary)
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(context: Context = app): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> isSystemNight(context)
        }
    }

    fun applyNightTheme() {
        if (DataStore.nightTheme == 0) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else if (DataStore.nightTheme == 1 || DataStore.appTheme == BLACK) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else if (DataStore.nightTheme == 2) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

}