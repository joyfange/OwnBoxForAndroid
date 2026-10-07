package moe.matsuri.nb4a.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.color.OwnBoxColorOverrides
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr

/**
 * A small dependency-free colour picker: hue / saturation / brightness sliders, a hex field and a live preview.
 * [onPicked] receives the opaque ARGB colour when the user confirms.
 */
object AccentColorPickerDialog {

    fun show(
        context: Context,
        initialColor: Int,
        @StringRes titleRes: Int = R.string.accent_custom,
        @StringRes noteRes: Int = 0,
        @StringRes fallbackNoteRes: Int = R.string.color_picker_fallback_note,
        onPicked: (Int) -> Unit,
    ) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor or 0xFF000000.toInt(), hsv)
        var updating = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(8), dp2px(20), dp2px(4))
        }

        val preview = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(64)).apply {
                bottomMargin = dp2px(12)
            }
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val previewBg = GradientDrawable().apply {
            cornerRadius = dp2px(14).toFloat()
        }
        preview.background = previewBg
        root.addView(preview)

        val hexLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.color_picker_hex)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(8) }
        }
        val hexInput = TextInputEditText(hexLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(7))
        }
        hexLayout.addView(hexInput)
        root.addView(hexLayout)

        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(context.getColorAttr(android.R.attr.textColorSecondary))
            setPadding(dp2px(2), dp2px(8), 0, 0)
        }

        fun track(): GradientDrawable = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = dp2px(6).toFloat()
        }

        fun slider(max: Int, trackDrawable: GradientDrawable) = SeekBar(context).apply {
            this.max = max
            progressDrawable = InsetDrawable(trackDrawable, 0, dp2px(9), 0, dp2px(9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(36))
        }

        val hueTrack = track().apply {
            setColors(IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) })
        }
        val satTrack = track()
        val valTrack = track()

        val hueLabel = label("")
        val satLabel = label("")
        val valLabel = label("")
        val hue = slider(360, hueTrack)
        val sat = slider(100, satTrack)
        val value = slider(100, valTrack)
        root.addView(hueLabel)
        root.addView(hue)
        root.addView(satLabel)
        root.addView(sat)
        root.addView(valLabel)
        root.addView(value)

        if (noteRes != 0) {
            root.addView(label(context.getString(noteRes)).apply {
                textSize = 12f
                setPadding(dp2px(2), dp2px(12), 0, 0)
            })
        }
        if (!OwnBoxColorOverrides.isAvailable()) {
            root.addView(label(context.getString(fallbackNoteRes)).apply {
                textSize = 12f
                setPadding(dp2px(2), dp2px(12), 0, 0)
            })
        }

        fun currentColor() = Color.HSVToColor(hsv)

        fun hexOf(color: Int) = String.format("#%06X", 0xFFFFFF and color)

        fun refresh(fromHex: Boolean) {
            updating = true
            val color = currentColor()
            previewBg.setColor(color)
            preview.text = hexOf(color)
            preview.setTextColor(
                if (ColorUtils.calculateContrast(Color.WHITE, color) >= 3.0) Color.WHITE else Color.BLACK
            )
            satTrack.setColors(
                intArrayOf(
                    Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])),
                    Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
                )
            )
            valTrack.setColors(intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))))
            hue.progress = hsv[0].toInt()
            sat.progress = (hsv[1] * 100).toInt()
            value.progress = (hsv[2] * 100).toInt()
            hueLabel.text = context.getString(R.string.color_picker_hue, hsv[0].toInt())
            satLabel.text = context.getString(R.string.color_picker_saturation, (hsv[1] * 100).toInt())
            valLabel.text = context.getString(R.string.color_picker_brightness, (hsv[2] * 100).toInt())
            if (!fromHex) {
                hexInput.setText(hexOf(color))
                hexInput.setSelection(hexInput.text?.length ?: 0)
            }
            hexLayout.error = null
            updating = false
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                when (seekBar) {
                    hue -> hsv[0] = progress.toFloat().coerceIn(0f, 359.9f)
                    sat -> hsv[1] = progress / 100f
                    value -> hsv[2] = progress / 100f
                }
                refresh(false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }
        hue.setOnSeekBarChangeListener(listener)
        sat.setOnSeekBarChangeListener(listener)
        value.setOnSeekBarChangeListener(listener)

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val parsed = parseHex(s?.toString())
                if (parsed == null) {
                    hexLayout.error = context.getString(R.string.color_picker_invalid)
                    return
                }
                Color.colorToHSV(parsed, hsv)
                refresh(true)
            }
        })

        refresh(false)

        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(NestedScrollView(context).apply { addView(root) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onPicked(currentColor() or 0xFF000000.toInt())
            }
            .show()
    }

    /** "#RRGGBB" / "RRGGBB" / "#RGB" to an opaque colour, or null. */
    fun parseHex(text: String?): Int? {
        var hex = text?.trim()?.removePrefix("#") ?: return null
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length != 6) return null
        val rgb = hex.toLongOrNull(16) ?: return null
        return (0xFF000000 or rgb).toInt()
    }
}
