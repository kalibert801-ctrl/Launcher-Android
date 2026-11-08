package com.aleksos.launcher

import android.content.ClipData
import android.content.Context
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject

/**
 * Рабочий стол: невидимая сетка ячеек, в которой иконки лежат там, куда их положили.
 * Долгое нажатие + перетаскивание переносит иконку (если ячейка занята, иконки меняются местами).
 * Долгое нажатие без перетаскивания открывает меню иконки.
 */
class DesktopGrid(context: Context) : ViewGroup(context) {

    /** Вызывается при нажатии на иконку и при долгом нажатии без переноса. */
    var onOpen: ((String) -> Unit)? = null
    var onMenu: ((String) -> Unit)? = null

    private val prefs = context.getSharedPreferences("desktop_positions", Context.MODE_PRIVATE)
    private val keys = ArrayList<String>()          // ключ иконки по порядку детей
    private val cells = HashMap<String, Int>()      // ключ → номер ячейки
    /** Лёжа (телефон повёрнут) — больше колонок, а места иконок хранятся отдельно. */
    private val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val prefsKey = if (landscape) "cells_land" else "cells"
    private var columns = 4
    private val cellHeight = Ui.dp(context, 96)

    private var dragKey: String? = null
    private var dragMoved = false

    init {
        setOnDragListener { _, event -> handleDrag(event) }
    }

    /** Заменить иконки на столе. Пары: ключ (стабильный id) и готовый вид иконки. */
    fun setEntries(entries: List<Pair<String, View>>) {
        removeAllViews()
        keys.clear()
        for ((key, view) in entries) {
            keys += key
            view.setOnClickListener { onOpen?.invoke(key) }
            view.setOnLongClickListener {
                dragKey = key
                dragMoved = false
                it.startDragAndDrop(ClipData.newPlainText("aleksos", key), DragShadowBuilder(it), key, 0)
                it.visibility = View.INVISIBLE
                true
            }
            addView(view)
        }
        cells.clear()
        requestLayout()
    }

    private fun rows(): Int = maxOf(1, height / cellHeight)
    private fun cellWidth(): Int = width / columns

    /** Раскладываем иконки: сохранённые места, остальные — в первые свободные ячейки. */
    private fun assignCells() {
        val total = rows() * columns
        val saved = loadSaved()
        cells.clear()
        val taken = HashSet<Int>()
        for (key in keys) {
            val cell = saved[key] ?: continue
            if (cell in 0 until total && cell !in taken) {
                cells[key] = cell
                taken += cell
            }
        }
        var next = 0
        for (key in keys) {
            if (key in cells) continue
            while (next in taken) next++
            cells[key] = next   // может выйти за экран, если иконок больше, чем ячеек
            taken += next
        }
        save()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cols = if (landscape) maxOf(4, w / Ui.dp(context, 100)) else 4
        if (cols != columns) { columns = cols; cells.clear() }
        val cw = MeasureSpec.makeMeasureSpec(w / columns, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (cells.size != keys.size || changed) assignCells()
        val cw = cellWidth()
        for (i in 0 until childCount) {
            val cell = cells[keys[i]] ?: continue
            val col = cell % columns
            val row = cell / columns
            val x = col * cw
            val y = row * cellHeight
            getChildAt(i).layout(x, y, x + cw, y + cellHeight)
        }
    }

    private fun handleDrag(event: DragEvent): Boolean {
        val key = event.localState as? String ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DROP -> {
                val col = (event.x / cellWidth()).toInt().coerceIn(0, columns - 1)
                val row = (event.y / cellHeight).toInt().coerceIn(0, rows() - 1)
                val target = row * columns + col
                val from = cells[key] ?: return true
                if (target != from) {
                    dragMoved = true
                    // если в ячейке уже есть иконка — меняемся местами
                    cells.entries.firstOrNull { it.value == target }?.let { it.setValue(from) }
                    cells[key] = target
                    save()
                    requestLayout()
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                val index = keys.indexOf(key)
                if (index >= 0) getChildAt(index).visibility = View.VISIBLE
                if (dragKey == key) {
                    dragKey = null
                    if (!dragMoved) onMenu?.invoke(key)
                }
                return true
            }
        }
        return true
    }

    private fun loadSaved(): Map<String, Int> = try {
        val json = JSONObject(prefs.getString(prefsKey, "{}") ?: "{}")
        json.keys().asSequence().associateWith { json.getInt(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun save() {
        // Сохраняем и старые места иконок, которых сейчас нет (например, приложение временно скрыто).
        val json = try { JSONObject(prefs.getString(prefsKey, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        cells.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(prefsKey, json.toString()).apply()
    }
}
