package com.aleksos.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * Оформление AleksOS: свои цвета, панели и логотип.
 * Расположение элементов привычное (панель задач, меню «Пуск», окна с заголовком),
 * но внешний вид собственный, без копирования Windows.
 */
object Ui {
    const val ACCENT = 0xFF22D3EE.toInt()        // бирюзовый
    const val ACCENT_2 = 0xFF8B5CF6.toInt()      // фиолетовый
    const val ACCENT_TEXT = 0xFF0E7490.toInt()   // бирюзовый для текста на светлом
    const val PANEL = 0xF2F4F7FB.toInt()         // светлое «матовое стекло»
    const val TASKBAR = 0xE0EEF2F8.toInt()
    const val FIELD = 0x14000000                 // поля ввода и кнопки на панелях
    const val BORDER = 0x22000000
    const val TEXT = 0xFF1E293B.toInt()
    const val TEXT_DIM = 0xFF64748B.toInt()
    const val PRESSED = 0x22000000

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** Фон с «подсветкой» при нажатии. */
    fun pressable(view: View, content: Drawable?, radiusPx: Float) {
        val mask = rounded(0xFFFFFFFF.toInt(), radiusPx)
        view.background = RippleDrawable(ColorStateList.valueOf(PRESSED), content, mask)
        view.isClickable = true
        view.isFocusable = true
    }
}

/** Логотип AleksOS: плитка с бирюзово-фиолетовым переливом и буквой «A». */
class LogoDrawable : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        bg.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            Ui.ACCENT, Ui.ACCENT_2, Shader.TileMode.CLAMP)
        val r = w * 0.3f
        canvas.drawRoundRect(RectF(b), r, r, bg)
        letter.textSize = h * 0.62f
        val cy = b.exactCenterY() - (letter.descent() + letter.ascent()) / 2
        canvas.drawText("A", b.exactCenterX(), cy, letter)
        canvas.drawCircle(b.right - w * 0.22f, b.top + h * 0.22f, w * 0.07f, dot)
    }

    override fun setAlpha(alpha: Int) {
        bg.alpha = alpha
        letter.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bg.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 96
    override fun getIntrinsicHeight(): Int = 96
}
