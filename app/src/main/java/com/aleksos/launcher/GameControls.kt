package com.aleksos.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Экранное игровое управление поверх Windows-программы: джойстик слева (W/A/S/D)
 * и кнопки клавиш справа. Клавиши держатся, пока палец на кнопке (игрокам это важно —
 * экранная клавиатура Android нажимает и сразу отпускает).
 * Пустые места оверлея касания не забирают — там работает обычная «мышь» пальцем.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class GameControls(
    context: Context,
    private val key: (keyCode: Int, down: Boolean) -> Unit,
    private val mouseLeft: (down: Boolean) -> Unit,
) : FrameLayout(context) {

    private fun dp(v: Int) = Ui.dp(context, v)

    init {
        val stick = Joystick(context)
        addView(stick, LayoutParams(dp(170), dp(170), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(18); bottomMargin = dp(18)
        })

        // кнопки как в типичных играх на ПК (GTA и др.)
        val buttons = listOf(
            "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "Enter" to KeyEvent.KEYCODE_ENTER,
            "Q" to KeyEvent.KEYCODE_Q, "E" to KeyEvent.KEYCODE_E, "F" to KeyEvent.KEYCODE_F,
            "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT, "Shift" to KeyEvent.KEYCODE_SHIFT_LEFT, "Space" to KeyEvent.KEYCODE_SPACE,
        )
        val grid = GridLayout(context).apply { columnCount = 3 }
        for ((label, code) in buttons) grid.addView(keyButton(label) { down -> key(code, down) })
        addView(grid, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(12); bottomMargin = dp(88) })

        // большая кнопка «огонь» = левая кнопка мыши
        val fire = keyButton("ЛКМ", big = true) { down -> mouseLeft(down) }
        addView(fire, LayoutParams(dp(76), dp(76), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16); bottomMargin = dp(8)
        })
    }

    private fun keyButton(label: String, big: Boolean = false, onPress: (Boolean) -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = if (big) 15f else 13f
        gravity = Gravity.CENTER
        val normal = GradientDrawable().apply {
            shape = if (big) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(0x55000000); setStroke(dp(1), 0x99FFFFFF.toInt())
        }
        background = normal
        if (!big) layoutParams = GridLayout.LayoutParams().apply {
            width = dp(58); height = dp(46); setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; onPress(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; onPress(false) }
            }
            true
        }
    }

    /** Отпустить всё (при скрытии управления, чтобы клавиши не «залипли»). */
    fun releaseAll() {
        for (code in listOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D)) key(code, false)
    }

    /** Джойстик: отклонение пальца → удерживаемые W/A/S/D (8 направлений). */
    private inner class Joystick(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; style = Paint.Style.FILL }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt() }
        private var kx = 0f
        private var ky = 0f
        private val held = mutableSetOf<Int>()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy) - dp(4)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, r, border)
            canvas.drawCircle(cx + kx, cy + ky, r * 0.38f, knob)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    var dx = e.x - cx; var dy = e.y - cy
                    val len = hypot(dx, dy)
                    if (len > r) { dx = dx / len * r; dy = dy / len * r }
                    kx = dx; ky = dy
                    val dead = r * 0.25f
                    val want = mutableSetOf<Int>()
                    // направление считается по наклону: диагонали включают две клавиши
                    if (dy < -dead && abs(dy) > abs(dx) * 0.4f) want += KeyEvent.KEYCODE_W
                    if (dy > dead && abs(dy) > abs(dx) * 0.4f) want += KeyEvent.KEYCODE_S
                    if (dx < -dead && abs(dx) > abs(dy) * 0.4f) want += KeyEvent.KEYCODE_A
                    if (dx > dead && abs(dx) > abs(dy) * 0.4f) want += KeyEvent.KEYCODE_D
                    update(want)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { kx = 0f; ky = 0f; update(emptySet()) }
            }
            invalidate()
            return true
        }

        private fun update(want: Set<Int>) {
            for (c in held - want) key(c, false)
            for (c in want - held) key(c, true)
            held.clear(); held.addAll(want)
        }
    }
}
