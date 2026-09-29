package app.veil.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Tiny view-building toolkit so the whole UI is plain Android framework code:
 * no AndroidX, no XML layouts, nothing to resolve at build time.
 */
object Ui {
    const val BG = 0xFFF5F6FA.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val PRIMARY = 0xFF1F2A44.toInt()
    const val ACCENT = 0xFF2F6BFF.toInt()
    const val GOOD = 0xFF1E9E5A.toInt()
    const val BAD = 0xFFD14343.toInt()
    const val WARN = 0xFFB7791F.toInt()
    const val TEXT = 0xFF1B1F2A.toInt()
    const val MUTED = 0xFF667085.toInt()
    const val LINE = 0xFFE4E7EC.toInt()
    const val CHIP = 0xFFEEF2FF.toInt()

    fun dp(c: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Float, c: Context, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(c, 1f), strokeColor)
        }

    fun vertical(c: Context, paddingDp: Float = 0f): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(c, paddingDp)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun horizontal(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun card(c: Context): LinearLayout = vertical(c, 16f).apply {
        background = rounded(CARD, 16f, c)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(c, 16f), dp(c, 6f), dp(c, 16f), dp(c, 6f))
        layoutParams = lp
        elevation = dp(c, 1f).toFloat()
    }

    fun text(c: Context, s: CharSequence, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    fun heading(c: Context, s: String): TextView = text(c, s, 17f, TEXT, true).apply {
        setPadding(0, 0, 0, dp(c, 6f))
    }

    fun caption(c: Context, s: CharSequence): TextView = text(c, s, 13f, MUTED).apply {
        setLineSpacing(0f, 1.15f)
    }

    fun space(c: Context, h: Float): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, h))
    }

    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(LINE)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1f))
        lp.setMargins(0, dp(c, 10f), 0, dp(c, 10f))
        layoutParams = lp
    }

    fun button(c: Context, label: String, filled: Boolean = true, color: Int = ACCENT, onClick: () -> Unit): Button =
        Button(c).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (filled) Color.WHITE else color)
            val base = if (filled) rounded(color, 12f, c) else rounded(Color.TRANSPARENT, 12f, c, color)
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), base, rounded(Color.BLACK, 12f, c))
            stateListAnimator = null
            elevation = 0f
            setPadding(dp(c, 18f), dp(c, 10f), dp(c, 18f), dp(c, 10f))
            minHeight = dp(c, 44f)
            minimumHeight = dp(c, 44f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    fun wideButton(c: Context, label: String, filled: Boolean = true, color: Int = ACCENT, onClick: () -> Unit): Button =
        button(c, label, filled, color, onClick).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(c, 8f)
            layoutParams = lp
        }

    fun input(c: Context, hint: String, singleLine: Boolean = true): EditText = EditText(c).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = singleLine
        background = rounded(0xFFF2F4F7.toInt(), 10f, c)
        setPadding(dp(c, 12f), dp(c, 10f), dp(c, 12f), dp(c, 10f))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    fun pinInput(c: Context, hint: String): EditText = input(c, hint).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, 8f)
        }
    }

    /** A row with a title, an optional description and a switch on the right. */
    fun switchRow(c: Context, title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = horizontal(c)
        row.setPadding(0, dp(c, 6f), 0, dp(c, 6f))
        val texts = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(text(c, title, 15f, TEXT, true))
        if (description != null) texts.addView(caption(c, description))
        row.addView(texts)
        val sw = Switch(c).apply {
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(c, 12f)
            }
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        return row
    }

    /** A removable list item (host or keyword) with an X button. */
    fun chipRow(c: Context, label: String, sub: String?, actionLabel: String, actionColor: Int = MUTED, onAction: () -> Unit): View {
        val row = horizontal(c)
        row.setPadding(dp(c, 12f), dp(c, 8f), dp(c, 6f), dp(c, 8f))
        row.background = rounded(CHIP, 10f, c)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(c, 6f)
        row.layoutParams = lp
        val texts = vertical(c).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        texts.addView(text(c, label, 14f, TEXT, true))
        if (sub != null) texts.addView(caption(c, sub))
        row.addView(texts)
        row.addView(button(c, actionLabel, filled = false, color = actionColor, onClick = onAction).apply {
            setPadding(dp(c, 12f), dp(c, 4f), dp(c, 12f), dp(c, 4f))
            minHeight = dp(c, 36f); minimumHeight = dp(c, 36f)
        })
        return row
    }

    fun pill(c: Context, s: String, color: Int): TextView = text(c, s, 12f, Color.WHITE, true).apply {
        background = rounded(color, 20f, c)
        setPadding(dp(c, 10f), dp(c, 3f), dp(c, 10f), dp(c, 3f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
