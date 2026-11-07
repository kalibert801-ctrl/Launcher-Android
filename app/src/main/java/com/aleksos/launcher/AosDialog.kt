package com.aleksos.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Окно в стиле AleksOS: заголовок с логотипом и крестиком, содержимое, кнопки справа внизу.
 * Заменяет стандартные AlertDialog во всём лаунчере.
 */
class AosDialog(private val context: Context) {

    private class Button(val text: String, val primary: Boolean, val onClick: (() -> Unit)?)

    private var title: String = ""
    private var message: CharSequence? = null
    private var items: List<String> = emptyList()
    private var onItem: ((Int) -> Unit)? = null
    private var customView: View? = null
    private var cancelable = true
    private val buttons = ArrayList<Button>()

    fun title(text: String) = apply { title = text }
    fun message(text: CharSequence) = apply { message = text }
    fun items(list: List<String>, onClick: (Int) -> Unit) = apply { items = list; onItem = onClick }
    fun view(view: View) = apply { customView = view }
    fun cancelable(value: Boolean) = apply { cancelable = value }
    /** Кнопка внизу. primary = выделенная (главное действие). */
    fun button(text: String, primary: Boolean = false, onClick: (() -> Unit)? = null) =
        apply { buttons += Button(text, primary, onClick) }

    fun show(): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dp = { v: Int -> Ui.dp(context, v) }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.PANEL or 0xFF000000.toInt(), dp(14).toFloat(), Ui.BORDER, dp(1))
            clipToOutline = true
        }

        // Заголовок окна
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(6), dp(10))
        }
        titleBar.addView(ImageView(context).apply { setImageDrawable(LogoDrawable()) },
            LinearLayout.LayoutParams(dp(20), dp(20)))
        titleBar.addView(TextView(context).apply {
            text = title
            setTextColor(Ui.TEXT)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(10), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (cancelable) {
            titleBar.addView(TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Ui.TEXT_DIM)
                gravity = Gravity.CENTER
                Ui.pressable(this, null, dp(8).toFloat())
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(40), dp(36)))
        }
        root.addView(titleBar)
        root.addView(View(context).apply { setBackgroundColor(Ui.BORDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // Содержимое
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        message?.let {
            body.addView(TextView(context).apply {
                text = it
                setTextColor(Ui.TEXT)
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            })
        }
        items.forEachIndexed { index, label ->
            body.addView(TextView(context).apply {
                text = label
                setTextColor(Ui.TEXT)
                textSize = 15f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                Ui.pressable(this, null, dp(8).toFloat())
                setOnClickListener {
                    dialog.dismiss()
                    onItem?.invoke(index)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        customView?.let { body.addView(it) }
        root.addView(ScrollView(context).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { weight = 1f })

        // Кнопки
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(dp(12), dp(4), dp(12), dp(12))
            }
            buttons.forEach { b ->
                row.addView(TextView(context).apply {
                    text = b.text
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (b.primary) 0xFFFFFFFF.toInt() else Ui.TEXT)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    Ui.pressable(this, Ui.rounded(if (b.primary) Ui.ACCENT_TEXT else Ui.FIELD, dp(8).toFloat()), dp(8).toFloat())
                    setOnClickListener {
                        dialog.dismiss()
                        b.onClick?.invoke()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                })
            }
            root.addView(row)
        }

        dialog.setContentView(root)
        dialog.setCancelable(cancelable)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val screen = context.resources.displayMetrics.widthPixels
            w.setLayout(minOf(screen - dp(32), dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.45f)
        }
        dialog.show()
        return dialog
    }

    companion object {
        /** Полоска прогресса для окна «Скачиваю…». */
        fun progressBar(context: Context): ProgressBar =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT_TEXT)
            }
    }
}
