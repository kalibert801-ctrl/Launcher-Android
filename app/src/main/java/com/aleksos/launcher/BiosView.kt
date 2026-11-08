package com.aleksos.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * «BIOS» AleksOS в стиле современного UEFI: вкладки, параметры, сохранение при выходе.
 * Изменения применяются только по кнопке «Сохранить и выйти».
 */
class BiosView(private val activity: Activity, private val onExit: () -> Unit) : FrameLayout(activity) {

    private val settings = SystemSettings(activity)
    /** Рабочая копия значений (до сохранения). */
    private val pending = HashMap<String, Int>().apply { SystemSettings.ALL.forEach { put(it.key, settings.get(it)) } }

    private val tabs = listOf("Главная", "Движок", "Графика", "Загрузка", "Выход")
    private var currentTab = 0
    private val tabViews = ArrayList<TextView>()
    private val content: LinearLayout
    private val hint: TextView

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
        isClickable = true   // не пропускать нажатия на рабочий стол
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // Верхняя строка: логотип, название, дата и время
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        top.addView(ImageView(activity).apply { setImageDrawable(LogoDrawable()) }, LinearLayout.LayoutParams(dp(30), dp(30)))
        top.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(label("AleksOS UEFI BIOS", 16f, Color.WHITE, bold = true))
            addView(label("Расширенный режим", 11f, ACCENT))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TextClock(activity).apply {
                format24Hour = "HH:mm:ss"; format12Hour = "HH:mm:ss"
                setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.MONOSPACE
            })
            addView(TextClock(activity).apply {
                format24Hour = "dd.MM.yyyy, EEEE"; format12Hour = "dd.MM.yyyy, EEEE"
                setTextColor(DIM); textSize = 11f
            })
        })
        column.addView(top)

        // Вкладки
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        tabs.forEachIndexed { i, name ->
            val tab = label(name, 14f, DIM, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { selectTab(i) }
            }
            tabViews += tab
            tabRow.addView(tab)
        }
        column.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })
        column.addView(View(activity).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // Содержимое вкладки
        content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(ScrollView(activity).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Нижняя подсказка
        hint = label("", 12f, DIM).apply {
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(0x33000000)
        }
        column.addView(hint)

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        currentTab = index
        tabViews.forEachIndexed { i, tab ->
            tab.setTextColor(if (i == index) Color.WHITE else DIM)
            tab.background = if (i == index) GradientDrawable().apply {
                setColor(0x2622D3EE); cornerRadius = dp(8).toFloat(); setStroke(dp(1), ACCENT)
            } else null
        }
        content.removeAllViews()
        when (index) {
            0 -> buildMain()
            1 -> buildOptions(SystemSettings.ENGINE_OPTIONS, "Параметры движка Windows (Wine + Box64)")
            2 -> buildOptions(SystemSettings.GRAPHICS_OPTIONS, "Графика и DirectX")
            3 -> buildOptions(SystemSettings.BOOT_OPTIONS, "Загрузка AleksOS")
            4 -> buildExit()
        }
        hint.text = when (index) {
            0 -> "Сведения о системе   •   вкладки сверху — разделы настроек"
            4 -> "Изменения сохраняются только кнопкой «Сохранить и выйти»"
            else -> "Нажмите на значение, чтобы изменить   •   изменено: ${changedCount()}"
        }
    }

    private fun buildMain() {
        content.addView(sectionTitle("Сведения о системе"))
        val card = card()
        content.addView(card)
        val loading = label("Опрашиваю оборудование…", 13f, DIM)
        card.addView(loading)
        thread(name = "bios-info") {
            val lines = HardwareInfo.collect(activity)
            activity.runOnUiThread {
                if (currentTab != 0) return@runOnUiThread
                card.removeAllViews()
                lines.forEach { card.addView(infoRow(it.label, it.value, it.ok)) }
            }
        }
        content.addView(sectionTitle("Модули движка Windows"))
        content.addView(card().apply {
            WindowsEngine.modules().forEach {
                addView(infoRow(it.name, (it.version ?: "не установлен") + "  ·  " + it.purpose, it.version != null))
            }
        })
        content.addView(sectionTitle("AleksOS"))
        content.addView(card().apply {
            addView(infoRow("Версия", "${Updater.currentVersionName(activity)} (сборка ${Updater.currentBuild(activity)})", true))
            addView(infoRow("Папка системы", "Внутренняя память/AleksOS", true))
        })
    }

    private fun buildOptions(options: List<SystemSettings.Option>, title: String) {
        content.addView(sectionTitle(title))
        if (options === SystemSettings.ENGINE_OPTIONS || options === SystemSettings.GRAPHICS_OPTIONS) {
            content.addView(label("Движок ещё не установлен — настройки начнут действовать после его установки.", 12f, WARN).apply {
                setPadding(dp(4), 0, dp(4), dp(10))
            })
        }
        val card = card()
        options.forEach { card.addView(optionRow(it)) }
        content.addView(card)
    }

    private fun buildExit() {
        content.addView(sectionTitle("Сохранение и выход"))
        content.addView(exitButton("Сохранить и выйти", primary = true) {
            settings.saveAll(pending)
            onExit()
        })
        content.addView(exitButton("Выйти без сохранения") { onExit() })
        content.addView(exitButton("Сбросить на значения по умолчанию") {
            SystemSettings.ALL.forEach { pending[it.key] = it.default }
            selectTab(4)
            android.widget.Toast.makeText(activity, "Значения по умолчанию загружены. Нажмите «Сохранить и выйти».", android.widget.Toast.LENGTH_LONG).show()
        })
        content.addView(sectionTitle("Движок Windows"))
        content.addView(exitButton("Переустановить движок", enabled = WindowsEngine.isAvailable) {})
        content.addView(exitButton("Сбросить диск C", enabled = WindowsEngine.isAvailable) {})
        if (!WindowsEngine.isAvailable) {
            content.addView(label("Недоступно: движок ещё не установлен.", 12f, DIM).apply { setPadding(dp(4), dp(4), 0, 0) })
        }
    }

    /** Нажатие «Назад»: если есть несохранённые изменения — спросить. */
    fun onBack() {
        if (changedCount() == 0) {
            onExit()
            return
        }
        AosDialog(activity)
            .title("Выход из BIOS")
            .message("Сохранить изменения (${changedCount()})?")
            .button("Не сохранять") { onExit() }
            .button("Сохранить", primary = true) { settings.saveAll(pending); onExit() }
            .show()
    }

    private fun changedCount() = SystemSettings.ALL.count { pending[it.key] != settings.get(it) }

    // ---------- Элементы ----------

    private fun optionRow(option: SystemSettings.Option): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(option.title, 14f, Color.WHITE))
            addView(label(option.hint, 11f, DIM))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = label("", 13f, ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        fun render() {
            val idx = pending[option.key] ?: option.default
            val changed = idx != settings.get(option)
            value.text = "‹ ${option.values[idx]} ›"
            value.setTextColor(if (changed) WARN else ACCENT)
            value.background = GradientDrawable().apply {
                setColor(0x1A22D3EE); cornerRadius = dp(6).toFloat(); setStroke(dp(1), if (changed) WARN else 0x5522D3EE)
            }
        }
        render()
        row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) })
        row.setOnClickListener {
            pending[option.key] = ((pending[option.key] ?: option.default) + 1) % option.values.size
            render()
            hint.text = "Нажмите на значение, чтобы изменить   •   изменено: ${changedCount()}"
        }
        Ui.pressable(row, null, dp(8).toFloat())
        return row
    }

    private fun infoRow(name: String, value: String, ok: Boolean) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label(name, 13f, DIM), LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(label(value, 13f, if (ok) Color.WHITE else WARN), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun exitButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
        label(text, 14f, if (primary) BG_TOP else Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(13), dp(14), dp(13))
            alpha = if (enabled) 1f else 0.35f
            Ui.pressable(this, GradientDrawable().apply {
                setColor(if (primary) ACCENT else 0x14FFFFFF); cornerRadius = dp(10).toFloat()
                if (!primary) setStroke(dp(1), LINE)
            }, dp(10).toFloat())
            isEnabled = enabled
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(12).toFloat(); setStroke(dp(1), LINE) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) }
    }

    private fun sectionTitle(text: String) = label(text.uppercase(), 12f, ACCENT, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(4), 0, dp(8))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = Ui.dp(activity, v)

    companion object {
        const val BG_TOP = 0xFF0B1220.toInt()
        const val BG_BOTTOM = 0xFF131C2E.toInt()
        const val ACCENT = 0xFF22D3EE.toInt()
        const val DIM = 0xFF8B9BB4.toInt()
        const val LINE = 0x33FFFFFF
        const val WARN = 0xFFFBBF24.toInt()
    }
}
