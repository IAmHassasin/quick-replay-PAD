package dev.quickreplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers to build the plain-View UI in code (no layout XML needed). */
internal object Ui {
    const val BG = 0xFF0F1115.toInt()
    const val CARD = 0xFF181B22.toInt()
    const val FG = 0xFFE7E9EE.toInt()
    const val MUTED = 0xFF8B92A1.toInt()
    const val ACCENT = 0xFF4F8CFF.toInt()
    const val OK = 0xFF3ECF8E.toInt()

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun text(context: Context, value: String, size: Float = 14f, color: Int = FG, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit) =
        Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else FG)
            background = GradientDrawable().apply {
                setColor(if (primary) ACCENT else 0xFF262A33.toInt())
                cornerRadius = dp(context, 8).toFloat()
            }
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            setOnClickListener { onClick() }
        }

    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(context, 12).toFloat()
        }
        val pad = dp(context, 16)
        setPadding(pad, pad, pad, pad)
    }

    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun spacedParams(context: Context, top: Int = 12) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(context, top) }

    fun gapParams(context: Context) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = dp(context, 8) }
}
