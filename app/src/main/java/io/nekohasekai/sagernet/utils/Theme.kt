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

    // Extra base themes (theme colour dialog). Ids are new so old saved values never collide with them.
    const val MIDNIGHT = 30
    const val GRAPHITE = 31
    const val MOCHA = 32
    const val FOREST = 33
    const val CREAM = 34
    const val MINT = 35
    const val SKY = 36
    const val SAKURA = 37
    const val LAVENDER = 38

    /** Dark base theme with a user-picked background colour ([DataStore.baseCustomColor], kept dark). */
    const val CUSTOM_BASE = 98

    /** Light base theme with a user-picked background colour ([DataStore.baseCustomLightColor], kept light). */
    const val CUSTOM_BASE_LIGHT = 97
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


    /**
     * A base theme: background, surfaces and text. [dark] bases extend the black theme, light ones the light gray
     * theme. Colours are colour resources so the dialog previews and the code-side palette match the XML styles.
     */
    class Base(
        val id: Int,
        @StringRes val title: Int,
        val dark: Boolean,
        @StyleRes val style: Int,
        @StyleRes val dialogStyle: Int,
        @androidx.annotation.ColorRes val background: Int,
        @androidx.annotation.ColorRes val card: Int,
        @androidx.annotation.ColorRes val textPrimary: Int,
        @androidx.annotation.ColorRes val textSecondary: Int,
        @androidx.annotation.ColorRes val primary: Int,
        @androidx.annotation.ColorRes val fab: Int,
    )

    val BASES = listOf(
        Base(WHITE, R.string.base_white, false, R.style.Theme_SagerNet_White, R.style.Theme_SagerNet_Dialog_White,
            R.color.color_white_theme_bg, R.color.color_white_theme_surface, R.color.color_white_theme_text_primary,
            R.color.color_white_theme_tab_unselected, R.color.color_white_theme_accent, R.color.color_white_theme_accent),
        Base(LIGHT_GRAY, R.string.base_light_gray, false, R.style.Theme_SagerNet_LightGray, R.style.Theme_SagerNet_Dialog_LightGray,
            R.color.color_light_gray_bg, R.color.color_light_gray_surface, R.color.color_light_gray_text_primary,
            R.color.color_light_gray_text_secondary, R.color.color_light_gray_primary, R.color.color_light_gray_primary),
        Base(CREAM, R.string.base_cream, false, R.style.Theme_SagerNet_BaseCream, R.style.Theme_SagerNet_Dialog_BaseCream,
            R.color.base_cream_bg, R.color.base_cream_card, R.color.base_cream_text_primary,
            R.color.base_cream_text_secondary, R.color.base_cream_primary, R.color.base_cream_primary),
        Base(MINT, R.string.base_mint, false, R.style.Theme_SagerNet_BaseMint, R.style.Theme_SagerNet_Dialog_BaseMint,
            R.color.base_mint_bg, R.color.base_mint_card, R.color.base_mint_text_primary,
            R.color.base_mint_text_secondary, R.color.base_mint_primary, R.color.base_mint_primary),
        Base(SKY, R.string.base_sky, false, R.style.Theme_SagerNet_BaseSky, R.style.Theme_SagerNet_Dialog_BaseSky,
            R.color.base_sky_bg, R.color.base_sky_card, R.color.base_sky_text_primary,
            R.color.base_sky_text_secondary, R.color.base_sky_primary, R.color.base_sky_primary),
        Base(SAKURA, R.string.base_sakura, false, R.style.Theme_SagerNet_BaseSakura, R.style.Theme_SagerNet_Dialog_BaseSakura,
            R.color.base_sakura_bg, R.color.base_sakura_card, R.color.base_sakura_text_primary,
            R.color.base_sakura_text_secondary, R.color.base_sakura_primary, R.color.base_sakura_primary),
        Base(LAVENDER, R.string.base_lavender, false, R.style.Theme_SagerNet_BaseLavender, R.style.Theme_SagerNet_Dialog_BaseLavender,
            R.color.base_lavender_bg, R.color.base_lavender_card, R.color.base_lavender_text_primary,
            R.color.base_lavender_text_secondary, R.color.base_lavender_primary, R.color.base_lavender_primary),
        Base(BLACK, R.string.base_black, true, R.style.Theme_SagerNet_Black, R.style.Theme_SagerNet_Dialog_Black,
            R.color.color_black_theme_bg, R.color.color_black_theme_card_elevated, R.color.color_black_theme_text_primary,
            R.color.color_black_theme_text_secondary, R.color.color_black_theme_primary, R.color.black),
        Base(GRAPHITE, R.string.base_graphite, true, R.style.Theme_SagerNet_BaseGraphite, R.style.Theme_SagerNet_Dialog_BaseGraphite,
            R.color.base_graphite_bg, R.color.base_graphite_card, R.color.base_graphite_text_primary,
            R.color.base_graphite_text_secondary, R.color.base_graphite_primary, R.color.base_graphite_material_300),
        Base(MIDNIGHT, R.string.base_midnight, true, R.style.Theme_SagerNet_BaseMidnight, R.style.Theme_SagerNet_Dialog_BaseMidnight,
            R.color.base_midnight_bg, R.color.base_midnight_card, R.color.base_midnight_text_primary,
            R.color.base_midnight_text_secondary, R.color.base_midnight_primary, R.color.base_midnight_primary),
        Base(FOREST, R.string.base_forest, true, R.style.Theme_SagerNet_BaseForest, R.style.Theme_SagerNet_Dialog_BaseForest,
            R.color.base_forest_bg, R.color.base_forest_card, R.color.base_forest_text_primary,
            R.color.base_forest_text_secondary, R.color.base_forest_primary, R.color.base_forest_primary),
        Base(MOCHA, R.string.base_mocha, true, R.style.Theme_SagerNet_BaseMocha, R.style.Theme_SagerNet_Dialog_BaseMocha,
            R.color.base_mocha_bg, R.color.base_mocha_card, R.color.base_mocha_text_primary,
            R.color.base_mocha_text_secondary, R.color.base_mocha_primary, R.color.base_mocha_primary),
    )

    fun baseOf(id: Int): Base? = BASES.firstOrNull { it.id == id }

    /** True for every value the theme colour dialog can save as the base ([DataStore.appTheme]). */
    fun isBaseTheme(id: Int): Boolean = isCustomBase(id) || baseOf(id) != null

    /** Either of the two custom bases (the light one or the dark one). */
    fun isCustomBase(id: Int): Boolean = id == CUSTOM_BASE || id == CUSTOM_BASE_LIGHT

    /** The colours the code paints with (app bar, tabs, stats bar, search field, FAB) for the base on screen. */
    class Palette(
        val id: Int,
        val dark: Boolean,
        @ColorInt val background: Int,
        @ColorInt val card: Int,
        @ColorInt val textPrimary: Int,
        @ColorInt val textSecondary: Int,
        @ColorInt val primary: Int,
        @ColorInt val fab: Int,
    )

    private fun isDarkColor(@ColorInt color: Int) = ColorUtils.calculateLuminance(color or 0xFF000000.toInt()) < 0.3

    /** Keeps a picked colour dark enough for the dark custom base (HSL lightness at most 22%). */
    @ColorInt
    fun toDarkBase(@ColorInt color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color or 0xFF000000.toInt(), hsl)
        if (hsl[2] > 0.22f) hsl[2] = 0.22f
        val c = ColorUtils.HSLToColor(hsl)
        return if (isDarkColor(c)) c else ColorUtils.blendARGB(c, Color.BLACK, 0.4f)
    }

    /** Keeps a picked colour light enough for the light custom base (HSL lightness at least 86%). */
    @ColorInt
    fun toLightBase(@ColorInt color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color or 0xFF000000.toInt(), hsl)
        if (hsl[2] < 0.86f) hsl[2] = 0.86f
        val c = ColorUtils.HSLToColor(hsl)
        return if (ColorUtils.calculateLuminance(c) >= 0.55) c else ColorUtils.blendARGB(c, Color.WHITE, 0.5f)
    }

    /** The background colour of a custom base, already pushed to its own light or dark range. */
    @ColorInt
    fun customBaseColor(id: Int): Int = if (id == CUSTOM_BASE_LIGHT) {
        toLightBase(DataStore.baseCustomLightColor)
    } else {
        toDarkBase(DataStore.baseCustomColor)
    }

    /**
     * Before the light / dark split there was a single custom base (id 98) whose lightness was read from the colour;
     * a light colour saved that way moves to the new light custom base so it keeps looking the same.
     */
    private fun migrateCustomBase() {
        if (DataStore.appTheme == CUSTOM_BASE && !isDarkColor(DataStore.baseCustomColor)) {
            DataStore.baseCustomLightColor = DataStore.baseCustomColor
            DataStore.baseCustomColor = DEFAULT_CUSTOM_DARK
            DataStore.appTheme = CUSTOM_BASE_LIGHT
        }
    }

    private val DEFAULT_CUSTOM_DARK = 0xFF1B2430.toInt()

    private fun isDarkBaseId(id: Int): Boolean = when (id) {
        CUSTOM_BASE -> true
        CUSTOM_BASE_LIGHT -> false
        else -> baseOf(id)?.dark ?: false
    }

    /** The preset whose background is closest to [color] (custom base fallback below Android 11). */
    fun closestBase(context: Context, @ColorInt color: Int): Base {
        val dark = isDarkColor(color)
        return BASES.filter { it.dark == dark }.minByOrNull {
            val c = ContextCompat.getColor(context, it.background)
            val dr = Color.red(c) - Color.red(color)
            val dg = Color.green(c) - Color.green(color)
            val db = Color.blue(c) - Color.blue(color)
            dr * dr + dg * dg + db * db
        } ?: BASES.first()
    }

    /** Selected base, legacy / unknown values mapped to light gray (never writes the store). */
    fun selectedBaseId(): Int {
        migrateCustomBase()
        return DataStore.appTheme.let { if (isBaseTheme(it)) it else LIGHT_GRAY }
    }

    /**
     * The base actually on screen: night mode keeps a dark base and shows black instead of a light one; the custom
     * base needs Android 11+ (colour resource overrides), below that its closest preset is shown.
     */
    fun currentBaseId(context: Context = app): Int {
        var id = selectedBaseId()
        if (isCustomBase(id) && !OwnBoxColorOverrides.isAvailable()) {
            id = closestBase(context, customBaseColor(id)).id
        }
        if (usingNightMode(context) && !isDarkBaseId(id)) id = BLACK
        return id
    }

    /** Colours for the custom base's colour resources (R.color.base_custom_*), derived from the picked background. */
    fun customBaseColors(@ColorInt picked: Int): Map<Int, Int> {
        val bg = picked or 0xFF000000.toInt()
        val dark = isDarkColor(bg)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(bg, hsl)
        val greyish = hsl[1] < 0.12f
        val primary = if (dark) {
            if (greyish) Color.parseColor("#E0E0E0") else ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.45f, 0.75f), 0.74f))
        } else {
            if (greyish) Color.parseColor("#1F2937") else ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.45f, 0.7f), 0.38f))
        }
        val lift = if (dark) Color.WHITE else Color.BLACK
        val surface = if (dark) ColorUtils.blendARGB(bg, Color.WHITE, 0.05f) else ColorUtils.blendARGB(bg, Color.WHITE, 0.75f)
        val card = if (dark) ColorUtils.blendARGB(bg, Color.WHITE, 0.08f) else surface
        val textPrimary = if (dark) ColorUtils.blendARGB(Color.WHITE, bg, 0.06f) else ColorUtils.blendARGB(Color.BLACK, bg, 0.14f)
        val textSecondary = if (dark) ColorUtils.blendARGB(Color.WHITE, bg, 0.36f) else ColorUtils.blendARGB(Color.BLACK, bg, 0.48f)
        val onPrimary = if (ColorUtils.calculateContrast(Color.WHITE, primary) >= 3.0) Color.WHITE else bg.let {
            if (dark) it else Color.BLACK
        }
        return mapOf(
            R.color.base_custom_bg to bg,
            R.color.base_custom_surface to surface,
            R.color.base_custom_card to card,
            R.color.base_custom_text_primary to textPrimary,
            R.color.base_custom_text_secondary to textSecondary,
            R.color.base_custom_primary to primary,
            R.color.base_custom_primary_dark to bg,
            R.color.base_custom_material_100 to ColorUtils.blendARGB(bg, lift, if (dark) 0.07f else 0.03f),
            R.color.base_custom_material_300 to ColorUtils.blendARGB(bg, lift, if (dark) 0.14f else 0.08f),
            R.color.base_custom_item_shape to ColorUtils.blendARGB(bg, lift, if (dark) 0.11f else 0.06f),
            R.color.base_custom_tab_unselected to textSecondary,
            R.color.base_custom_ripple to ColorUtils.setAlphaComponent(primary, if (dark) 0x33 else 0x1A),
            R.color.base_custom_on_primary to onPrimary,
        )
    }

    fun palette(context: Context = app): Palette {
        val id = currentBaseId(context)
        if (isCustomBase(id)) {
            val c = customBaseColors(customBaseColor(id))
            val dark = isDarkBaseId(id)
            val primary = c.getValue(R.color.base_custom_primary)
            return Palette(
                id, dark, c.getValue(R.color.base_custom_bg), c.getValue(R.color.base_custom_card),
                c.getValue(R.color.base_custom_text_primary), c.getValue(R.color.base_custom_text_secondary),
                primary, primary,
            )
        }
        val base = baseOf(id) ?: baseOf(LIGHT_GRAY)!!
        fun col(res: Int) = ContextCompat.getColor(context, res)
        return Palette(
            base.id, base.dark, col(base.background), col(base.card), col(base.textPrimary),
            col(base.textSecondary), col(base.primary), col(base.fab),
        )
    }

    /**
     * Stats bar colour: the base's raised surface with a soft wash of the accent (or of the base's own primary on the
     * tinted bases), so the bar, the FAB cradle and the gesture-bar area read as one coloured surface.
     */
    @ColorInt
    fun statsBarColor(context: Context): Int {
        val p = palette(context)
        val tint = accentColor(context) ?: if (p.id == BLACK || p.id == WHITE || p.id == LIGHT_GRAY) null else p.primary
        val surface = if (p.dark) ColorUtils.blendARGB(p.background, Color.WHITE, 0.07f) else p.card
        if (tint == null) {
            return if (p.dark) surface else if (p.id == WHITE) Color.WHITE else p.background
        }
        return ColorUtils.blendARGB(surface, tint or 0xFF000000.toInt(), if (p.dark) 0.20f else 0.13f)
    }

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
     * Applies the selected accent on top of the base theme already set on [context]. [customInstalled] tells whether
     * the custom accent's colour resources were overridden (Android 11+, activities only); otherwise the closest
     * preset stands in for the custom colour.
     */
    fun applyAccent(context: Context, customInstalled: Boolean = false) {
        val dark = isBlackTheme(context)
        when (val id = DataStore.accentTheme) {
            ACCENT_NONE -> return
            CUSTOM -> {
                if (customInstalled) {
                    context.theme.applyStyle(R.style.ThemeOverlay_OwnBox_Accent_Custom, true)
                } else {
                    val preset = closestAccent(context, DataStore.accentCustomColor)
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
        val dark = isBlackTheme(context)
        if (id == CUSTOM) {
            val color = DataStore.accentCustomColor or 0xFF000000.toInt()
            if (OwnBoxColorOverrides.isAvailable()) return color
            val preset = closestAccent(context, color)
            return ContextCompat.getColor(context, if (dark) preset.darkColor else preset.lightColor)
        }
        val accent = accentOf(id) ?: return null
        return ContextCompat.getColor(context, if (dark) accent.darkColor else accent.lightColor)
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

    private fun applyInternal(context: Context, dialog: Boolean) {
        val baseId = currentBaseId(context)
        // One resources loader for every runtime colour (custom base + custom accent); activities only.
        val overrides = HashMap<Int, Int>()
        if (isCustomBase(baseId)) overrides.putAll(customBaseColors(customBaseColor(baseId)))
        if (DataStore.accentTheme == CUSTOM) {
            overrides.putAll(customAccentColors(DataStore.accentCustomColor, isDarkBaseId(baseId)))
        }
        val installed = overrides.isNotEmpty() && context is android.app.Activity &&
                OwnBoxColorOverrides.apply(context, overrides)
        val effectiveBase = if (isCustomBase(baseId) && !installed) {
            closestBase(context, customBaseColor(baseId)).id
        } else baseId
        context.setTheme(styleOf(effectiveBase, dialog))
        applyAccent(context, installed)
    }

    fun apply(context: Context) = applyInternal(context, false)

    fun applyDialog(context: Context) = applyInternal(context, true)

    @StyleRes
    private fun styleOf(id: Int, dialog: Boolean): Int {
        if (isCustomBase(id)) {
            return if (id == CUSTOM_BASE) {
                if (dialog) R.style.Theme_SagerNet_Dialog_BaseCustom else R.style.Theme_SagerNet_BaseCustom
            } else {
                if (dialog) R.style.Theme_SagerNet_Dialog_BaseCustomLight else R.style.Theme_SagerNet_BaseCustomLight
            }
        }
        val base = baseOf(id) ?: baseOf(LIGHT_GRAY)!!
        return if (dialog) base.dialogStyle else base.style
    }

    fun getTheme(context: Context = app): Int = styleOf(currentBaseId(context), false)

    fun getDialogTheme(context: Context = app): Int = styleOf(currentBaseId(context), true)

    fun getTheme(theme: Int): Int = styleOf(if (isBaseTheme(theme)) theme else LIGHT_GRAY, false)

    fun getDialogTheme(theme: Int): Int = styleOf(if (isBaseTheme(theme)) theme else LIGHT_GRAY, true)

    fun isSystemNight(context: Context = app): Boolean {
        val ctxUiMode = (context as? android.app.Activity)?.resources?.configuration?.uiMode
            ?: context.resources?.configuration?.uiMode
        if (ctxUiMode != null && (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val sysUiMode = android.content.res.Resources.getSystem().configuration.uiMode
        return (sysUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun isWhiteTheme(context: Context = app): Boolean = currentBaseId(context) == WHITE
    fun isLightGrayTheme(context: Context = app): Boolean = currentBaseId(context) == LIGHT_GRAY

    /** True when a dark base is on screen (pure black, any dark preset, a dark custom base, or night mode). */
    fun isBlackTheme(context: Context = app): Boolean = isDarkBaseId(currentBaseId(context))

    /** True only for the pure black (AMOLED) base. */
    fun isPureBlackTheme(context: Context = app): Boolean = currentBaseId(context) == BLACK

    fun getPrimaryColor(context: Context): Int {
        accentColor(context)?.let { return it }
        val p = palette(context)
        // The plain black / white / gray bases stay monochrome; the tinted bases use their own primary.
        return when (p.id) {
            BLACK -> Color.WHITE
            WHITE -> Color.parseColor("#212121")
            LIGHT_GRAY -> Color.parseColor("#1F2937")
            else -> p.primary
        }
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
        } else if (DataStore.nightTheme == 1 || isDarkBaseId(selectedBaseId())) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else if (DataStore.nightTheme == 2) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

}