package com.gamechanger.shield

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Design tokens + a small view kit. Every color, size and spacing in the UI comes from here.
 *
 * Direction: calm, private instrument. Dark, one accent (teal = "protected"),
 * depth from surface tints and 1px borders, not shadows.
 */
object T {
    // Color
    const val BG = 0xFF0D1013.toInt()
    const val SURFACE_1 = 0xFF151A1E.toInt()
    const val SURFACE_2 = 0xFF1C2227.toInt()
    const val BORDER = 0xFF262D33.toInt()
    const val BORDER_STRONG = 0xFF39424A.toInt()
    const val TEXT_1 = 0xFFEEF1F3.toInt()
    const val TEXT_2 = 0xFFB3BBC2.toInt()
    const val TEXT_3 = 0xFF838C95.toInt() // labels only
    const val ACCENT = 0xFF6CC3AE.toInt()
    const val ACCENT_SOFT = 0x246CC3AE
    const val ON_ACCENT = 0xFF0B1A16.toInt()
    const val WARN = 0xFFEB7F70.toInt()
    const val WARN_SOFT = 0x24EB7F70
    const val RIPPLE = 0x33FFFFFF

    // Type (sp)
    const val LABEL = 13f
    const val BODY = 16f
    const val TITLE = 20f
    const val STATUS = 32f
    const val HERO = 44f

    // Spacing (dp, 4pt grid)
    const val S1 = 4
    const val S2 = 8
    const val S3 = 12
    const val S4 = 16
    const val S5 = 20
    const val S6 = 24
    const val S8 = 32

    // Radius (dp)
    const val R_INPUT = 8
    const val R_BUTTON = 12
    const val R_CARD = 20

    const val TOUCH = 48
}

class Kit(private val c: Context) {

    val regular: Typeface = ResourcesCompat.getFont(c, R.font.heebo_regular) ?: Typeface.DEFAULT
    val medium: Typeface = ResourcesCompat.getFont(c, R.font.heebo_medium) ?: Typeface.DEFAULT
    val bold: Typeface = ResourcesCompat.getFont(c, R.font.heebo_bold) ?: Typeface.DEFAULT_BOLD

    fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * c.resources.displayMetrics.density

    fun column(padH: Int = 0, padV: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun row() = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(border: Int = T.BORDER, fill: Int = T.SURFACE_1) = column(T.S5, T.S5).apply {
        background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(T.R_CARD.toFloat())
            setStroke(dp(1), border)
        }
    }

