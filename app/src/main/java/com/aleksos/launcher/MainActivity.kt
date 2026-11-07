package com.aleksos.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Главный экран AleksOS: Android-приложения и Windows-программы в одной сетке. */
class MainActivity : Activity() {

    private enum class Kind { ANDROID, WINDOWS, ADD_WINDOWS }

    private class Item(
        val kind: Kind,
        val label: String,
        val icon: Drawable,
        val component: ComponentName? = null,
        val windowsApp: WindowsApp? = null,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: WindowsAppStore
    private lateinit var adapter: ItemAdapter
    private lateinit var search: EditText

    private var allItems: List<Item> = emptyList()
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WindowsAppStore(this)
        adapter = ItemAdapter()
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /** Кнопка «Назад» на рабочем столе ничего не делает — только очищает поиск. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) search.setText("")
    }

    // ---------- Интерфейс ----------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x55000000)
        }

        search = EditText(this).apply {
            hint = "Поиск приложений"
            setHintTextColor(0xAAFFFFFF.toInt())
            setTextColor(Color.WHITE)
            isSingleLine = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0x44FFFFFF)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        root.addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(12), dp(16), dp(8))
            }
        )

        val grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onItemClick(this@MainActivity.adapter.getItem(position)) }
            setOnItemLongClickListener { _, _, position, _ ->
                onItemLongClick(this@MainActivity.adapter.getItem(position))
                true
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Не залезать под статус-бар и панель навигации (Android 15 рисует «от края до края»).
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        return root
    }

    // ---------- Загрузка списка ----------

    private fun reload() {
        val generation = ++loadGeneration
        val windowsApps = store.load()
        executor.execute {
            val androidItems = loadAndroidApps()
            runOnUiThread {
                if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                val items = ArrayList<Item>()
                items += Item(Kind.ADD_WINDOWS, "Добавить .exe", TileIconDrawable.addTile())
                windowsApps.sortedBy { it.name.lowercase() }.forEach {
                    items += Item(Kind.WINDOWS, it.name, TileIconDrawable.forWindowsApp(it.name), windowsApp = it)
                }
                items += androidItems
                allItems = items
                applyFilter()
            }
        }
    }

    private fun loadAndroidApps(): List<Item> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return activities
            .filter { it.activityInfo.packageName != packageName }
            .map { info ->
                Item(
                    kind = Kind.ANDROID,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun applyFilter() {
        val query = search.text.toString().trim().lowercase()
        adapter.items = if (query.isEmpty()) {
            allItems
        } else {
            allItems.filter { it.kind != Kind.ADD_WINDOWS && it.label.lowercase().contains(query) }
        }
    }

    // ---------- Нажатия ----------

    private fun onItemClick(item: Item) {
        when (item.kind) {
            Kind.ANDROID -> {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(item.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    toast("Не удалось открыть ${item.label}")
                    reload()
                } catch (e: SecurityException) {
                    toast("Нет доступа к ${item.label}")
                }
            }
            Kind.WINDOWS -> item.windowsApp?.let { WindowsEngine.launch(this, it) }
            Kind.ADD_WINDOWS -> pickWindowsProgram()
        }
    }

    private fun onItemLongClick(item: Item) {
        when (item.kind) {
            Kind.ANDROID -> {
                val pkg = item.component?.packageName ?: return
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Kind.WINDOWS -> {
                val app = item.windowsApp ?: return
                AlertDialog.Builder(this)
                    .setTitle(app.name)
                    .setItems(arrayOf("Убрать с рабочего стола")) { _, _ ->
                        store.remove(app.id)
                        try {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(app.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {
                        }
                        reload()
                    }
                    .show()
            }
            Kind.ADD_WINDOWS -> {}
        }
    }

    // ---------- Добавление Windows-программы ----------

    private fun pickWindowsProgram() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PICK_EXE)
        } catch (e: ActivityNotFoundException) {
            toast("Не найден файловый менеджер")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_EXE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val fileName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Программа"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in WINDOWS_EXTENSIONS) {
            toast("Это не Windows-программа. Нужен файл .exe, .msi, .bat или .lnk")
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Некоторые файловые менеджеры не дают постоянный доступ — сохраним ссылку всё равно.
        }
        val name = fileName.substringBeforeLast('.')
        store.add(name, uri.toString())
        toast("«$name» добавлена на рабочий стол")
        reload()
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }

    // ---------- Сетка ----------

    private inner class ItemAdapter : BaseAdapter() {
        var items: List<Item> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: createCell()
            val icon = cell.findViewWithTag<ImageView>(TAG_ICON)
            val badge = cell.findViewWithTag<TextView>(TAG_BADGE)
            val label = cell.findViewWithTag<TextView>(TAG_LABEL)
            val item = items[position]
            icon.setImageDrawable(item.icon)
            label.text = item.label
            badge.visibility = if (item.kind == Kind.WINDOWS) View.VISIBLE else View.GONE
            return cell
        }

        private fun createCell(): LinearLayout {
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            val frame = FrameLayout(this@MainActivity)
            frame.addView(
                ImageView(this@MainActivity).apply { tag = TAG_ICON },
                FrameLayout.LayoutParams(dp(56), dp(56))
            )
            frame.addView(
                TextView(this@MainActivity).apply {
                    tag = TAG_BADGE
                    text = "WIN"
                    textSize = 8f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dp(3), dp(1), dp(3), dp(1))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(0xFF0EA5E9.toInt())
                    }
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                )
            )
            cell.addView(frame, LinearLayout.LayoutParams(dp(60), dp(60)))
            cell.addView(
                TextView(this@MainActivity).apply {
                    tag = TAG_LABEL
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            )
            return cell
        }
    }

    // ---------- Утилиты ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQUEST_PICK_EXE = 1001
        const val TAG_ICON = "icon"
        const val TAG_BADGE = "badge"
        const val TAG_LABEL = "label"
        val WINDOWS_EXTENSIONS = setOf("exe", "msi", "bat", "lnk")
    }
}
