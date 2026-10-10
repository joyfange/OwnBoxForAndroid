package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false
    private var lastUseSystemTheme: Boolean = false
    private var lastWallpaperColor: Int? = null
    private var lastAppTheme: Int = 0
    private var lastNightTheme: Int = 0
    private var lastUsingNight: Boolean = false
    private var lastAccent: Int = 0
    private var lastAccentColor: Int = 0
    private var lastBaseColor: Int = 0
    private var lastBaseLightColor: Int = 0
    /** Set while [switchThemeSmoothly] drives the change, so the uiMode callback does not recreate a second time. */
    private var themeSwitchInProgress = false

    companion object {
        /** Last frame of the activity being recreated for a theme change; faded out over the new one, then freed. */
        private var themeSnapshot: Bitmap? = null
        private const val POPUP_DISMISS_DELAY_MS = 180L
        private const val CROSSFADE_MS = 220L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        lastUseSystemTheme = DataStore.useSystemTheme
        lastWallpaperColor = if (DataStore.useSystemTheme) Theme.getSystemWallpaperColor(this) else null
        lastAppTheme = DataStore.appTheme
        lastNightTheme = DataStore.nightTheme
        lastUsingNight = Theme.usingNightMode(this)
        lastAccent = DataStore.accentTheme
        lastAccentColor = DataStore.accentCustomColor
        lastBaseColor = DataStore.baseCustomColor
        lastBaseLightColor = DataStore.baseCustomLightColor

        Theme.applyNightTheme()
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode

        window.statusBarColor = Color.TRANSPARENT
        if (!isDialog) {
            // The theme maps the navigation bar to colorPrimaryDark, which accent overlays turn into a coloured
            // strip at the bottom of every screen. Match the window background instead.
            window.navigationBarColor = getColorAttr(android.R.attr.colorBackground)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val insetController = WindowCompat.getInsetsController(window, window.decorView)
            val isDark = Theme.isBlackTheme(this)
            val isLightSurface = !isDark && ColorUtils.calculateLuminance(getColorAttr(R.attr.colorSurface)) > 0.45

            insetController.isAppearanceLightStatusBars = isLightSurface
            insetController.isAppearanceLightNavigationBars = isLightSurface
        }

        applyAppBarInsets()
    }

    fun applyAppBarInsets() {
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            findViewById<AppBarLayout>(R.id.appbar)?.apply {
                updatePadding(top = bars.top)
            }
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyAppBarInsets()
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyAppBarInsets()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        applyAppBarInsets()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    private var themeCheckToken = 0

    /**
     * Checks whether the theme changed while the activity was away. The settings are database reads and the wallpaper
     * colour is a system call; they used to run on the main thread on every return to the app, delaying its first
     * frames, so they are read in the background and the activity is recreated only when something changed.
     */
    override fun onResume() {
        super.onResume()
        val token = ++themeCheckToken
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) { runCatching { themeChangedSinceCreate() }.getOrDefault(false) }
            if (changed && token == themeCheckToken && !isFinishing && !isDestroyed) {
                ActivityCompat.recreate(this@ThemedActivity)
            }
        }
    }

    private fun themeChangedSinceCreate(): Boolean {
        val currentWallpaperColor = if (DataStore.useSystemTheme) Theme.getSystemWallpaperColor(this) else null
        val currentUsingNight = Theme.usingNightMode(this)
        return (lastUseSystemTheme != DataStore.useSystemTheme ||
            (DataStore.useSystemTheme && lastWallpaperColor != currentWallpaperColor) ||
            (!DataStore.useSystemTheme && lastAppTheme != DataStore.appTheme) ||
            lastNightTheme != DataStore.nightTheme ||
            lastUsingNight != currentUsingNight ||
            lastAccent != DataStore.accentTheme ||
            (lastAccent == Theme.CUSTOM && lastAccentColor != DataStore.accentCustomColor) ||
            (lastAppTheme == Theme.CUSTOM_BASE && lastBaseColor != DataStore.baseCustomColor) ||
            (lastAppTheme == Theme.CUSTOM_BASE_LIGHT && lastBaseLightColor != DataStore.baseCustomLightColor))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            if (!themeSwitchInProgress) ActivityCompat.recreate(this)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val snapshot = themeSnapshot ?: return
        themeSnapshot = null
        val decor = window.decorView as? ViewGroup ?: run { snapshot.recycle(); return }
        val cover = ImageView(this).apply {
            setImageBitmap(snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decor.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Fade only once the new theme has drawn its first frame, so there is never a blank or half-styled frame.
        cover.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                cover.viewTreeObserver.removeOnPreDrawListener(this)
                cover.animate().alpha(0f).setDuration(CROSSFADE_MS).withLayer().withEndAction {
                    decor.removeView(cover)
                    cover.setImageDrawable(null)
                    snapshot.recycle()
                }.start()
                return true
            }
        })
    }

    /**
     * Applies a night mode / theme change with a single activity recreation and a crossfade, instead of the
     * AppCompat uiMode recreation plus a manual recreate (two rebuilds, visible stutter), and waits for the
     * menu or dialog that triggered it to finish dismissing so its shadow is not left behind on screen.
     */
    fun switchThemeSmoothly() {
        val decor = window.decorView
        decor.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            captureSnapshot { bitmap ->
                if (isFinishing || isDestroyed) { bitmap?.recycle(); return@captureSnapshot }
                themeSnapshot?.recycle()
                themeSnapshot = bitmap
                themeSwitchInProgress = true
                Theme.applyNightTheme()
                ActivityCompat.recreate(this)
            }
        }, POPUP_DISMISS_DELAY_MS)
    }

    private fun captureSnapshot(done: (Bitmap?) -> Unit) {
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return done(null)
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return done(null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // PixelCopy reads the GPU frame, so hardware bitmaps and blur are captured exactly and nothing is redrawn.
            try {
                PixelCopy.request(window, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) done(bitmap) else { bitmap.recycle(); done(null) }
                }, Handler(Looper.getMainLooper()))
            } catch (_: Throwable) {
                bitmap.recycle()
                done(null)
            }
        } else {
            try {
                decor.draw(Canvas(bitmap))
                done(bitmap)
            } catch (_: Throwable) {
                bitmap.recycle()
                done(null)
            }
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
