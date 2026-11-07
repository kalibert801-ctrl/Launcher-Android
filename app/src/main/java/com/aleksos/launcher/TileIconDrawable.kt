package com.aleksos.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Простая рисованная иконка: цветная плитка с «окном» и буквой.
 * Используется для Windows-программ (пока не умеем доставать иконку из .exe)
 * и для плитки «Добавить».
 */
class TileIconDrawable(
    private val text: String,
    private val background: Int,
    private val drawWindowFrame: Boolean = true,
) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val r = w * 0.22f
        canvas.drawRoundRect(RectF(b), r, r, bgPaint)

        if (drawWindowFrame) {
            val frame = RectF(b.left + w * 0.18f, b.top + h * 0.2f, b.right - w * 0.18f, b.bottom - h * 0.2f)
            framePaint.strokeWidth = w * 0.04f
            canvas.drawRoundRect(frame, w * 0.05f, w * 0.05f, framePaint)
            canvas.drawRect(frame.left, frame.top, frame.right, frame.top + h * 0.12f, barPaint)
            textPaint.textSize = h * 0.32f
            val cy = frame.centerY() + h * 0.06f - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, frame.centerX(), cy, textPaint)
        } else {
            textPaint.textSize = h * 0.55f
            val cy = b.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, b.exactCenterX(), cy, textPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = 192
    override fun getIntrinsicHeight(): Int = 192

    companion object {
        private val COLORS = intArrayOf(
            0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(),
            0xFFEA580C.toInt(), 0xFF059669.toInt(), 0xFF0891B2.toInt(),
        )

        /** Иконка для Windows-программы: цвет зависит от названия, буква — первая буква названия. */
        fun forWindowsApp(name: String): TileIconDrawable {
            val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val color = COLORS[Math.floorMod(name.hashCode(), COLORS.size)]
            return TileIconDrawable(letter, color)
        }

        fun addTile(): TileIconDrawable = TileIconDrawable("+", 0x66FFFFFF, drawWindowFrame = false)
    }
}