    fun text(
        s: CharSequence, size: Float = T.BODY, color: Int = T.TEXT_2,
        face: Typeface = regular, lineMult: Float = 1.06f, // Heebo already has ~1.47em line height
    ) = TextView(c).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face
        setLineSpacing(0f, lineMult)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        includeFontPadding = false
    }

    /** Small label above a value or a section. Hebrew has no caps: weight + muted color. */
    fun label(s: String) = text(s, T.LABEL, T.TEXT_3, medium, 1.0f)

    fun title(s: String) = text(s, T.TITLE, T.TEXT_1, bold, 0.95f).apply {
        isAccessibilityHeading = true
    }

    fun number(s: String, size: Float, color: Int = T.TEXT_1) = text(s, size, color, bold, 0.9f).apply {
        fontFeatureSettings = "tnum"
        // Digits have no direction of their own; anchor them to the Hebrew (right) edge.
        textDirection = View.TEXT_DIRECTION_RTL
    }

    enum class Kind { PRIMARY, SECONDARY, QUIET, DANGER }

    fun button(s: String, kind: Kind = Kind.PRIMARY, onClick: () -> Unit) = Button(c).apply {
        text = s
        isAllCaps = false
        typeface = medium
        setTextSize(TypedValue.COMPLEX_UNIT_SP, T.BODY)
        minHeight = dp(T.TOUCH)
        minimumHeight = dp(T.TOUCH)
        stateListAnimator = null
        setPadding(dp(T.S4), 0, dp(T.S4), 0)
        val (fill, fg, stroke) = when (kind) {
            Kind.PRIMARY -> Triple(T.ACCENT, T.ON_ACCENT, T.ACCENT)
            Kind.SECONDARY -> Triple(T.SURFACE_2, T.TEXT_1, T.BORDER_STRONG)
            Kind.QUIET -> Triple(0, T.ACCENT, 0)
            Kind.DANGER -> Triple(T.WARN_SOFT, T.WARN, T.WARN)
        }
        setTextColor(fg)
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(T.R_BUTTON.toFloat())
            if (stroke != 0) setStroke(dp(1), stroke)
        }
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt()); cornerRadius = dpf(T.R_BUTTON.toFloat())
        }
        background = RippleDrawable(ColorStateList.valueOf(T.RIPPLE), shape, mask)
        if (kind == Kind.QUIET) gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setOnClickListener { onClick() }
    }

    fun input(hint: String, password: Boolean = false, number: Boolean = false) = EditText(c).apply {
        this.hint = hint
        typeface = regular
        setTextSize(TypedValue.COMPLEX_UNIT_SP, T.BODY)
        setTextColor(T.TEXT_1)
        setHintTextColor(T.TEXT_3)
        minHeight = dp(T.TOUCH)
        minimumHeight = dp(T.TOUCH)
        setPadding(dp(T.S3), dp(T.S3), dp(T.S3), dp(T.S3))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        textDirection = View.TEXT_DIRECTION_RTL
        background = GradientDrawable().apply {
            setColor(T.BG)
            cornerRadius = dpf(T.R_INPUT.toFloat())
            setStroke(dp(1), T.BORDER_STRONG)
        }
        contentDescription = hint
    }

    fun space(h: Int) = View(c).apply { minimumHeight = dp(h) }

    fun lp(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
}

/** Progress bar that fills from the start edge (right, in Hebrew). */
class Bar(c: Context, private val fraction: Float, private val color: Int = T.ACCENT) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val rad = h / 2
        p.color = T.SURFACE_2
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, rad, rad, p)
        val w = width * fraction.coerceIn(0f, 1f)
        if (w <= 0f) return
        p.color = color
        if (layoutDirection == LAYOUT_DIRECTION_RTL) r.set(width - w, 0f, width.toFloat(), h)
        else r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, rad, rad, p)
    }
}

/**
 * The rail of the level ladder / setup steps: a line with a marker.
 * done = filled with a check, current = ring with a dot, ahead = muted ring.
 */
class Rail(
    c: Context,
    private val state: State,
    private val first: Boolean,
    private val last: Boolean,
    private val lineDoneBelow: Boolean,
) : View(c) {
    enum class State { DONE, CURRENT, AHEAD }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val check = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = 22 * d // aligned with the first text line
        val rad = 9 * d
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        if (!first) {
            p.color = if (state != State.AHEAD) T.ACCENT else T.BORDER_STRONG
            canvas.drawLine(cx, 0f, cx, cy - rad, p)
        }
        if (!last) {
            p.color = if (lineDoneBelow) T.ACCENT else T.BORDER_STRONG
            canvas.drawLine(cx, cy + rad, cx, height.toFloat(), p)
        }
        when (state) {
            State.DONE -> {
                p.style = Paint.Style.FILL; p.color = T.ACCENT
                canvas.drawCircle(cx, cy, rad, p)
                p.style = Paint.Style.STROKE; p.color = T.ON_ACCENT; p.strokeWidth = 2 * d
                p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                check.reset()
                check.moveTo(cx - 4 * d, cy)
                check.lineTo(cx - 1 * d, cy + 3 * d)
                check.lineTo(cx + 4.5f * d, cy - 3 * d)
                canvas.drawPath(check, p)
            }
            State.CURRENT -> {
                p.color = T.ACCENT
                canvas.drawCircle(cx, cy, rad, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, 4 * d, p)
            }
            State.AHEAD -> {
                p.color = T.BORDER_STRONG
                canvas.drawCircle(cx, cy, rad, p)
            }
        }
    }
}

/** Small status dot used in the header. */
class Dot(c: Context, private val color: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        p.color = color
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, p)
    }
}
