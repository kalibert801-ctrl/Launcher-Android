package com.aleksos.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Загрузка AleksOS «как настоящая ОС»:
 * 1) экран проверки оборудования и модулей ядра с кнопкой BIOS;
 * 2) заставка AleksOS;
 * 3) рабочий стол.
 */
class BootScreen(private val activity: Activity, private val host: FrameLayout, private val onDone: () -> Unit) {

    private val settings = SystemSettings(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val overlay = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
    }
    private var bios: BiosView? = null
    private var finished = false
    private var waitingForBios = false

    /** Открыт ли сейчас BIOS (для кнопки «Назад»). */
    val isBiosOpen get() = bios != null
    val isActive get() = !finished

    fun start() {
        settings.markBootShown()
        host.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setFullscreen(true)
        showPost()
    }

    fun onBack() {
        bios?.onBack()
    }

    // ---------- 1. Проверка оборудования ----------

    private fun showPost() {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(18))
        }
        // шапка
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(activity).apply { setImageDrawable(LogoDrawable()) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(mono("AleksOS UEFI  v1.0", 15f, Color.WHITE, bold = true))
            addView(mono("Copyright (C) AleksOS Project", 11f, GRAY))
        })
        column.addView(header)
        column.addView(View(activity), LinearLayout.LayoutParams(1, dp(18)))

        val log = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(log)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // низ: кнопка BIOS и отсчёт
        val biosButton = mono("[ BIOS ]", 16f, ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(2), ACCENT); setColor(0x1A22D3EE) }
            setOnClickListener { openBios() }
        }
        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bottom.addView(biosButton)
        bottom.addView(mono("Нажмите BIOS, чтобы настроить систему", 12f, GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        val countdown = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        bottom.addView(countdown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        column.addView(bottom)

        overlay.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // нажатие мимо кнопки — пропустить ожидание
        val skip = View.OnClickListener { if (waitingForBios) continueBoot() }
        overlay.setOnClickListener(skip)
        column.setOnClickListener(skip)
        log.setOnClickListener(skip)

        log.addView(mono("Проверка оборудования…", 13f, GRAY))
        thread(name = "post") {
            val hw = HardwareInfo.collect(activity).filter { it.label != "Windows Kernel" }
            val modules = WindowsEngine.modules(activity)
            activity.runOnUiThread {
                if (finished) return@runOnUiThread
                val lines = ArrayList<CharSequence>()
                hw.forEach { lines += statusLine(it.label, it.value, if (it.ok) "OK" else "!!", it.ok) }
                lines += ""
                lines += "Модули Windows Kernel:"
                modules.forEach {
                    lines += statusLine("  " + it.name, it.version ?: it.purpose, if (it.version != null) "OK" else "--", it.version != null)
                }
                lines += ""
                lines += if (WindowsEngine.isAvailable(activity)) "Windows Kernel готов." else "Windows Kernel не установлен: Windows-программы недоступны."
                printLines(log, lines) { startCountdown(countdown) }
            }
        }
    }

    /** Строки появляются по одной, как при настоящей загрузке. */
    private fun printLines(log: LinearLayout, lines: List<CharSequence>, done: () -> Unit) {
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (finished || bios != null) return
                if (i >= lines.size) {
                    done()
                    return
                }
                log.addView(mono("", 12.5f, Color.WHITE).apply { text = lines[i] })
                i++
                handler.postDelayed(this, 110)
            }
        }
        handler.post(step)
    }

    private fun startCountdown(bar: ProgressBar) {
        waitingForBios = true
        val total = SystemSettings.biosWaitMillis(settings)
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (finished || bios != null || !waitingForBios) return
                val elapsed = System.currentTimeMillis() - started
                bar.progress = (elapsed * 1000 / total).toInt().coerceAtMost(1000)
                if (elapsed >= total) continueBoot() else handler.postDelayed(this, 30)
            }
        }
        handler.post(tick)
    }

    private fun statusLine(label: String, value: String, status: String, ok: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(label.padEnd(19, ' '), ForegroundColorSpan(GRAY), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(value)
        sb.append("  [")
        sb.append(status, ForegroundColorSpan(if (ok) GREEN else YELLOW), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("]")
        return sb
    }

    // ---------- BIOS ----------

    private fun openBios() {
        waitingForBios = false
        val view = BiosView(activity) {
            bios?.let { overlay.removeView(it) }
            bios = null
            continueBoot()
        }
        bios = view
        overlay.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------- 2. Заставка ----------

    private fun continueBoot() {
        if (finished) return
        waitingForBios = false
        if (settings.get(SystemSettings.SPLASH) == 1) {
            finish()
            return
        }
        val splash = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        splash.addView(ImageView(activity).apply {
            setImageResource(R.drawable.boot_splash)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // три «дышащие» точки под логотипом
        val dots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dotViews = (0 until 3).map {
            View(activity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                alpha = 0.3f
            }.also { dots.addView(it, LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(dp(5), 0, dp(5), 0) }) }
        }
        splash.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = (activity.resources.displayMetrics.heightPixels * 0.22f).toInt()
        })
        splash.addView(TextView(activity).apply {
            text = "Запуск AleksOS…"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = (activity.resources.displayMetrics.heightPixels * 0.22f).toInt() + dp(22)
        })
        overlay.addView(splash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash.animate().alpha(1f).setDuration(400).start()

        var frame = 0
        val pulse = object : Runnable {
            override fun run() {
                if (finished) return
                dotViews.forEachIndexed { i, v -> v.animate().alpha(if (i == frame % 3) 1f else 0.3f).setDuration(250).start() }
                frame++
                handler.postDelayed(this, 300)
            }
        }
        handler.post(pulse)
        handler.postDelayed({ finish() }, 2600)
    }

    // ---------- 3. Рабочий стол ----------

    private fun finish() {
        if (finished) return
        finished = true
        setFullscreen(false)
        overlay.animate().alpha(0f).setDuration(450).withEndAction {
            host.removeView(overlay)
            handler.removeCallbacksAndMessages(null)
        }.start()
        onDone()
    }

    private fun setFullscreen(on: Boolean) = Ui.setFullscreen(activity, on)

    private fun mono(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun dp(v: Int) = Ui.dp(activity, v)

    private companion object {
        const val ACCENT = 0xFF22D3EE.toInt()
        const val GRAY = 0xFF9CA3AF.toInt()
        const val GREEN = 0xFF4ADE80.toInt()
        const val YELLOW = 0xFFFBBF24.toInt()
    }
}
